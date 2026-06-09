package top.rootu.dddplayer.player

data class BackendAudioTrack(
    val id: Int,
    val label: String,
    val selected: Boolean = false,
    val codec: String? = null,
    val originalCodec: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val language: String? = null,
    val description: String? = null
)
