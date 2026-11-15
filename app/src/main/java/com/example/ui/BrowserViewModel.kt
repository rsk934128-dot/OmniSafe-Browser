package com.example.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.PrivacyScripts
import com.example.browser.TorProxyController
import com.example.browser.TrackerAndUrlEngine
import com.example.browser.WebViewEventCallbacks
import com.example.browser.WebViewPool
import com.example.data.ABOUT_START_URL
import com.example.data.BookmarkEntity
import com.example.data.BrowserLibraryRepository
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.DownloadEntity
import com.example.data.HistoryEntity
import com.example.data.OnionShieldDatabase
import com.example.data.PendingDownloadRequest
import com.example.data.PrivacyPreferencesRepository
import com.example.data.ProxyProtocol
import com.example.data.SearchEngine
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val activeTabId: String = tabs.first().id,
    val omniboxInput: String = "",
    val isOmniboxFocused: Boolean = false,
    val securityConfig: SecurityConfig = SecurityConfig(),
    val torProxyState: TorProxyState = TorProxyState.Inactive,
    val totalTrackersBlocked: Int = 0,
    val totalScriptsBlocked: Int = 0,
    val pendingDownload: PendingDownloadRequest? = null,
    val showDisclaimerDialog: Boolean = false,
    val showShredConfirmDialog: Boolean = false,
    val statusBannerMessage: String? = null,
) {
    val activeTab: BrowserTab
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
}

class BrowserViewModel(application: Application) : AndroidViewModel(application), WebViewEventCallbacks {

    private val prefsRepo = PrivacyPreferencesRepository(application)
    private val libraryRepo = BrowserLibraryRepository(OnionShieldDatabase.getInstance(application))
    val webViewPool = WebViewPool(application, this)

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<BookmarkEntity>> = libraryRepo.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = libraryRepo.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = libraryRepo.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(_uiState, bookmarks) { state, list ->
        val url = state.activeTab.url
        url != ABOUT_START_URL && list.any { it.url == url }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var lastAppliedProxyKey: String? = null

    init {
        viewModelScope.launch {
            prefsRepo.securityConfig.collectLatest { config ->
                _uiState.update { current ->
                    current.copy(
                        securityConfig = config,
                        showDisclaimerDialog = !config.disclaimerAccepted,
                    )
                }
                webViewPool.updateSecurityConfig(config)

                val proxyKey = "${config.browserMode}:${config.torProxyProtocol}:${config.torProxyHost}:${config.torProxyPort}"
                if (proxyKey != lastAppliedProxyKey) {
                    lastAppliedProxyKey = proxyKey
                    applyProxyForMode(config)
                }
            }
        }
    }

    private suspend fun applyProxyForMode(config: SecurityConfig) {
        if (config.browserMode == BrowserMode.TOR_ONION) {
            _uiState.update { it.copy(torProxyState = TorProxyState.Checking) }
        }
        val result = TorProxyController.applyProxyMode(
            mode = config.browserMode,
            host = config.torProxyHost,
            port = config.torProxyPort,
            protocol = config.torProxyProtocol,
        )
        _uiState.update { it.copy(torProxyState = result) }
    }

    fun switchBrowserMode(targetMode: BrowserMode) {
        viewModelScope.launch {
            prefsRepo.setBrowserMode(targetMode)
            val currentTab = _uiState.value.activeTab
            updateTab(currentTab.id) { it.copy(mode = targetMode, lastError = null) }
            if (targetMode == BrowserMode.TOR_ONION && !_uiState.value.securityConfig.disclaimerAccepted) {
                _uiState.update { it.copy(showDisclaimerDialog = true) }
            }
        }
    }

    fun testTorProxyConnection() {
        viewModelScope.launch {
            val cfg = _uiState.value.securityConfig
            _uiState.update { it.copy(torProxyState = TorProxyState.Checking) }
            val result = TorProxyController.checkProxyReachability(
                host = cfg.torProxyHost,
                port = cfg.torProxyPort,
                protocol = cfg.torProxyProtocol,
            )
            _uiState.update { it.copy(torProxyState = result) }
        }
    }

    fun openUrlOrSearch(rawInput: String, targetTabId: String = _uiState.value.activeTabId) {
        val cfg = _uiState.value.securityConfig
        val resolved = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = rawInput,
            mode = cfg.browserMode,
            httpsEverywhere = cfg.httpsEverywhereEnabled,
            searchEngine = cfg.searchEngine,
        )
        val isOnion = TrackerAndUrlEngine.isOnionUrl(resolved)

        // Automatically elevate to Tor Onion mode if user enters a .onion domain
        if (isOnion && cfg.browserMode != BrowserMode.TOR_ONION) {
            viewModelScope.launch {
                prefsRepo.setBrowserMode(BrowserMode.TOR_ONION)
            }
        }

        updateTab(targetTabId) { tab ->
            tab.copy(
                url = resolved,
                title = if (resolved == ABOUT_START_URL) "Start Page" else resolved,
                isOnionAddress = isOnion,
                isHttpsSecure = resolved.startsWith("https://", ignoreCase = true) || isOnion,
                lastError = null,
                mode = if (isOnion) BrowserMode.TOR_ONION else cfg.browserMode,
            )
        }
        _uiState.update {
            it.copy(
                omniboxInput = if (resolved == ABOUT_START_URL) "" else resolved,
                isOmniboxFocused = false,
            )
        }
        if (resolved != ABOUT_START_URL) {
            val tab = _uiState.value.tabs.firstOrNull { it.id == targetTabId } ?: _uiState.value.activeTab
            webViewPool.getOrCreateWebView(tab.id, tab.fingerprintSeed, cfg).loadUrl(resolved)
        }
    }

    fun onOmniboxChanged(text: String) {
        _uiState.update { it.copy(omniboxInput = text) }
    }

    fun onOmniboxFocusChanged(focused: Boolean) {
        _uiState.update { state ->
            val currentUrl = state.activeTab.url
            val nextInput = if (focused) {
                if (currentUrl == ABOUT_START_URL) "" else currentUrl
            } else {
                if (currentUrl == ABOUT_START_URL) "" else currentUrl
            }
            state.copy(isOmniboxFocused = focused, omniboxInput = nextInput)
        }
    }

    fun createNewTab(mode: BrowserMode = _uiState.value.securityConfig.browserMode, initialUrl: String = ABOUT_START_URL) {
        val seed = UUID.randomUUID().toString().take(8).uppercase()
        val profile = PrivacyScripts.profileForSeed(seed)
        val newTab = BrowserTab(
            mode = mode,
            url = initialUrl,
            title = if (initialUrl == ABOUT_START_URL) "Start Page" else initialUrl,
            fingerprintSeed = seed,
            userAgentLabel = profile.label,
        )
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs + newTab,
                activeTabId = newTab.id,
                omniboxInput = if (initialUrl == ABOUT_START_URL) "" else initialUrl,
                isOmniboxFocused = false,
            )
        }
        viewModelScope.launch {
            if (mode != _uiState.value.securityConfig.browserMode) {
                prefsRepo.setBrowserMode(mode)
            }
        }
        if (initialUrl != ABOUT_START_URL) {
            openUrlOrSearch(initialUrl, newTab.id)
        }
    }

    fun selectTab(tabId: String) {
        val target = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        _uiState.update {
            it.copy(
                activeTabId = tabId,
                omniboxInput = if (target.isStartPage) "" else target.url,
                isOmniboxFocused = false,
            )
        }
        viewModelScope.launch {
            if (target.mode != _uiState.value.securityConfig.browserMode) {
                prefsRepo.setBrowserMode(target.mode)
            }
        }
    }

    fun closeTab(tabId: String) {
        webViewPool.destroyTabWebView(tabId)
        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(mode = state.securityConfig.browserMode)
                state.copy(
                    tabs = listOf(fresh),
                    activeTabId = fresh.id,
                    omniboxInput = "",
                )
            } else {
                val nextActive = if (state.activeTabId == tabId) remaining.last() else remaining.first { it.id == state.activeTabId }
                state.copy(
                    tabs = remaining,
                    activeTabId = nextActive.id,
                    omniboxInput = if (nextActive.isStartPage) "" else nextActive.url,
                )
            }
        }
    }

    fun goBack(): Boolean {
        val tab = _uiState.value.activeTab
        val wv = webViewPool.getWebView(tab.id)
        return if (wv != null && wv.canGoBack()) {
            wv.goBack()
            true
        } else if (!tab.isStartPage) {
            updateTab(tab.id) {
                it.copy(
                    url = ABOUT_START_URL,
                    title = "Start Page",
                    isLoading = false,
                    canGoBack = false,
                    lastError = null,
                )
            }
            _uiState.update { it.copy(omniboxInput = "") }
            true
        } else {
            false
        }
    }

    fun goForward() {
        val tab = _uiState.value.activeTab
        webViewPool.getWebView(tab.id)?.let { wv ->
            if (wv.canGoForward()) wv.goForward()
        }
    }

    fun reloadOrStop() {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return
        val wv = webViewPool.getWebView(tab.id)
        if (tab.isLoading) {
            wv?.stopLoading()
            updateTab(tab.id) { it.copy(isLoading = false, progress = 0) }
        } else {
            updateTab(tab.id) { it.copy(lastError = null) }
            wv?.reload() ?: openUrlOrSearch(tab.url, tab.id)
        }
    }

    fun goHome() {
        val tab = _uiState.value.activeTab
        updateTab(tab.id) {
            it.copy(
                url = ABOUT_START_URL,
                title = "Start Page",
                isLoading = false,
                progress = 0,
                lastError = null,
            )
        }
        _uiState.update { it.copy(omniboxInput = "") }
    }

    fun requestShredSession() {
        _uiState.update { it.copy(showShredConfirmDialog = true) }
    }

    fun dismissShredConfirm() {
        _uiState.update { it.copy(showShredConfirmDialog = false) }
    }

    fun confirmShredSession() {
        webViewPool.shredAllSessions()
        val seed = UUID.randomUUID().toString().take(8).uppercase()
        val profile = PrivacyScripts.profileForSeed(seed)
        val cleanTab = BrowserTab(
            mode = _uiState.value.securityConfig.browserMode,
            fingerprintSeed = seed,
            userAgentLabel = profile.label,
        )
        _uiState.update {
            it.copy(
                tabs = listOf(cleanTab),
                activeTabId = cleanTab.id,
                omniboxInput = "",
                totalTrackersBlocked = 0,
                totalScriptsBlocked = 0,
                showShredConfirmDialog = false,
                statusBannerMessage = "Session shredded: cookies, cache, storage & fingerprint seed wiped.",
            )
        }
    }

    fun regenerateFingerprintSeed() {
        val seed = UUID.randomUUID().toString().take(8).uppercase()
        val profile = PrivacyScripts.profileForSeed(seed)
        val tab = _uiState.value.activeTab
        webViewPool.getWebView(tab.id)?.let { it.tag = seed }
        updateTab(tab.id) {
            it.copy(fingerprintSeed = seed, userAgentLabel = profile.label)
        }
        webViewPool.updateSecurityConfig(_uiState.value.securityConfig)
        _uiState.update {
            it.copy(statusBannerMessage = "Fingerprint rotated: Seed $seed (${profile.label})")
        }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun toggleBookmarkCurrentPage() {
        val tab = _uiState.value.activeTab
        if (tab.isStartPage) return
        viewModelScope.launch {
            if (isCurrentPageBookmarked.value) {
                libraryRepo.removeBookmarkByUrl(tab.url)
                _uiState.update { it.copy(statusBannerMessage = "Removed from bookmarks") }
            } else {
                libraryRepo.addBookmark(tab.title, tab.url)
                _uiState.update { it.copy(statusBannerMessage = "Bookmarked locally") }
            }
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { libraryRepo.removeBookmarkById(id) }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch { libraryRepo.deleteHistoryItem(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { libraryRepo.clearAllHistory() }
    }

    fun confirmPendingDownload() {
        val pending = _uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        val app = getApplication<Application>()

        viewModelScope.launch {
            val systemId = runCatching {
                val request = DownloadManager.Request(Uri.parse(pending.url)).apply {
                    setMimeType(pending.mimeType)
                    addRequestHeader("User-Agent", pending.userAgent)
                    setTitle(pending.fileName)
                    setDescription(
                        if (pending.isOnionSource) "OnionShield Tor Download" else "OnionShield Download",
                    )
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, pending.fileName)
                }
                val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            }.getOrDefault(-1L)

            libraryRepo.recordDownload(
                DownloadEntity(
                    fileName = pending.fileName,
                    url = pending.url,
                    mimeType = pending.mimeType,
                    status = if (systemId >= 0L) "DOWNLOADING" else "FAILED",
                    systemDownloadId = systemId,
                    isOnionSource = pending.isOnionSource,
                    sizeBytes = pending.contentLength.coerceAtLeast(0L),
                ),
            )
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (systemId >= 0L) {
                        "Download queued: ${pending.fileName}"
                    } else {
                        "Could not enqueue download for ${pending.fileName}"
                    },
                )
            }
        }
    }

    fun cancelPendingDownload() {
        val pending = _uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        viewModelScope.launch {
            libraryRepo.recordDownload(
                DownloadEntity(
                    fileName = pending.fileName,
                    url = pending.url,
                    mimeType = pending.mimeType,
                    status = "CANCELLED",
                    isOnionSource = pending.isOnionSource,
                    sizeBytes = pending.contentLength.coerceAtLeast(0L),
                ),
            )
        }
    }

    fun deleteDownloadRecord(id: Long) {
        viewModelScope.launch { libraryRepo.deleteDownload(id) }
    }

    fun clearDownloads() {
        viewModelScope.launch { libraryRepo.clearDownloads() }
    }

    // Security preference mutations
    fun setNoScript(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setNoScript(enabled)
        _uiState.value.activeTab.let { tab ->
            if (!tab.isStartPage) webViewPool.getWebView(tab.id)?.reload()
        }
    }

    fun setFingerprintRandomization(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setFingerprintRandomization(enabled)
    }

    fun setHttpsEverywhere(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setHttpsEverywhere(enabled)
    }

    fun setWebRtcLeakProtection(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setWebRtcLeakProtection(enabled)
    }

    fun setTrackerBlocker(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setTrackerBlocker(enabled)
    }

    fun setBlockThirdPartyCookies(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setBlockThirdPartyCookies(enabled)
    }

    fun setSaveSurfaceHistory(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setSaveSurfaceHistory(enabled)
    }

    fun updateTorProxyConfig(host: String, port: Int, protocol: ProxyProtocol) = viewModelScope.launch {
        prefsRepo.updateTorProxy(host, port, protocol)
    }

    fun setSearchEngine(engine: SearchEngine) = viewModelScope.launch {
        prefsRepo.setSearchEngine(engine)
    }

    fun setForceDarkTheme(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setForceDarkTheme(enabled)
    }

    fun acceptDisclaimer() = viewModelScope.launch {
        prefsRepo.acceptDisclaimer()
        _uiState.update { it.copy(showDisclaimerDialog = false) }
    }

    fun openDisclaimer() {
        _uiState.update { it.copy(showDisclaimerDialog = true) }
    }

    // WebViewEventCallbacks
    override fun onPageStarted(tabId: String, url: String) {
        updateTab(tabId) { tab ->
            tab.copy(
                url = url,
                isLoading = true,
                progress = 10,
                isOnionAddress = TrackerAndUrlEngine.isOnionUrl(url),
                isHttpsSecure = url.startsWith("https://", ignoreCase = true) || TrackerAndUrlEngine.isOnionUrl(url),
                lastError = null,
            )
        }
        if (_uiState.value.activeTabId == tabId && !_uiState.value.isOmniboxFocused) {
            _uiState.update { it.copy(omniboxInput = url) }
        }
    }

    override fun onPageFinished(
        tabId: String,
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: url
        updateTab(tabId) { tab ->
            tab.copy(
                url = url,
                title = cleanTitle,
                isLoading = false,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isOnionAddress = TrackerAndUrlEngine.isOnionUrl(url),
                isHttpsSecure = url.startsWith("https://", ignoreCase = true) || TrackerAndUrlEngine.isOnionUrl(url),
            )
        }
        if (_uiState.value.activeTabId == tabId && !_uiState.value.isOmniboxFocused) {
            _uiState.update { it.copy(omniboxInput = url) }
        }

        val cfg = _uiState.value.securityConfig
        if (cfg.saveSurfaceHistory && cfg.browserMode == BrowserMode.SURFACE && url != ABOUT_START_URL) {
            viewModelScope.launch {
                libraryRepo.recordHistory(cleanTitle, url)
            }
        }
    }

    override fun onProgressChanged(tabId: String, progress: Int) {
        updateTab(tabId) { it.copy(progress = progress, isLoading = progress < 100) }
    }

    override fun onTitleReceived(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title) }
    }

    override fun onTrackerBlocked(tabId: String) {
        updateTab(tabId) { it.copy(blockedTrackersCount = it.blockedTrackersCount + 1) }
        _uiState.update { it.copy(totalTrackersBlocked = it.totalTrackersBlocked + 1) }
    }

    override fun onScriptBlocked(tabId: String) {
        updateTab(tabId) { it.copy(blockedScriptsCount = it.blockedScriptsCount + 1) }
        _uiState.update { it.copy(totalScriptsBlocked = it.totalScriptsBlocked + 1) }
    }

    override fun onPageError(tabId: String, url: String, errorDescription: String) {
        updateTab(tabId) {
            it.copy(
                isLoading = false,
                progress = 0,
                lastError = errorDescription,
            )
        }
    }

    override fun onDownloadIntercepted(tabId: String, request: PendingDownloadRequest) {
        _uiState.update { it.copy(pendingDownload = request) }
    }

    override fun onRendererTerminated(tabId: String) {
        updateTab(tabId) {
            it.copy(
                isLoading = false,
                lastError = "WebView renderer process terminated cleanly for isolation. Tap Reload to resume.",
            )
        }
    }

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) transform(tab) else tab
                },
            )
        }
    }

    override fun onCleared() {
        webViewPool.shredAllSessions()
        super.onCleared()
    }
}
