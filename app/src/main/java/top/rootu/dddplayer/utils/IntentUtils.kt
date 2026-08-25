package top.rootu.dddplayer.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import top.rootu.dddplayer.bridge.BridgeConfig
import top.rootu.dddplayer.bridge.BridgeMode
import top.rootu.dddplayer.bridge.DddSyncContext
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

object IntentUtils {
    private const val DDD_QUERY_PREFIX = "ddd_"
    private const val DDD_SYNC_HEADER = "X-Lampa-DDD-Sync"

    /**
     * Lampa duplicates DDD bridge metadata in both the query and fragment so that
     * different external players can consume it.  Those parameters belong to the
     * player integration, not to the media endpoint: forwarding them to strict
     * endpoints such as PiTor makes an otherwise valid URL return HTTP 400.
     */
    private fun cleanPlaybackUri(uri: Uri?): Uri? {
        if (uri == null) return null
        val cleanEncodedQuery = uri.encodedQuery
            ?.split("&")
            ?.filterNot { part ->
                val encodedKey = part.substringBefore("=")
                Uri.decode(encodedKey).startsWith("ddd_", ignoreCase = true)
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }

        return uri.buildUpon()
            .encodedQuery(cleanEncodedQuery)
            .fragment(null)
            .build()
    }

    private fun normalizePlaybackUri(uri: Uri?): String {
        return cleanPlaybackUri(uri)
            ?.toString()
            ?.replace("%20", " ")
            ?.trim()
            .orEmpty()
    }

    private fun getQueryParamSafe(uri: Uri?, key: String): String? = try {
        uri?.getQueryParameter(key)
    } catch (_: Throwable) {
        null
    }

    private fun torrServerIdentity(uri: Uri?): Pair<String?, String?> {
        return getQueryParamSafe(uri, "link") to getQueryParamSafe(uri, "index")
    }

    private fun lastPathSegmentNormalized(uri: Uri?): String? {
        return uri?.lastPathSegment?.replace("%20", " ")?.trim()?.lowercase()
    }

    private fun samePlaybackItem(a: Uri?, b: Uri?): Boolean {
        if (a == null || b == null) return false
        val na = normalizePlaybackUri(a)
        val nb = normalizePlaybackUri(b)
        if (na == nb) return true
        val (aLink, aIndex) = torrServerIdentity(a)
        val (bLink, bIndex) = torrServerIdentity(b)
        if (!aLink.isNullOrBlank() && !bLink.isNullOrBlank() && aLink == bLink && aIndex == bIndex) return true
        val aPath = lastPathSegmentNormalized(a)
        val bPath = lastPathSegmentNormalized(b)
        return !aPath.isNullOrBlank() && aPath == bPath
    }

    /**
     * Парсит Intent и возвращает список медиа-элементов и стартовую позицию.
     * Требует Context для разрешения имен файлов из content:// URI.
     */
    fun parseIntent(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        val dataUri = intent.data
        val extras = intent.extras ?: Bundle.EMPTY

        // 1. Проверяем, есть ли специфичный список воспроизведения (внутренний формат)
        val videoListUris = getParcelableArrayCompat(extras, "video_list")

        if (!videoListUris.isNullOrEmpty()) {
            // The sync envelope's sourceKey is an identity, not necessarily a
            // URL.  PidTor commonly sends `infohash|S|filename.mkv` there.
            // Always prefer the real transport URIs from video_list and only
            // merge progress/identity metadata from X-Lampa-DDD-Sync.
            return parseInternalPlaylist(extras, videoListUris, dataUri)
        }

        // Lampa can also send a complete playlist only in the control header.
        // Use that fallback solely when every sourceKey is a real playable URI.
        parseLampaDddPlaylist(extras, dataUri)?.let { return it }

        // 2. Проверяем одиночный файл (Запуск из файлового менеджера или ACTION_VIEW)
        if (dataUri != null) {
            return parseSingleFile(context, intent)
        }

        // 3. Пусто
        return Pair(emptyList(), 0)
    }

    fun parseBridgeConfig(intent: Intent): BridgeConfig {
        val data = intent.data
        val fragmentParams = parseDddParams(data)
        val extrasMode = intent.getStringExtra("bridge_mode")
        val modeValue = fragmentParams["ddd_mode"] ?: extrasMode
        val mode = when (modeValue?.lowercase()) {
            "local" -> BridgeMode.LOCAL
            "both" -> BridgeMode.BOTH
            else -> BridgeMode.BROADCAST
        }
        val hasFragmentBridge = listOf("ddd_mode","ddd_sid","ddd_port","ddd_token","ddd_client").any { fragmentParams.containsKey(it) }

        return BridgeConfig(
            enabled = intent.getBooleanExtra("bridge_enabled", false) || hasFragmentBridge,
            sessionId = fragmentParams["ddd_sid"] ?: intent.getStringExtra("bridge_session_id"),
            mode = mode,
            emitPosition = intent.getBooleanExtra("bridge_emit_position", true),
            emitUserActions = intent.getBooleanExtra("bridge_emit_user_actions", true),
            positionIntervalMs = intent.getLongExtra("bridge_position_interval_ms", 1000L).coerceAtLeast(250L),
            client = fragmentParams["ddd_client"] ?: intent.getStringExtra("bridge_client") ?: "lampa",
            eventAction = intent.getStringExtra("bridge_event_action") ?: "top.rootu.dddplayer.bridge.EVENT",
            receiverPackage = intent.getStringExtra("bridge_receiver_package"),
            schemaVersion = intent.getIntExtra("bridge_schema_version", 1),
            localPort = (fragmentParams["ddd_port"]?.toIntOrNull() ?: intent.getIntExtra("bridge_local_port", 39677)).coerceIn(1024, 65535),
            localToken = fragmentParams["ddd_token"] ?: intent.getStringExtra("bridge_local_token")
        )
    }

    private fun parseSingleFile(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        val rawUri = intent.data ?: return Pair(emptyList(), 0)
        val uri = cleanPlaybackUri(rawUri) ?: return Pair(emptyList(), 0)
        val extras = intent.extras ?: Bundle.EMPTY

        // Пытаемся найти заголовок в Extras (некоторые приложения передают его)
        var title = extras.getString("title") ?: extras.getString("android.intent.extra.TITLE")

        // Если заголовка нет, пытаемся получить имя файла из URI
        val filename = resolveFileName(context, uri)

        if (title.isNullOrEmpty()) {
            title = filename ?: uri.lastPathSegment ?: "Video"
        }

        // The envelope is produced for this exact launch. Fragment parameters
        // can survive playlist navigation and therefore may describe the
        // previously played episode/movie. Prefer the fresh envelope and keep
        // URI metadata only as a compatibility fallback.
        val syncContext = parseLampaDddSyncContext(extras, uri, title, filename, null)
            ?: parseDddSyncContext(rawUri, title, filename)
        val startPosition = getLongExtraCompat(extras, "position", 0L)
            .takeIf { it > 0L }
            ?: syncContext?.lampaPositionMs?.takeIf { it > 0L }
            ?: 0L
        // Single poster
        val singlePoster = extras.getString("thumbnail")
        // Single Video Subtitles
        val singleSubs = parseSubtitles(extras, "subs")
        val headers = parseHeaders(extras)
        val item = MediaItem(
            uri = uri,
            title = title,
            filename = filename,
            posterUri = singlePoster?.toUri(),
            headers = headers,
            subtitles = singleSubs,
            startPositionMs = startPosition,
            dddSyncContext = syncContext
        )

        return Pair(listOf(item), 0)
    }

    private fun parseLampaDddPlaylist(
        extras: Bundle,
        dataUri: Uri?
    ): Pair<List<MediaItem>, Int>? {
        val root = parseLampaSyncEnvelope(extras) ?: return null
        val eventsUrl = root.stringOrNull("eventsUrl") ?: return null
        val deviceId = root.stringOrNull("deviceId") ?: return null
        val items = root.getAsJsonArray("items") ?: return null
        if (items.size() <= 1) return null

        val headers = parseHeaders(extras)
        val poster = parseFragmentParams(dataUri?.fragment)["ddd_poster"]
        val indexedItems = items.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val sourceKey = item.stringOrNull("sourceKey") ?: return@mapNotNull null
            val uri = cleanPlaybackUri(sourceKey.toUri()) ?: return@mapNotNull null
            // sourceKey is primarily an identity.  Only a known transport
            // scheme makes it safe to reuse as media; values such as
            // `hash|S|episode.mkv` or `show:s3:e6` must never reach DataSource.
            if (!isPlayableHeaderSource(uri)) return@mapNotNull null
            val index = item.intOrNull("index") ?: return@mapNotNull null
            val title = item.stringOrNull("title") ?: item.stringOrNull("filename") ?: "Video"
            val filename = item.stringOrNull("filename")
            val syncContext = DddSyncContext(
                remoteEventsUrl = eventsUrl,
                remoteLatestUrl = root.stringOrNull("latestUrl"),
                schema = root.intOrNull("schema") ?: 1,
                deviceId = deviceId,
                sessionId = root.stringOrNull("sessionId"),
                contentKey = item.stringOrNull("contentKey"),
                sourceKey = sourceKey,
                timelineHash = item.stringOrNull("timelineHash"),
                sourceKind = item.stringOrNull("sourceKind"),
                uri = uri.toString(),
                title = title,
                filename = filename,
                lampaPositionMs = item.longOrNull("positionMs"),
                lampaDurationMs = item.longOrNull("durationMs"),
                lampaPercent = item.intOrNull("percent"),
                lampaAudioTrack = item.stringOrNull("audioTrack"),
                lampaAudioTrackId = item.stringOrNull("audioTrackId"),
                lampaAudioTrackLanguage = item.stringOrNull("audioTrackLanguage"),
                lampaAudioTrackMimeType = item.stringOrNull("audioTrackMime")
            )
            index to MediaItem(
                uri = uri,
                title = title,
                filename = filename,
                posterUri = poster?.takeIf { it.isNotBlank() }?.toUri(),
                headers = headers,
                startPositionMs = item.longOrNull("positionMs")?.coerceAtLeast(0L) ?: 0L,
                dddSyncContext = syncContext
            )
        }.sortedBy { it.first }
        if (indexedItems.isEmpty() || indexedItems.size != items.size()) return null

        val activeIndex = root.intOrNull("activeIndex")
        val playlist = indexedItems.map { it.second }
        val startIndex = indexedItems
            .indexOfFirst { it.first == activeIndex }
            .takeIf { it >= 0 }
            ?: playlist.indexOfFirst { samePlaybackItem(it.uri, dataUri) }
                .takeIf { it >= 0 }
            ?: 0
        android.util.Log.i(
            "DDDPlayer/Intent",
            "parseLampaDddPlaylist activeIndex=$activeIndex finalStartIndex=$startIndex playlistSize=${playlist.size}"
        )
        return playlist to startIndex
    }

    private fun parseInternalPlaylist(
        extras: Bundle,
        videoListUris: Array<Parcelable>,
        dataUri: Uri?
    ): Pair<List<MediaItem>, Int> {
        val cleanDataUri = cleanPlaybackUri(dataUri)
        val names = getSmartStringArray(extras, "video_list.name")
        val filenames = getSmartStringArray(extras, "video_list.filename")
        val posters = getSmartStringArray(extras, "video_list.thumbnail")
        val playlistSubsBundles = getParcelableArrayListCompat<Bundle>(extras, "video_list.subtitles")

        val headersMap = parseHeaders(extras)
        val syncEnvelope = parseLampaSyncEnvelope(extras)
        val syncActiveIndex = syncEnvelope?.intOrNull("activeIndex")

        val playlist = mutableListOf<MediaItem>()
        val extrasStartIndex = extras.getInt("start_index", 0)
        var matchedStartIndex: Int? = null

        for (i in videoListUris.indices) {
            val rawUri = ((videoListUris[i] as? Uri) ?: (videoListUris[i] as? String)?.toUri()) ?: continue
            val uri = cleanPlaybackUri(rawUri) ?: continue

            var title = names?.getOrNull(i)
            if (title.isNullOrEmpty()) title = filenames?.getOrNull(i)
            if (title.isNullOrEmpty()) title = uri.lastPathSegment
            val syncContext = parseLampaDddSyncContext(extras, uri, title, filenames?.getOrNull(i), i)
                ?: parseDddSyncContext(rawUri, title, filenames?.getOrNull(i))

            val itemSubs = if (playlistSubsBundles != null && i < playlistSubsBundles.size) {
                parseSubtitles(playlistSubsBundles[i], "uris", "names")
            } else {
                emptyList()
            }

            val isCurrent = samePlaybackItem(uri, cleanDataUri)
            if (isCurrent) matchedStartIndex = playlist.size
            val pos = if (isCurrent) {
                getLongExtraCompat(extras, "position", 0L)
                    .takeIf { it > 0L }
                    ?: syncContext?.lampaPositionMs?.takeIf { it > 0L }
                    ?: 0L
            } else {
                syncContext?.lampaPositionMs?.takeIf { it > 0L } ?: 0L
            }

            playlist.add(
                MediaItem(
                    uri = uri,
                    title = title,
                    filename = filenames?.getOrNull(i),
                    posterUri = posters?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toUri(),
                    headers = headersMap,
                    subtitles = itemSubs,
                    startPositionMs = pos,
                    dddSyncContext = syncContext
                )
            )
        }
        val startIndex = when {
            playlist.isEmpty() -> 0
            syncActiveIndex != null -> syncActiveIndex.coerceIn(0, playlist.lastIndex)
            cleanDataUri != null && matchedStartIndex != null -> matchedStartIndex!!
            cleanDataUri != null -> 0
            else -> extrasStartIndex.coerceIn(0, playlist.lastIndex)
        }
        android.util.Log.i(
            "DDDPlayer/Intent",
            "parseInternalPlaylist dataUri=$cleanDataUri extrasStartIndex=$extrasStartIndex matchedStartIndex=$matchedStartIndex finalStartIndex=$startIndex playlistSize=${playlist.size}"
        )
        return Pair(playlist, startIndex)
    }

    private fun parseFragmentParams(fragment: String?): Map<String, String> {
        return fragment
            ?.split("&")
            ?.mapNotNull {
                val p = it.split("=", limit = 2)
                val key = Uri.decode(p.getOrNull(0) ?: return@mapNotNull null)
                val value = Uri.decode(p.getOrElse(1) { "" })
                key to value
            }
            ?.toMap()
            .orEmpty()
    }

    private fun parseDddParams(uri: Uri?): Map<String, String> {
        if (uri == null) return emptyMap()
        val queryParams = try {
            uri.queryParameterNames
                .filter { it.startsWith(DDD_QUERY_PREFIX, ignoreCase = true) }
                .associateWith { uri.getQueryParameter(it).orEmpty() }
        } catch (_: Throwable) {
            emptyMap()
        }
        // Fragment values win in browsers; query values survive Android Intents.
        return queryParams + parseFragmentParams(uri.fragment)
    }

    private fun isPlayableHeaderSource(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf(
            ContentResolver.SCHEME_CONTENT,
            ContentResolver.SCHEME_FILE,
            "http",
            "https",
            "rtsp",
            "rtmp",
            "udp"
        )

    private fun parseDddSyncContext(uri: Uri?, title: String?, filename: String?): DddSyncContext? {
        val params = parseDddParams(uri)
        val remoteEventsUrl = params["ddd_remote_events_url"] ?: return null
        val deviceId = params["ddd_device_id"] ?: return null
        val cleanUri = cleanPlaybackUri(uri)?.toString()

        return DddSyncContext(
            remoteEventsUrl = remoteEventsUrl,
            remoteLatestUrl = params["ddd_remote_latest_url"],
            schema = params["ddd_remote_schema"]?.toIntOrNull() ?: 1,
            deviceId = deviceId,
            sessionId = params["ddd_sid"] ?: params["bridge_session_id"],
            contentKey = params["ddd_content_key"],
            sourceKey = params["ddd_source_key"],
            timelineHash = params["ddd_timeline_hash"],
            sourceKind = params["ddd_source_kind"],
            uri = cleanUri,
            title = params["ddd_title"] ?: title,
            filename = params["ddd_filename"] ?: filename,
            lampaPositionMs = params["ddd_lampa_position"]?.toLongOrNull(),
            lampaDurationMs = params["ddd_lampa_duration"]?.toLongOrNull(),
            lampaPercent = params["ddd_lampa_percent"]?.toIntOrNull(),
            lampaAudioTrack = params["ddd_audio_track"],
            lampaAudioTrackId = params["ddd_audio_track_id"],
            lampaAudioTrackIndex = params["ddd_audio_track_index"]?.toIntOrNull(),
            lampaAudioTrackLanguage = params["ddd_audio_track_language"],
            lampaAudioTrackMimeType = params["ddd_audio_track_mime"],
            lampaAudioTrackChannelCount = params["ddd_audio_track_channels"]?.toIntOrNull()
        ).takeIf { it.enabled }
    }

    private fun parseLampaSyncEnvelope(extras: Bundle): JsonObject? {
        val payload = getSmartStringArray(extras, "headers")
            ?.asList()
            ?.chunked(2)
            ?.firstOrNull { pair ->
                pair.size == 2 && pair[0].equals(DDD_SYNC_HEADER, ignoreCase = true)
            }
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            JsonParser.parseString(Uri.decode(payload)).asJsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseLampaDddSyncContext(
        extras: Bundle,
        playbackUri: Uri,
        title: String?,
        filename: String?,
        playlistIndex: Int?
    ): DddSyncContext? {
        val root = parseLampaSyncEnvelope(extras) ?: return null
        val eventsUrl = root.stringOrNull("eventsUrl") ?: return null
        val deviceId = root.stringOrNull("deviceId") ?: return null
        val targetIndex = playlistIndex ?: root.intOrNull("activeIndex") ?: 0
        val items = root.getAsJsonArray("items") ?: return null
        val item = items
            .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .firstOrNull { (it.intOrNull("index") ?: -1) == targetIndex }
            ?: targetIndex.takeIf { it in 0 until items.size() }
                ?.let(items::get)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
            ?: return null

        return DddSyncContext(
            remoteEventsUrl = eventsUrl,
            remoteLatestUrl = root.stringOrNull("latestUrl"),
            schema = root.intOrNull("schema") ?: 1,
            deviceId = deviceId,
            sessionId = root.stringOrNull("sessionId"),
            contentKey = item.stringOrNull("contentKey"),
            sourceKey = item.stringOrNull("sourceKey"),
            timelineHash = item.stringOrNull("timelineHash") ?: root.stringOrNull("timelineHash"),
            sourceKind = item.stringOrNull("sourceKind"),
            uri = playbackUri.toString(),
            title = item.stringOrNull("title") ?: title,
            filename = item.stringOrNull("filename") ?: filename,
            lampaPositionMs = item.longOrNull("positionMs"),
            lampaDurationMs = item.longOrNull("durationMs"),
            lampaPercent = item.intOrNull("percent"),
            lampaAudioTrack = item.stringOrNull("audioTrack"),
            lampaAudioTrackId = item.stringOrNull("audioTrackId"),
            lampaAudioTrackIndex = item.intOrNull("audioTrackIndex"),
            lampaAudioTrackLanguage = item.stringOrNull("audioTrackLanguage"),
            lampaAudioTrackMimeType = item.stringOrNull("audioTrackMime"),
            lampaAudioTrackChannelCount = item.intOrNull("audioTrackChannels")
        ).takeIf { it.enabled }
    }


    private fun parseHeaders(extras: Bundle): Map<String, String> {
        val headersArray = getSmartStringArray(extras, "headers") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (i in 0 until headersArray.size - 1 step 2) {
            val key = headersArray[i]
            val value = headersArray[i + 1]
            // This header is a Lampa → player control channel.  Forwarding it
            // through redirects to the actual media server makes strict nginx /
            // TorrServer endpoints reject the request with HTTP 400.
            val isInternalDddHeader = key.startsWith("X-Lampa-DDD-", ignoreCase = true)
            if (key.isNotBlank() && !isInternalDddHeader) result[key] = value
        }
        return result
    }

    private fun JsonObject.stringOrNull(key: String): String? = try {
        get(key)?.takeUnless { it.isJsonNull }?.asString
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.intOrNull(key: String): Int? = try {
        get(key)?.takeUnless { it.isJsonNull }?.asInt
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.longOrNull(key: String): Long? = try {
        get(key)?.takeUnless { it.isJsonNull }?.asLong
    } catch (_: Throwable) {
        null
    }

    private fun getLongExtraCompat(bundle: Bundle, key: String, defaultValue: Long = 0L): Long {
        return when (val value = bundle.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    /**
     * Извлекает реальное имя файла из content:// URI.
     */
    private fun resolveFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            return cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Fallback для file:// или если query не сработал
        return uri.lastPathSegment
    }

    /**
     * Пытается извлечь массив строк любым доступным способом.
     * Поддерживает: String[], ArrayList<String>, CharSequence[]
     */
    private fun getSmartStringArray(bundle: Bundle, key: String): Array<String>? {
        val strArray = bundle.getStringArray(key)
        if (strArray != null) return strArray

        val strList = bundle.getStringArrayList(key)
        if (strList != null) return strList.toTypedArray()

        val charSeqArray = bundle.getCharSequenceArray(key)
        if (charSeqArray != null) {
            return charSeqArray.map { it.toString() }.toTypedArray()
        }

        val charSeqList = bundle.getCharSequenceArrayList(key)
        if (charSeqList != null) {
            return charSeqList.map { it.toString() }.toTypedArray()
        }

        return null
    }

    private fun parseSubtitles(bundle: Bundle, keyUri: String, keyName: String = "$keyUri.name"): List<SubtitleItem> {
        val uris = getParcelableArrayCompat(bundle, keyUri) ?: return emptyList()
        val names = getSmartStringArray(bundle, keyName)
        val filenames = getSmartStringArray(bundle, "$keyUri.filename")

        val list = mutableListOf<SubtitleItem>()
        for (i in uris.indices) {
            val uri = (uris[i] as? Uri) ?: (uris[i] as? String)?.toUri() ?: continue
            list.add(
                SubtitleItem(
                    uri,
                    names?.getOrNull(i),
                    filenames?.getOrNull(i),
                    MediaFormatHelper.getSubtitleMimeType(uri)
                )
            )
        }
        return list
    }

    // Универсальный метод для получения массива Parcelable (совместимость с API 33+)
    @Suppress("DEPRECATION")
    private fun getParcelableArrayCompat(bundle: Bundle, key: String): Array<Parcelable>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArray(key, Parcelable::class.java)
        } else {
            bundle.getParcelableArray(key)
        } ?: run {
            // Fallback: некоторые передают ArrayList вместо Array
            getParcelableArrayListCompat<Parcelable>(bundle, key)?.toTypedArray()
        } ?: run {
            // Fallback: строки
            bundle.getStringArrayList(key)?.map { it.toUri() }?.toTypedArray()
        }
    }

    // Универсальный метод для получения ArrayList (совместимость с API 33+)
    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> getParcelableArrayListCompat(bundle: Bundle, key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArrayList(key, T::class.java)
        } else {
            bundle.getParcelableArrayList(key)
        }
    }
}
