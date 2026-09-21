package io.github.mangi.eta.agent.model.oauth

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

internal class OAuthCallbackServer(
    private val port: Int,
    private val onCode: (code: String, state: String?) -> Unit,
) {
    @Volatile private var sockets: List<ServerSocket> = emptyList()
    @Volatile private var running = false
    @Volatile var onFailure: ((Throwable) -> Unit)? = null

    fun start() {
        running = true
        val bound = bindLocalSockets(port)
        if (bound.isEmpty()) {
            running = false
            error("Cannot listen on localhost:$port; make sure no other app is using that port")
        }
        sockets = bound
        bound.forEach { socket ->
            Thread({
                try {
                    while (running) {
                        val client = socket.accept()
                        handleClient(client)
                    }
                } catch (_: Exception) {
                }
            }, "eta-oauth-callback").apply { isDaemon = true }.start()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            drainHeaders(reader)
            if (requestLine.startsWith("OPTIONS")) {
                write(client, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
                return
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val uri = Uri.parse("http://localhost${parts[1]}")
            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
            write(
                client,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html",
            )
            if (!OAuthCallback.isRedirect(uri)) return
            val (code, state) = OAuthCallback.parse(uri)
            onCode(code, state)
            stop()
        } catch (error: Exception) {
            onFailure?.invoke(error)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun drainHeaders(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
    }

    private fun write(client: Socket, payload: String) {
        val out = client.getOutputStream()
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun stop() {
        running = false
        sockets.forEach { runCatching { it.close() } }
        sockets = emptyList()
    }

    companion object {
        private fun bindLocalSockets(port: Int): List<ServerSocket> {
            val hosts = listOf("127.0.0.1", "::1")
            val bound = mutableListOf<ServerSocket>()
            for (host in hosts) {
                val socket = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName(host), port))
                    }
                }.getOrNull()
                if (socket != null) bound += socket
            }
            if (bound.isEmpty()) {
                runCatching { ServerSocket(port) }.getOrNull()?.let { bound += it }
            }
            return bound
        }
    }
}
