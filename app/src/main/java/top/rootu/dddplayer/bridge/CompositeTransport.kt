package top.rootu.dddplayer.bridge

class CompositeTransport(private val transports: List<BridgeTransport>) : BridgeTransport {
    override fun send(envelope: BridgeEnvelope) {
        transports.forEach { runCatching { it.send(envelope) } }
    }
}
