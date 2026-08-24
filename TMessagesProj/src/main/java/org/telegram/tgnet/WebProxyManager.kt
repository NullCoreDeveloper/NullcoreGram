package org.telegram.tgnet

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object WebProxyManager {
    private const val TAG = "WEBPROXY"

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private var currentHost = ""
    private var localPort = 0
    private var proxyThread: Thread? = null

    private var ws: WebSocket? = null
    private val nextStreamId = AtomicInteger(1)
    private val activeStreams = ConcurrentHashMap<Int, Socket>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun start(proxyAddress: String) {
        if (isRunning.get() && currentHost == proxyAddress) return
        stop()

        currentHost = proxyAddress
        isRunning.set(true)

        serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        localPort = serverSocket!!.localPort

        proxyThread = thread(start = true, name = "WebProxyThread") {
            try {
                Log.d(TAG, "WebProxy ServerSocket started on port $localPort for address $proxyAddress")
                
                var cleanHost = proxyAddress
                if (cleanHost.startsWith("wss://")) cleanHost = cleanHost.substring(6)
                if (cleanHost.startsWith("ws://")) cleanHost = cleanHost.substring(5)
                if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8)
                if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7)
                
                val parts = cleanHost.split("/")
                val hostname = parts[0]
                val secret = if (parts.size > 1) parts[1] else ""
                
                if (secret.isEmpty()) throw Exception("No secret provided")

                // 1. Derive bridge capability
                val secretBytes = hexStringToByteArray(secret)
                val context = "tdesktop-web-proxy-bridge-v1\n$hostname".toByteArray(Charsets.UTF_8)
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
                val hmacResult = mac.doFinal(context)
                val bridgeCapability = Base64.encodeToString(hmacResult, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                
                Log.d(TAG, "Bridge capability computed")

                // 2. Fetch bridge page and extract bootstrap token
                val bridgeUrl = "https://$hostname/?bridge=$bridgeCapability"
                val bridgeRequest = Request.Builder().url(bridgeUrl).build()
                val bridgeResponse = client.newCall(bridgeRequest).execute()
                
                if (bridgeResponse.code != 200) throw Exception("Bridge returned HTTP ${bridgeResponse.code}")
                
                val bridgeHtml = bridgeResponse.body?.string() ?: ""
                val bootstrapPattern = Pattern.compile("bootstrap=\"([A-Za-z0-9_-]{43})\"")
                val matcher = bootstrapPattern.matcher(bridgeHtml)
                val bootstrapToken = if (matcher.find()) matcher.group(1) else null
                
                if (bootstrapToken == null) throw Exception("Bootstrap token not found in bridge page")
                Log.d(TAG, "Bootstrap token retrieved successfully")

                // 3. Create Session
                val helloFrame = byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01)
                val sessionRequest = Request.Builder()
                    .url("https://$hostname/api/v1/session")
                    .header("Authorization", "Bearer $bootstrapToken")
                    .post(helloFrame.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val sessionResponse = client.newCall(sessionRequest).execute()
                
                if (sessionResponse.code == 503) throw Exception("Server returned 503 Service Unavailable (Retry-After: ${sessionResponse.header("Retry-After")})")
                if (!sessionResponse.isSuccessful) throw Exception("Session creation failed: HTTP ${sessionResponse.code}")
                
                val sessionToken = sessionResponse.header("X-Session-Token") ?: throw Exception("Missing X-Session-Token")
                val carrierMode = sessionResponse.header("X-Carrier-Mode") ?: "https"
                Log.d(TAG, "Session created successfully, carrier mode: $carrierMode")

                // We only implement multiplexed WebSocket for now, which is standard for tproxy-server
                if (carrierMode != "websocket") {
                    Log.w(TAG, "Warning: server suggested carrier mode '$carrierMode', attempting websocket anyway...")
                }

                // 4. Connect WebSocket Carrier
                val wsUrl = "wss://$hostname/api/v1/ws"
                val wsRequest = Request.Builder()
                    .url(wsUrl)
                    .header("Origin", "https://$hostname")
                    .header("Sec-WebSocket-Protocol", "tproxy-v1.$sessionToken")
                    .build()
                
                val latch = CountDownLatch(1)
                var connected = false

                val wsListener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "Carrier WebSocket opened")
                        ws = webSocket
                        connected = true
                        latch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            handleDownlinkData(bytes.toByteArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling downlink", e)
                        }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Carrier WebSocket closed ($code): $reason")
                        stop()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Carrier WebSocket failed", t)
                        latch.countDown()
                        stop()
                    }
                }

                client.newWebSocket(wsRequest, wsListener)
                
                // Wait for connection to establish
                latch.await(15, TimeUnit.SECONDS)
                if (!connected) throw Exception("WebSocket connection timeout")

                // 5. Accept local TGNet connections and multiplex them
                while (isRunning.get() && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    thread(start = true) {
                        handleLocalStream(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "WebProxy error", e)
                    // Sleep to prevent tight failure loop
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                    if (isRunning.get()) {
                        Log.d(TAG, "Restarting WebProxy...")
                        start(proxyAddress) 
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "WebProxy stopping...")
        currentHost = ""
        localPort = 0
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { ws?.close(1000, "Stop") } catch (_: Exception) {}
        ws = null
        activeStreams.values.forEach { try { it.close() } catch (_: Exception) {} }
        activeStreams.clear()
        proxyThread?.interrupt()
        proxyThread = null
    }

    @Synchronized
    fun getPort(): Int = localPort

    private fun handleLocalStream(socket: Socket) {
        val streamId = nextStreamId.getAndIncrement()
        activeStreams[streamId] = socket
        try {
            // Send OPEN frame
            ws?.send(createFrame(0x01, streamId, ByteArray(0)).toByteString())

            val input = socket.getInputStream()
            val buf = ByteArray(65536)
            while (isRunning.get() && !socket.isClosed) {
                val read = input.read(buf)
                if (read < 0) break
                if (read > 0) {
                    val dataFrame = createFrame(0x02, streamId, buf.copyOfRange(0, read))
                    ws?.send(dataFrame.toByteString())
                }
            }
        } catch (e: Exception) {
            // normal disconnect or read error
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeStreams.remove(streamId)
            // Send CLOSE frame
            try { ws?.send(createFrame(0x03, streamId, ByteArray(0)).toByteString()) } catch (_: Exception) {}
        }
    }

    private fun handleDownlinkData(batch: ByteArray) {
        val buffer = ByteBuffer.wrap(batch).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 8) {
            val type = buffer.get().toInt() and 0xFF
            val streamId = ((buffer.get().toInt() and 0xFF) shl 16) or ((buffer.get().toInt() and 0xFF) shl 8) or (buffer.get().toInt() and 0xFF)
            val length = buffer.getInt()
            
            if (buffer.remaining() < length) break 
            
            val payload = ByteArray(length)
            buffer.get(payload)

            when (type) {
                0x02 -> { // DATA
                    val socket = activeStreams[streamId]
                    if (socket != null && !socket.isClosed) {
                        try {
                            socket.getOutputStream().write(payload)
                            socket.getOutputStream().flush()
                            
                            // Grant WINDOW credit back to server for what we just consumed
                            val windowPayload = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
                            val windowFrame = createFrame(0x04, streamId, windowPayload)
                            ws?.send(windowFrame.toByteString())
                        } catch (e: Exception) {
                            socket.close()
                        }
                    }
                }
                0x03 -> { // CLOSE
                    val socket = activeStreams[streamId]
                    socket?.close()
                }
            }
        }
    }

    private fun createFrame(type: Int, streamId: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(type.toByte())
        buf.put((streamId ushr 16).toByte())
        buf.put((streamId ushr 8).toByte())
        buf.put(streamId.toByte())
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
