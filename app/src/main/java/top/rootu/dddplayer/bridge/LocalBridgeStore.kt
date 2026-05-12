package top.rootu.dddplayer.bridge

class LocalBridgeStore(private val maxEvents: Int = 200) {
    private val lock = Any()
    private val states = mutableMapOf<String, BridgeEnvelope>()
    private val events = mutableMapOf<String, ArrayDeque<BridgeEnvelope>>()
    private fun key(sessionId: String?) = sessionId ?: "__default__"

    fun append(envelope: BridgeEnvelope) = synchronized(lock) {
        val k = key(envelope.sessionId)
        states[k] = envelope
        val q = events.getOrPut(k) { ArrayDeque() }
        q.addLast(envelope)
        while (q.size > maxEvents) q.removeFirst()
    }

    fun getState(sessionId: String?): BridgeEnvelope? = synchronized(lock) { states[key(sessionId)] }

    fun getEvents(sessionId: String?, sinceTs: Long? = null, limit: Int? = null): List<BridgeEnvelope> = synchronized(lock) {
        val list = events[key(sessionId)]?.toList().orEmpty().filter { sinceTs == null || it.ts >= sinceTs }
        if (limit == null || limit <= 0) list else list.takeLast(limit)
    }

    fun clear(sessionId: String?) = synchronized(lock) {
        val k = key(sessionId); states.remove(k); events.remove(k)
    }
}
