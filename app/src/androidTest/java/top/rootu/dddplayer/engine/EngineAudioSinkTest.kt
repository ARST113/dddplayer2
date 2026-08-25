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

/** Полный беззвучный audio path до аппаратного playback head Pixel. */
@RunWith(AndroidJUnit4::class)
class EngineAudioSinkTest {
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
    fun decodedPcmAdvancesRealAudioClockAndKeepsPitchSpeed() {
        val file = File(mediaDir, "dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)
        val io = DataSourceEngineIo.open(
            FileDataSource(),
            DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
        )
        val handle = NativeDemuxer.open(null, io)
        var decoder: NativeAudioDecoder? = null
        var sink: EngineAudioSink? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertTrue("нет audio", probe.bestAudioIndex >= 0)
            assertTrue(NativeDemuxer.selectStreams(handle, -1, probe.bestAudioIndex, -1))
            assertTrue(NativeDemuxer.start(handle))
            decoder = NativeAudioDecoder.create(handle, probe.bestAudioIndex)
            sink = EngineAudioSink(decoder.sampleRate, decoder.channels)
            sink.setVolume(0f)
            sink.speed = 1.25f
            sink.play()

            val pcm = NativeAudioDecoder.allocateBuffer(4096, decoder.channels)
            var firstPts: Long? = null
            while (sink.submittedFramesCount < decoder.sampleRate / 2L) {
                val chunk = decoder.nextPcm(pcm, 4096, 20)
                when (chunk.step) {
                    NativeAudioDecoder.Step.PCM -> {
                        if (firstPts == null) firstPts = chunk.ptsUs
                        val written = sink.write(pcm, chunk.ptsUs)
                        assertTrue("PCM не записался", written == chunk.frames)
                    }
                    NativeAudioDecoder.Step.AGAIN -> Unit
                    NativeAudioDecoder.Step.EOS -> fail("EOS до 0.5 с audio")
                    NativeAudioDecoder.Step.ERROR -> fail("audio decode error")
                }
            }

            val deadline = System.nanoTime() + 5_000_000_000L
            while (sink.playedFramesCount < decoder.sampleRate / 10L &&
                System.nanoTime() < deadline) Thread.sleep(10)

            assertTrue("аппаратный playback head не двигается", sink.playedFramesCount > 4_800)
            val position = sink.positionUs
            assertTrue("нет audio clock", position != null)
            assertTrue("clock раньше первого PTS: $position / $firstPts",
                position!! >= firstPts!!)
            assertTrue("очередь audio имеет неверную длительность: ${sink.queuedDurationUs}",
                sink.queuedDurationUs in 0..1_000_000)
        } finally {
            sink?.release()
            decoder?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
        }
    }

    @Test
    fun pumpSurvivesRepeatedPauseResumeWithoutDroppingPcmRemainder() {
        val file = File(mediaDir, "dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)
        val io = DataSourceEngineIo.open(
            FileDataSource(),
            DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
        )
        val handle = NativeDemuxer.open(null, io)
        var decoder: NativeAudioDecoder? = null
        var sink: EngineAudioSink? = null
        var pump: EngineAudioPump? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertTrue("нет audio", probe.bestAudioIndex >= 0)
            assertTrue(NativeDemuxer.selectStreams(handle, -1, probe.bestAudioIndex, -1))
            assertTrue(NativeDemuxer.start(handle))
            decoder = NativeAudioDecoder.create(handle, probe.bestAudioIndex)
            sink = EngineAudioSink(decoder.sampleRate, decoder.channels)
            sink.setVolume(0f)
            pump = EngineAudioPump(decoder, sink)
            pump.start()

            awaitPlayedFrames(sink, decoder.sampleRate / 20L)
            repeat(4) {
                pump.pause()
                Thread.sleep(75)
                assertTrue("pump упал на pause #$it: ${pump.error}", pump.error == null)
                pump.resume()
                val before = sink.playedFramesCount
                awaitPlayedFrames(sink, before + decoder.sampleRate / 50L)
                assertTrue("pump упал на resume #$it: ${pump.error}", pump.error == null)
            }
        } finally {
            pump?.stop()
            sink?.release()
            decoder?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
        }
    }

    private fun awaitPlayedFrames(sink: EngineAudioSink, target: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (sink.playedFramesCount < target && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            "playback head не дошёл до $target: ${sink.playedFramesCount}",
            sink.playedFramesCount >= target
        )
    }
}
