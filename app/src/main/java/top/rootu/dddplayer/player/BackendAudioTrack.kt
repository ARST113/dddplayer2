package top.rootu.dddplayer.player

data class BackendAudioTrack(
    val id: Int,
    val label: String,
    val selected: Boolean = false
)
