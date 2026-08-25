package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentUtilsTest {
    @Test
    fun lampaBridgeQueryMetadataIsNotForwardedToPidtor() {
        val playbackUrl =
            "http://lampac.fun/lite/pidtor/item?rjson=true&tsid=7&uid=user&raw=true" +
                "&ddd_mode=local&ddd_playlist_size=8&ddd_pidtor_episode=7" +
                "#ddd_mode=local&ddd_sid=session&ddd_port=39677&ddd_token=token" +
                "&ddd_remote_events_url=http%3A%2F%2Flampac.fun%2Fddd-sync%2Fv1%2Fevents" +
                "&ddd_device_id=pixel"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playbackUrl))
            .putExtra(
                "headers",
                arrayOf(
                    "X-Lampa-DDD-Sync", "internal-control-payload",
                    "User-Agent", "Lampa"
                )
            )
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val (playlist, startIndex) = IntentUtils.parseIntent(context, intent)

        assertEquals(0, startIndex)
        assertEquals(1, playlist.size)
        val cleanUri = playlist.single().uri.toString()
        assertEquals(
            "http://lampac.fun/lite/pidtor/item?rjson=true&tsid=7&uid=user&raw=true",
            cleanUri
        )
        assertFalse(cleanUri.contains("ddd_"))
        assertEquals(mapOf("User-Agent" to "Lampa"), playlist.single().headers)

        val bridge = IntentUtils.parseBridgeConfig(intent)
        assertTrue(bridge.enabled)
        assertEquals("session", bridge.sessionId)
        assertEquals(39677, bridge.localPort)
        assertNotNull(playlist.single().dddSyncContext)
    }

    @Test
    fun lampaSyncHeaderBuildsRealSerialPlaylist() {
        val payload = """{
          "eventsUrl":"http://lampac.fun/ddd-sync/v1/events",
          "latestUrl":"http://lampac.fun/ddd-sync/v1/latest",
          "schema":1,
          "deviceId":"pixel",
          "sessionId":"session",
          "activeIndex":1,
          "items":[
            {"index":0,"contentKey":"show:s1:e1","sourceKey":"http://lampac.fun/pidtor/a?rjson=true&tsid=1&ddd_mode=local","title":"Series 1","filename":"e1.mkv","positionMs":12000,"durationMs":60000,"percent":20},
            {"index":1,"contentKey":"show:s1:e2","sourceKey":"http://lampac.fun/pidtor/a?rjson=true&tsid=2&ddd_mode=local","title":"Series 2","filename":"e2.mkv","positionMs":3000,"durationMs":60000,"percent":5}
          ]
        }""".trimIndent()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("http://lampac.fun/pidtor/a?rjson=true&tsid=2#ddd_poster=http%3A%2F%2Fimg%2Fposter.jpg")
        ).putExtra(
            "headers",
            arrayOf(
                "X-Lampa-DDD-Sync", Uri.encode(payload),
                "User-Agent", "Lampa"
            )
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val (playlist, startIndex) = IntentUtils.parseIntent(context, intent)

        assertEquals(2, playlist.size)
        assertEquals(1, startIndex)
        assertEquals("Series 1", playlist[0].title)
        assertEquals(12000L, playlist[0].startPositionMs)
        assertEquals("http://lampac.fun/pidtor/a?rjson=true&tsid=1", playlist[0].uri.toString())
        assertEquals("show:s1:e2", playlist[1].dddSyncContext?.contentKey)
        assertEquals(mapOf("User-Agent" to "Lampa"), playlist[1].headers)
    }
}
