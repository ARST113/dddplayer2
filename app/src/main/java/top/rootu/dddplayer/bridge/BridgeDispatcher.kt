package top.rootu.dddplayer.bridge

import android.util.Log

class BridgeDispatcher(
    private val config: BridgeConfig,
    private val transport: BridgeTransport
) {
    fun emit(event: BridgeEvent) {
        if (!config.enabled) return
        try {
            transport.send(BridgeEnvelope.from(config, event))
        } catch (e: Exception) {
            Log.w("DDDPlayerBridge", "Failed to send bridge event: ${event::class.simpleName}", e)
        }
    }
}
