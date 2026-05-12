package top.rootu.dddplayer.bridge

class LocalBridgeTransport(private val config: BridgeConfig, private val store: LocalBridgeStore) : BridgeTransport {
    override fun send(event: BridgeEvent) {
        store.append(BridgeEnvelope.from(config, event))
    }
}
