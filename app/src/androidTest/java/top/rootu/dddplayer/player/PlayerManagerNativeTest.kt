package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.engine.NativeDemuxer
import top.rootu.dddplayer.model.MediaItem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Проверяет реальное подключение DDD Native через тот же PlayerManager, что использует UI. */
@RunWith(AndroidJUnit4::class)
class PlayerManagerNativeTest {
    @Test
    fun nativeSettingSelectsBackendAndSupportsPlaylistControls() {
        assumeTrue("native недоступен", NativeDemuxer.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "dddtest/dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)

        val settings = SettingsRepository.getInstance(context)
        val previousEngine = settings.getPlaybackEngine()
        val playing = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val manager = PlayerManager(context, object : Player.Listener {})
        try {
            settings.setPlaybackEngine(SettingsRepository.PLAYBACK_ENGINE_NATIVE_ONLY)
            manager.onBackendPlayingChanged = { if (it) playing.countDown() }
            manager.onBackendError = { error.set(it) }
            manager.initializePlayer()
            assertNull("для Native не должен создаваться ExoPlayer", manager.exoPlayer)

            manager.loadPlaylist(
                listOf(MediaItem(Uri.fromFile(file), title = "DDD Native integration")),
                startIndex = 0
            )
            assertEquals("NATIVE", manager.getActiveBackendId())
            assertTrue("backend не начал играть: ${error.get()}",
                playing.await(5, TimeUnit.SECONDS))
            waitFor(5_000) { manager.getPositionMs() >= 200 || error.get() != null }
            error.get()?.let { throw it }
            assertTrue("позиция не движется: ${manager.getPositionMs()}",
                manager.getPositionMs() >= 200)
            assertEquals("DDD Native integration", manager.getCurrentTitle())
            val audioTracks = manager.getVlcAudioTracks()
            assertTrue("аудиодорожки не дошли до PlayerManager", audioTracks.isNotEmpty())
            val selectedAudio = manager.getVlcSelectedAudioTrackId()
            assertTrue("не отмечена выбранная аудиодорожка",
                audioTracks.any { it.id == selectedAudio && it.selected })
            assertTrue("повторный выбор текущей дорожки отвергнут",
                manager.selectVlcAudioTrackById(selectedAudio!!))
            manager.setPlaybackSpeed(1.25f)

            assertTrue(manager.playIndex(0, 1_000))
            waitFor(5_000) { manager.getPositionMs() in 900..2_500 || error.get() != null }
            error.get()?.let { throw it }
            assertTrue("playIndex/seek не применился: ${manager.getPositionMs()}",
                manager.getPositionMs() in 900..2_500)
        } finally {
            manager.releasePlayer(isFinalRelease = true, saveState = false)
            settings.setPlaybackEngine(previousEngine)
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("условие не выполнено за $timeoutMs ms")
    }
}
