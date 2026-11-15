package com.example.browser

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceResponse
import com.example.data.ABOUT_START_URL
import com.example.data.BrowserMode
import com.example.data.SearchEngine
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TrackerAndUrlEngine {

    private val blockedTrackerDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "connect.facebook.net",
        "pixel.facebook.com",
        "an.facebook.com",
        "ads-twitter.com",
        "analytics.twitter.com",
        "scorecardresearch.com",
        "quantserve.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "moatads.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "adnxs.com",
        "amazon-adsystem.com",
        "clarity.ms",
        "bing.com/bat",
        "yandex.ru/metrika",
        "tiktok.com/i18n/pixel",
    )

    fun isTrackerRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return blockedTrackerDomains.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }
    }

    fun emptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked by OnionShield",
            mapOf("X-OnionShield-Blocked" to "true"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    fun isOnionUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        return host?.endsWith(".onion") == true || url.contains(".onion", ignoreCase = true)
    }

    fun isLocalhostOrLoopback(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    fun resolveInputToUrl(
        rawInput: String,
        mode: BrowserMode,
        httpsEverywhere: Boolean,
        searchEngine: SearchEngine,
    ): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ABOUT_START_URL
        if (trimmed.equals(ABOUT_START_URL, ignoreCase = true)) return ABOUT_START_URL

        // Reject unsafe schemes explicitly
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("data:")) {
            return ABOUT_START_URL
        }

        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        val looksLikeDomain = !trimmed.contains(' ') && (
            trimmed.contains('.') ||
                lower.startsWith("localhost") ||
                lower.startsWith("127.0.0.1")
            )

        val candidate = when {
            hasScheme -> trimmed
            looksLikeDomain -> {
                val isOnion = lower.endsWith(".onion") || lower.contains(".onion/")
                val isLoopback = lower.startsWith("localhost") || lower.startsWith("127.0.0.1")
                if (isOnion || isLoopback) "http://$trimmed" else "https://$trimmed"
            }
            else -> {
                val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
                val template = if (mode == BrowserMode.TOR_ONION) {
                    searchEngine.onionQueryTemplate
                } else {
                    searchEngine.surfaceQueryTemplate
                }
                return template.replace("%s", encoded)
            }
        }

        return applyHttpsUpgradeIfNeeded(candidate, httpsEverywhere)
    }

    fun applyHttpsUpgradeIfNeeded(url: String, httpsEverywhere: Boolean): String {
        if (!httpsEverywhere) return url
        if (!url.startsWith("http://", ignoreCase = true)) return url
        if (isOnionUrl(url) || isLocalhostOrLoopback(url)) return url
        return "https://" + url.substring(7)
    }

    fun normalizeHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    fun extractIntentFallbackUrl(intentUri: String): String? {
        if (!intentUri.startsWith("intent:", ignoreCase = true)) return null
        val parsedFallback = runCatching {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            intent.getStringExtra("browser_fallback_url")
        }.getOrNull()
        val candidate = parsedFallback ?: run {
            val marker = "S.browser_fallback_url="
            val idx = intentUri.indexOf(marker)
            if (idx >= 0) {
                val rawVal = intentUri.substring(idx + marker.length).substringBefore(';')
                runCatching { java.net.URLDecoder.decode(rawVal, "UTF-8") }.getOrNull()
            } else {
                null
            }
        }
        return candidate?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    fun extractFirstHttpUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        val match = regex.find(rawText.trim())?.value
        if (!match.isNullOrBlank()) {
            return match.trimEnd('.', ',', ';', ')', ']')
        }
        return null
    }

    fun isApkAsset(url: String?, fileName: String?, mimeType: String?): Boolean {
        if (mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)) {
            return true
        }
        if (!fileName.isNullOrBlank() && fileName.trim().lowercase().endsWith(".apk")) {
            return true
        }
        val path = runCatching { Uri.parse(url.orEmpty()).path?.lowercase() }.getOrNull().orEmpty()
        return path.endsWith(".apk")
    }
}
