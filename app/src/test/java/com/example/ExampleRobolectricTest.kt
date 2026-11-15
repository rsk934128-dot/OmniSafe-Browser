package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.PrivacyScripts
import com.example.browser.TrackerAndUrlEngine
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
