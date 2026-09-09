package org.telegram.tgnet

import android.util.Base64
import android.util.Log
import org.telegram.messenger.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    // Explicit hard watchdog for bridge/session bootstrap calls.
    // OkHttp callTimeout/connectTimeout have been observed to remain blocked
    // much longer than configured on some Android network failures.
    internal const val SETUP_WATCHDOG_TIMEOUT_MS = 10_000L

    internal val setupWatchdogExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WebProxySetupWatchdog").apply {
                isDaemon = true
            }
        }

    private class DohResolver : Dns {
        private data class CachedAnswer(
            val addresses: List<java.net.InetAddress>,
            val savedAtMs: Long
        )

        /*
         * DNS stale-if-error cache.
         *
         * If Android's system DNS temporarily dies after a network/WSS failure,
         * keep using the last successfully resolved IPs for a short period.
         * This avoids a reconnect storm of immediate UnknownHostException.
         */
        private val lastKnownGood =
            ConcurrentHashMap<String, CachedAnswer>()

        private val staleIfErrorMs = 5 * 60 * 1000L

        private fun nowMs(): Long =
            android.os.SystemClock.elapsedRealtime()

        private fun remember(
            hostname: String,
            addresses: List<java.net.InetAddress>
        ) {
            if (addresses.isEmpty()) {
                return
            }

            val copy = addresses.toList()

            lastKnownGood[hostname] = CachedAnswer(
                addresses = copy,
                savedAtMs = nowMs()
            )

        }

        private fun getCached(
            hostname: String
        ): List<java.net.InetAddress>? {
            val cached = lastKnownGood[hostname] ?: return null
            val ageMs = nowMs() - cached.savedAtMs

            if (ageMs > staleIfErrorMs) {
                lastKnownGood.remove(hostname, cached)


                return null
            }


            return cached.addresses
        }

        override fun lookup(hostname: String): List<java.net.InetAddress> {
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)

                if (addresses.isNotEmpty()) {

                    remember(hostname, addresses)
                    return addresses
                }
            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Standard DNS failed for $hostname, trying cached DNS/DoH...",
                    e
                )
            }

            /*
             * Critical recovery path:
             * use the last known good addresses BEFORE hostname-based DoH.
             *
             * The DoH endpoint hostnames themselves need DNS, so they cannot
             * reliably rescue us during a complete Android DNS outage.
             */
            getCached(hostname)?.let {
                return it
            }

            val endpoints = listOf(
                "https://cloudflare-dns.com/dns-query?name=$hostname&type=A",
                "https://dns.google/resolve?name=$hostname&type=A"
            )

            for (endpoint in endpoints) {
                try {
                    val url = java.net.URL(endpoint)
                    val conn = url.openConnection() as java.net.HttpURLConnection

                    conn.setRequestProperty("Accept", "application/dns-json")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream
                            .bufferedReader()
                            .use { it.readText() }

                        val addresses =
                            mutableListOf<java.net.InetAddress>()

                        val matcher = Pattern
                            .compile("\"data\":\"([0-9.]+)\"")
                            .matcher(body)

                        while (matcher.find()) {
                            addresses.add(
                                java.net.InetAddress.getByName(
                                    matcher.group(1)
                                )
                            )
                        }

                        if (addresses.isNotEmpty()) {

                            remember(hostname, addresses)
                            return addresses
                        }
                    }
                } catch (e: Exception) {

                    Log.w(TAG, "DoH endpoint $endpoint failed", e)
                }
            }


            Log.e(
                TAG,
                "All DNS, cached DNS and DoH resolution failed for $hostname"
            )

            throw java.net.UnknownHostException(hostname)
        }
    }

    internal val client = OkHttpClient.Builder()
        // Reduced connect timeout for faster failover
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .dns(DohResolver())
        .build()

    // Dedicated client for bridge + /api/v1/session setup.
    // callTimeout is a secondary guard; executeSetupCall() also has
    // an explicit hard watchdog.
    internal val setupClient = client.newBuilder()
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Non-invasive availability check for a WEB proxy.
     *
     * This deliberately does NOT call start(), stop(), change currentHost,
     * create a local ServerSocket or touch active relay sessions.
     *
     * A successful authenticated bridge request proves that DNS/TLS/ECH,
     * the WEB endpoint and this profile's secret are usable.
     */
    fun checkProxyAvailability(
        proxyAddress: String,
        requestTimeDelegate: RequestTimeDelegate?
    ): Long {
        thread(start = true, name = "WebProxyCheck") {
            val startedAt = System.nanoTime()

            try {
                var cleanHost = proxyAddress
                if (cleanHost.startsWith("wss://")) cleanHost = cleanHost.substring(6)
                if (cleanHost.startsWith("ws://")) cleanHost = cleanHost.substring(5)
                if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8)
                if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7)

                val parts = cleanHost.split("/")
                val hostname = parts[0]
                val secret = if (parts.size > 1) parts[1] else ""

                if (hostname.isEmpty()) {
                    throw IllegalArgumentException("No WEB proxy hostname")
                }
                if (secret.isEmpty()) {
                    throw IllegalArgumentException("No WEB proxy secret")
                }

                var secretBytes = hexStringToByteArray(secret)
                if (secretBytes.size > 16) {
                    val firstByte = secretBytes[0].toInt() and 0xFF
                    if (firstByte == 0xee || firstByte == 0xdd) {
                        secretBytes = secretBytes.copyOfRange(1, 17)
                    }
                }

                val context =
                    "tdesktop-web-proxy-bridge-v1\n$hostname"
                        .toByteArray(Charsets.UTF_8)

                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))

                val bridgeCapability = Base64.encodeToString(
                    mac.doFinal(context),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                val response = CronetHttpClient.execute(
                    url = "https://$hostname/?bridge=$bridgeCapability",
                    timeoutSeconds = 8
                )

                val elapsedMs = maxOf(
                    1L,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAt
                    )
                )

                requestTimeDelegate?.run(
                    if (response.code == 200) elapsedMs else -1L
                )
            } catch (_: Throwable) {
                requestTimeDelegate?.run(-1L)
            }
        }

        // WEB probe is managed by Kotlin, not native_checkProxy().
        return 0L
    }

    @Synchronized
    fun start(proxyAddress: String) {
        if (isRunning.get() && currentHost == proxyAddress) return
        stop()

        currentHost = proxyAddress
        isRunning.set(true)

        serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        localPort = serverSocket!!.localPort

        proxyThread = thread(start = true, name = "WebProxyThread") {
            val currentThread = Thread.currentThread()
            try {
                
                var cleanHost = proxyAddress
                if (cleanHost.startsWith("wss://")) cleanHost = cleanHost.substring(6)
                if (cleanHost.startsWith("ws://")) cleanHost = cleanHost.substring(5)
                if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8)
                if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7)
                
                val parts = cleanHost.split("/")
                val hostname = parts[0]
                val secret = if (parts.size > 1) parts[1] else ""

                // LAB only: strict-ECH negative test.
                // example.com publishes HTTPS/SVCB but no ECHConfig.
                if (BuildConfig.LAB_BUILD) {
                    CronetEchProbe.run("example.com")
                }
                
                if (secret.isEmpty()) throw Exception("No secret provided")

                // 1. Derive bridge capability
                var secretBytes = hexStringToByteArray(secret)
                if (secretBytes.size > 16) {
                    val firstByte = secretBytes[0].toInt() and 0xFF
                    if (firstByte == 0xee || firstByte == 0xdd) {
                        secretBytes = secretBytes.copyOfRange(1, 17)
                    }
                }

                val context = "tdesktop-web-proxy-bridge-v1\n$hostname".toByteArray(Charsets.UTF_8)
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
                val hmacResult = mac.doFinal(context)
                val bridgeCapability = Base64.encodeToString(hmacResult, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                // Optional DIRECT fallback.
                // Public builds leave both host values empty.
                val fallbackHostname =
                    if (
                        BuildConfig.WEB_PROXY_DIRECT_PRIMARY_HOST.isNotEmpty() &&
                        BuildConfig.WEB_PROXY_DIRECT_FALLBACK_HOST.isNotEmpty() &&
                        hostname.equals(
                            BuildConfig.WEB_PROXY_DIRECT_PRIMARY_HOST,
                            ignoreCase = true
                        )
                    ) {
                        BuildConfig.WEB_PROXY_DIRECT_FALLBACK_HOST
                    } else {
                        null
                    }

                val fallbackBridgeCapability = fallbackHostname?.let { fallbackHost ->
                    val fallbackContext =
                        "tdesktop-web-proxy-bridge-v1\n$fallbackHost"
                            .toByteArray(Charsets.UTF_8)

                    val fallbackMac = Mac.getInstance("HmacSHA256")
                    fallbackMac.init(SecretKeySpec(secretBytes, "HmacSHA256"))

                    Base64.encodeToString(
                        fallbackMac.doFinal(fallbackContext),
                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                    )
                }
                
                // 3. Accept local TGNet connections and spawn session handlers
                while (isRunning.get() && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    val sessionId = nextSessionId.getAndIncrement()
                    val handler = SessionHandler(
                        sessionId,
                        socket,
                        hostname,
                        bridgeCapability,
                        fallbackHostname,
                        fallbackBridgeCapability
                    )
                    activeSessions[sessionId] = handler
                    handler.start()
                }
            } catch (e: Exception) {
                if (isRunning.get() && Thread.currentThread() == proxyThread) {
                    Log.e(TAG, "WebProxy error", e)
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                    if (isRunning.get() && Thread.currentThread() == proxyThread) {
                        start(proxyAddress) 
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
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
    primaryHostname: String,
    primaryBridgeCapability: String,
    private val fallbackHostname: String?,
    private val fallbackBridgeCapability: String?
) {
    @Volatile
    private var activeHostname = primaryHostname

    @Volatile
    private var activeBridgeCapability = primaryBridgeCapability

    private var fallbackAttempted = false
    private val TAG = "WEBPROXY_SESSION_$sessionId"
    private var isRunning = AtomicBoolean(true)
    private var sessionToken = ""
    private var downCursor = "0"
    private var carrierMode = "https"
    private var upSequence = AtomicInteger(0)
    private var ws: WebSocket? = null
    private var readThread: Thread? = null

    // Bridge/session setup is synchronous. Keep the active OkHttp Call so
    // SessionHandler.stop() can abort a DNS/TCP/TLS/HTTP setup that is still
    // blocked inside execute().
    private val setupCall =
        java.util.concurrent.atomic.AtomicReference<CronetHttpClient.Call?>(null)

    // Active Cronet long-poll /api/v1/down call.
    // Keep a reference so stop() can abort it immediately instead of waiting
    // for the request timeout.
    private val downlinkCall =
        java.util.concurrent.atomic.AtomicReference<CronetHttpClient.Call?>(null)

    // Active Cronet /api/v1/up call.
    // stop() cancels the exact in-flight upload immediately.
    private val uplinkCall =
        java.util.concurrent.atomic.AtomicReference<CronetHttpClient.Call?>(null)

    private val createdAtNanos = System.nanoTime()
    @Volatile private var setupStage = "CREATED"

    private fun markSetupStage(stage: String) {
        setupStage = stage
    }

    private fun sessionAgeMs(): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - createdAtNanos
        )

    private fun switchToFallback(reason: Throwable): Boolean {
        val fallbackHost = fallbackHostname ?: return false
        val fallbackBridge = fallbackBridgeCapability ?: return false

        if (fallbackAttempted || activeHostname == fallbackHost) {
            return false
        }

        fallbackAttempted = true

        Log.w(
            TAG,
            "PRIMARY SETUP FAILED host=$activeHostname stage=$setupStage; " +
                "switching to DIRECT fallback=$fallbackHost age=${sessionAgeMs()}ms",
            reason
        )

        activeHostname = fallbackHost
        activeBridgeCapability = fallbackBridge

        sessionToken = ""
        downCursor = "0"
        carrierMode = "https"
        upSequence.set(0)

        markSetupStage("FALLBACK_SWITCH")
        return true
    }

    private fun executeSetupCall(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): CronetHttpClient.Response {
        val call = CronetHttpClient.newCall(
            url = url,
            method = method,
            headers = headers,
            body = body
        )
        setupCall.set(call)

        if (!isRunning.get()) {
            if (setupCall.compareAndSet(call, null)) {
                call.cancel()
            }
            throw IOException("Session stopped before setup call")
        }

        val watchdog = WebProxyManager.setupWatchdogExecutor.schedule(
            {
                if (setupCall.get() === call && !call.isCanceled()) {
                    try {
                        call.cancel()
                    } catch (_: Exception) {
                    }
                }
            },
            WebProxyManager.SETUP_WATCHDOG_TIMEOUT_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )

        return try {
            call.execute()
        } finally {
            watchdog.cancel(false)
            setupCall.compareAndSet(call, null)
        }
    }

    // WebSocket downlink is already stored inside Okio ByteString.
    // Reuse one scratch buffer instead of allocating body.toByteArray()
    // for every incoming WebSocket message.
    private val websocketDownlinkScratch = ByteArray(65536)

    // tproxy-v1 grants every stream 4 MiB of client -> relay DATA credit.
    // DATA consumes this credit; relay WINDOW frames replenish it only after
    // the bytes have reached the MTProxy backend socket.
    private val uplinkWindowLock = Object()
    private var uplinkCredit = 4L * 1024L * 1024L

    // HTTP /up is strictly sequenced by tproxy-v1: at most one request may
    // be in flight for a relay session. This also guarantees that a retry of
    // a 503 uses the same sequence number and the exact same request body.
    private val uplinkLock = Any()

    // HTTPS downlink WINDOW batching.
    //
    // HTTP carriers must not perform a complete /api/v1/up round-trip for
    // every tiny DATA frame received from the relay. Each stream starts with
    // 4 MiB of receive credit, so safely accumulate delivered downlink bytes
    // and return them in substantially larger WINDOW deltas.
    //
    // WebSocket transport keeps its existing immediate WINDOW behaviour.
    private val httpsDownlinkWindowLock = Any()
    private val httpsDownlinkWindowPending =
        java.util.HashMap<Int, Long>()
    private val httpsDownlinkWindowBatchBytes = 2048L * 1024L

    private fun createFrame(type: Int, streamId: Int, data: ByteArray): ByteArray {
        return createFrame(type, streamId, data, 0, data.size)
    }

    private fun createFrame(
        type: Int,
        streamId: Int,
        data: ByteArray,
        dataOffset: Int,
        size: Int
    ): ByteArray {
        val frame = ByteArray(8 + size)
        frame[0] = type.toByte()
        frame[1] = (streamId ushr 16).toByte()
        frame[2] = (streamId ushr 8).toByte()
        frame[3] = streamId.toByte()
        frame[4] = (size ushr 24).toByte()
        frame[5] = (size ushr 16).toByte()
        frame[6] = (size ushr 8).toByte()
        frame[7] = size.toByte()
        System.arraycopy(data, dataOffset, frame, 8, size)
        return frame
    }

    private fun processDownlink(body: ByteArray) {
        var offset = 0
        while (offset < body.size) {
            if (body.size - offset < 8) {
                Log.e(TAG, "Incomplete frame header")
                break
            }

            val type = body[offset].toInt() and 0xFF
            val streamId = ((body[offset + 1].toInt() and 0xFF) shl 16) or
                           ((body[offset + 2].toInt() and 0xFF) shl 8) or
                           (body[offset + 3].toInt() and 0xFF)
            val size = ((body[offset + 4].toInt() and 0xFF) shl 24) or
                       ((body[offset + 5].toInt() and 0xFF) shl 16) or
                       ((body[offset + 6].toInt() and 0xFF) shl 8) or
                       (body[offset + 7].toInt() and 0xFF)


            val end = offset + 8 + size
            if (size < 0 || size > 1048576 || end > body.size) {
                Log.e(TAG, "Invalid frame size: $size")
                break
            }

            if (type == 2) {
                if (streamId == 0) {
                    Log.e(TAG, "DATA frame with stream id 0")
                    stop()
                    break
                }

                if (size > 0) {
                    val output = socket.getOutputStream()
                    output.write(body, offset + 8, size)
                    output.flush()

                    // tproxy-v1 starts each direction with a 4 MiB receive
                    // window. Return exactly the amount of downlink DATA that
                    // has been handed to the local TGNet socket, otherwise the
                    // relay eventually exhausts its send credit and media
                    // transfer stalls.
                    returnHttpsDownlinkCredit(streamId, size)
                    if (!isRunning.get()) break
                }
            } else if (type == 3) {
                stop()
                break
            } else if (type == 4) {
                if (streamId == 0 || size != 4) {
                    Log.e(TAG, "Invalid WINDOW frame: stream=$streamId size=$size")
                    stop()
                    break
                }

                val delta =
                    ((body[offset + 8].toLong() and 0xFFL) shl 24) or
                    ((body[offset + 9].toLong() and 0xFFL) shl 16) or
                    ((body[offset + 10].toLong() and 0xFFL) shl 8) or
                    (body[offset + 11].toLong() and 0xFFL)

                if (delta == 0L) {
                    Log.e(TAG, "WINDOW frame with zero delta")
                    stop()
                    break
                }

                synchronized(uplinkWindowLock) {
                    uplinkCredit += delta
                    uplinkWindowLock.notifyAll()
                }
            }

            offset = end
        }
    }

    private fun returnHttpsDownlinkCredit(streamId: Int, size: Int) {
        if (size <= 0 || !isRunning.get()) return

        var deltaToSend = 0L

        synchronized(httpsDownlinkWindowLock) {
            val pending =
                (httpsDownlinkWindowPending[streamId] ?: 0L) +
                    size.toLong()

            if (pending >= httpsDownlinkWindowBatchBytes) {
                deltaToSend = pending
                httpsDownlinkWindowPending.remove(streamId)
            } else {
                httpsDownlinkWindowPending[streamId] = pending
            }
        }

        if (deltaToSend == 0L) return

        while (deltaToSend > 0L && isRunning.get()) {
            val delta = minOf(deltaToSend, 0xFFFF_FFFFL)

            val windowDelta = byteArrayOf(
                (delta ushr 24).toByte(),
                (delta ushr 16).toByte(),
                (delta ushr 8).toByte(),
                delta.toByte()
            )


            sendDataRaw(createFrame(4, streamId, windowDelta))
            deltaToSend -= delta
        }
    }

    private fun processDownlink(body: okio.ByteString) {
        var offset = 0
        val bodySize = body.size
        val output = socket.getOutputStream()

        while (offset < bodySize) {
            if (bodySize - offset < 8) {
                Log.e(TAG, "Incomplete WebSocket frame header")
                break
            }

            val type = body[offset].toInt() and 0xFF
            val streamId =
                ((body[offset + 1].toInt() and 0xFF) shl 16) or
                ((body[offset + 2].toInt() and 0xFF) shl 8) or
                (body[offset + 3].toInt() and 0xFF)

            val size =
                ((body[offset + 4].toInt() and 0xFF) shl 24) or
                ((body[offset + 5].toInt() and 0xFF) shl 16) or
                ((body[offset + 6].toInt() and 0xFF) shl 8) or
                (body[offset + 7].toInt() and 0xFF)

            val end = offset + 8 + size

            if (size < 0 || size > 1048576 || end > bodySize) {
                Log.e(TAG, "Invalid WebSocket frame size: $size")
                break
            }

            if (type == 2) {
                if (streamId == 0) {
                    Log.e(TAG, "DATA frame with stream id 0")
                    stop()
                    break
                }

                if (size > 0) {
                    // ByteString.copyInto() works directly against both regular
                    // ByteString storage and SegmentedByteString segments.
                    // Only the current payload slice is copied into one reusable
                    // 64 KiB scratch buffer; no full-message ByteArray is created.
                    var sourceOffset = offset + 8
                    var remaining = size

                    while (remaining > 0) {
                        val n = minOf(remaining, websocketDownlinkScratch.size)

                        body.copyInto(
                            sourceOffset,
                            websocketDownlinkScratch,
                            0,
                            n
                        )

                        output.write(websocketDownlinkScratch, 0, n)

                        sourceOffset += n
                        remaining -= n
                    }

                    output.flush()

                    val windowDelta = byteArrayOf(
                        (size ushr 24).toByte(),
                        (size ushr 16).toByte(),
                        (size ushr 8).toByte(),
                        size.toByte()
                    )
                    sendDataRaw(createFrame(4, streamId, windowDelta))
                    if (!isRunning.get()) break
                }
            } else if (type == 3) {
                stop()
                break
            } else if (type == 4) {
                if (streamId == 0 || size != 4) {
                    Log.e(TAG, "Invalid WINDOW frame: stream=$streamId size=$size")
                    stop()
                    break
                }

                val delta =
                    ((body[offset + 8].toLong() and 0xFFL) shl 24) or
                    ((body[offset + 9].toLong() and 0xFFL) shl 16) or
                    ((body[offset + 10].toLong() and 0xFFL) shl 8) or
                    (body[offset + 11].toLong() and 0xFFL)

                if (delta == 0L) {
                    Log.e(TAG, "WINDOW frame with zero delta")
                    stop()
                    break
                }

                synchronized(uplinkWindowLock) {
                    uplinkCredit += delta
                    uplinkWindowLock.notifyAll()
                }
            }

            offset = end
        }
    }

    fun start() {
        thread(start = true, name = "WebProxySetup_$sessionId") {
            try {
                markSetupStage("START")

                var setupComplete = false

                while (!setupComplete && isRunning.get()) {
                    try {
                        val bridgeUrl =
                            "https://$activeHostname/?bridge=$activeBridgeCapability"

                        markSetupStage("BRIDGE_REQUEST")
                        val bridgeResponse = executeSetupCall(bridgeUrl)
                        markSetupStage("BRIDGE_RESPONSE_${bridgeResponse.code}")

                        if (bridgeResponse.code != 200) {
                            throw Exception(
                                "Bridge returned HTTP ${bridgeResponse.code}"
                            )
                        }

                        val bridgeHtml =
                            bridgeResponse.body.toString(Charsets.UTF_8)

                        val bootstrapPattern =
                            Pattern.compile(
                                "bootstrap=\"([A-Za-z0-9_-]{43})\""
                            )

                        val matcher = bootstrapPattern.matcher(bridgeHtml)
                        val bootstrapToken =
                            if (matcher.find()) matcher.group(1) else null

                        if (bootstrapToken == null) {
                            throw Exception(
                                "Bootstrap token not found in bridge page"
                            )
                        }

                        val helloFrame = byteArrayOf(
                            0x10, 0x00, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x01, 0x01
                        )

                        markSetupStage("SESSION_REQUEST")
                        val sessionResponse = executeSetupCall(
                            url = "https://$activeHostname/api/v1/session",
                            method = "POST",
                            headers = mapOf(
                                "Authorization" to "Bearer $bootstrapToken",
                                "Content-Type" to "application/octet-stream"
                            ),
                            body = helloFrame
                        )

                        markSetupStage(
                            "SESSION_RESPONSE_${sessionResponse.code}"
                        )

                        if (sessionResponse.code == 503) {
                            throw Exception(
                                "Server returned 503 Service Unavailable"
                            )
                        }

                        if (sessionResponse.code !in 200..299) {
                            throw Exception(
                                "Session creation failed: HTTP ${sessionResponse.code}"
                            )
                        }

                        sessionToken =
                            sessionResponse.header("X-Session-Token")
                                ?: throw Exception("Missing X-Session-Token")

                        downCursor =
                            sessionResponse.header("X-Down-Cursor") ?: "0"

                        carrierMode =
                            sessionResponse.header("X-Carrier-Mode") ?: "https"



                        setupComplete = true
                    } catch (setupError: Exception) {
                        if (!isRunning.get()) {
                            return@thread
                        }

                        if (!switchToFallback(setupError)) {
                            throw setupError
                        }
                    }
                }

                if (!setupComplete || !isRunning.get()) {
                    return@thread
                }

                val input = socket.getInputStream()
                val buf = ByteArray(65536)

                markSetupStage("TGNET_FIRST_READ_WAIT")
                val read = input.read(buf)
                markSetupStage("TGNET_FIRST_READ_$read")

                if (read <= 0) throw Exception("Failed to read first chunk from TGNet")
                
                val firstChunk = buf.copyOfRange(0, read)
                val openFrame = createFrame(1, 1, ByteArray(0))
                val dataFrame = createFrame(2, 1, firstChunk)
                val combined = openFrame + dataFrame

                if (carrierMode == "websocket" || carrierMode == "websocket-lanes") {
                    val wsUrl = "wss://$activeHostname/api/v1/ws"
                    val protocol = if (carrierMode == "websocket-lanes") "tproxy-lane-v1.$sessionToken.1" else "tproxy-v1.$sessionToken"
                    val wsRequest = Request.Builder()
                        .url(wsUrl)
                        .header("Origin", "https://$activeHostname")
                        .header("Sec-WebSocket-Protocol", protocol)
                        .build()
                    
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var connected = false

                    val wsListener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            ws = webSocket
                            connected = true
                            setupStage = "WS_OPEN"
                            latch.countDown()
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                            try {
                                processDownlink(bytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "WS MESSAGE ERROR age=${sessionAgeMs()}ms", e)
                                stop()
                            }
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String
                        ) {
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String
                        ) {
                            stop()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?
                        ) {
                            val httpCode = response?.code?.toString() ?: "none"
                            Log.e(
                                TAG,
                                "WS FAILURE http=$httpCode age=${sessionAgeMs()}ms",
                                t
                            )
                            latch.countDown()
                            stop()
                        }
                    }

                    markSetupStage("WS_CREATE")
                    WebProxyManager.client.newWebSocket(wsRequest, wsListener)

                    markSetupStage("WS_WAIT_OPEN")
                    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    if (!connected) throw Exception("WebSocket connection timeout")

                    // WebSocket must be OPEN before the first OPEN+DATA frame is sent.
                    takeUplinkCredit(firstChunk.size)
                    if (!isRunning.get()) return@thread
                    sendDataRaw(combined)
                } else {
                    takeUplinkCredit(firstChunk.size)
                    if (!isRunning.get()) return@thread
                    sendDataRaw(combined)

                    // Start poll loop
                    thread(start = true, name = "WebProxyPoll_$sessionId") { pollLoop() }
                }

                readThread = thread(start = true, name = "WebProxyRead_$sessionId") {
                    try {
                        val buffer = ByteArray(65536)
                        while (isRunning.get() && !socket.isClosed) {
                            val r = socket.getInputStream().read(buffer)
                            if (r < 0) {
                                break
                            }
                            if (r > 0) sendData(buffer, r)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "TGNET READ ERROR age=${sessionAgeMs()}ms", e)
                    } finally {
                        stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Setup error at stage=$setupStage", e)
                stop()
            }
        }
    }

    private fun takeUplinkCredit(bytes: Int) {
        var remaining = bytes.toLong()

        synchronized(uplinkWindowLock) {
            while (remaining > 0L && isRunning.get()) {
                while (uplinkCredit <= 0L && isRunning.get()) {
                    try {
                        uplinkWindowLock.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }

                if (!isRunning.get()) return

                val taken = minOf(uplinkCredit, remaining)
                uplinkCredit -= taken
                remaining -= taken

                // This helper is used for the initial <=64 KiB DATA chunk,
                // so normally one iteration is enough. If the available
                // credit is smaller, wait for WINDOW until the whole frame
                // has been granted before sending it.
                if (remaining > 0L) {
                    while (uplinkCredit <= 0L && isRunning.get()) {
                        try {
                            uplinkWindowLock.wait()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
            }
        }
    }

    private fun sendData(data: ByteArray, length: Int) {
        var offset = 0

        while (offset < length && isRunning.get()) {
            val allowed: Int

            synchronized(uplinkWindowLock) {
                while (uplinkCredit <= 0L && isRunning.get()) {
                    try {
                        uplinkWindowLock.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }

                if (!isRunning.get()) return

                allowed = minOf(
                    uplinkCredit,
                    (length - offset).toLong(),
                    65536L
                ).toInt()

                uplinkCredit -= allowed.toLong()
            }

            sendDataRaw(createFrame(2, 1, data, offset, allowed))

            if (!isRunning.get()) return
            offset += allowed
        }
    }

    private fun sendDataRaw(frameData: ByteArray) {
        if (!isRunning.get()) return

        if (carrierMode == "websocket" || carrierMode == "websocket-lanes") {
            val sent = ws?.send(frameData.toByteString()) ?: false
            if (!sent && isRunning.get()) {
                Log.e(TAG, "WebSocket uplink queue rejected frame")
                stop()
            }
            return
        }

        synchronized(uplinkLock) {
            if (!isRunning.get()) return

            // One sequence number belongs to this exact immutable body.
            // Keep both unchanged across HTTP 503 and retryable network errors.
            val seq = upSequence.incrementAndGet()

            val headers = linkedMapOf(
                "Authorization" to "Bearer $sessionToken",
                "X-Up-Seq" to seq.toString(),
                "Content-Type" to "application/octet-stream"
            )

            if (carrierMode == "https-lanes") {
                headers["X-Lane-ID"] = "1"
            }

            val retryDeadlineNanos =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS.toNanos(90)

            var consecutiveRetryableFailures = 0

            while (isRunning.get()) {
                val call = CronetHttpClient.newCall(
                    url = "https://$activeHostname/api/v1/up",
                    method = "POST",
                    headers = headers,
                    body = frameData,
                    timeoutSeconds = 45
                )

                uplinkCall.set(call)

                if (!isRunning.get()) {
                    if (uplinkCall.compareAndSet(call, null)) {
                        call.cancel()
                    }
                    return
                }

                try {
                    val response = call.execute()

                    if (!isRunning.get()) return

                    consecutiveRetryableFailures = 0

                    if (response.code == 204) {
                        return
                    }

                    if (response.code == 503) {
                        val remainingNanos =
                            retryDeadlineNanos - System.nanoTime()

                        if (remainingNanos <= 0L) {
                            Log.e(
                                TAG,
                                "Cronet uplink retry budget exhausted for seq $seq"
                            )
                            stop()
                            return
                        }

                        val retryAfterSeconds =
                            response.header("Retry-After")
                                ?.trim()
                                ?.toLongOrNull()
                                ?.coerceIn(1L, 30L)
                                ?: 1L

                        val requestedDelayMs =
                            retryAfterSeconds * 1000L

                        val remainingMs =
                            java.util.concurrent.TimeUnit.NANOSECONDS
                                .toMillis(remainingNanos)
                                .coerceAtLeast(1L)

                        val delayMs =
                            minOf(requestedDelayMs, remainingMs)


                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            if (isRunning.get()) stop()
                            return
                        }

                        continue
                    }

                    Log.e(
                        TAG,
                        "Cronet uplink rejected: HTTP ${response.code}"
                    )
                    stop()
                    return

                } catch (e: IOException) {
                    if (!isRunning.get()) return

                    val networkException =
                        e as? org.chromium.net.NetworkException

                    if (
                        networkException != null &&
                        networkException.immediatelyRetryable()
                    ) {
                        consecutiveRetryableFailures++

                        val remainingNanos =
                            retryDeadlineNanos - System.nanoTime()

                        if (
                            consecutiveRetryableFailures <= 5 &&
                            remainingNanos > 0L
                        ) {
                            val requestedDelayMs =
                                minOf(
                                    250L * consecutiveRetryableFailures,
                                    1000L
                                )

                            val remainingMs =
                                java.util.concurrent.TimeUnit.NANOSECONDS
                                    .toMillis(remainingNanos)
                                    .coerceAtLeast(1L)

                            val delayMs =
                                minOf(requestedDelayMs, remainingMs)


                            try {
                                Thread.sleep(delayMs)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                if (isRunning.get()) stop()
                                return
                            }

                            continue
                        }

                        Log.e(
                            TAG,
                            "Cronet uplink retry limit exceeded " +
                                "errorCode=${networkException.errorCode} " +
                                "internal=${networkException.cronetInternalErrorCode} " +
                                "seq=$seq",
                            e
                        )
                        stop()
                        return
                    }

                    Log.e(TAG, "Cronet uplink non-retryable error", e)
                    stop()
                    return

                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Cronet uplink error", e)
                        stop()
                    }
                    return

                } finally {
                    uplinkCall.compareAndSet(call, null)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        val pendingSetupCall = setupCall.getAndSet(null)
        if (pendingSetupCall != null) {
            try {
                pendingSetupCall.cancel()
            } catch (_: Exception) {
            }
        }

        val pendingDownlinkCall = downlinkCall.getAndSet(null)
        if (pendingDownlinkCall != null) {
            try {
                pendingDownlinkCall.cancel()
            } catch (_: Exception) {
            }
        }

        val pendingUplinkCall = uplinkCall.getAndSet(null)
        if (pendingUplinkCall != null) {
            try {
                pendingUplinkCall.cancel()
            } catch (_: Exception) {
            }
        }

        synchronized(uplinkWindowLock) {
            uplinkWindowLock.notifyAll()
        }

        synchronized(httpsDownlinkWindowLock) {
            httpsDownlinkWindowPending.clear()
        }

        try { socket.close() } catch (_: Exception) {}
        try { ws?.close(1000, "Stop") } catch (_: Exception) {}
        ws = null
        
        if (sessionToken.isNotEmpty()) {
            val deleteHostname = activeHostname
            val deleteToken = sessionToken

            thread(start = true) {
                try {
                    val call = CronetHttpClient.newCall(
                        url = "https://$deleteHostname/api/v1/session",
                        method = "DELETE",
                        headers = mapOf(
                            "Authorization" to "Bearer $deleteToken"
                        ),
                        body = null,
                        timeoutSeconds = 12
                    )

                    call.execute()

                } catch (e: Exception) {
                    Log.w(TAG, "Cronet session DELETE failed", e)
                }
            }
        }

        readThread?.interrupt()
        WebProxyManager.removeSession(sessionId)
    }

    private fun pollLoop() {
        var consecutiveRetryableFailures = 0

        while (isRunning.get()) {
            val requestCursor = downCursor

            val headers = linkedMapOf(
                "Authorization" to "Bearer $sessionToken",
                "X-Down-Cursor" to requestCursor
            )

            if (carrierMode == "https-lanes") {
                headers["X-Lane-ID"] = "1"
            }

            val call = CronetHttpClient.newCall(
                url = "https://$activeHostname/api/v1/down",
                method = "POST",
                headers = headers,
                body = null,
                timeoutSeconds = 45
            )

            downlinkCall.set(call)

            if (!isRunning.get()) {
                if (downlinkCall.compareAndSet(call, null)) {
                    call.cancel()
                }
                return
            }

            try {
                val response = call.execute()

                if (!isRunning.get()) return

                consecutiveRetryableFailures = 0

                if (response.code == 204) {
                    continue
                }

                if (response.code == 200) {
                    val nextCursor = response.header("X-Down-Cursor")

                    if (nextCursor != null) {
                        downCursor = nextCursor
                    }

                    val body = response.body


                    if (body.isNotEmpty()) {
                        try {
                            processDownlink(body)
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.e(TAG, "Cronet downlink processing failed", e)
                                stop()
                            }
                            return
                        }
                    }

                    continue
                }

                Log.e(TAG, "Cronet downlink rejected: HTTP ${response.code}")
                stop()
                return

            } catch (e: IOException) {
                if (!isRunning.get()) return

                val networkException =
                    e as? org.chromium.net.NetworkException

                if (
                    networkException != null &&
                    networkException.immediatelyRetryable()
                ) {
                    consecutiveRetryableFailures++

                    if (consecutiveRetryableFailures <= 5) {
                        val delayMs =
                            minOf(
                                250L * consecutiveRetryableFailures,
                                1000L
                            )


                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            if (isRunning.get()) {
                                stop()
                            }
                            return
                        }

                        continue
                    }

                    Log.e(
                        TAG,
                        "Cronet downlink retry limit exceeded " +
                            "errorCode=${networkException.errorCode} " +
                            "internal=${networkException.cronetInternalErrorCode}",
                        e
                    )
                    stop()
                    return
                }

                Log.e(TAG, "Cronet downlink non-retryable error", e)
                stop()
                return

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Cronet downlink error", e)
                    stop()
                }
                return

            } finally {
                downlinkCall.compareAndSet(call, null)
            }
        }
    }
}
