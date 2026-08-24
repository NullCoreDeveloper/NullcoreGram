package org.telegram.tgnet

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog

object WebProxyManager {

    private var webView: WebView? = null
    private var isRunning = false
    private var currentProxyHost: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(proxyHost: String) {
        // Если уже запущен с тем же хостом — ничего не делаем.
        if (isRunning && currentProxyHost == proxyHost) return

        // Если хост изменился — останавливаем старый и запускаем новый.
        if (isRunning) {
            ConnectionsManager.native_stopWebProxy()
        }

        isRunning = true
        currentProxyHost = proxyHost

        // 1. Start C++ Proxy Server
        ConnectionsManager.native_startWebProxy(proxyHost)

        // 2. Setup WebView on Main Thread (перезагрузить с новым URL)
        mainHandler.post {
            setupWebView(forceReload = true)
        }
    }

    fun stop() {
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
            if (webView == null) {
                webView = WebView(ApplicationLoader.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            FileLog.d("WebProxyManager: WebView finished loading $url")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                FileLog.d("WebProxyManager [JS]: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
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

            val port = ConnectionsManager.native_getWebProxyPort()
            val token = ConnectionsManager.native_getWebProxyToken()

            val url = "http://127.0.0.1:$port/#$token"
            FileLog.d("WebProxyManager: Loading Proxy URL: $url")
            webView?.loadUrl(url)

        } catch (e: Exception) {
            FileLog.e("WebProxyManager: Failed to setup WebView", e)
        }
    }

    private fun clearWebView() {
        try {
            webView?.loadUrl("about:blank")
            FileLog.d("WebProxyManager: WebView cleared")
        } catch (e: Exception) {
            FileLog.e("WebProxyManager: Failed to clear WebView", e)
        }
    }
}
