package com.example

import com.example.browser.DetectedTradeButton
import com.example.browser.DownloadFileHelper
import com.example.browser.PrivacyScripts
import com.example.browser.TradeDecision
import com.example.browser.TradeDomSnapshot
import com.example.browser.TradeLearningEngine
import com.example.browser.TradeLearningProfile
import com.example.browser.TradeOutcome
import com.example.browser.TradePilotHelper
import com.example.browser.TradeSignalDecision
import com.example.browser.WebsiteLogoCache
import com.example.data.DownloadEntity
import com.example.data.repository.GeminiPersonalRestClient
import com.example.data.repository.GeminiShieldRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun extractNormalizedHost_stripsWwwAndIgnoresInternalSchemes() {
        assertEquals("youtube.com", WebsiteLogoCache.extractNormalizedHost("https://www.YouTube.com/watch?v=123"))
        assertEquals("m.youtube.com", WebsiteLogoCache.extractNormalizedHost("https://m.youtube.com/feed"))
        assertNull(WebsiteLogoCache.extractNormalizedHost("about:start"))
        assertNull(WebsiteLogoCache.extractNormalizedHost("javascript:void(0)"))
        assertNull(WebsiteLogoCache.extractNormalizedHost(""))
    }

    @Test
    fun extractRootHost_handlesSubdomainsAndCountryTlds() {
        assertEquals("wikipedia.org", WebsiteLogoCache.extractRootHost("en.m.wikipedia.org"))
        assertEquals("youtube.com", WebsiteLogoCache.extractRootHost("m.youtube.com"))
        assertEquals("bbc.co.uk", WebsiteLogoCache.extractRootHost("news.bbc.co.uk"))
        assertEquals("github.com", WebsiteLogoCache.extractRootHost("github.com"))
    }

    @Test
    fun buildFaviconCandidateUrls_blocksOnionLocalhostAndTorModeFetch() {
        val onionUrl = "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"
        assertTrue(WebsiteLogoCache.isOnionHostOrUrl(onionUrl))
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls(onionUrl, allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("https://localhost:8080/app", allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("http://127.0.0.1:9050", allowNetworkFetch = true).isEmpty())
        assertTrue(WebsiteLogoCache.buildFaviconCandidateUrls("https://github.com", allowNetworkFetch = false).isEmpty())
    }

    @Test
    fun buildFaviconCandidateUrls_buildsHighResAndDirectFallbacksForSurfaceUrls() {
        val candidates = WebsiteLogoCache.buildFaviconCandidateUrls(
            url = "https://m.youtube.com/watch?v=abc",
            allowNetworkFetch = true,
        )
        assertTrue(candidates.any { it.contains("google.com/s2/favicons") && it.contains("sz=128") })
        assertTrue(candidates.any { it.contains("icons.duckduckgo.com/ip3/m.youtube.com.ico") })
        assertTrue(candidates.any { it == "https://m.youtube.com/apple-touch-icon.png" })
        assertTrue(candidates.any { it == "https://m.youtube.com/favicon.ico" })
        assertTrue(candidates.any { it.contains("domain=youtube.com") })
    }

    @Test
    fun parseHtmlIconUrls_prioritizesAppleTouchAndPngAndSkipsOnionOrData() {
        val html = """
            <html><head>
              <link rel="shortcut icon" href="/favicon.ico">
              <link rel="icon" type="image/png" href="/assets/logo-192.png">
              <link rel="apple-touch-icon" href="https://cdn.example.com/apple-icon.png">
              <link rel="icon" href="data:image/png;base64,AAA">
              <link rel="icon" href="http://hidden123456.onion/icon.png">
            </head></html>
        """.trimIndent()
        val parsed = WebsiteLogoCache.parseHtmlIconUrls(html, "https://example.com/home")
        assertEquals(
            listOf(
                "https://cdn.example.com/apple-icon.png",
                "https://example.com/assets/logo-192.png",
                "https://example.com/favicon.ico",
            ),
            parsed,
        )
        assertFalse(parsed.any { it.contains(".onion") || it.startsWith("data:") })
    }

    @Test
    fun buildDiskFileName_isDeterministicPngSha256() {
        val a = WebsiteLogoCache.buildDiskFileName("youtube.com")
        val b = WebsiteLogoCache.buildDiskFileName("youtube.com")
        val c = WebsiteLogoCache.buildDiskFileName("github.com")
        assertEquals(a, b)
        assertTrue(a.endsWith(".png"))
        assertTrue(a != c)
    }

    @Test
    fun privacyScripts_protectYouTubeShortsFromConcurrentResumeAndGuardHooksOnce() {
        val script = PrivacyScripts.buildHardeningScript(
            seed = "SHORTS99",
            enableWebRtcGuard = true,
            enableFingerprintRandomization = true,
            enableCosmeticAdBlock = true,
            enablePopupGuard = true,
            enableBackgroundMediaPlayback = true,
        )
        assertTrue(script.contains("__osWebRtcGuardInstalled"))
        assertTrue(script.contains("__osFingerprintGuardInstalled"))
        assertTrue(script.contains("OnionShield Virtual GPU (SHORTS99)"))
        assertTrue(script.contains("WebGL2RenderingContext"))
        assertTrue(script.contains("isShortsRoute"))
        assertTrue(script.contains("isElementInViewport"))
        assertTrue(script.contains("visibleMediaArea"))
        assertTrue(script.contains("__osSetAppBackground"))
        assertTrue(script.contains("ytm-reel-player-overlay-renderer"))
        assertTrue(script.contains("if (/^\\/shorts(\\/|$)/i.test(location.pathname || ''))"))

        val blobScript = PrivacyScripts.buildBlobDownloadHelperScript()
        assertTrue(blobScript.contains("__osBlobDownloadHelperInstalled"))
        assertTrue(blobScript.contains("__osPeekBlobHint"))
        assertTrue(blobScript.contains("__osFetchBlobForDownload"))
        assertTrue(blobScript.contains("OnionShieldDownloadBridge"))

        val webGlGuardScript = PrivacyScripts.buildWebGlMipmapGuardScript()
        assertTrue(webGlGuardScript.contains("__osWebGlMipmapGuardInstalled"))
        assertTrue(webGlGuardScript.contains("generateMipmap"))
        assertTrue(webGlGuardScript.contains("texStorage2D"))
        assertTrue(webGlGuardScript.contains("UNSUPPORTED_MIPMAP_FORMATS"))
    }

    @Test
    fun downloadFileHelper_replacesUuidBlobNamesAndSanitizesDispositions() {
        assertTrue(DownloadFileHelper.isUuidLikeName("760cb5e9-3e14-43c3-a130-fd1234567890"))
        assertTrue(DownloadFileHelper.isUuidLikeName("760cb5e9-3e14-43c3-a130-fd1234567890.bin"))
        assertFalse(DownloadFileHelper.isUuidLikeName("NetPulse-WiFi-v1.0.1-debug.apk"))

        assertEquals(
            "download_export.zip",
            DownloadFileHelper.resolveDownloadFileName(
                rawGuessedName = "760cb5e9-3e14-43c3-a130-fd1234567890.bin",
                url = "blob:https://example.com/760cb5e9-3e14-43c3-a130-fd1234567890",
                contentDisposition = null,
                mimeType = "application/zip",
            ),
        )
        assertEquals(
            "my app build.apk",
            DownloadFileHelper.resolveDownloadFileName(
                rawGuessedName = "download.bin",
                url = "https://github.com/org/repo/releases/download/v1/app.apk",
                contentDisposition = "attachment; filename*=UTF-8''my%20app%20build.apk",
                mimeType = "application/vnd.android.package-archive",
            ),
        )
        assertEquals(
            "report_2026_.pdf",
            DownloadFileHelper.sanitizeFileName("report:2026?.pdf"),
        )
    }

    @Test
    fun downloadFileHelper_formatsSizesAndExplainsFailuresClearly() {
        assertEquals("", DownloadFileHelper.formatByteSize(0L))
        assertEquals("512 B", DownloadFileHelper.formatByteSize(512L))
        assertEquals("250 KB", DownloadFileHelper.formatByteSize(250 * 1024L))
        assertEquals("14.5 MB", DownloadFileHelper.formatByteSize((14.5 * 1024 * 1024).toLong()))

        val uuidFailed = DownloadEntity(
            id = 1L,
            fileName = "760cb5e9-3e14-43c3-a130-fd1234567890",
            url = "blob:https://example.com/760cb5e9-3e14-43c3-a130-fd1234567890",
            mimeType = "application/octet-stream",
            status = "FAILED",
        )
        val blobReason = DownloadFileHelper.describeFailureReason(uuidFailed, null)
        assertTrue(blobReason!!.contains("blob", ignoreCase = true))

        val completed = uuidFailed.copy(status = "COMPLETED", fileName = "app.apk")
        assertNull(DownloadFileHelper.describeFailureReason(completed, null))
    }

    @Test
    fun tradePilotHelper_parsesDomButtonsAndResolvesClickTargetsSafely() {
        val rawEvalJson = "\"[{\\\"action\\\":\\\"SELL\\\",\\\"label\\\":\\\"SELL 86%\\\",\\\"normX\\\":0.28,\\\"normY\\\":0.88,\\\"widthRatio\\\":0.42,\\\"heightRatio\\\":0.09,\\\"enabled\\\":true},{\\\"action\\\":\\\"BUY\\\",\\\"label\\\":\\\"BUY 86%\\\",\\\"normX\\\":0.72,\\\"normY\\\":0.88,\\\"widthRatio\\\":0.42,\\\"heightRatio\\\":0.09,\\\"enabled\\\":true}]\""
        val parsed = TradePilotHelper.parseDetectedButtonsJson(rawEvalJson)
        assertEquals(2, parsed.size)
        assertEquals("SELL", parsed[0].action)
        assertEquals("BUY", parsed[1].action)

        val buyTarget = TradePilotHelper.resolveClickTarget(
            decision = TradeDecision.BUY,
            aiNormX = 0.65f,
            aiNormY = 0.80f,
            domButtons = parsed,
        )
        assertNotNull(buyTarget)
        assertEquals("DOM", buyTarget!!.source)
        assertEquals(0.72f, buyTarget.normX, 0.001f)
        assertEquals(0.88f, buyTarget.normY, 0.001f)

        val canvasFallbackTarget = TradePilotHelper.resolveClickTarget(
            decision = TradeDecision.SELL,
            aiNormX = 0.25f,
            aiNormY = 0.87f,
            domButtons = emptyList(),
        )
        assertNotNull(canvasFallbackTarget)
        assertEquals("VISION", canvasFallbackTarget!!.source)
        assertEquals(0.25f, canvasFallbackTarget.normX, 0.001f)

        assertNull(
            TradePilotHelper.resolveClickTarget(
                decision = TradeDecision.HOLD,
                aiNormX = 0.5f,
                aiNormY = 0.5f,
                domButtons = parsed,
            ),
        )
    }

    @Test
    fun tradePilot_parsesVisionDecisionAndEnforcesConfidenceThreshold() {
        val sampleJson = """
            {
              "decision": "BUY",
              "confidence": 84,
              "assetName": "EUR / USD (OTC)",
              "accountMode": "DEMO",
              "marketBias": "Strong Uptrend",
              "reasoning": "Higher lows breaking above resistance with upward slope.",
              "targetLabel": "BUY 86%",
              "normalizedX": 0.72,
              "normalizedY": 0.89
            }
        """.trimIndent()
        val parsed = GeminiShieldRepository.parseTradeDecisionJson(sampleJson)
        assertEquals(TradeDecision.BUY, parsed.decision)
        assertEquals(84, parsed.confidence)
        assertEquals("EUR / USD (OTC)", parsed.assetName)
        assertEquals("DEMO", parsed.accountMode)
        assertTrue(TradePilotHelper.shouldAutoExecuteClick(parsed, minConfidencePercent = 75))
        assertFalse(TradePilotHelper.shouldAutoExecuteClick(parsed, minConfidencePercent = 90))

        val holdDecision = GeminiShieldRepository.parseTradeDecisionJson(
            """{"decision":"HOLD","confidence":95,"normalizedX":1.4,"normalizedY":-0.2}""",
        )
        assertEquals(TradeDecision.HOLD, holdDecision.decision)
        assertEquals(1.0f, holdDecision.normalizedX, 0.001f)
        assertEquals(0.0f, holdDecision.normalizedY, 0.001f)
        assertFalse(TradePilotHelper.shouldAutoExecuteClick(holdDecision, minConfidencePercent = 50))

        val prompt = GeminiShieldRepository.buildTradeAnalysisPrompt(
            pageUrl = "https://m.expertoption.com",
            domControlsSummary = TradePilotHelper.formatControlsSummary(emptyList()),
            hasTwoFrames = true,
        )
        assertTrue(prompt.contains("https://m.expertoption.com"))
        assertTrue(prompt.contains("BUY or SELL"))
        assertTrue(prompt.contains("FRAME A"))
        assertTrue(prompt.contains("FRAME B"))
        assertTrue(GeminiShieldRepository.TRADE_ANALYST_SYSTEM_INSTRUCTION.contains("Never default to HOLD"))
        assertNotNull(GeminiShieldRepository.TRADE_VISION_SCHEMA)
    }

    @Test
    fun geminiRepository_detectsAppCheckErrorsDistinctFromTransientLoadErrors() {
        assertTrue(GeminiShieldRepository.isAppCheckOrAttestationError(RuntimeException("HTTP 403 App Check token is invalid")))
        assertTrue(GeminiShieldRepository.isAppCheckOrAttestationError(RuntimeException("PERMISSION_DENIED: missing attestation")))
        assertTrue(GeminiShieldRepository.isAppCheckOrAttestationError(IllegalStateException("Play Integrity check failed")))
        assertFalse(GeminiShieldRepository.isAppCheckOrAttestationError(RuntimeException("Socket timeout after 15000ms")))
        assertFalse(GeminiShieldRepository.isAppCheckOrAttestationError(RuntimeException("HTTP 503 Service Unavailable: high demand")))
        assertFalse(GeminiShieldRepository.isTransientHighDemandError(RuntimeException("HTTP 403 App Check token is invalid")))
        assertTrue(GeminiShieldRepository.isTransientHighDemandError(RuntimeException("HTTP 503 Service Unavailable")))
        assertTrue(GeminiShieldRepository.isTransientHighDemandError(RuntimeException("The service is currently unavailable.")))
        assertTrue(GeminiShieldRepository.isTransientHighDemandError(java.net.SocketTimeoutException("read timed out")))
    }

    @Test
    fun geminiPersonalRestClient_picksBestActiveFlashModelAndStripsJsonFences() {
        val sampleModelsJson = """
            {
              "models": [
                {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-3.5-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-flash-latest", "supportedGenerationMethods": ["generateContent"]}
              ]
            }
        """.trimIndent()
        val picked = GeminiPersonalRestClient.pickBestFlashModelName(sampleModelsJson)
        assertEquals("gemini-3.8-flash", picked)
        assertNull(GeminiPersonalRestClient.pickBestFlashModelName(""))

        val fenced = """
            ```json
            {"decision": "SELL", "confidence": 82}
            ```
        """.trimIndent()
        assertEquals(
            """{"decision": "SELL", "confidence": 82}""",
            GeminiPersonalRestClient.stripJsonCodeFences(fenced),
        )
    }

    @Test
    fun tradeLearningEngine_tracksWinsLossesAdaptsThresholdsAndSerializesCleanly() {
        val snap = TradeLearningEngine.parseDomSnapshot(
            """{"balanceText":"৳1,107.50","timerText":"00:00:30","outcomeHint":"WIN","profitDelta":"107.5"}""",
        )
        assertEquals(1107.50, snap.balance ?: 0.0, 0.01)
        assertEquals(30, snap.expirySeconds)
        assertEquals("WIN", snap.outcomeHint)
        assertEquals(107.5, snap.profitDelta ?: 0.0, 0.01)

        val domWin = TradeLearningEngine.evaluateOutcomeFromDom(1000.0, snap)
        assertNotNull(domWin)
        assertEquals(TradeOutcome.WIN, domWin?.first)

        val domLoss = TradeLearningEngine.evaluateOutcomeFromDom(
            1000.0,
            TradeDomSnapshot(balance = 875.0),
        )
        assertEquals(TradeOutcome.LOSS, domLoss?.first)
        assertEquals(-125.0, domLoss?.second ?: 0.0, 0.01)

        val neutralDom = TradeLearningEngine.evaluateOutcomeFromDom(
            1000.0,
            TradeDomSnapshot(balance = 1000.0),
        )
        assertNull(neutralDom)

        var profile = TradeLearningProfile()
        profile = TradeLearningEngine.recordOutcome(
            profile = profile,
            assetName = "EUR / USD (OTC)",
            accountMode = "DEMO",
            decision = TradeDecision.BUY,
            confidence = 62,
            marketBias = "Sideways",
            reasoning = "Choppy mid-range entry",
            outcome = TradeOutcome.LOSS,
            profitDelta = -50.0,
            aiReflectionLesson = "Avoid mid-range sideways BUY without clear breakout confirmation.",
            timestampMs = 1_000L,
            recordId = "trade_1",
        )
        assertEquals(0, profile.totalWins)
        assertEquals(1, profile.totalLosses)
        assertEquals(1, profile.buyLosses)
        assertEquals(1, profile.currentLossStreak)
        assertEquals(62, profile.adaptedMinConfidence)
        assertEquals(10, profile.adaptedCooldownSec)

        profile = TradeLearningEngine.recordOutcome(
            profile = profile,
            assetName = "EUR / USD (OTC)",
            accountMode = "DEMO",
            decision = TradeDecision.SELL,
            confidence = 78,
            marketBias = "Resistance Rejection",
            reasoning = "Lower high rejection off ceiling",
            outcome = TradeOutcome.WIN,
            profitDelta = 93.0,
            timestampMs = 2_000L,
            recordId = "trade_2",
        )
        profile = TradeLearningEngine.recordOutcome(
            profile = profile,
            assetName = "EUR / USD (OTC)",
            accountMode = "DEMO",
            decision = TradeDecision.SELL,
            confidence = 82,
            marketBias = "Resistance Rejection",
            reasoning = "Follow-through drop in Frame B",
            outcome = TradeOutcome.WIN,
            profitDelta = 93.0,
            timestampMs = 3_000L,
            recordId = "trade_3",
        )
        assertEquals(2, profile.totalWins)
        assertEquals(1, profile.totalLosses)
        assertEquals(67, profile.winRatePercent)
        assertEquals(2, profile.currentWinStreak)
        assertEquals(2, profile.bestWinStreak)
        assertEquals(60, profile.adaptedMinConfidence)

        val memory = TradeLearningEngine.buildPromptMemoryContext(profile)
        assertTrue(memory.contains("2 Wins / 1 Losses"))
        assertTrue(memory.contains("sideways"))
        assertTrue(memory.contains("resistance_reject"))
        assertTrue(memory.contains("Avoid mid-range sideways BUY"))

        val promptWithMemory = GeminiShieldRepository.buildTradeAnalysisPrompt(
            pageUrl = "https://m.expertoption.com",
            domControlsSummary = "",
            hasTwoFrames = true,
            learningContext = memory,
        )
        assertTrue(promptWithMemory.contains("Self-Learning Performance Memory"))

        val serialized = TradeLearningEngine.serializeProfile(profile)
        val restored = TradeLearningEngine.deserializeProfile(serialized)
        assertEquals(profile.totalWins, restored.totalWins)
        assertEquals(profile.totalLosses, restored.totalLosses)
        assertEquals(profile.sellWins, restored.sellWins)
        assertEquals(profile.buyLosses, restored.buyLosses)
        assertEquals(profile.adaptedMinConfidence, restored.adaptedMinConfidence)
        assertEquals(profile.learnedLessons, restored.learnedLessons)
        assertEquals(profile.recentTrades.size, restored.recentTrades.size)
        assertEquals(profile.patternStats["resistance_reject"]?.wins, restored.patternStats["resistance_reject"]?.wins)

        val verified = GeminiShieldRepository.parseTradeOutcomeVerificationJson(
            """{"outcome":"LOSS","profitDeltaText":"-৳125.00","lessonLearned":"Wait for candle close above support before BUY."}""",
        )
        assertEquals(TradeOutcome.LOSS, verified.outcome)
        assertEquals(125.0, kotlin.math.abs(verified.profitDelta ?: 0.0), 0.01)
        assertTrue(verified.lessonLearned.contains("Wait for candle close"))

        // Verify Profit/Loss time-series curve generation
        val emptySeries = TradeLearningEngine.buildProfitLossSeries(TradeLearningProfile())
        assertTrue(emptySeries.points.isEmpty())

        val currencySeries = TradeLearningEngine.buildProfitLossSeries(profile)
        assertTrue(currencySeries.isCurrencySeries)
        assertEquals(4, currencySeries.points.size) // baseline 0 + 3 trades
        assertEquals(0.0, currencySeries.points[0].cumulativeValue, 0.01)
        assertEquals(-50.0, currencySeries.points[1].cumulativeValue, 0.01)
        assertEquals(43.0, currencySeries.points[2].cumulativeValue, 0.01)
        assertEquals(136.0, currencySeries.points[3].cumulativeValue, 0.01)
        assertEquals(136.0, currencySeries.netValue, 0.01)
        assertEquals(136.0, currencySeries.peakValue, 0.01)
        assertEquals(50.0, currencySeries.maxDrawdown, 0.01)

        val manualProfile = TradeLearningEngine.recordOutcome(
            profile = profile,
            assetName = "GBP / USD",
            accountMode = "DEMO",
            decision = TradeDecision.BUY,
            confidence = 72,
            marketBias = "Pullback Bounce",
            reasoning = "Manual loss tag",
            outcome = TradeOutcome.LOSS,
            profitDelta = null,
            timestampMs = 4_000L,
            recordId = "trade_4",
        )
        val unitSeries = TradeLearningEngine.buildProfitLossSeries(manualProfile)
        assertFalse(unitSeries.isCurrencySeries)
        assertEquals(5, unitSeries.points.size)
        assertEquals(-1.0, unitSeries.points[1].cumulativeValue, 0.01)
        assertEquals(0.0, unitSeries.points[2].cumulativeValue, 0.01)
        assertEquals(1.0, unitSeries.points[3].cumulativeValue, 0.01)
        assertEquals(0.0, unitSeries.points[4].cumulativeValue, 0.01)
        assertEquals(1.0, unitSeries.peakValue, 0.01)
        assertEquals(1.0, unitSeries.maxDrawdown, 0.01)

        // Verify CSV export content, ordering, and escaping
        val emptyCsv = TradeLearningEngine.exportTradesToCsv(TradeLearningProfile())
        assertTrue(emptyCsv.startsWith("Trade #,Timestamp,Asset,Account Mode,Decision,Confidence %,Market Bias,Outcome,Profit/Loss,Cumulative P/L"))
        assertEquals(1, emptyCsv.trim().lines().size)

        val csv = TradeLearningEngine.exportTradesToCsv(profile)
        val csvLines = csv.trim().lines()
        assertEquals(4, csvLines.size) // header + 3 trades in chronological order
        assertTrue(csvLines[1].startsWith("1,"))
        assertTrue(csvLines[1].contains("EUR / USD (OTC),DEMO,BUY,62,Sideways,LOSS,-50.00,-50.00"))
        assertTrue(csvLines[2].startsWith("2,"))
        assertTrue(csvLines[2].contains(",SELL,78,Resistance Rejection,WIN,93.00,43.00"))
        assertTrue(csvLines[3].startsWith("3,"))
        assertTrue(csvLines[3].contains(",SELL,82,Resistance Rejection,WIN,93.00,136.00"))

        val trickyProfile = TradeLearningEngine.recordOutcome(
            profile = TradeLearningProfile(),
            assetName = "BTC, \"USD\"",
            accountMode = "REAL",
            decision = TradeDecision.BUY,
            confidence = 85,
            marketBias = "Breakout",
            reasoning = "Clean breakout, then\nretest",
            outcome = TradeOutcome.WIN,
            profitDelta = 120.5,
            timestampMs = 5_000L,
            recordId = "tricky_1",
        )
        val trickyCsv = TradeLearningEngine.exportTradesToCsv(trickyProfile)
        assertTrue(trickyCsv.contains("\"BTC, \"\"USD\"\"\""))
        assertTrue(trickyCsv.contains("\"Clean breakout, then retest\""))
        assertTrue(TradeLearningEngine.buildCsvExportFileName(0L).endsWith(".csv"))
    }
}
