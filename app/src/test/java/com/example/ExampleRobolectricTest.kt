package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.browser.LiveMonitorForegroundService
import com.example.browser.LiveSessionRuntime
import com.example.browser.PrivacyScripts
import com.example.browser.TorCircuitService
import com.example.browser.TrackerAndUrlEngine
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.CircuitNodeRole
import com.example.data.CircuitPrivacyLevel
import com.example.data.OnionAddressStatus
import com.example.data.SearchEngine
import com.example.security.VaultCryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OnionShield", appName)
    }

    @Test
    fun `https everywhere upgrades clearnet http but preserves onion`() {
        val clearnet = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "http://example.org",
            mode = BrowserMode.SURFACE,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertEquals("https://example.org", clearnet)

        val onion = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
            mode = BrowserMode.TOR_ONION,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertTrue(onion.startsWith("http://"))
        assertTrue(TrackerAndUrlEngine.isOnionUrl(onion))
    }

    @Test
    fun `unsafe schemes are rejected to start page`() {
        val jsScheme = TrackerAndUrlEngine.resolveInputToUrl(
            rawInput = "javascript:alert(1)",
            mode = BrowserMode.SURFACE,
            httpsEverywhere = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        assertEquals(ABOUT_START_URL, jsScheme)
    }

    @Test
    fun `tracker blocker detects analytics and allows normal sites`() {
        assertTrue(TrackerAndUrlEngine.isTrackerRequest("https://www.google-analytics.com/analytics.js"))
        assertTrue(TrackerAndUrlEngine.isTrackerRequest("https://pixel.facebook.com/tr"))
        assertFalse(TrackerAndUrlEngine.isTrackerRequest("https://en.wikipedia.org/wiki/Tor"))
    }

    @Test
    fun `privacy script includes webrtc guard and fingerprint noise`() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "A1B2C3D4",
            enableWebRtcGuard = true,
            enableFingerprintRandomization = true,
        )
        assertTrue(script.contains("RTCPeerConnection"))
        assertTrue(script.contains("OnionShield Virtual GPU (A1B2C3D4)"))
    }

    @Test
    fun `v3 onion validator distinguishes 56 char v3 from 16 char v2`() {
        assertEquals(
            OnionAddressStatus.V3_VERIFIED,
            TorCircuitService.classifyUrl("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"),
        )
        assertEquals(
            OnionAddressStatus.V2_DEPRECATED,
            TorCircuitService.classifyUrl("http://3g2upl4pq6kufc4m.onion"),
        )
        assertEquals(
            OnionAddressStatus.CLEARNET_HTTPS,
            TorCircuitService.classifyUrl("https://www.torproject.org"),
        )
    }

    @Test
    fun `obfs4 bridge line validator accepts valid line and rejects malformed lines`() {
        val valid = TorCircuitService.validateBridgeLines(
            "Bridge obfs4 192.0.2.10:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=abcd1234 iat-mode=0",
        )
        assertTrue(valid.isValid)
        assertEquals(1, valid.normalizedLines.size)
        assertTrue(valid.normalizedLines.first().startsWith("obfs4 "))

        val invalid = TorCircuitService.validateBridgeLines("obfs4 192.0.2.10:443 SHORTFP cert=abc")
        assertFalse(invalid.isValid)
    }

    @Test
    fun `control port circuit status parser extracts guard middle exit hops`() {
        val sample = """
            250+circuit-status=
            14 BUILT ${'$'}AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA~AlphaGuard,${'$'}BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB~BetaMid,${'$'}CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC~GammaExit BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL
            .
            250 OK
        """.trimIndent()
        val parsed = TorCircuitService.parseCircuitStatusResponse(sample)
        assertNotNull(parsed)
        assertEquals("14", parsed!!.first)
        assertEquals(3, parsed.second.size)
        assertEquals(CircuitNodeRole.GUARD, parsed.second[0].role)
        assertEquals(CircuitNodeRole.MIDDLE, parsed.second[1].role)
        assertEquals(CircuitNodeRole.EXIT, parsed.second[2].role)
    }

    @Test
    fun `relay descriptor parser extracts ip orPort and flags from ns id response`() {
        val nsLines = listOf(
            "250+ns/id/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "r AlphaGuard qqqqqqqqqqqqqqqqqqqqqqqqqqq rrrrrrrrrrrrrrrrrrrrrrrrrrr 2026-11-15 08:00:00 198.51.100.42 9001 9030",
            "s Fast Guard Running Stable Valid",
            ".",
            "250 OK",
        )
        val summary = TorCircuitService.parseRelayDescriptorResponse(nsLines)
        assertNotNull(summary)
        assertEquals("AlphaGuard", summary!!.nickname)
        assertEquals("198.51.100.42", summary.ipAddress)
        assertEquals(9001, summary.orPort)
        assertTrue(summary.flags.contains("Guard"))
    }

    @Test
    fun `circuit graph normalizer always yields guard middle exit hops with latencies`() {
        val normalized = com.example.ui.components.normalizeThreeHops(
            hops = emptyList(),
            isConnected = true,
            proxyLatencyMs = 14L,
            controllerLatencyMs = 28L,
            exitCheckLatencyMs = 340L,
        )
        assertEquals(3, normalized.size)
        assertEquals(CircuitNodeRole.GUARD, normalized[0].role)
        assertEquals(14L, normalized[0].latencyMs)
        assertEquals(CircuitNodeRole.MIDDLE, normalized[1].role)
        assertEquals(28L, normalized[1].latencyMs)
        assertEquals(CircuitNodeRole.EXIT, normalized[2].role)
        assertEquals(340L, normalized[2].latencyMs)
    }

    @Test
    fun `vault crypto manager encrypts decrypts aes256 gcm and verifies pbkdf2 pin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = VaultCryptoManager.getOrCreateVaultKey(context)
        val secret = "onion-secret-key-9050"
        val sealed = VaultCryptoManager.encrypt(secret, key)
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains(secret))
        assertEquals(secret, VaultCryptoManager.decrypt(sealed, key))

        val salt = VaultCryptoManager.generateSaltBase64()
        val hash = VaultCryptoManager.hashPassphrase("7391", salt)
        assertTrue(VaultCryptoManager.verifyPassphrase("7391", salt, hash))
        assertFalse(VaultCryptoManager.verifyPassphrase("0000", salt, hash))
    }

    @Test
    fun `circuit privacy level strict uses faster poll interval than standard`() {
        assertTrue(CircuitPrivacyLevel.STRICT.livePollIntervalMs < CircuitPrivacyLevel.STANDARD.livePollIntervalMs)
    }

    @Test
    fun `host normalization and intent fallback url parser work for modern links`() {
        assertEquals("youtube.com", TrackerAndUrlEngine.normalizeHost("https://www.youtube.com/watch?v=abc"))
        assertEquals("github.com", TrackerAndUrlEngine.normalizeHost("https://github.com/rsk934128-dot"))

        val intentUri = "intent://watch?v=3a9pHxp#Intent;scheme=https;package=com.google.android.youtube;S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D3a9pHxp;end"
        assertEquals(
            "https://m.youtube.com/watch?v=3a9pHxp",
            TrackerAndUrlEngine.extractIntentFallbackUrl(intentUri),
        )
    }

    @Test
    fun `surface mode user agent profile is mobile compatible and shortcut title falls back cleanly`() {
        val surfaceProfile = PrivacyScripts.profileForSeed("ABCD1234", BrowserMode.SURFACE)
        assertTrue(surfaceProfile.userAgent.contains("Android"))
        assertTrue(surfaceProfile.userAgent.contains("Mobile"))

        assertEquals(
            "Youtube",
            com.example.browser.HomeAppShortcutHelper.deriveDisplayTitle("", "https://m.youtube.com/watch?v=123"),
        )
        assertEquals(
            "YouTube",
            com.example.browser.HomeAppShortcutHelper.deriveDisplayTitle("YouTube - Music Video", "https://m.youtube.com"),
        )
    }

    @Test
    fun `biometric wrapped vault dek encrypts and decrypts vault entries only after unwrap`() {
        val kekBytes = ByteArray(32) { (it + 7).toByte() }
        val kek = javax.crypto.spec.SecretKeySpec(kekBytes, "AES")

        val encCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, kek)
        }
        val (dek, wrappedEnvelope) = VaultCryptoManager.generateAndWrapVaultDek(encCipher)
        assertTrue(wrappedEnvelope.startsWith("bio1:"))

        val sealedSecret = VaultCryptoManager.encrypt("onion-private-token", dek)
        assertTrue(sealedSecret.startsWith("v1:"))

        // Decrypting with wrong key must fail
        val wrongKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val failedWrongKey = runCatching { VaultCryptoManager.decrypt(sealedSecret, wrongKey) }.isFailure
        assertTrue(failedWrongKey)

        // Unwrapping DEK with authenticated cipher recovers exact Vault key
        val ivB64 = wrappedEnvelope.split(':')[1]
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        val decCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, kek, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        val unwrappedDek = VaultCryptoManager.unwrapVaultDek(decCipher, wrappedEnvelope)
        assertEquals("onion-private-token", VaultCryptoManager.decrypt(sealedSecret, unwrappedDek))
    }

    @Test
    fun `extractFirstHttpUrl parses shared link from external message text`() {
        val shared = "Check this app release: https://github.com/rsk934128-dot/OmniSafe-Browser/releases/latest, very cool!"
        assertEquals(
            "https://github.com/rsk934128-dot/OmniSafe-Browser/releases/latest",
            TrackerAndUrlEngine.extractFirstHttpUrl(shared),
        )
        assertEquals(null, TrackerAndUrlEngine.extractFirstHttpUrl("no url here"))
    }

    @Test
    fun `isApkAsset detects apk packages by mime filename or url path`() {
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://github.com/rsk934128-dot/OmniSafe-Browser/releases/download/v1.0/omnisafe.apk?raw=true",
                fileName = null,
                mimeType = null,
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/download?id=99",
                fileName = "app-release.apk",
                mimeType = "application/octet-stream",
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/file",
                fileName = "package.bin",
                mimeType = "application/vnd.android.package-archive",
            ),
        )
        assertFalse(
            TrackerAndUrlEngine.isApkAsset(
                url = "https://example.org/manual.pdf",
                fileName = "manual.pdf",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun `session guard engine computes net loss alert and bucket distribution accurately`() {
        val rounds = listOf(
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 50.0,
                crashMultiplier = 1.20,
                cashoutMultiplier = null,
                netProfit = -50.0,
            ),
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 40.0,
                crashMultiplier = 5.71,
                cashoutMultiplier = 2.0,
                netProfit = 40.0,
            ),
            com.example.data.SessionRoundLogEntity(
                siteHost = "example.org",
                betAmount = 100.0,
                crashMultiplier = 12.50,
                cashoutMultiplier = null,
                netProfit = -100.0,
            ),
        )
        val stats = com.example.browser.SessionGuardEngine.computeSessionStats(
            rounds = rounds,
            guardEnabled = true,
            elapsedMinutes = 15L,
            timeLimitMinutes = 30,
            budgetLimit = 100.0,
        )
        assertEquals(3, stats.totalRounds)
        assertEquals(1, stats.winsCount)
        assertEquals(-110.0, stats.netProfit, 0.001)
        assertEquals(12.50, stats.maxCrashMultiplier, 0.001)
        assertEquals(com.example.browser.GuardAlertLevel.SAFE, stats.timeAlertLevel)
        assertEquals(com.example.browser.GuardAlertLevel.LIMIT_REACHED, stats.budgetAlertLevel)
        assertEquals(com.example.browser.GuardAlertLevel.LIMIT_REACHED, stats.overallAlertLevel)
        assertEquals(1, stats.buckets[0].count) // 1.0–1.49x
        assertEquals(1, stats.buckets[2].count) // 3.0–9.99x
        assertEquals(1, stats.buckets[3].count) // 10x+
    }

    @Test
    fun `provably fair sha256 and hmac verifier checks post-round commitment`() {
        val serverSeed = "demo_revealed_server_seed_2026"
        val expectedHash = com.example.browser.SessionGuardEngine.sha256Hex(serverSeed)
        val validResult = com.example.browser.SessionGuardEngine.verifyCrashFairness(
            serverSeed = serverSeed,
            clientSeed = "client_seed_abc",
            nonce = "42",
            expectedServerSeedHash = expectedHash,
        )
        assertTrue(validResult.isValidInput)
        assertEquals(true, validResult.commitmentMatched)
        assertTrue(validResult.derivedMultiplier >= 1.00)

        val tamperedResult = com.example.browser.SessionGuardEngine.verifyCrashFairness(
            serverSeed = serverSeed,
            clientSeed = "client_seed_abc",
            nonce = "42",
            expectedServerSeedHash = "0000000000000000",
        )
        assertEquals(false, tamperedResult.commitmentMatched)
    }

    @Test
    fun `live session runtime redacts onion hosts and refreshes elapsed timer`() {
        val now = 1_700_000_000_000L
        LiveSessionRuntime.resetTimer(now)
        assertEquals(0L, LiveSessionRuntime.elapsedMinutes.value)

        val elapsed = LiveSessionRuntime.refreshElapsed(now + 12 * 60_000L)
        assertEquals(12L, elapsed)
        assertEquals(12L, LiveSessionRuntime.elapsedMinutes.value)

        LiveSessionRuntime.updateActiveSiteHost("https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/search")
        assertEquals("onion_redacted", LiveSessionRuntime.activeSiteHost.value)

        LiveSessionRuntime.updateActiveSiteHost("https://example.org/app")
        assertEquals("example.org", LiveSessionRuntime.activeSiteHost.value)
    }

    @Test
    fun `foreground live monitor service enters foreground and stops cleanly on ACTION_STOP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_START)
        val controller = Robolectric.buildService(LiveMonitorForegroundService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        assertTrue(LiveSessionRuntime.isForegroundServiceRunning.value)
        val shadowService = Shadows.shadowOf(service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(LiveMonitorForegroundService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(LiveMonitorForegroundService.CHANNEL_ID)
        assertNotNull(channel)

        val stopIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_STOP)
        service.onStartCommand(stopIntent, 0, 2)

        assertFalse(LiveSessionRuntime.isForegroundServiceRunning.value)
        assertTrue(shadowService.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `foreground live monitor notification includes Tor connectivity status and New Circuit action in Tor mode`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, LiveMonitorForegroundService::class.java)
            .setAction(LiveMonitorForegroundService.ACTION_START)
        val controller = Robolectric.buildService(LiveMonitorForegroundService::class.java, startIntent)
            .create()
        val service = controller.get()

        val torConfig = com.example.data.SecurityConfig(
            browserMode = BrowserMode.TOR_ONION,
            torProxyHost = "127.0.0.1",
            torProxyPort = 9050,
        )
        val torNotification = service.buildNotification(
            stats = com.example.browser.SessionGuardStats(elapsedMinutes = 7L, totalRounds = 2),
            config = torConfig,
            syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
            torState = com.example.data.TorProxyState.Connected("socks5://127.0.0.1:9050", 14L),
        )
        val shadowNotif = Shadows.shadowOf(torNotification)
        assertEquals(context.getString(R.string.live_monitor_notif_tor_title), shadowNotif.contentTitle.toString())
        assertTrue(shadowNotif.contentText.toString().contains("Tor Connected (14ms)"))
        assertEquals(2, torNotification.actions.size)
        assertEquals(context.getString(R.string.live_monitor_stop_action), torNotification.actions[0].title.toString())
        assertEquals(context.getString(R.string.live_monitor_new_circuit_action), torNotification.actions[1].title.toString())

        val surfaceNotification = service.buildNotification(
            stats = com.example.browser.SessionGuardStats(elapsedMinutes = 3L),
            config = com.example.data.SecurityConfig(browserMode = BrowserMode.SURFACE),
            syncStatus = com.example.browser.SessionMonitorSyncStatus.Disabled,
            torState = com.example.data.TorProxyState.Inactive,
        )
        assertEquals(1, surfaceNotification.actions.size)
        controller.destroy()
    }

    @Test
    fun `ad blocker detects ad networks path rules custom domains and respects site whitelist`() {
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://propellerads.com/tag.min.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://mc.yandex.ru/metrika/tag.js"))
        assertTrue(TrackerAndUrlEngine.isAdOrTrackerRequest("https://www.tiktok.com/i18n/pixel/events.js"))
        assertTrue(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://cdn.thirdparty.net/pagead/banner.js",
                pageUrl = "https://news.example.org/article",
            ),
        )
        assertTrue(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://annoying-promo.example.net/banner.png",
                pageUrl = "https://news.example.org/article",
                customBlockedDomains = setOf("annoying-promo.example.net"),
            ),
        )
        assertFalse(
            TrackerAndUrlEngine.isAdOrTrackerRequest(
                url = "https://doubleclick.net/pixel.gif",
                pageUrl = "https://trusted.example.org/home",
                whitelistHosts = setOf("trusted.example.org"),
            ),
        )

        assertTrue(TrackerAndUrlEngine.isPopupAdUrl("https://popads.net/pop.js"))
        assertTrue(TrackerAndUrlEngine.isPopupAdUrl("https://promo.example.com/popunder?id=1"))
        assertFalse(TrackerAndUrlEngine.isPopupAdUrl("https://en.wikipedia.org/wiki/Tor_(network)"))

        assertEquals(
            "application/javascript",
            TrackerAndUrlEngine.emptyBlockedResponseForUrl("https://doubleclick.net/ads.js").mimeType,
        )
    }

    @Test
    fun `privacy scripts generate cosmetic adblock css and popup guard when enabled`() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "TESTSEED",
            enableWebRtcGuard = false,
            enableFingerprintRandomization = false,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
        )
        assertTrue(script.contains("os-cosmetic-adblock"))
        assertTrue(script.contains("adsbygoogle"))
        assertTrue(script.contains("__osPopupGuardInstalled"))
    }

    @Test
    fun `privacy preferences repository toggles javascript webrtc https and applies defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.PrivacyPreferencesRepository(context)

        repo.setNoScript(true)
        repo.setWebRtcLeakProtection(false)
        repo.setHttpsEverywhere(false)
        repo.addTrustedJsHost("example.org")

        var current = repo.securityConfig.first()
        assertTrue(current.noScriptEnabled)
        assertFalse(current.webRtcLeakProtectionEnabled)
        assertFalse(current.httpsEverywhereEnabled)
        assertTrue(current.trustedJsHosts.contains("example.org"))

        repo.removeTrustedJsHost("example.org")
        current = repo.securityConfig.first()
        assertFalse(current.trustedJsHosts.contains("example.org"))

        repo.applyPrivacyDefaults(maximumPrivacy = true)
        current = repo.securityConfig.first()
        assertTrue(current.noScriptEnabled)
        assertTrue(current.webRtcLeakProtectionEnabled)
        assertTrue(current.httpsEverywhereEnabled)
        assertEquals(CircuitPrivacyLevel.STRICT, current.circuitPrivacyLevel)

        repo.applyPrivacyDefaults(maximumPrivacy = false)
        current = repo.securityConfig.first()
        assertFalse(current.noScriptEnabled)
        assertTrue(current.webRtcLeakProtectionEnabled)
        assertTrue(current.httpsEverywhereEnabled)
        assertEquals(CircuitPrivacyLevel.STANDARD, current.circuitPrivacyLevel)
    }
}
