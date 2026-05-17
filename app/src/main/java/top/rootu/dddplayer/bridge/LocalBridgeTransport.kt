package top.rootu.dddplayer.bridge

class LocalBridgeTransport(private val config: BridgeConfig, private val store: LocalBridgeStore) : BridgeTransport {
    override fun send(envelope: BridgeEnvelope) {
        store.append(envelope)
    }
}
