package top.rootu.dddplayer.engine

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.player.DataSourceEngineIo
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Реальный audio path: DataSource → demux → libavcodec → swresample → float PCM. */
@RunWith(AndroidJUnit4::class)
class NativeAudioDecoderTest {

    private val mediaDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, "dddtest")
    }

    @Before
    fun setUp() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
        assumeTrue("нет $mediaDir — запустите push-test-media.sh", mediaDir.isDirectory)
    }

    @Test
    fun aacDecodesToFiniteStereoFloatWithMonotonicPts() {
        withAudio("dovi-p84.mov") { decoder, _ ->
            assertEquals(48_000, decoder.sampleRate)
            assertEquals(2, decoder.channels)
            assertTrue("неизвестный decoder", decoder.decoderName.startsWith("libavcodec:"))

            val buffer = NativeAudioDecoder.allocateBuffer(4096, decoder.channels)
            var decodedFrames = 0L
            var lastPts = Long.MIN_VALUE
            var peak = 0f
            val deadline = System.nanoTime() + 15_000_000_000L
            while (decodedFrames < decoder.sampleRate * 4L && System.nanoTime() < deadline) {
                val chunk = decoder.nextPcm(buffer, 4096, 20)
                when (chunk.step) {
                    NativeAudioDecoder.Step.PCM -> {
                        assertTrue("пустой PCM chunk", chunk.frames > 0)
                        assertTrue("PTS пошёл назад: $lastPts → ${chunk.ptsUs}",
                            lastPts == Long.MIN_VALUE || chunk.ptsUs >= lastPts)
                        lastPts = chunk.ptsUs
                        peak = maxOf(peak, validateAndPeak(buffer))
                        decodedFrames += chunk.frames
                    }
                    NativeAudioDecoder.Step.AGAIN -> Unit
                    NativeAudioDecoder.Step.EOS -> break
                    NativeAudioDecoder.Step.ERROR -> fail("ошибка ${decoder.decoderName}")
                }
            }
            assertTrue("получено слишком мало PCM: $decodedFrames", decodedFrames > 48_000)
            assertTrue("аудио осталось тишиной: peak=$peak", peak > 0.01f)
            assertTrue("декодер не получил пакеты", decoder.packetsIn > 0)
            assertEquals(decodedFrames, decoder.sampleFramesOut)
            assertTrue("не определился входной sample rate", decoder.inputSampleRate > 0)
            assertTrue("не определились входные каналы", decoder.inputChannels > 0)
        }
    }

    @Test
    fun seekFlushMovesAudioPtsToRequestedPosition() {
        withAudio("dovi-p84.mov") { decoder, handle ->
            val buffer = NativeAudioDecoder.allocateBuffer(2048, decoder.channels)
            val first = awaitPcm(decoder, buffer)
            val durationMs = NativeDemuxer.durationMs(handle)
            assertTrue("слишком короткий файл для seek: $durationMs ms", durationMs > 1_000)
            val targetMs = (durationMs / 2).coerceIn(500, 5_000)
            val beforeSeeks = NativeDemuxer.stats(handle)!!.seeks
            assertTrue("seek не принят", NativeDemuxer.seek(handle, targetMs))

            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline &&
                (NativeDemuxer.stats(handle)?.seeks ?: beforeSeeks) <= beforeSeeks) {
                Thread.sleep(10)
            }
            assertTrue("demux не подтвердил seek",
                (NativeDemuxer.stats(handle)?.seeks ?: beforeSeeks) > beforeSeeks)
            assertTrue("audio flush", decoder.flush())

            val after = awaitPcm(decoder, buffer)
            assertTrue("PTS не сдвинулся после seek: ${first.ptsUs} → ${after.ptsUs}",
                after.ptsUs > first.ptsUs + targetMs * 500)
            assertTrue("seek ушёл слишком далеко: ${after.ptsUs}",
                after.ptsUs in ((targetMs - 1_000).coerceAtLeast(0) * 1_000)..
                    ((targetMs + 2_000) * 1_000))
        }
    }

    private fun withAudio(
        name: String,
        body: (NativeAudioDecoder, Long) -> Unit
    ) {
        val file = File(mediaDir, name)
        assumeTrue("нет ${file.name}", file.isFile)
        val io = DataSourceEngineIo.open(
            FileDataSource(),
            DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
        )
        val handle = NativeDemuxer.open(null, io)
        var decoder: NativeAudioDecoder? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertTrue("нет аудиопотока", probe.bestAudioIndex >= 0)
            assertTrue("selectStreams",
                NativeDemuxer.selectStreams(handle, -1, probe.bestAudioIndex, -1))
            assertTrue("start", NativeDemuxer.start(handle))
            decoder = NativeAudioDecoder.create(handle, probe.bestAudioIndex)
            body(decoder, handle)
        } finally {
            decoder?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
        }
    }

    private fun awaitPcm(
        decoder: NativeAudioDecoder,
        buffer: ByteBuffer
    ): NativeAudioDecoder.Chunk {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val chunk = decoder.nextPcm(buffer, 2048, 20)
            when (chunk.step) {
                NativeAudioDecoder.Step.PCM -> return chunk
                NativeAudioDecoder.Step.AGAIN -> Unit
                NativeAudioDecoder.Step.EOS -> fail("EOS до PCM")
                NativeAudioDecoder.Step.ERROR -> fail("ошибка ${decoder.decoderName}")
            }
        }
        fail("нет PCM за 10 секунд")
        throw AssertionError("unreachable")
    }

    private fun validateAndPeak(buffer: ByteBuffer): Float {
        val floats = buffer.asFloatBuffer()
        var peak = 0f
        while (floats.hasRemaining()) {
            val value = floats.get()
            assertTrue("PCM содержит NaN/Inf", value.isFinite())
            assertTrue("PCM вышел за float range: $value", value in -1.01f..1.01f)
            peak = maxOf(peak, abs(value))
        }
        return peak
    }
}
