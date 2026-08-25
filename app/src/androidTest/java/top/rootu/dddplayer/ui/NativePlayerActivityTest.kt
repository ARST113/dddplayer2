package top.rootu.dddplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.engine.NativeDemuxer
import java.io.File
import java.util.concurrent.TimeUnit

/** Сквозная проверка: ACTION_VIEW → PlayerActivity → PlayerManager → native → SurfaceView. */
@RunWith(AndroidJUnit4::class)
class NativePlayerActivityTest {
    @Test
    fun actionViewRendersDecodedFrameIntoRealSurfaceView() {
        assumeTrue("native недоступен", NativeDemuxer.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "dddtest/buck480p30.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        val settings = SettingsRepository.getInstance(context)
        val previousEngine = settings.getPlaybackEngine()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hadMediaPermission = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hadMediaPermission) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        }
        settings.setPlaybackEngine(SettingsRepository.PLAYBACK_ENGINE_NATIVE_ONLY)
        val intent = Intent(Intent.ACTION_VIEW)
            .setClass(context, PlayerActivity::class.java)
            .setDataAndType(Uri.fromFile(file), "video/mp4")

        try {
            ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                var rendered = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!rendered && System.nanoTime() < deadline) {
                    var surfaceBounds: IntArray? = null
                    scenario.onActivity { activity ->
                        val surface = activity.findViewById<SurfaceView>(R.id.surface_view_standard)
                        if (surface.width <= 0 || surface.height <= 0 ||
                            !surface.holder.surface.isValid) {
                            return@onActivity
                        }
                        val location = IntArray(2)
                        surface.getLocationOnScreen(location)
                        surfaceBounds = intArrayOf(
                            location[0], location[1],
                            location[0] + surface.width,
                            location[1] + surface.height
                        )
                    }
                    val bounds = surfaceBounds
                    if (bounds != null) {
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        if (screenshot != null) {
                            rendered = hasVideoVariation(screenshot, bounds)
                            screenshot.recycle()
                        }
                    }
                    if (!rendered) Thread.sleep(150)
                }
                assertTrue("декодированный кадр не появился в SurfaceView", rendered)
            }
        } finally {
            settings.setPlaybackEngine(previousEngine)
        }
    }

    private fun hasVideoVariation(bitmap: Bitmap, bounds: IntArray): Boolean {
        var minLuma = 255
        var maxLuma = 0
        var nonBlack = 0
        var colorful = 0
        val left = bounds[0].coerceIn(0, bitmap.width - 1)
        val top = bounds[1].coerceIn(0, bitmap.height - 1)
        val right = bounds[2].coerceIn(left + 1, bitmap.width)
        val bottom = bounds[3].coerceIn(top + 1, bitmap.height)
        val x0 = left + (right - left) / 5
        val x1 = left + (right - left) * 4 / 5
        val y0 = top + (bottom - top) / 5
        val y1 = top + (bottom - top) * 4 / 5
        val stepX = ((x1 - x0) / 40).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 24).coerceAtLeast(1)
        for (y in y0 until y1 step stepY) {
            for (x in x0 until x1 step stepX) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luma = (red * 54 + green * 183 + blue * 19) shr 8
                minLuma = minOf(minLuma, luma)
                maxLuma = maxOf(maxLuma, luma)
                if (luma > 8) nonBlack++
                if (maxOf(red, green, blue) - minOf(red, green, blue) >= 18) colorful++
            }
        }
        return nonBlack >= 40 && colorful >= 20 && maxLuma - minLuma >= 18
    }
}
