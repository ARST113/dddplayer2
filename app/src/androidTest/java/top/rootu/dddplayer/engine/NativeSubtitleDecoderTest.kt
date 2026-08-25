package top.rootu.dddplayer.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Проверяет весь JNI/FFmpeg-тракт текстовых субтитров, включая UTF-8. */
@RunWith(AndroidJUnit4::class)
class NativeSubtitleDecoderTest {
    @Test
    fun decodesRussianSrtWithTiming() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "subtitles-russian.srt")
        instrumentation.context.assets.open("subtitles-russian.srt").use { input ->
            source.outputStream().use(input::copyTo)
        }

        val handle = NativeDemuxer.open(source.absolutePath, null)
        assertTrue("не открылся SRT", handle != 0L)
        var decoder: NativeSubtitleDecoder? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertEquals(1, probe.tracks.subtitle.size)
            val track = probe.tracks.subtitle.single()
            assertEquals("subrip", track.codec)
            assertTrue("не выбран subtitle stream", NativeDemuxer.selectStreams(handle, -1, -1, track.id))
            assertTrue("не запустился demux", NativeDemuxer.start(handle))
            decoder = NativeSubtitleDecoder.create(handle, track.id)

            val deadline = System.nanoTime() + 5_000_000_000L
            var cue: NativeSubtitleDecoder.Cue? = null
            while (System.nanoTime() < deadline && cue?.step != NativeSubtitleDecoder.Step.CUE) {
                cue = decoder.nextCue(100)
            }

            assertEquals(NativeSubtitleDecoder.Step.CUE, cue?.step)
            assertEquals("Привет, Pixel!", cue?.text)
            assertTrue("начало кия неверно: ${cue?.startUs}", cue!!.startUs in 900_000L..1_100_000L)
            assertTrue("конец кия неверно: ${cue.endUs}", cue.endUs in 3_400_000L..3_600_000L)
        } finally {
            decoder?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
            source.delete()
        }
    }
}
