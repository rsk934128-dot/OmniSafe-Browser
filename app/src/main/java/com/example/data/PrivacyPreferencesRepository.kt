package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onion_shield_privacy_prefs"
)

class PrivacyPreferencesRepository(private val context: Context) {

    private object Keys {
        val BROWSER_MODE = stringPreferencesKey("browser_mode")
        val NO_SCRIPT = booleanPreferencesKey("no_script_enabled")
        val FINGERPRINT_RANDOMIZATION = booleanPreferencesKey("fingerprint_randomization")
        val HTTPS_EVERYWHERE = booleanPreferencesKey("https_everywhere")
        val WEBRTC_LEAK_PROTECTION = booleanPreferencesKey("webrtc_leak_protection")
        val TRACKER_BLOCKER = booleanPreferencesKey("tracker_blocker")
        val BLOCK_THIRD_PARTY_COOKIES = booleanPreferencesKey("block_third_party_cookies")
        val SAVE_SURFACE_HISTORY = booleanPreferencesKey("save_surface_history")
        val TOR_PROXY_HOST = stringPreferencesKey("tor_proxy_host")
        val TOR_PROXY_PORT = intPreferencesKey("tor_proxy_port")
        val TOR_PROXY_PROTOCOL = stringPreferencesKey("tor_proxy_protocol")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val FORCE_DARK_THEME = booleanPreferencesKey("force_dark_theme")
    }

    val securityConfig: Flow<SecurityConfig> = context.privacyDataStore.data.map { prefs ->
        SecurityConfig(
            browserMode = prefs[Keys.BROWSER_MODE]?.let {
                runCatching { BrowserMode.valueOf(it) }.getOrNull()
            } ?: BrowserMode.SURFACE,
            noScriptEnabled = prefs[Keys.NO_SCRIPT] ?: true,
            fingerprintRandomizationEnabled = prefs[Keys.FINGERPRINT_RANDOMIZATION] ?: true,
            httpsEverywhereEnabled = prefs[Keys.HTTPS_EVERYWHERE] ?: true,
            webRtcLeakProtectionEnabled = prefs[Keys.WEBRTC_LEAK_PROTECTION] ?: true,
            trackerBlockerEnabled = prefs[Keys.TRACKER_BLOCKER] ?: true,
            blockThirdPartyCookies = prefs[Keys.BLOCK_THIRD_PARTY_COOKIES] ?: true,
            saveSurfaceHistory = prefs[Keys.SAVE_SURFACE_HISTORY] ?: false,
            torProxyHost = prefs[Keys.TOR_PROXY_HOST] ?: "127.0.0.1",
            torProxyPort = prefs[Keys.TOR_PROXY_PORT] ?: 9050,
            torProxyProtocol = prefs[Keys.TOR_PROXY_PROTOCOL]?.let {
                runCatching { ProxyProtocol.valueOf(it) }.getOrNull()
            } ?: ProxyProtocol.SOCKS5,
            searchEngine = prefs[Keys.SEARCH_ENGINE]?.let {
                runCatching { SearchEngine.valueOf(it) }.getOrNull()
            } ?: SearchEngine.DUCKDUCKGO,
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
            forceDarkWebTheme = prefs[Keys.FORCE_DARK_THEME] ?: true,
        )
    }

    suspend fun setBrowserMode(mode: BrowserMode) {
        context.privacyDataStore.edit { it[Keys.BROWSER_MODE] = mode.name }
    }

    suspend fun setNoScript(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.NO_SCRIPT] = enabled }
    }

    suspend fun setFingerprintRandomization(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.FINGERPRINT_RANDOMIZATION] = enabled }
    }

    suspend fun setHttpsEverywhere(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.HTTPS_EVERYWHERE] = enabled }
    }

    suspend fun setWebRtcLeakProtection(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.WEBRTC_LEAK_PROTECTION] = enabled }
    }

    suspend fun setTrackerBlocker(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.TRACKER_BLOCKER] = enabled }
    }

    suspend fun setBlockThirdPartyCookies(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.BLOCK_THIRD_PARTY_COOKIES] = enabled }
    }

    suspend fun setSaveSurfaceHistory(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.SAVE_SURFACE_HISTORY] = enabled }
    }

    suspend fun updateTorProxy(host: String, port: Int, protocol: ProxyProtocol) {
        context.privacyDataStore.edit {
            it[Keys.TOR_PROXY_HOST] = host.trim().ifBlank { "127.0.0.1" }
            it[Keys.TOR_PROXY_PORT] = port.coerceIn(1, 65535)
            it[Keys.TOR_PROXY_PROTOCOL] = protocol.name
        }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.privacyDataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }
    }

    suspend fun acceptDisclaimer() {
        context.privacyDataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = true }
    }

    suspend fun setForceDarkTheme(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.FORCE_DARK_THEME] = enabled }
    }
}
