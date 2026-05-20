package top.rootu.dddplayer.player.backend

enum class BufferProfile { FAST, BALANCED, ANDROID_TV, TORRSERVER, HEAVY, CUSTOM }

data class BufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

object BufferProfiles {
    fun defaults(profile: BufferProfile): BufferConfig = when (profile) {
        BufferProfile.FAST -> BufferConfig(15000, 50000, 500, 2000)
        BufferProfile.BALANCED -> BufferConfig(30000, 60000, 1500, 2500)
        BufferProfile.ANDROID_TV -> BufferConfig(50000, 90000, 3000, 2500)
        BufferProfile.TORRSERVER -> BufferConfig(70000, 120000, 4000, 3000)
        BufferProfile.HEAVY -> BufferConfig(90000, 150000, 5000, 4000)
        BufferProfile.CUSTOM -> BufferConfig(30000, 60000, 1500, 2500)
    }
}

