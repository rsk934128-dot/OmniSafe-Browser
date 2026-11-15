package com.example.data

import java.util.UUID

enum class BrowserMode {
    SURFACE,
    TOR_ONION,
}

enum class ProxyProtocol(val label: String, val scheme: String, val defaultPort: Int) {
    SOCKS5("SOCKS5 (Tor)", "socks5", 9050),
    HTTP("HTTP (Orbot/Privoxy)", "http", 8118),
}

enum class SearchEngine(
    val displayName: String,
    val surfaceQueryTemplate: String,
    val onionQueryTemplate: String,
) {
    DUCKDUCKGO(
        displayName = "DuckDuckGo",
        surfaceQueryTemplate = "https://duckduckgo.com/?q=%s",
        onionQueryTemplate = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q=%s",
    ),
    STARTPAGE(
        displayName = "Startpage",
        surfaceQueryTemplate = "https://www.startpage.com/do/search?query=%s",
        onionQueryTemplate = "https://www.startpage.com/do/search?query=%s",
    ),
    BRAVE(
        displayName = "Brave Search",
        surfaceQueryTemplate = "https://search.brave.com/search?q=%s",
        onionQueryTemplate = "https://search.brave4u7jddbv7cyviptqjc7jusxh72uik7zt6adtckl5f4nwy2v72qd.onion/search?q=%s",
    ),
}

sealed class TorProxyState {
    data object Inactive : TorProxyState()
    data object Checking : TorProxyState()
    data class Connected(val endpoint: String, val latencyMs: Long) : TorProxyState()
    data class Unreachable(val endpoint: String, val reason: String) : TorProxyState()
    data class UnsupportedApi(val reason: String) : TorProxyState()
}

data class SecurityConfig(
    val browserMode: BrowserMode = BrowserMode.SURFACE,
    val noScriptEnabled: Boolean = true,
    val fingerprintRandomizationEnabled: Boolean = true,
    val httpsEverywhereEnabled: Boolean = true,
    val webRtcLeakProtectionEnabled: Boolean = true,
    val trackerBlockerEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val saveSurfaceHistory: Boolean = false,
    val torProxyHost: String = "127.0.0.1",
    val torProxyPort: Int = 9050,
    val torProxyProtocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val disclaimerAccepted: Boolean = false,
    val forceDarkWebTheme: Boolean = true,
) {
    val torEndpoint: String
        get() = "${torProxyProtocol.scheme}://$torProxyHost:$torProxyPort"
}

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val mode: BrowserMode = BrowserMode.SURFACE,
    val url: String = ABOUT_START_URL,
    val title: String = "Start Page",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blockedTrackersCount: Int = 0,
    val blockedScriptsCount: Int = 0,
    val isHttpsSecure: Boolean = true,
    val isOnionAddress: Boolean = false,
    val lastError: String? = null,
    val fingerprintSeed: String = UUID.randomUUID().toString().take(8).uppercase(),
    val userAgentLabel: String = "Hardened Mobile",
) {
    val isStartPage: Boolean
        get() = url == ABOUT_START_URL
}

data class PendingDownloadRequest(
    val url: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimeType: String,
    val contentLength: Long,
    val fileName: String,
    val isOnionSource: Boolean,
)

const val ABOUT_START_URL = "about:start"
