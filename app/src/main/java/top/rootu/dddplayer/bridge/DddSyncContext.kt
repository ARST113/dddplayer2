package top.rootu.dddplayer.bridge

data class DddSyncContext(
    val remoteEventsUrl: String,
    val remoteLatestUrl: String? = null,
    val schema: Int = 1,
    val deviceId: String,
    val sessionId: String? = null,
    val playlistIndex: Int? = null,
    val playlistSize: Int? = null,
    val contentKey: String? = null,
    val sourceKey: String? = null,
    val timelineHash: String? = null,
    val sourceKind: String? = null,
    val uri: String? = null,
    val title: String? = null,
    val filename: String? = null,
    val lampaPositionMs: Long? = null,
    val lampaDurationMs: Long? = null,
    val lampaPercent: Int? = null,
    val lampaAudioTrack: String? = null,
    val lampaAudioTrackId: String? = null,
    val lampaAudioTrackIndex: Int? = null,
    val lampaAudioTrackLanguage: String? = null,
    val lampaAudioTrackMimeType: String? = null,
    val lampaAudioTrackChannelCount: Int? = null
) {
    val enabled: Boolean
        get() = remoteEventsUrl.isNotBlank() && deviceId.isNotBlank()
}
