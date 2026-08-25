package top.rootu.dddplayer.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.engine.NativeDemuxer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Контракт UI-backend: prepare/play/pause/seek без подмены Media3. */
@RunWith(AndroidJUnit4::class)
class NativePlaybackBackendTest {
    @Test
    fun backendPlaysPausesAndSeeksOnAudioClock() {
        assumeTrue("native недоступен", NativeDemuxer.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dddtest")
        val file = File(mediaDir, "dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)

        val factory = DataSource.Factory { FileDataSource() }
        val backend = NativePlaybackBackend(factory)
        val playing = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        backend.setListener(object : PlaybackBackend.Listener {
            override fun onPlaying() { playing.countDown() }
            override fun onError(value: Throwable) { error.set(value) }
        })
        try {
            backend.prepare(android.net.Uri.fromFile(file))
            assertTrue("backend не стал playing: ${error.get()}",
                playing.await(5, TimeUnit.SECONDS))
            waitFor(5_000) { backend.getPositionMs() >= 200 || error.get() != null }
            error.get()?.let { throw it }
            assertTrue("позиция не движется: ${backend.getPositionMs()}",
                backend.getPositionMs() >= 200)
            assertTrue("не прочиталась duration: ${backend.getDurationMs()}",
                backend.getDurationMs() > 3_000)
            assertTrue("не обновился buffer: ${backend.getBufferedPositionMs()}",
                backend.getBufferedPositionMs() > 0)

            backend.pause()
            Thread.sleep(150)
            val paused = backend.getPositionMs()
            Thread.sleep(250)
            assertTrue("позиция ползёт на паузе: $paused → ${backend.getPositionMs()}",
                abs(backend.getPositionMs() - paused) < 80)

            backend.seekTo(1_000)
            backend.play()
            waitFor(5_000) {
                backend.getPositionMs() in 900..2_500 || error.get() != null
            }
            error.get()?.let { throw it }
            assertTrue("seek не применился: ${backend.getPositionMs()}",
                backend.getPositionMs() in 900..2_500)
        } finally {
            backend.release()
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("условие не выполнено за $timeoutMs ms")
    }
}
