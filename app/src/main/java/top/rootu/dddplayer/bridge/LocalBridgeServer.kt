package top.rootu.dddplayer.bridge

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LocalBridgeServer(private val host: String = "127.0.0.1", private val port: Int = 39677, private val token: String?, private val store: LocalBridgeStore) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val gson = Gson()

    fun start() {
        if (server != null) return
        server = ServerSocket(port, 50, InetAddress.getByName(host))
        thread(name = "ddd-local-bridge", isDaemon = true) {
            while (server?.isClosed == false) {
                val socket = runCatching { server?.accept() }.getOrNull() ?: break
                pool.execute {
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val request = reader.readLine() ?: return@execute
                        val parts = request.split(" ")
                        val method = parts.getOrNull(0) ?: "GET"
                        val pathWithQuery = parts.getOrNull(1) ?: "/"
                        while (reader.readLine()?.isNotEmpty() == true) {}
                        val (path, query) = pathWithQuery.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
                        val q = parseQuery(query)
                        if (method != "GET" && method != "OPTIONS") return@execute write(s, 405, mapOf("error" to "method_not_allowed"))
                        val body = when {
                            method == "OPTIONS" -> mapOf("ok" to true)
                            path == "/ping" -> mapOf("ok" to true, "service" to "dddplayer-local-bridge", "port" to port)
                            path == "/state" -> {
                                if (!checkToken(q)) return@execute write(s, 403, mapOf("error" to "forbidden"))
                                mapOf("ok" to true, "state" to store.getStateView(q["sid"]))
                            }
                            path == "/events" -> {
                                if (!checkToken(q)) return@execute write(s, 403, mapOf("error" to "forbidden"))
                                mapOf("ok" to true, "events" to store.getEvents(q["sid"], q["since"]?.toLongOrNull(), q["limit"]?.toIntOrNull()))
                            }
                            else -> mapOf("error" to "not_found")
                        }
                        write(s, 200, body)
                    }
                }
            }
        }
    }

    private fun checkToken(query: Map<String, String>) = token.isNullOrBlank() || token == query["token"]
    private fun parseQuery(query: String?): Map<String, String> = query?.split('&')?.mapNotNull {
        val p = it.split('=', limit = 2); if (p.isEmpty()) null else URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1){""}, "UTF-8")
    }?.toMap().orEmpty()

    private fun write(socket: java.net.Socket, code: Int, body: Any) {
        val json = gson.toJson(body)
        val status = if (code == 200) "200 OK" else if (code == 403) "403 Forbidden" else "404 Not Found"
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nContent-Length: ${json.toByteArray().size}\r\n\r\n$json").toByteArray())
        out.flush()
    }

    fun stop() { runCatching { server?.close() }; server = null; pool.shutdownNow() }
}
