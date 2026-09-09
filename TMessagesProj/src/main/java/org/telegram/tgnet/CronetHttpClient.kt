package org.telegram.tgnet


import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object CronetHttpClient {

    data class Response(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val protocol: String
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    private val executor = Executors.newCachedThreadPool()

    private val readyLock = Any()

    @Volatile
    private var engineReady = false

    private fun ensureEngineReady() {
        if (engineReady) return

        synchronized(readyLock) {
            if (engineReady) return

            // Force lazy Cronet engine creation first.
            CronetEchEngine.engine

            // This 100 ms cold-start delay is the value already proven
            // reliable during the DNS HTTPS/SVCB + ECH tests.
            Thread.sleep(100)

            engineReady = true
        }
    }

    fun newCall(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutSeconds: Long = 45
    ): Call {
        return Call(
            url = url,
            method = method,
            headers = headers,
            body = body,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutSeconds: Long = 45
    ): Response {
        return newCall(
            url = url,
            method = method,
            headers = headers,
            body = body,
            timeoutSeconds = timeoutSeconds
        ).execute()
    }

    class Call internal constructor(
        private val url: String,
        private val method: String,
        private val headers: Map<String, String>,
        private val body: ByteArray?,
        private val timeoutSeconds: Long
    ) {
        private val canceled = AtomicBoolean(false)
        private val requestRef = AtomicReference<UrlRequest?>(null)

        fun isCanceled(): Boolean = canceled.get()

        fun cancel() {
            canceled.set(true)

            try {
                requestRef.get()?.cancel()
            } catch (_: Exception) {
            }
        }

        fun execute(): Response {
            ensureEngineReady()

            if (canceled.get()) {
                throw IOException("Cronet call canceled before start")
            }

            val latch = CountDownLatch(1)
            val result = AtomicReference<Response?>()
            val failure = AtomicReference<Throwable?>()
            val output = ByteArrayOutputStream()

            val callback = object : UrlRequest.Callback() {

                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String
                ) {
                    if (canceled.get()) {
                        request.cancel()
                    } else {
                        request.followRedirect()
                    }
                }

                override fun onResponseStarted(
                    request: UrlRequest,
                    info: UrlResponseInfo
                ) {
                    if (canceled.get()) {
                        request.cancel()
                        return
                    }

                    request.read(ByteBuffer.allocateDirect(64 * 1024))
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer
                ) {
                    byteBuffer.flip()

                    if (byteBuffer.hasRemaining()) {
                        val chunk = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(chunk)
                        output.write(chunk)
                    }

                    if (canceled.get()) {
                        request.cancel()
                        return
                    }

                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(
                    request: UrlRequest,
                    info: UrlResponseInfo
                ) {

                    result.set(
                        Response(
                            code = info.httpStatusCode,
                            headers = info.allHeaders,
                            body = output.toByteArray(),
                            protocol = info.negotiatedProtocol
                        )
                    )
                    latch.countDown()
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException
                ) {
                    failure.set(error)
                    latch.countDown()
                }

                override fun onCanceled(
                    request: UrlRequest,
                    info: UrlResponseInfo?
                ) {
                    failure.compareAndSet(
                        null,
                        IOException("Cronet call canceled")
                    )
                    latch.countDown()
                }
            }

            val builder = CronetEchEngine.engine
                .newUrlRequestBuilder(url, callback, executor)
                .setHttpMethod(method)

            for ((name, value) in headers) {
                builder.addHeader(name, value)
            }

            if (body != null) {
                builder.setUploadDataProvider(
                    UploadDataProviders.create(body),
                    executor
                )
            }

            val request = builder.build()
            requestRef.set(request)

            // stop() may have raced with request construction.
            if (canceled.get()) {
                request.cancel()
                requestRef.compareAndSet(request, null)
                throw IOException("Cronet call canceled before start")
            }

            request.start()

            try {
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    cancel()
                    throw SocketTimeoutException(
                        "Cronet request timed out after ${timeoutSeconds}s: $method $url"
                    )
                }

                failure.get()?.let { throwable ->
                    if (throwable is IOException) {
                        throw throwable
                    }

                    throw IOException(
                        "Cronet request failed: $method $url",
                        throwable
                    )
                }

                return result.get()
                    ?: throw IOException(
                        "Cronet request finished without response: $method $url"
                    )
            } finally {
                requestRef.compareAndSet(request, null)
            }
        }
    }
}
