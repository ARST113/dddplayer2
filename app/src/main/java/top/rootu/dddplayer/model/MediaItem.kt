package top.rootu.dddplayer.model

import android.net.Uri
import top.rootu.dddplayer.bridge.DddSyncContext
import java.util.UUID

data class MediaItem(
    val uri: Uri,
    val title: String? = null,
    val filename: String? = null,
    val posterUri: Uri? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val startPositionMs: Long = 0,
    val dddSyncContext: DddSyncContext? = null,
    val pidtor: PidTorTransport? = null,
    // Уникальный ID для DiffUtil, генерируется автоматически при создании объекта
    val uuid: String = UUID.randomUUID().toString()
)

data class PidTorTransport(
    val manifestUrl: String,
    val schema: Int = 1,
    val qualityKey: String? = null,
    val audioKey: String? = null,
    val subtitleKey: String? = null,
    val season: Int = 0,
    val episode: Int = 0
)

data class SubtitleItem(
    val uri: Uri,
    val name: String? = null,
    val filename: String? = null,
    val mimeType: String? = null
)
