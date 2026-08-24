package org.telegram.tgnet

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog

private const val TAG = "WEBPROXY"

object WebProxyManager {

    private var webView: WebView? = null
    private var isRunning = false
    private var currentProxyHost: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(proxyHost: String) {
        Log.d(TAG, "start() called: proxyHost=$proxyHost, isRunning=$isRunning, currentHost=$currentProxyHost")
        // Если уже запущен с тем же хостом — ничего не делаем.
        if (isRunning && currentProxyHost == proxyHost) {
            Log.d(TAG, "start() skipped: same host already running")
            return
        }

        // Если хост изменился — останавливаем старый и запускаем новый.
        if (isRunning) {
            Log.d(TAG, "start() stopping old proxy before restart")
            ConnectionsManager.native_stopWebProxy()
        }

        isRunning = true
        currentProxyHost = proxyHost

        // 1. Start C++ Proxy Server
        Log.d(TAG, "start() calling native_startWebProxy($proxyHost)")
        ConnectionsManager.native_startWebProxy(proxyHost)

        // 2. Setup WebView on Main Thread (перезагрузить с новым URL)
        mainHandler.post {
            setupWebView(forceReload = true)
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called, isRunning=$isRunning")
        if (!isRunning) return
        isRunning = false
        currentProxyHost = ""

        // 1. Stop C++ Proxy Server
        ConnectionsManager.native_stopWebProxy()

        // 2. Destroy/Clear WebView on Main Thread
        mainHandler.post {
            clearWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(forceReload: Boolean = false) {
        try {
            Log.d(TAG, "setupWebView() forceReload=$forceReload, webView=${if (webView == null) "null" else "exists"}")
            if (webView == null) {
                webView = WebView(ApplicationLoader.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "WebView.onPageFinished: $url")
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            Log.w(TAG, "WebView.onReceivedSslError: $error — proceeding anyway")
                            handler?.proceed()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.e(TAG, "WebView.onReceivedError: url=${request?.url} err=${error?.description}")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                Log.d(TAG, "JS[${it.messageLevel()}]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                            }
                            return true
                        }
                    }
                }
            } else if (forceReload) {
                // При смене хоста сначала очищаем старый контекст WebView.
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
            }

            // Ожидаем инициализации C++ сервера через polling
            var attempts = 0
            val maxAttempts = 50
            val pollIntervalMs = 20L

            val checkTask = object : Runnable {
                override fun run() {
                    val port = ConnectionsManager.native_getWebProxyPort()
                    Log.d(TAG, "polling C++ port: attempt=$attempts, port=$port")
                    if (port > 0) {
                        val token = ConnectionsManager.native_getWebProxyToken()
                        val url = "http://127.0.0.1:$port/#$token"
                        Log.d(TAG, "C++ server ready: port=$port, token=$token")
                        Log.d(TAG, "Loading URL: $url")
                        webView?.loadUrl(url)
                    } else if (attempts < maxAttempts) {
                        attempts++
                        mainHandler.postDelayed(this, pollIntervalMs)
                    } else {
                        Log.e(TAG, "TIMEOUT: C++ server did not start in ${maxAttempts * pollIntervalMs}ms")
                    }
                }
            }
            mainHandler.post(checkTask)

        } catch (e: Exception) {
            Log.e(TAG, "setupWebView exception", e)
        }
    }

    private fun clearWebView() {
        try {
            webView?.loadUrl("about:blank")
            Log.d(TAG, "clearWebView() done")
        } catch (e: Exception) {
            Log.e(TAG, "clearWebView exception", e)
        }
    }
}

