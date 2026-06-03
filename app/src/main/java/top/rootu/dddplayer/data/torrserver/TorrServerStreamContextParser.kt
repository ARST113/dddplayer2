package top.rootu.dddplayer.data.torrserver

import android.net.Uri

data class TorrServerStreamContext(
    val baseUrl: String,
    val hash: String,
    val fileIndex: Int?
)

object TorrServerStreamContextParser {
    fun parse(url: String?): TorrServerStreamContext? {
        if (url.isNullOrBlank()) return null

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val hash = runCatching {
            uri.getQueryParameter("link") ?: uri.getQueryParameter("hash")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val baseUrl = if (uri.port > 0) {
            "$scheme://$host:${uri.port}"
        } else {
            "$scheme://$host"
        }

        val index = runCatching {
            uri.getQueryParameter("index")?.toIntOrNull()
        }.getOrNull()

        return TorrServerStreamContext(
            baseUrl = baseUrl,
            hash = hash,
            fileIndex = index
        )
    }
}
