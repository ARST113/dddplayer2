package top.rootu.dddplayer.bridge

interface BridgeTransport {
    fun send(envelope: BridgeEnvelope)
}
