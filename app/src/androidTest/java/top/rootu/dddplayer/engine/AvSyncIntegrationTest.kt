package top.rootu.dddplayer.engine

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.player.DataSourceEngineIo
import java.io.File
import kotlin.math.abs

/** Один demux одновременно кормит audio clock и синхронизированный GL video. */
@RunWith(AndroidJUnit4::class)
class AvSyncIntegrationTest {
    private val mediaDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, "dddtest")
    }

    @Before
    fun setUp() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
        assumeTrue("нет $mediaDir", mediaDir.isDirectory)
    }

    @Test
    fun audioClockSchedulesDecodedVideoFromSameDemux() {
        val file = File(mediaDir, "dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)
        val io = DataSourceEngineIo.open(
            FileDataSource(),
            DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
        )
        val handle = NativeDemuxer.open(null, io)
        var audio: NativeAudioDecoder? = null
        var video: NativeVideoDecoder? = null
        var sink: EngineAudioSink? = null
        var renderer: NativeRenderer? = null
        var pump: EngineAudioPump? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertTrue("нужны video+audio", probe.bestVideoIndex >= 0 && probe.bestAudioIndex >= 0)
            assertTrue(NativeDemuxer.selectStreams(
                handle, probe.bestVideoIndex, probe.bestAudioIndex, -1
            ))
            assertTrue(NativeDemuxer.start(handle))
            val audioDecoder = NativeAudioDecoder.create(handle, probe.bestAudioIndex)
            audio = audioDecoder
            val videoDecoder = NativeVideoDecoder.create(handle, probe.bestVideoIndex)
            video = videoDecoder
            val audioSink = EngineAudioSink(audioDecoder.sampleRate, audioDecoder.channels)
            sink = audioSink
            audioSink.setVolume(0f)
            val videoRenderer = requireNotNull(NativeRenderer.offscreen(320, 180)) {
                "не создался renderer"
            }
            renderer = videoRenderer
            videoRenderer.setHdrParams(NativeRenderer.HdrParams.from(probe.color, 500f))

            val sync = AvSyncController()
            val audioPump = EngineAudioPump(audioDecoder, audioSink)
            pump = audioPump
            audioPump.start()

            // Audio — master clock: первый video frame не выпускаем, пока
            // аппаратный playback head реально не начал двигаться.
            val primeDeadline = System.nanoTime() + 5_000_000_000L
            while ((audioSink.positionUs == null || audioSink.playedFramesCount == 0L) &&
                System.nanoTime() < primeDeadline) {
                audioPump.error?.let { throw it }
                Thread.sleep(2)
            }
            assertTrue("audio clock не стартовал", audioSink.playedFramesCount > 0)

            var rendered = 0
            var dropped = 0
            var maxRenderSkewUs = 0L
            val deadline = System.nanoTime() + 15_000_000_000L
            while (rendered < 24 && System.nanoTime() < deadline) {
                audioPump.error?.let { throw it }
                when (videoDecoder.nextFrame(20)) {
                    NativeVideoDecoder.Step.AGAIN -> continue
                    NativeVideoDecoder.Step.EOS -> break
                    NativeVideoDecoder.Step.ERROR -> fail("video decode error: ${videoDecoder.decoderName}")
                    NativeVideoDecoder.Step.FRAME -> {
                        var decided = false
                        while (!decided && System.nanoTime() < deadline) {
                            audioPump.error?.let { throw it }
                            when (val decision = sync.decide(videoDecoder.framePtsUs, audioSink.positionUs)) {
                                AvSyncController.Decision.Drop -> {
                                    dropped++
                                    decided = true
                                }
                                AvSyncController.Decision.Render -> {
                                    val clock = audioSink.positionUs
                                    if (clock != null) {
                                        maxRenderSkewUs = maxOf(maxRenderSkewUs, abs(videoDecoder.framePtsUs - clock))
                                    }
                                    assertTrue("video upload", videoDecoder.uploadToRenderer(videoRenderer))
                                    assertTrue("video draw", videoRenderer.draw(0, NativeRenderer.ScaleMode.STRETCH))
                                    rendered++
                                    decided = true
                                }
                                is AvSyncController.Decision.Wait -> Thread.sleep(decision.milliseconds)
                            }
                        }
                        videoDecoder.releaseFrame()
                    }
                }
            }

            assertTrue("не сыграл audio clock", audioSink.playedFramesCount > 0)
            assertTrue("слишком мало синхронных кадров: render=$rendered drop=$dropped", rendered >= 8)
            assertTrue("render вышел далеко из sync-window: $maxRenderSkewUs us",
                maxRenderSkewUs <= AvSyncController.DEFAULT_LATE_DROP_US + 25_000)
        } finally {
            pump?.stop()
            renderer?.release()
            sink?.release()
            audio?.release()
            video?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
        }
    }
}
