package top.rootu.dddplayer.bridge

import android.util.Log

class CompositeTransport(private val transports: List<BridgeTransport>) : BridgeTransport {
    override fun send(envelope: BridgeEnvelope) {
        transports.forEach { transport ->
            runCatching { transport.send(envelope) }
                .onFailure { e -> Log.e("DDDPlayerBridge", "Transport failed: ${transport::class.java.simpleName}", e) }
        }
    }
}
