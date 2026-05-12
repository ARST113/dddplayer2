package top.rootu.dddplayer.bridge

class CompositeTransport(private val transports: List<BridgeTransport>) : BridgeTransport {
    override fun send(event: BridgeEvent) {
        transports.forEach { runCatching { it.send(event) } }
    }
}
