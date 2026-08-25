package top.rootu.dddplayer.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class LocalBridgeServerTest {
    @Test
    fun canRestartWithoutSubmittingToTerminatedPool() {
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalBridgeServer(port = port, token = null, store = LocalBridgeStore())
        try {
            repeat(12) {
                server.start()
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 1_000
                    socket.getOutputStream().apply {
                        write("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                        flush()
                    }
                    val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                    assertTrue(status.orEmpty().contains("200 OK"))
                }
                server.stop()
            }
        } finally {
            server.stop()
        }
    }
}
