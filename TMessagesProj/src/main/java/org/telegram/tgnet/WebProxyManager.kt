package org.telegram.tgnet

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object WebProxyManager {
    private const val TAG = "WEBPROXY"

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private var currentHost = ""
    private var localPort = 0
    private var proxyThread: Thread? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) 
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun start(proxyHost: String) {
        if (isRunning.get() && currentHost == proxyHost) return
        stop()

        currentHost = proxyHost
        isRunning.set(true)

        serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        localPort = serverSocket!!.localPort

        proxyThread = thread(start = true, name = "WebProxyAcceptThread") {
            try {
                Log.d(TAG, "WebProxy ServerSocket started on port $localPort for host $proxyHost")
                while (isRunning.get() && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    thread(start = true) {
                        handleClient(socket, proxyHost)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "WebProxy ServerSocket error", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "WebProxy ServerSocket stopping...")
        currentHost = ""
        localPort = 0
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        proxyThread?.interrupt()
        proxyThread = null
    }

    @Synchronized
    fun getPort(): Int = localPort

    private fun handleClient(socket: Socket, proxyHost: String) {
        var ws: WebSocket? = null
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val dataInput = DataInputStream(input)

            socket.soTimeout = 10000
            val buf = ByteArray(256)
            
            // SOCKS5 Handshake Phase 1
            dataInput.readFully(buf, 0, 2)
            if (buf[0] != 0x05.toByte()) return
            val methodsCount = buf[1].toInt()
            dataInput.readFully(buf, 0, methodsCount)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // SOCKS5 Handshake Phase 2
            dataInput.readFully(buf, 0, 4)
            val atyp = buf[3].toInt()
            when (atyp) {
                1 -> dataInput.readFully(buf, 0, 6) 
                3 -> {
                    val domainLen = dataInput.readUnsignedByte()
                    dataInput.readFully(buf, 0, domainLen + 2)
                }
                4 -> dataInput.readFully(buf, 0, 18) 
                else -> return
            }
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()

            socket.soTimeout = 0

            // Safely parse proxyHost which could be "domain.com/secret" or "wss://domain.com/secret"
            var cleanHost = proxyHost
            if (cleanHost.startsWith("wss://")) cleanHost = cleanHost.substring(6)
            if (cleanHost.startsWith("ws://")) cleanHost = cleanHost.substring(5)

            val parts = cleanHost.split("/")
            val secret = if (parts.size > 1) parts[1] else ""
            
            val isUnsecure = proxyHost.startsWith("ws://")
            val scheme = if (isUnsecure) "ws://" else "wss://"
            val wsUrl = "$scheme$cleanHost"

            val request = Request.Builder()
                .url(wsUrl)
                .apply {
                    if (secret.isNotEmpty()) {
                        addHeader("X-Telegram-Proxy", secret)
                    }
                }
                .build()

            val latch = CountDownLatch(1)
            var connected = false

            val wsListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket
                    connected = true
                    latch.countDown()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        output.write(bytes.toByteArray())
                        output.flush()
                    } catch (e: Exception) {
                        webSocket.cancel()
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    try { socket.close() } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try { socket.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }

            client.newWebSocket(request, wsListener)

            latch.await(15, TimeUnit.SECONDS)

            if (!connected) {
                socket.close()
                return
            }

            val relayBuf = ByteArray(8192)
            while (isRunning.get() && !socket.isClosed) {
                val read = input.read(relayBuf)
                if (read < 0) break 
                if (read > 0) {
                    ws?.send(relayBuf.toByteString(0, read))
                }
            }

            ws?.close(1000, "Client disconnect")
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error handling client", e)
            }
        } finally {
            try { ws?.cancel() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
