package top.rootu.dddplayer.bridge

class LocalBridgeStore(private val maxEvents: Int = 200) {
    data class LocalBridgeState(
        val schema: Int,
        val sessionId: String?,
        val client: String,
        val updatedAt: Long,
        val uri: String?,
        val position: Long?,
        val duration: Long?,
        val bufferedPosition: Long?,
        val bufferedPercentage: Int?,
        val title: String?,
        val windowIndex: Int?,
        val playlistSize: Int?,
        val isPlaying: Boolean?,
        val isBuffering: Boolean?,
        val lastEventType: String?,
        val lastReason: String?,
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
        val alreadyFinished = prev?.finished == true
        stateView[k] = when (val p = envelope.payload) {
            is BridgeEvent.PositionTick -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,p.bufferedPosition,p.bufferedPercentage,p.title,p.windowIndex,null,null,null,envelope.type,p.reason,false,null,null,null,null))
                .copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, bufferedPosition = p.bufferedPosition, bufferedPercentage = p.bufferedPercentage, title = p.title, windowIndex = p.windowIndex, lastEventType = envelope.type, lastReason = p.reason, finished = alreadyFinished, endBy = prev?.endBy)
            is BridgeEvent.PlaybackStateChanged -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,null,null,p.title,p.windowIndex,null,p.isPlaying,p.isBuffering,envelope.type,p.reason,false,null,null,null,null))
                .copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, title = p.title, windowIndex = p.windowIndex, isPlaying = p.isPlaying, isBuffering = p.isBuffering, lastEventType = envelope.type, lastReason = p.reason, finished = alreadyFinished, endBy = prev?.endBy)
            is BridgeEvent.SeekCompleted -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.toPosition,null,null,null,null,p.windowIndex,null,null,null,envelope.type,null,false,null,null,null,null)).copy(updatedAt = envelope.ts, uri = p.uri, position = p.toPosition, windowIndex = p.windowIndex, lastEventType = envelope.type, lastReason = "seek", finished = alreadyFinished, endBy = prev?.endBy)
            is BridgeEvent.PlaylistItemChanged -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,null,null,p.title,p.windowIndex,p.playlistSize,null,null,envelope.type,p.reason,false,null,null,null,null)).copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, title = p.title, windowIndex = p.windowIndex, playlistSize = p.playlistSize, lastEventType = envelope.type, lastReason = p.reason, finished = alreadyFinished, endBy = prev?.endBy)
            is BridgeEvent.PlaybackEnded -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,null,null,p.title,p.windowIndex,p.playlistSize,null,null,envelope.type,"ended",true,"ended",null,null,null)).copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, title = p.title, windowIndex = p.windowIndex, playlistSize = p.playlistSize, lastEventType = envelope.type, lastReason = "ended", finished = true, endBy = "ended")
            is BridgeEvent.SessionFinished -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,null,null,p.title,p.windowIndex,p.playlistSize,null,null,envelope.type,p.endBy,true,p.endBy,null,null,null)).copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, title = p.title, windowIndex = p.windowIndex, playlistSize = p.playlistSize, lastEventType = envelope.type, lastReason = p.endBy, finished = true, endBy = p.endBy)
            is BridgeEvent.Error -> (prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,p.position,p.duration,p.bufferedPosition,p.bufferedPercentage,p.title,p.windowIndex,p.playlistSize,null,null,envelope.type,"error",true,"error",null,null,null)).copy(updatedAt = envelope.ts, uri = p.uri, position = p.position, duration = p.duration, bufferedPosition = p.bufferedPosition, bufferedPercentage = p.bufferedPercentage, title = p.title, windowIndex = p.windowIndex, playlistSize = p.playlistSize, lastEventType = envelope.type, lastReason = "error", error = p, finished = true, endBy = "error")
            is BridgeEvent.TrackSelectionChanged -> {
                val base = prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,p.uri,null,null,null,null,null,null,null,null,null,envelope.type,p.reason,false,null,null,null,null)
                if (p.trackType == "audio") base.copy(currentAudioTrack = p, updatedAt = envelope.ts, uri = p.uri, lastEventType = envelope.type, lastReason = p.reason) else base.copy(currentSubtitleTrack = p, updatedAt = envelope.ts, uri = p.uri, lastEventType = envelope.type, lastReason = p.reason)
            }
            else -> prev ?: LocalBridgeState(envelope.schema,envelope.sessionId,envelope.client,envelope.ts,envelope.payload.uri,null,null,null,null,null,null,null,null,null,envelope.type,null,false,null,null,null,null)
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
