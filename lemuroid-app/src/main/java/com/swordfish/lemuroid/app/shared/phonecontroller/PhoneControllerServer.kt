package com.swordfish.lemuroid.app.shared.phonecontroller

import android.util.Base64
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP + WebSocket server (no external dependencies) used to turn a phone browser
 * into a gamepad. It serves a single HTML page on "/" and accepts WebSocket connections on "/ws".
 */
class PhoneControllerServer(
    private val pageProvider: () -> ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onClientConnected(client: Client)

        fun onClientDisconnected(client: Client)

        fun onClientMessage(
            client: Client,
            message: String,
        )
    }

    class Client internal constructor(val id: Int, private val socket: Socket) {
        private val output: OutputStream = socket.getOutputStream()

        // Writes never happen on the caller thread (it is often the main thread).
        private val sender = Executors.newSingleThreadExecutor()

        fun send(text: String) {
            runCatching { sender.execute { sendNow(text) } }
        }

        @Synchronized
        private fun sendNow(text: String) {
            runCatching {
                val payload = text.toByteArray(Charsets.UTF_8)
                val header = ByteArrayOutputStream()
                header.write(0x81)
                when {
                    payload.size < 126 -> header.write(payload.size)
                    else -> {
                        header.write(126)
                        header.write((payload.size shr 8) and 0xFF)
                        header.write(payload.size and 0xFF)
                    }
                }
                output.write(header.toByteArray())
                output.write(payload)
                output.flush()
            }.onFailure { close() }
        }

        @Synchronized
        internal fun sendControl(
            opcode: Int,
            payload: ByteArray,
        ) {
            runCatching {
                output.write(0x80 or opcode)
                output.write(payload.size)
                output.write(payload)
                output.flush()
            }
        }

        fun close() {
            runCatching { socket.close() }
            sender.shutdown()
        }
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val clientIds = AtomicInteger(1)
    private val clients = mutableSetOf<Client>()

    @Volatile
    var port: Int = -1
        private set

    @Synchronized
    fun start(): Boolean {
        if (serverSocket != null) return true

        val socket =
            PORTS.firstNotNullOfOrNull { candidate ->
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(candidate))
                    }
                }.getOrNull()
            } ?: return false

        serverSocket = socket
        port = socket.localPort
        val pool = Executors.newCachedThreadPool()
        executor = pool
        pool.execute { acceptLoop(socket, pool) }
        Timber.i("Phone controller server listening on port $port")
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clients) {
            clients.forEach { it.close() }
            clients.clear()
        }
        executor?.shutdownNow()
        executor = null
        port = -1
    }

    private fun acceptLoop(
        server: ServerSocket,
        pool: ExecutorService,
    ) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { pool.execute { handleSocket(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HTTP_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }

            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val isWebSocket = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

            when {
                isWebSocket -> handleWebSocket(socket, input, headers)
                path == "/" || path.startsWith("/?") || path.startsWith("/index") -> servePage(socket)
                else -> serveNotFound(socket)
            }
        } catch (e: Exception) {
            Timber.d(e, "Phone controller connection error")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun servePage(socket: Socket) {
        val body = pageProvider()
        val header =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun serveNotFound(socket: Socket) {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        headers: Map<String, String>,
    ) {
        val key = headers["sec-websocket-key"] ?: return
        val digest = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1))
        val accept = Base64.encodeToString(digest, Base64.NO_WRAP)
        val response =
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
        socket.getOutputStream().apply {
            write(response.toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        // The page sends a heartbeat every second: if nothing arrives for a while
        // (phone locked, Wi-Fi lost) we drop the client so that no button stays pressed.
        socket.soTimeout = WS_TIMEOUT_MS

        val client = Client(clientIds.getAndIncrement(), socket)
        synchronized(clients) { clients.add(client) }
        listener.onClientConnected(client)

        try {
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) break

                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) {
                    length = ((readByte(input) shl 8) or readByte(input)).toLong()
                } else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or readByte(input).toLong() }
                }
                if (length > MAX_FRAME_SIZE) break

                val mask = if (masked) readFully(input, 4) else null
                val payload = readFully(input, length.toInt())
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    OPCODE_TEXT -> listener.onClientMessage(client, String(payload, Charsets.UTF_8))
                    OPCODE_CLOSE -> break
                    OPCODE_PING -> client.sendControl(OPCODE_PONG, payload)
                    else -> Unit
                }
            }
        } catch (e: SocketTimeoutException) {
            Timber.i("Phone controller client ${client.id} timed out")
        } catch (e: Exception) {
            Timber.d(e, "Phone controller client ${client.id} disconnected")
        } finally {
            synchronized(clients) { clients.remove(client) }
            client.close()
            listener.onClientDisconnected(client)
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun readFully(
        input: InputStream,
        size: Int,
    ): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return buffer
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) buffer.write(c)
            if (buffer.size() > MAX_LINE_SIZE) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val MAX_FRAME_SIZE = 64 * 1024
        private const val MAX_LINE_SIZE = 8 * 1024
        private const val HTTP_TIMEOUT_MS = 10_000
        private const val WS_TIMEOUT_MS = 5_000
        private val PORTS = (8765..8775).toList() + 0

        /** Returns the best local IPv4 address of this device (Wi-Fi or Ethernet), if any. */
        fun findLocalAddress(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
            val candidates =
                interfaces
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { networkInterface ->
                        networkInterface.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .filter { !it.isLoopbackAddress }
                            .map { networkInterface.name to it }
                    }

            fun priority(name: String): Int =
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }

            return candidates
                .sortedWith(compareBy({ !it.second.isSiteLocalAddress }, { priority(it.first) }))
                .firstOrNull()
                ?.second
                ?.hostAddress
        }
    }
}
