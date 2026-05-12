package top.rootu.dddplayer.bridge

class LocalBridgeStore(private val maxEvents: Int = 200) {
    data class LocalBridgeState(
        val sessionId: String?,
        val ts: Long,
        val uri: String?,
        val position: Long?,
        val duration: Long?,
        val title: String?,
        val windowIndex: Int?,
        val playlistSize: Int?,
        val finished: Boolean,
        val endBy: String?,
        val error: BridgeEvent.Error?,
        val currentAudioTrack: BridgeEvent.TrackSelectionChanged?,
        val currentSubtitleTrack: BridgeEvent.TrackSelectionChanged?
    )

    private val lock = Any()
    private val states = mutableMapOf<String, BridgeEnvelope>()
    private val stateView = mutableMapOf<String, LocalBridgeState>()
    private val events = mutableMapOf<String, ArrayDeque<BridgeEnvelope>>()
    private fun key(sessionId: String?) = sessionId ?: "__default__"

    fun append(envelope: BridgeEnvelope) = synchronized(lock) {
        val k = key(envelope.sessionId)
        states[k] = envelope
        val q = events.getOrPut(k) { ArrayDeque() }
        q.addLast(envelope)
        while (q.size > maxEvents) q.removeFirst()
        val prev = stateView[k]
        stateView[k] = when (val p = envelope.payload) {
            is BridgeEvent.PositionTick -> LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, null, false, null, prev?.error, prev?.currentAudioTrack, prev?.currentSubtitleTrack)
            is BridgeEvent.PlaybackStateChanged -> LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, null, false, null, prev?.error, prev?.currentAudioTrack, prev?.currentSubtitleTrack)
            is BridgeEvent.PlaylistItemChanged -> LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, p.playlistSize, false, null, prev?.error, prev?.currentAudioTrack, prev?.currentSubtitleTrack)
            is BridgeEvent.PlaybackEnded -> LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, p.playlistSize, true, "ended", prev?.error, prev?.currentAudioTrack, prev?.currentSubtitleTrack)
            is BridgeEvent.SessionFinished -> LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, p.playlistSize, true, p.endBy, prev?.error, prev?.currentAudioTrack, prev?.currentSubtitleTrack)
            is BridgeEvent.Error -> (prev ?: LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, p.position, p.duration, p.title, p.windowIndex, p.playlistSize, true, "error", null, null, null)).copy(error = p, finished = true, endBy = "error")
            is BridgeEvent.TrackSelectionChanged -> {
                val base = prev ?: LocalBridgeState(envelope.sessionId, envelope.ts, p.uri, null, null, null, null, null, false, null, null, null, null)
                if (p.trackType == "audio") base.copy(currentAudioTrack = p, ts = envelope.ts, uri = p.uri) else base.copy(currentSubtitleTrack = p, ts = envelope.ts, uri = p.uri)
            }
            else -> prev ?: LocalBridgeState(envelope.sessionId, envelope.ts, envelope.payload.uri, null, null, null, null, null, false, null, null, null, null)
        }
    }

    fun getState(sessionId: String?): BridgeEnvelope? = synchronized(lock) { states[key(sessionId)] }
    fun getStateView(sessionId: String?): LocalBridgeState? = synchronized(lock) { stateView[key(sessionId)] }

    fun getEvents(sessionId: String?, sinceTs: Long? = null, limit: Int? = null): List<BridgeEnvelope> = synchronized(lock) {
        val list = events[key(sessionId)]?.toList().orEmpty().filter { sinceTs == null || it.ts >= sinceTs }
        if (limit == null || limit <= 0) list else list.takeLast(limit)
    }

    fun clear(sessionId: String?) = synchronized(lock) {
        val k = key(sessionId); states.remove(k); events.remove(k)
        stateView.remove(k)
    }
}
