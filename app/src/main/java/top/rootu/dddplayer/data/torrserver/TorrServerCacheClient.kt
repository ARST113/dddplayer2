package top.rootu.dddplayer.data.torrserver

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.rootu.dddplayer.model.TorrentPieceHealth
import kotlin.math.roundToInt

class TorrServerCacheClient(
    private val httpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun loadPieceHealth(
        torrServerBaseUrl: String,
        hash: String
    ): TorrentPieceHealth? = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("action", "get")
            addProperty("hash", hash)
        }.toString()

        val request = Request.Builder()
            .url(torrServerBaseUrl.trimEnd('/') + "/cache")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val raw = response.body?.string() ?: return@use null
                val root = JsonParser.parseString(raw).asObjectOrNull() ?: return@use null
                parsePieceHealth(root)
            }
        }.getOrNull()
    }

    private fun parsePieceHealth(root: JsonObject): TorrentPieceHealth? {
        val readers = root.get("Readers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val readerObj = readers.firstOrNull()?.asObjectOrNull() ?: return null

        val reader = readerObj.intOrNull("Reader") ?: return null
        val end = readerObj.intOrNull("End") ?: return null
        val total = end - reader
        if (total <= 0) return null

        val pieces = root.get("Pieces") ?: return null
        var cursor = reader
        var loaded = 0

        while (cursor < end && isPieceCompleted(pieces, cursor)) {
            cursor++
            loaded++
        }

        val ratio = loaded.toFloat() / total.toFloat()
        val percent = (ratio * 100).roundToInt().coerceIn(0, 100)
        val level = when {
            percent > 80 -> TorrentPieceHealth.Level.GREEN
            percent >= 40 -> TorrentPieceHealth.Level.YELLOW
            else -> TorrentPieceHealth.Level.RED
        }
        val totalDots = 5
        val activeDots = (ratio * totalDots).roundToInt().coerceIn(0, totalDots)

        return TorrentPieceHealth(
            percent = percent,
            level = level,
            activeDots = activeDots,
            totalDots = totalDots
        )
    }

    private fun isPieceCompleted(pieces: JsonElement, index: Int): Boolean {
        return when {
            pieces.isJsonArray -> {
                val array = pieces.asJsonArray
                if (index !in 0 until array.size()) return false

                array[index]
                    ?.asObjectOrNull()
                    ?.booleanOrNull("Completed") == true
            }

            pieces.isJsonObject -> {
                pieces.asJsonObject
                    .get(index.toString())
                    ?.asObjectOrNull()
                    ?.booleanOrNull("Completed") == true
            }

            else -> false
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return runCatching { get(key)?.asInt }.getOrNull()
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        return runCatching { get(key)?.asBoolean }.getOrNull()
    }
}
