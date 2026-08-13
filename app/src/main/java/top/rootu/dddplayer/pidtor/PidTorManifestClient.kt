package top.rootu.dddplayer.pidtor

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.rootu.dddplayer.model.MediaItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PidTorManifest(
    val schema: Int = 1,
    val title: String? = null,
    val serial: Boolean = false,
    val season: Int = 0,
    val episode: Int = 0,
    val gst: Boolean = false,
    val variants: List<PidTorVariant> = emptyList()
)

data class PidTorVariant(
    val id: String = "",
    val video: PidTorVideo = PidTorVideo(),
    val audio: List<PidTorTrack> = emptyList(),
    val subtitles: List<PidTorTrack> = emptyList(),
    val replicas: List<PidTorReplica> = emptyList()
)

data class PidTorVideo(
    val width: Int = 0,
    val height: Int = 0,
    val quality: String = "SD",
    val source: String? = null,
    val codec: String? = null,
    val hdr: String? = null,
    val bit_depth: Int = 0,
    val bitrate: Long = 0
)

data class PidTorTrack(
    val id: String = "",
    val stream_index: Int = -1,
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val channels: Int = 0
)

data class PidTorReplica(
    val infohash: String? = null,
    val seeders: Int = 0,
    val stream_url: String? = null,
    val episodes_url: String? = null
)

data class PidTorQuality(
    val key: String,
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int
)

data class PidTorTrackChoice(
    val key: String,
    val label: String,
    val language: String?,
    val streamIndex: Int,
    val variantId: String,
    val qualityKey: String,
    val subtitle: Boolean,
    val seeders: Int
)

data class PidTorResolvedSelection(
    val playlist: List<MediaItem>,
    val audio: PidTorTrackChoice?,
    val subtitle: PidTorTrackChoice?
)

private data class PidTorEpisodes(val data: List<PidTorEpisode> = emptyList())
private data class PidTorEpisode(val s: Int = 0, val e: Int = 0, val title: String? = null, val url: String? = null)

class PidTorManifestClient {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun load(url: String): PidTorManifest = withContext(Dispatchers.IO) {
        manifestCache[manifestCacheKey(url)] ?: getJson(url, PidTorManifest::class.java).also {
            require(it.variants.isNotEmpty()) { "PidTor manifest is empty" }
            manifestCache[manifestCacheKey(url)] = it
        }
    }

    fun qualityOptions(manifest: PidTorManifest): List<PidTorQuality> = manifest.variants
        .groupBy(::qualityKey)
        .map { (key, variants) ->
            val best = variants.maxByOrNull { variant -> variant.replicas.maxOfOrNull { it.seeders } ?: 0 } ?: variants.first()
            PidTorQuality(
                key = key,
                label = key,
                width = best.video.width,
                height = best.video.height,
                bitrate = best.video.bitrate.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            )
        }
        .sortedWith(compareByDescending<PidTorQuality> { it.height }.thenByDescending { it.width }.thenBy { it.key })

    fun audioOptions(manifest: PidTorManifest, qualityKey: String): List<PidTorTrackChoice> =
        trackOptions(manifest, qualityKey, subtitle = false)

    fun subtitleOptions(manifest: PidTorManifest, qualityKey: String): List<PidTorTrackChoice> =
        trackOptions(manifest, qualityKey, subtitle = true)

    suspend fun resolveQuality(
        manifestUrl: String,
        manifest: PidTorManifest,
        qualityKey: String,
        playlist: List<MediaItem>,
        audioKey: String? = null,
        subtitleKey: String? = null
    ): PidTorResolvedSelection {
        val variants = manifest.variants.filter { qualityKey(it) == qualityKey }
        require(variants.isNotEmpty()) { "PidTor quality $qualityKey is unavailable" }
        val audio = preferredAudio(audioOptions(manifest, qualityKey), audioKey)
        val subtitle = preferredTrack(subtitleOptions(manifest, qualityKey), subtitleKey)
        val compatible = variants.filter { variant -> audio == null || variant.id == audio.variantId }.ifEmpty { variants }
        val replicas = compatible.flatMap { variant -> variant.replicas.map { variant to it } }
            .distinctBy { it.second.infohash ?: it.second.episodes_url ?: it.second.stream_url }
            .sortedByDescending { it.second.seeders }

        if (!manifest.serial) {
            val source = replicas.firstNotNullOfOrNull { it.second.stream_url }
                ?: error("PidTor movie stream is unavailable")
            return PidTorResolvedSelection(
                playlist.mapIndexed { index, item ->
                    if (index == 0) item.withPidTorSource(withAccess(source, manifestUrl), qualityKey, audio, subtitle) else item
                },
                audio,
                subtitle
            )
        }

        val episodeSources = resolveEpisodeSources(manifestUrl, replicas)
        val currentEpisode = playlist.firstOrNull()?.pidtor?.episode ?: manifest.episode
        require(episodeSources.containsKey(currentEpisode) || playlist.any { episodeSources.containsKey(it.pidtor?.episode) }) {
            "PidTor quality $qualityKey has no playable episodes"
        }

        return PidTorResolvedSelection(playlist.mapIndexed { index, item ->
            val episode = item.pidtor?.episode?.takeIf { it > 0 } ?: index + 1
            val source = episodeSources[episode] ?: return@mapIndexed item
            item.withPidTorSource(source, qualityKey, audio, subtitle)
        }, audio, subtitle)
    }

    private fun trackOptions(manifest: PidTorManifest, key: String, subtitle: Boolean): List<PidTorTrackChoice> {
        val byIdentity = linkedMapOf<String, PidTorTrackChoice>()
        manifest.variants.filter { qualityKey(it) == key }.forEach { variant ->
            val seeders = variant.replicas.maxOfOrNull { it.seeders } ?: 0
            val tracks = if (subtitle) variant.subtitles else variant.audio
            tracks.forEachIndexed { index, track ->
                val identity = trackIdentity(track, subtitle)
                val choice = PidTorTrackChoice(
                    key = "$key|${if (subtitle) "subtitles" else "audio"}|$identity",
                    label = trackLabel(track, index, subtitle),
                    language = normalizeLanguage(track.language),
                    streamIndex = track.stream_index,
                    variantId = variant.id,
                    qualityKey = key,
                    subtitle = subtitle,
                    seeders = seeders
                )
                if (byIdentity[identity] == null || seeders > (byIdentity[identity]?.seeders ?: -1)) {
                    byIdentity[identity] = choice
                }
            }
        }
        return byIdentity.values.sortedWith(
            compareByDescending<PidTorTrackChoice> { !subtitle && it.language == "ru" }
                .thenByDescending { it.seeders }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun preferredAudio(options: List<PidTorTrackChoice>, requested: String?): PidTorTrackChoice? =
        preferredTrack(options, requested)
            ?: options.filter { it.language == "ru" }.maxByOrNull { it.seeders }
            ?: options.maxByOrNull { it.seeders }

    private fun preferredTrack(options: List<PidTorTrackChoice>, requested: String?): PidTorTrackChoice? {
        if (requested.isNullOrBlank()) return null
        val normalizedRequested = normalize(requested)
        return options.firstOrNull { it.key == requested }
            ?: options.firstOrNull { normalizedRequested.endsWith(normalize(choiceIdentity(it))) }
    }

    private fun choiceIdentity(choice: PidTorTrackChoice): String =
        "${normalize(choice.label)}|${normalizeLanguage(choice.language)}"

    private fun trackIdentity(track: PidTorTrack, subtitle: Boolean): String =
        "${normalize(trackLabel(track, 0, subtitle))}|${normalizeLanguage(track.language)}"

    private fun trackLabel(track: PidTorTrack, index: Int, subtitle: Boolean): String {
        val raw = track.title.orEmpty()
            .replace(Regex("^\\s*\\d+\\s*[/.|:_-]+\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*(?:ru|rus|russian|en|eng|english|ja|jpn|japanese)\\s*[/.|:_-]+\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        if (raw.isNotBlank() && !Regex("^(?:audio|track|sound|subtitle|sub)\\s*#?\\d*$", RegexOption.IGNORE_CASE).matches(raw)) {
            return raw
        }
        return when (normalizeLanguage(track.language)) {
            "ru" -> if (subtitle) "Русские" else "Русская дорожка"
            "en" -> if (subtitle) "English" else "Original"
            "ja" -> if (subtitle) "Japanese" else "Оригинал"
            else -> if (subtitle) "Субтитры ${index + 1}" else "Аудиодорожка ${index + 1}"
        }
    }

    private fun normalizeLanguage(value: String?): String = when (value.orEmpty().trim().lowercase()) {
        "ru", "rus", "russian" -> "ru"
        "en", "eng", "english" -> "en"
        "ja", "jpn", "japanese" -> "ja"
        else -> value.orEmpty().trim().lowercase()
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\[\\](){}]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private suspend fun resolveEpisodeSources(
        manifestUrl: String,
        replicas: List<Pair<PidTorVariant, PidTorReplica>>
    ): Map<Int, String> = coroutineScope {
        replicas.take(12).mapNotNull { (_, replica) ->
            val url = replica.episodes_url ?: return@mapNotNull null
            async(Dispatchers.IO) {
                runCatching {
                    val endpoint = withAccess(url, manifestUrl)
                    val response = episodeCache[endpoint]
                        ?: getJson(endpoint, PidTorEpisodes::class.java).also { episodeCache[endpoint] = it }
                    replica.seeders to response.data
                }.onFailure { error ->
                    Log.w(TAG, "episodes failed url=$url", error)
                }.getOrNull()
            }
        }.awaitAll()
            .filterNotNull()
            .sortedByDescending { it.first }
            .fold(linkedMapOf()) { result, (_, episodes) ->
                episodes.forEach { episode ->
                    if (episode.e > 0 && !episode.url.isNullOrBlank() && !result.containsKey(episode.e)) {
                        result[episode.e] = withAccess(episode.url, manifestUrl)
                    }
                }
                result
            }
    }

    private fun MediaItem.withPidTorSource(
        source: String,
        key: String,
        audio: PidTorTrackChoice?,
        subtitle: PidTorTrackChoice?
    ): MediaItem = copy(
        uri = Uri.parse(source),
        pidtor = pidtor?.copy(qualityKey = key, audioKey = audio?.key, subtitleKey = subtitle?.key)
    )

    private fun qualityKey(variant: PidTorVariant): String {
        val suffix = when (variant.video.hdr?.lowercase()) {
            "dolby_vision" -> " DV"
            "hdr10_plus" -> " HDR10+"
            "hdr10" -> " HDR"
            "hlg" -> " HLG"
            else -> ""
        }
        return (variant.video.quality.ifBlank { "SD" } + suffix).trim()
    }

    private fun withAccess(source: String, manifestUrl: String): String {
        val manifestUri = Uri.parse(manifestUrl)
        val parsedSource = Uri.parse(source)
        val sourceUri = when {
            parsedSource.host == "127.0.0.1" || parsedSource.host == "localhost" -> parsedSource.buildUpon()
                .scheme(manifestUri.scheme)
                .encodedAuthority(manifestUri.encodedAuthority)
                .build()
            parsedSource.scheme.isNullOrBlank() -> manifestUri.buildUpon()
                .encodedPath(parsedSource.encodedPath)
                .encodedQuery(parsedSource.encodedQuery)
                .fragment(parsedSource.fragment)
                .build()
            else -> parsedSource
        }
        val builder = sourceUri.buildUpon()
        listOf("account_email", "uid", "token").forEach { key ->
            if (sourceUri.getQueryParameter(key).isNullOrBlank()) {
                manifestUri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(key, it) }
            }
        }
        if (sourceUri.path?.contains("/lite/pidtor/s") == true && sourceUri.getQueryParameter("raw") != "true") {
            builder.appendQueryParameter("raw", "true")
        }
        return builder.build().toString()
    }

    private fun manifestCacheKey(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.sorted().filterNot { it == "e" }.forEach { key ->
            uri.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
        }
        return builder.build().toString()
    }

    private fun <T> getJson(url: String, type: Class<T>): T {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) {
                "PidTor HTTP ${response.code} url=$url body=${body.take(400)}"
            }
            return gson.fromJson(body, type)
        }
    }

    private companion object {
        const val TAG = "DDDPlayer/PidTor"
        val manifestCache = ConcurrentHashMap<String, PidTorManifest>()
        val episodeCache = ConcurrentHashMap<String, PidTorEpisodes>()
    }
}
