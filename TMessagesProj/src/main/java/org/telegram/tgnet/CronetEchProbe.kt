package org.telegram.tgnet

import android.util.Log
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.telegram.messenger.ApplicationLoader
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object CronetEchProbe {

    private const val TAG = "CronetEchProbe"

    private val executor = Executors.newSingleThreadExecutor()
    private val hasRun = AtomicBoolean(false)

    private val engine
        get() = CronetEchEngine.engine

    fun run(host: String) {
        if (!hasRun.compareAndSet(false, true)) {
            Log.i(TAG, "Probe already ran in this process; skipping")
            return
        }

        val cleanHost = host
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')

        val url = "https://$cleanHost/"

        val netLogDir = ApplicationLoader.applicationContext.getExternalFilesDir(null)
            ?: ApplicationLoader.applicationContext.filesDir
        val netLogFile = java.io.File(
            netLogDir,
            "cronet-ech-netlog-first.json"
        )

        val netLogStopped = AtomicBoolean(false)

        fun stopNetLogOnce() {
            if (!netLogStopped.compareAndSet(false, true)) {
                return
            }

            try {
                engine.stopNetLog()
                Log.i(TAG, "NetLog stopped: ${netLogFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "NetLog stop failed", e)
            }
        }

        try {
            engine.startNetLogToFile(netLogFile.absolutePath, true)
            Log.i(TAG, "NetLog started: ${netLogFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "NetLog start failed", e)
        }

        Log.i(TAG, "Starting Cronet ECH probe: $url")

        val callback = object : UrlRequest.Callback() {

            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String
            ) {
                request.followRedirect()
            }

            override fun onResponseStarted(
                request: UrlRequest,
                info: UrlResponseInfo
            ) {
                Log.i(
                    TAG,
                    "Connected: HTTP ${info.httpStatusCode}, protocol=${info.negotiatedProtocol}"
                )

                stopNetLogOnce()
                request.cancel()
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer
            ) {
            }

            override fun onSucceeded(
                request: UrlRequest,
                info: UrlResponseInfo
            ) {
                stopNetLogOnce()
                Log.i(TAG, "Probe succeeded")
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException
            ) {
                stopNetLogOnce()
                Log.e(TAG, "Probe failed", error)
            }

            override fun onCanceled(
                request: UrlRequest,
                info: UrlResponseInfo?
            ) {
                stopNetLogOnce()
                Log.i(TAG, "Probe finished after TLS handshake")
            }
        }

        executor.execute {
            Thread.sleep(100)

            engine.newUrlRequestBuilder(url, callback, executor)
                .build()
                .start()
        }
    }
}
