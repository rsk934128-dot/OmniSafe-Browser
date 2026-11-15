package com.example.data

import java.util.UUID

enum class BrowserMode {
    SURFACE,
    TOR_ONION,
}

enum class CircuitPrivacyLevel(
    val label: String,
    val summary: String,
    val livePollIntervalMs: Long,
) {
    STANDARD(
        label = "Standard",
        summary = "Balanced speed & Tor defaults with customizable script controls",
        livePollIntervalMs = 10_000L,
    ),
    STRICT(
        label = "Strict",
        summary = "Locks No-Script, WebRTC, HTTPS, anti-fingerprinting & 5s circuit checks",
        livePollIntervalMs = 5_000L,
    ),
}

enum class VaultEntryType(val label: String) {
    NOTE("Private Note"),
    CREDENTIAL("Password / Login"),
    BOOKMARK("Secret Bookmark"),
}

data class DecryptedVaultItem(
    val id: Long,
    val entryType: VaultEntryType,
    val title: String,
    val secret: String,
    val url: String,
    val isOnion: Boolean,
    val updatedAt: Long,
)

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

enum class BridgeTransport(val label: String, val transportName: String, val summary: String) {
    DIRECT(
        label = "Direct",
        transportName = "direct",
        summary = "Connect directly to public Tor entry guards",
    ),
    OBFS4(
        label = "obfs4",
        transportName = "obfs4",
        summary = "Scramble Tor packet signatures against DPI censorship",
    ),
    SNOWFLAKE(
        label = "Snowflake",
        transportName = "snowflake",
        summary = "Tunnel via ephemeral WebRTC browser proxies",
    ),
    MEEK_AZURE(
        label = "meek-azure",
        transportName = "meek_lite",
        summary = "Domain-fronting transport over Azure CDN",
    ),
    CUSTOM(
        label = "Custom obfs4",
        transportName = "custom",
        summary = "Use user-supplied private bridge lines",
    ),
}

enum class CircuitNodeRole {
    DEVICE,
    LOCAL_PROXY,
    GUARD,
    MIDDLE,
    EXIT,
    ORBOT_MANAGED,
}

enum class OnionAddressStatus(val label: String) {
    START_PAGE("Start Page"),
    V3_VERIFIED("V3 .onion (56-char Ed25519)"),
    V2_DEPRECATED("Deprecated V2 .onion (16-char)"),
    CLEARNET_HTTPS("Clearnet HTTPS"),
    CLEARNET_HTTP("Insecure HTTP"),
}

data class CircuitHopInfo(
    val role: CircuitNodeRole,
    val title: String,
    val detail: String,
    val fingerprint: String? = null,
    val latencyMs: Long? = null,
    val countryCode: String? = null,
    val ipAddress: String? = null,
    val orPort: Int? = null,
    val flags: List<String> = emptyList(),
)

data class TorCircuitDiagnostic(
    val isChecking: Boolean = false,
    val socks5HandshakeOk: Boolean? = null,
    val proxyLatencyMs: Long? = null,
    val controllerLatencyMs: Long? = null,
    val controlPortConnected: Boolean = false,
    val controlPortMessage: String = "ControlPort not queried",
    val activeCircuitId: String? = null,
    val circuitHops: List<CircuitHopInfo> = emptyList(),
    val hops: List<CircuitHopInfo> = emptyList(),
    val exitIp: String? = null,
    val isTorExitVerified: Boolean? = null,
    val exitCheckLatencyMs: Long? = null,
    val exitCheckError: String? = null,
    val lastCheckedEpochMs: Long? = null,
)

sealed class TorProxyState {
    data object Inactive : TorProxyState()
    data object Checking : TorProxyState()
    data class Connected(val endpoint: String, val latencyMs: Long) : TorProxyState()
    data class Unreachable(val endpoint: String, val reason: String) : TorProxyState()
    data class UnsupportedApi(val reason: String) : TorProxyState()
}

data class SecurityConfig(
    val browserMode: BrowserMode = BrowserMode.SURFACE,
    val noScriptEnabled: Boolean = false,
    val trustedJsHosts: Set<String> = emptySet(),
    val fingerprintRandomizationEnabled: Boolean = true,
    val httpsEverywhereEnabled: Boolean = true,
    val webRtcLeakProtectionEnabled: Boolean = true,
    val trackerBlockerEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val saveSurfaceHistory: Boolean = false,
    val torProxyHost: String = "127.0.0.1",
    val torProxyPort: Int = 9050,
    val torProxyProtocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val bridgeTransport: BridgeTransport = BridgeTransport.DIRECT,
    val customBridgeLines: String = "",
    val torControlPort: Int = 9051,
    val torControlPassword: String = "",
    val circuitPrivacyLevel: CircuitPrivacyLevel = CircuitPrivacyLevel.STANDARD,
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

data class LinkMenuTarget(
    val url: String,
    val title: String = "",
    val isSharedFromExternalApp: Boolean = false,
)

const val ABOUT_START_URL = "about:start"
