package top.rootu.dddplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_settings")
data class VideoSettings(
    @PrimaryKey val uri: String,
    val lastUpdated: Long,
    val lastPosition: Long = 0L,
    val duration: Long = 0L,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null
)
