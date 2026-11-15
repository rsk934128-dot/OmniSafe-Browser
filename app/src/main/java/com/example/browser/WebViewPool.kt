package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.data.BrowserMode
import com.example.data.PendingDownloadRequest
import com.example.data.SecurityConfig

interface WebViewEventCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleReceived(tabId: String, title: String)
    fun onTrackerBlocked(tabId: String)
    fun onScriptBlocked(tabId: String)
    fun onPageError(tabId: String, url: String, errorDescription: String)
    fun onDownloadIntercepted(tabId: String, request: PendingDownloadRequest)
    fun onRendererTerminated(tabId: String)
}

class WebViewPool(
    private val context: Context,
    private val callbacks: WebViewEventCallbacks,
) {
    private val pool = mutableMapOf<String, WebView>()
    private var currentConfig: SecurityConfig = SecurityConfig()

    fun updateSecurityConfig(config: SecurityConfig) {
        currentConfig = config
        pool.values.forEach { webView ->
            val seed = (webView.tag as? String) ?: "DEFAULT"
            applySettingsToWebView(webView, config, seed)
        }
    }

    fun getOrCreateWebView(tabId: String, seed: String, config: SecurityConfig): WebView {
        currentConfig = config
        return pool.getOrPut(tabId) {
            createHardenedWebView(tabId, seed, config)
        }.also { webView ->
            applySettingsToWebView(webView, config, seed)
        }
    }

    fun getWebView(tabId: String): WebView? = pool[tabId]

    fun destroyTabWebView(tabId: String) {
        pool.remove(tabId)?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.clearHistory()
            wv.destroy()
        }
    }

    fun shredAllSessions() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        pool.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.clearCache(true)
            wv.clearFormData()
            wv.clearHistory()
            wv.destroy()
        }
        pool.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createHardenedWebView(
        tabId: String,
        seed: String,
        config: SecurityConfig,
    ): WebView {
        val webView = WebView(context).apply {
            tag = seed
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        applySettingsToWebView(webView, config, seed)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChanged(tabId, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    callbacks.onTitleReceived(tabId, title)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (currentConfig.webRtcLeakProtectionEnabled) {
                    request?.deny()
                } else {
                    super.onPermissionRequest(request)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                // Always deny geolocation for privacy
                callback?.invoke(origin, false, false)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                val lower = targetUrl.lowercase()

                if (lower.startsWith("intent:") || lower.startsWith("market:") || lower.startsWith("tel:")) {
                    return true // Prevent silent external app leaks from web pages
                }

                if (currentConfig.httpsEverywhereEnabled && lower.startsWith("http://") &&
                    !TrackerAndUrlEngine.isOnionUrl(targetUrl) &&
                    !TrackerAndUrlEngine.isLocalhostOrLoopback(targetUrl)
                ) {
                    val upgraded = TrackerAndUrlEngine.applyHttpsUpgradeIfNeeded(targetUrl, true)
                    view?.loadUrl(upgraded)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null

                if (currentConfig.trackerBlockerEnabled && TrackerAndUrlEngine.isTrackerRequest(reqUrl)) {
                    callbacks.onTrackerBlocked(tabId)
                    return TrackerAndUrlEngine.emptyBlockedResponse()
                }

                if (currentConfig.noScriptEnabled) {
                    val path = request.url?.path?.lowercase().orEmpty()
                    if (path.endsWith(".js") || path.endsWith(".mjs")) {
                        callbacks.onScriptBlocked(tabId)
                        return TrackerAndUrlEngine.emptyBlockedResponse()
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val safeUrl = url.orEmpty()
                callbacks.onPageStarted(tabId, safeUrl)
                if (!currentConfig.noScriptEnabled) {
                    val script = PrivacyScripts.buildHardeningScript(
                        seed = (view?.tag as? String) ?: seed,
                        enableWebRtcGuard = currentConfig.webRtcLeakProtectionEnabled,
                        enableFingerprintRandomization = currentConfig.fingerprintRandomizationEnabled,
                    )
                    if (script.isNotEmpty()) {
                        view?.evaluateJavascript(script, null)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val safeUrl = url.orEmpty()
                if (!currentConfig.noScriptEnabled) {
                    val script = PrivacyScripts.buildHardeningScript(
                        seed = (view?.tag as? String) ?: seed,
                        enableWebRtcGuard = currentConfig.webRtcLeakProtectionEnabled,
                        enableFingerprintRandomization = currentConfig.fingerprintRandomizationEnabled,
                    )
                    if (script.isNotEmpty()) {
                        view?.evaluateJavascript(script, null)
                    }
                }
                callbacks.onPageFinished(
                    tabId = tabId,
                    url = safeUrl,
                    title = view?.title,
                    canGoBack = view?.canGoBack() == true,
                    canGoForward = view?.canGoForward() == true,
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    val urlStr = request.url?.toString().orEmpty()
                    val desc = error?.description?.toString() ?: "Connection failed"
                    callbacks.onPageError(tabId, urlStr, desc)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                pool.remove(tabId)
                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                callbacks.onRendererTerminated(tabId)
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            callbacks.onDownloadIntercepted(
                tabId,
                PendingDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType ?: "application/octet-stream",
                    contentLength = contentLength,
                    fileName = guessedName,
                    isOnionSource = TrackerAndUrlEngine.isOnionUrl(url) ||
                        currentConfig.browserMode == BrowserMode.TOR_ONION,
                ),
            )
        }

        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettingsToWebView(
        webView: WebView,
        config: SecurityConfig,
        seed: String,
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = !config.noScriptEnabled
        settings.domStorageEnabled = config.browserMode == BrowserMode.SURFACE
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = if (config.httpsEverywhereEnabled) {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        if (config.fingerprintRandomizationEnabled) {
            settings.userAgentString = PrivacyScripts.profileForSeed(seed).userAgent
        } else {
            settings.userAgentString = null
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, config.forceDarkWebTheme)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(config.browserMode == BrowserMode.SURFACE)
        cookieManager.setAcceptThirdPartyCookies(webView, !config.blockThirdPartyCookies)
    }
}
