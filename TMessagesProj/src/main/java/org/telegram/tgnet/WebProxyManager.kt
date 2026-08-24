package org.telegram.tgnet

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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

    private val nextSessionId = AtomicInteger(1)
    private val activeSessions = ConcurrentHashMap<Int, SessionHandler>()

    internal val client = OkHttpClient.Builder()
        // Reduced connect timeout for faster failover
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
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

                // 3. Accept local TGNet connections and spawn session handlers
                while (isRunning.get() && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    val sessionId = nextSessionId.getAndIncrement()
                    val handler = SessionHandler(sessionId, socket, bootstrapToken, hostname)
                    activeSessions[sessionId] = handler
                    handler.start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "WebProxy error", e)
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
        
        activeSessions.values.forEach { it.stop() }
        activeSessions.clear()
        proxyThread?.interrupt()
        proxyThread = null
    }

    @Synchronized
    fun getPort(): Int = localPort

    internal fun removeSession(sessionId: Int) {
        activeSessions.remove(sessionId)
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

class SessionHandler(
    private val sessionId: Int,
    private val socket: Socket,
    private val bootstrapToken: String,
    private val activeHostname: String
) {
    private val TAG = "WEBPROXY_SESSION_$sessionId"
    private var isRunning = AtomicBoolean(true)
    private var sessionToken = ""
    private var downCursor = "0"
    private var upSequence = 1
    
    private val upQueue = java.util.ArrayDeque<ByteArray>()
    private val upLock = Object()

    private var upThread: Thread? = null
    private var pollThread: Thread? = null
    private var readThread: Thread? = null
    private var ws: WebSocket? = null
    private var carrierMode = "https"

    fun start() {
        thread(start = true, name = "WebProxySetup_$sessionId") {
            try {
                val input = socket.getInputStream()
                val buf = ByteArray(65536)
                
                // Read the very first chunk from TGNet (MTProto obfuscation header)
                val read = input.read(buf)
                if (read <= 0) {
                    stop()
                    return@thread
                }
                
                val firstChunk = buf.copyOfRange(0, read)
                Log.d(TAG, "Read first chunk of size ${firstChunk.size}")

                val sessionRequest = Request.Builder()
                    .url("https://$activeHostname/api/v1/session")
                    .header("Authorization", "Bearer $bootstrapToken")
                    .post(firstChunk.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                
                val sessionResponse = WebProxyManager.client.newCall(sessionRequest).execute()
                if (sessionResponse.code == 503) throw Exception("Server returned 503 Service Unavailable")
                if (!sessionResponse.isSuccessful) throw Exception("Session creation failed: HTTP ${sessionResponse.code}")
                
                sessionToken = sessionResponse.header("X-Session-Token") ?: throw Exception("Missing X-Session-Token")
                downCursor = sessionResponse.header("X-Down-Cursor") ?: "0"
                carrierMode = sessionResponse.header("X-Carrier-Mode") ?: "https"
                Log.d(TAG, "Session created successfully, carrier mode: $carrierMode")

                val welcomeBytes = sessionResponse.body?.bytes()
                if (welcomeBytes != null && welcomeBytes.isNotEmpty()) {
                    socket.getOutputStream().write(welcomeBytes)
                    socket.getOutputStream().flush()
                }

                if (carrierMode == "websocket") {
                    val wsUrl = "wss://$activeHostname/api/v1/ws"
                    val wsRequest = Request.Builder()
                        .url(wsUrl)
                        .header("Origin", "https://$activeHostname")
                        .header("Sec-WebSocket-Protocol", "tproxy-v1.$sessionToken")
                        .build()
                    
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var connected = false

                    val wsListener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            ws = webSocket
                            connected = true
                            latch.countDown()
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                            try {
                                val output = socket.getOutputStream()
                                output.write(bytes.toByteArray())
                                output.flush()
                            } catch (e: Exception) {
                                stop()
                            }
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            stop()
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            latch.countDown()
                            stop()
                        }
                    }

                    WebProxyManager.client.newWebSocket(wsRequest, wsListener)
                    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    if (!connected) throw Exception("WebSocket connection timeout")
                } else {
                    upThread = thread(start = true, name = "WebProxyUp_$sessionId") { upLoop() }
                    pollThread = thread(start = true, name = "WebProxyPoll_$sessionId") { pollLoop() }
                }

                // Read subsequent chunks from local socket
                readThread = thread(start = true, name = "WebProxyRead_$sessionId") {
                    try {
                        val buffer = ByteArray(65536)
                        while (isRunning.get() && !socket.isClosed) {
                            val r = socket.getInputStream().read(buffer)
                            if (r < 0) break
                            if (r > 0) {
                                sendData(buffer.copyOfRange(0, r))
                            }
                        }
                    } catch (e: Exception) {
                    } finally {
                        stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Setup error", e)
                stop()
            }
        }
    }

    private fun sendData(data: ByteArray) {
        if (carrierMode == "websocket") {
            ws?.send(data.toByteString())
        } else {
            synchronized(upLock) {
                upQueue.addLast(data)
                upLock.notifyAll()
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "Stopping session handler")
        try { socket.close() } catch (_: Exception) {}
        try { ws?.close(1000, "Stop") } catch (_: Exception) {}
        ws = null
        
        synchronized(upLock) {
            upQueue.clear()
            upLock.notifyAll()
        }
        
        if (sessionToken.isNotEmpty()) {
            thread(start = true) {
                try {
                    val request = Request.Builder()
                        .url("https://$activeHostname/api/v1/session")
                        .header("Authorization", "Bearer $sessionToken")
                        .delete()
                        .build()
                    WebProxyManager.client.newCall(request).execute().close()
                } catch (_: Exception) {}
            }
        }

        upThread?.interrupt()
        pollThread?.interrupt()
        readThread?.interrupt()
        
        WebProxyManager.removeSession(sessionId)
    }

    private fun upLoop() {
        while (isRunning.get()) {
            val batch = ByteArrayOutputStream()
            synchronized(upLock) {
                while (upQueue.isEmpty() && isRunning.get()) {
                    try { upLock.wait(1000) } catch (e: InterruptedException) { return }
                }
                if (!isRunning.get()) return
                
                while (upQueue.isNotEmpty()) {
                    val frame = upQueue.peekFirst()
                    if (batch.size() > 0 && batch.size() + frame.size > 2000000) break
                    batch.write(upQueue.removeFirst())
                }
            }
            if (batch.size() == 0) continue

            val seq = upSequence.toString()
            val request = Request.Builder()
                .url("https://$activeHostname/api/v1/up")
                .header("Authorization", "Bearer $sessionToken")
                .header("X-Up-Seq", seq)
                .post(batch.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
                .build()

            try {
                val response = WebProxyManager.client.newCall(request).execute()
                if (response.code == 204 && response.header("X-Up-Ack") == seq) {
                    upSequence++
                } else {
                    Log.e(TAG, "Uplink rejected: HTTP ${response.code}")
                    stop()
                    return
                }
                response.close()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Uplink error", e)
                    stop()
                }
                return
            }
        }
    }

    private fun pollLoop() {
        while (isRunning.get()) {
            val request = Request.Builder()
                .url("https://$activeHostname/api/v1/down")
                .header("Authorization", "Bearer $sessionToken")
                .header("X-Down-Cursor", downCursor)
                .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                .build()

            try {
                val response = WebProxyManager.client.newCall(request).execute()
                if (response.code == 204) {
                    response.close()
                    continue
                } else if (response.code == 200) {
                    val nextCursor = response.header("X-Down-Cursor")
                    if (nextCursor != null) {
                        downCursor = nextCursor
                    }
                    val body = response.body?.bytes()
                    if (body != null && body.isNotEmpty()) {
                        try {
                            val output = socket.getOutputStream()
                            output.write(body)
                            output.flush()
                        } catch (e: Exception) {
                            response.close()
                            stop()
                            return
                        }
                    }
                    response.close()
                } else {
                    Log.e(TAG, "Downlink rejected: HTTP ${response.code}")
                    response.close()
                    stop()
                    return
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Downlink error", e)
                    stop()
                }
                return
            }
        }
    }
}
