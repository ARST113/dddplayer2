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
import top.rootu.dddplayer.bridge.BridgeConfig
import top.rootu.dddplayer.bridge.BridgeMode
import top.rootu.dddplayer.bridge.DddSyncContext
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

object IntentUtils {
    private const val DDD_QUERY_PREFIX = "ddd_"

    private fun stripDddMetadata(uri: Uri?): Uri? {
        if (uri == null) return null

        val filteredQuery = uri.encodedQuery
            ?.split("&")
            ?.filter { part ->
                val encodedKey = part.substringBefore('=')
                val key = try {
                    Uri.decode(encodedKey)
                } catch (_: Throwable) {
                    encodedKey
                }
                !key.startsWith(DDD_QUERY_PREFIX)
            }
            ?.joinToString("&")
            .orEmpty()

        return uri.buildUpon()
            .encodedQuery(filteredQuery.takeIf { it.isNotBlank() })
            .fragment(null)
            .build()
    }

    private fun normalizePlaybackUri(uri: Uri?): String {
        if (uri == null) return ""
        val clean = stripDddMetadata(uri) ?: return ""
        return clean.toString().replace("%20", " ").trim()
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
            // --- PLAYLIST MODE (Внутренний запуск) ---
            return parseInternalPlaylist(extras, videoListUris, dataUri)
        }

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
        val uri = stripDddMetadata(rawUri) ?: return Pair(emptyList(), 0)
        val extras = intent.extras ?: Bundle.EMPTY

        // Пытаемся найти заголовок в Extras (некоторые приложения передают его)
        var title = extras.getString("title") ?: extras.getString("android.intent.extra.TITLE")

        // Если заголовка нет, пытаемся получить имя файла из URI
        val filename = resolveFileName(context, uri)

        if (title.isNullOrEmpty()) {
            title = filename ?: uri.lastPathSegment ?: "Video"
        }

        val syncContext = parseDddSyncContext(rawUri, title, filename)
        val startPosition = getLongExtraCompat(extras, "position", 0L)
            .takeIf { it > 0L }
            ?: syncContext?.lampaPositionMs?.takeIf { it > 0L }
            ?: 0L
        // Single poster
        val singlePoster = extras.getString("thumbnail")
        // Single Video Subtitles
        val singleSubs = parseSubtitles(extras, "subs")

        val item = MediaItem(
            uri = uri,
            title = title,
            filename = filename,
            posterUri = singlePoster?.toUri(),
            headers = parseHeaders(extras),
            subtitles = singleSubs,
            startPositionMs = startPosition,
            dddSyncContext = syncContext
        )

        return Pair(listOf(item), 0)
    }

    private fun parseInternalPlaylist(
        extras: Bundle,
        videoListUris: Array<Parcelable>,
        dataUri: Uri?
    ): Pair<List<MediaItem>, Int> {
        val cleanDataUri = stripDddMetadata(dataUri)
        val names = getSmartStringArray(extras, "video_list.name")
        val filenames = getSmartStringArray(extras, "video_list.filename")
        val posters = getSmartStringArray(extras, "video_list.thumbnail")
        val playlistSubsBundles = getParcelableArrayListCompat<Bundle>(extras, "video_list.subtitles")

        val headersMap = parseHeaders(extras)

        val playlist = mutableListOf<MediaItem>()
        val extrasStartIndex = extras.getInt("start_index", 0)
        var matchedStartIndex: Int? = null

        for (i in videoListUris.indices) {
            val rawUri = ((videoListUris[i] as? Uri) ?: (videoListUris[i] as? String)?.toUri()) ?: continue
            val uri = stripDddMetadata(rawUri) ?: continue

            var title = names?.getOrNull(i)
            if (title.isNullOrEmpty()) title = filenames?.getOrNull(i)
            if (title.isNullOrEmpty()) title = uri.lastPathSegment
            val syncContext = parseDddSyncContext(rawUri, title, filenames?.getOrNull(i))

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
                0L
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
                .filter { it.startsWith(DDD_QUERY_PREFIX) }
                .associateWith { uri.getQueryParameter(it).orEmpty() }
        } catch (_: Throwable) {
            emptyMap()
        }

        // Fragment values win in browsers; query values survive Android Intents.
        return queryParams + parseFragmentParams(uri.fragment)
    }

    private fun parseDddSyncContext(uri: Uri?, title: String?, filename: String?): DddSyncContext? {
        val params = parseDddParams(uri)
        val remoteEventsUrl = params["ddd_remote_events_url"] ?: return null
        val deviceId = params["ddd_device_id"] ?: return null
        val cleanUri = stripDddMetadata(uri)?.toString()

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


    private fun parseHeaders(extras: Bundle): Map<String, String> {
        val headersArray = getSmartStringArray(extras, "headers") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (i in 0 until headersArray.size - 1 step 2) {
            val key = headersArray[i]
            val value = headersArray[i + 1]
            if (key.isNotBlank()) result[key] = value
        }
        return result
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
