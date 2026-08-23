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
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(proxyHost: String) {
        if (isRunning) return
        isRunning = true

        // 1. Start C++ Proxy Server
        ConnectionsManager.native_startWebProxy(proxyHost)

        // 2. Setup WebView on Main Thread
        mainHandler.post {
            setupWebView()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        // 1. Stop C++ Proxy Server
        ConnectionsManager.native_stopWebProxy()

        // 2. Destroy/Clear WebView on Main Thread
        mainHandler.post {
            clearWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            if (webView == null) {
                webView = WebView(ApplicationLoader.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.mediaPlaybackRequiresUserGesture = false
                    
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
