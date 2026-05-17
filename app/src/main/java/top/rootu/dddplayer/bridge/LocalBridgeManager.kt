package top.rootu.dddplayer.bridge

import android.util.Log
import android.os.Handler
import android.os.Looper

object LocalBridgeManager {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null
    private var generation = 0L
    private var server: LocalBridgeServer? = null
    private var store: LocalBridgeStore? = null
    private var host: String = "127.0.0.1"
    private var port: Int = 39677
    private var token: String? = null

    @Synchronized
    fun startOrReuse(config: BridgeConfig): LocalBridgeStore {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
        generation++
        if (server != null && config.localPort == port && config.localHost == host && config.localToken == token && store != null) {
            return store!!
        }
        stopNowLocked()
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
        pendingStop?.let { handler.removeCallbacks(it) }
        val stopGeneration = generation
        val task = Runnable {
            synchronized(this) {
                if (generation == stopGeneration) {
                    stopNowLocked()
                }
            }
        }
        pendingStop = task
        handler.postDelayed(task, delayMs)
    }

    @Synchronized
    fun stopNow() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
        generation++
        stopNowLocked()
    }

    private fun stopNowLocked() {
        runCatching { server?.stop() }
        server = null
        store = null
    }
}
