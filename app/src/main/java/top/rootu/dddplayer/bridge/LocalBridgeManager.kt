package top.rootu.dddplayer.bridge

import android.util.Log

object LocalBridgeManager {
    private var server: LocalBridgeServer? = null
    private var store: LocalBridgeStore? = null
    private var host: String = "127.0.0.1"
    private var port: Int = 39677
    private var token: String? = null

    @Synchronized
    fun startOrReuse(config: BridgeConfig): LocalBridgeStore {
        if (server != null && config.localPort == port && config.localHost == host && config.localToken == token && store != null) {
            return store!!
        }
        stopNow()
        host = config.localHost
        port = config.localPort
        token = config.localToken
        val createdStore = LocalBridgeStore(config.localMaxEvents)
        store = createdStore
        server = LocalBridgeServer(host, port, token, createdStore)
        server?.start()
        Log.i("DDDLocalBridge", "Local bridge started on $host:$port")
        return createdStore
    }

    @Synchronized
    fun stopDelayed(delayMs: Long = 5000L) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopNow() }, delayMs)
    }

    @Synchronized
    fun stopNow() {
        runCatching { server?.stop() }
        server = null
        store = null
    }
}
