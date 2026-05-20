package top.rootu.dddplayer.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class UpdateChannel { STABLE, NIGHTLY, DISABLED }

data class UpdateInfo(val version: String, val description: String, val downloadUrl: String, val size: Long, val publishedAt: String? = null)

class UpdateManager(private val context: Context) {
    private val client = OkHttpClient()
    private val latestUrl = "https://api.github.com/repos/ARST113/dddplayer2/releases/latest"
    private val nightlyUrl = "https://api.github.com/repos/ARST113/dddplayer2/releases/tags/nightly"

    suspend fun checkForUpdates(currentVersionName: String?, channel: UpdateChannel): UpdateInfo? = withContext(Dispatchers.IO) {
        if (channel == UpdateChannel.DISABLED) return@withContext null
        val primary = if (channel == UpdateChannel.NIGHTLY) nightlyUrl else latestUrl
        val release = (fetchRelease(primary) ?: if (channel == UpdateChannel.NIGHTLY) fetchRelease(latestUrl) else null) ?: return@withContext null
        val tag = release.optString("tag_name")
        val isNightly = channel == UpdateChannel.NIGHTLY || tag.equals("nightly", ignoreCase = true)
        if (!isNightly && currentVersionName != null) {
            val normalizedCurrent = currentVersionName.removePrefix("v")
            val normalizedTag = tag.removePrefix("v")
            if (normalizedCurrent == normalizedTag || !VersionComparator.isRemoteNewer(normalizedCurrent, normalizedTag)) return@withContext null
        }
        val asset = pickApkAsset(release.optJSONArray("assets") ?: return@withContext null) ?: return@withContext null
        val url = asset.optString("browser_download_url")
        if (!isAllowedUrl(url)) return@withContext null
        UpdateInfo(tag, release.optString("body", ""), url, asset.optLong("size", 0), release.optString("published_at"))
    }

    private fun fetchRelease(url: String): JSONObject? = try { client.newCall(Request.Builder().url(url).build()).execute().use { if (!it.isSuccessful) null else JSONObject(it.body.string()) } } catch (_: Exception) { null }
    private fun pickApkAsset(assets: org.json.JSONArray): JSONObject? {
        val list = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") }
        val nonDebug = list.filterNot { it.optString("name").contains("debug", true) }
        return nonDebug.firstOrNull { it.optString("name").contains("release", true) }
            ?: nonDebug.firstOrNull { it.optString("name") == "app-release.apk" }
            ?: nonDebug.firstOrNull { it.optString("name") == "dddplayer2-release.apk" }
            ?: nonDebug.firstOrNull()
    }
    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return uri.scheme == "https" && (host == "github.com" || host == "objects.githubusercontent.com" || host == "api.github.com")
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(info.downloadUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val fileName = info.downloadUrl.substringAfterLast('/').ifBlank { "update.apk" }
                val file = File(dir, fileName)
                body.byteStream().use { input -> FileOutputStream(file).use { out ->
                    val totalLength = body.contentLength().takeIf { it > 0 } ?: info.size
                    val data = ByteArray(8192); var count:Int; var total=0L
                    while (input.read(data).also { count = it } != -1) { total += count; out.write(data,0,count); if (totalLength > 0) onProgress(((total*100)/totalLength).toInt()) }
                }}
                if (!file.exists() || file.length() <= 0L) return@withContext null
                if (info.size > 0 && file.length() != info.size) return@withContext null
                file
            }
        } catch (_: Exception) { null }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(intent) } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
