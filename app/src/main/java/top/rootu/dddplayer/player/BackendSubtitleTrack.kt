package top.rootu.dddplayer.player

data class BackendSubtitleTrack(
    val id: Int,
    val label: String,
    val selected: Boolean = false,
    val codec: String? = null,
    val language: String? = null,
    val isBitmap: Boolean = false
)
