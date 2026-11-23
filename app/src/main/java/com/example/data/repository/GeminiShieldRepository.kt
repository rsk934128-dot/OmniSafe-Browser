package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.browser.PendingTradeSnapshot
import com.example.browser.TradeDecision
import com.example.browser.TradeLearningEngine
import com.example.browser.TradeOutcome
import com.example.browser.TradeOutcomeVerification
import com.example.browser.TradeSignalDecision
import com.example.security.UserGeminiKeyStore
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

enum class ChatRole {
    USER,
    MODEL,
}

data class GeminiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val promptText: String = text,
    val pageUrl: String? = null,
    val audit: PagePrivacyAudit? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PageAiContext(
    val url: String,
    val title: String,
    val browserModeLabel: String,
    val trackersBlockedCount: Int,
    val scriptsBlockedCount: Int,
    val pageTextSnippet: String,
)

data class PagePrivacyAudit(
    val riskLevel: String,
    val summary: String,
    val privacySignals: List<String>,
    val recommendedAction: String,
)

open class GeminiShieldRepository {

    @Volatile
    private var appContext: Context? = null

    private val personalRestClient by lazy { GeminiPersonalRestClient() }

    fun attachAppContext(context: Context) {
        val app = context.applicationContext
        appContext = app
        UserGeminiKeyStore.syncFromPrefs(app)
    }

    private fun resolvePersonalKeyConfig(): Pair<String, Boolean>? {
        val ctx = appContext ?: return null
        val key = UserGeminiKeyStore.getApiKey(ctx) ?: return null
        val prefer = UserGeminiKeyStore.isPersonalKeyPreferred(ctx)
        return key to prefer
    }

    private val chatModel by lazy {
        createChatModel(MODEL_NAME)
    }

    private val fallbackChatModel by lazy {
        createChatModel(FALLBACK_MODEL_NAME)
    }

    private val auditModel by lazy {
        createAuditModel(MODEL_NAME)
    }

    private val fallbackAuditModel by lazy {
        createAuditModel(FALLBACK_MODEL_NAME)
    }

    private val tradeVisionModel by lazy {
        createTradeVisionModel(MODEL_NAME)
    }

    private val fallbackTradeVisionModel by lazy {
        createTradeVisionModel(FALLBACK_MODEL_NAME)
    }

    private val tradeOutcomeVisionModel by lazy {
        createTradeOutcomeVisionModel(MODEL_NAME)
    }

    private val fallbackTradeOutcomeVisionModel by lazy {
        createTradeOutcomeVisionModel(FALLBACK_MODEL_NAME)
    }

    private fun createChatModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            systemInstruction = content {
                text(
                    "You are OnionShield AI, a concise security, privacy, and research assistant " +
                        "built inside the OnionShield privacy browser. Explain web privacy, Tor " +
                        "networking, trackers, phishing signals, and web page summaries accurately and concisely.",
                )
            },
        )

    private fun createAuditModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = PAGE_AUDIT_SCHEMA
            },
        )

    private fun createTradeVisionModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = TRADE_VISION_SCHEMA
            },
            systemInstruction = content {
                text(TRADE_ANALYST_SYSTEM_INSTRUCTION)
            },
        )

    private fun createTradeOutcomeVisionModel(modelName: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = TRADE_OUTCOME_SCHEMA
            },
        )

    open fun sendChatMessageStream(
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): Flow<String> = flow {
        val personalCfg = resolvePersonalKeyConfig()
        if (personalCfg != null && personalCfg.second) {
            val reply = personalRestClient.generateChatReply(
                apiKey = personalCfg.first,
                history = history,
                userPrompt = userPrompt,
                pageContext = pageContext,
            )
            emit(reply)
            return@flow
        }

        val sdkHistory = buildSdkHistory(history)
        val fullPrompt = buildContextualPrompt(userPrompt, pageContext)

        // Try: (1) primary stream (15s), (2) lite stream (18s), (3) lite unary HTTP fallback (18s)
        val plan = listOf(
            Triple(chatModel, true, PRIMARY_ATTEMPT_TIMEOUT_MS),
            Triple(fallbackChatModel, true, FALLBACK_ATTEMPT_TIMEOUT_MS),
            Triple(fallbackChatModel, false, FALLBACK_ATTEMPT_TIMEOUT_MS),
        )
        var lastError: Exception? = null

        for ((attemptIdx, step) in plan.withIndex()) {
            val (candidate, useStream, attemptTimeoutMs) = step
            var emittedAny = false
            try {
                withTimeout(attemptTimeoutMs) {
                    val chat = candidate.startChat(history = sdkHistory)
                    if (useStream) {
                        chat.sendMessageStream(fullPrompt).collect { chunk ->
                            val piece = chunk.text
                            if (!piece.isNullOrEmpty()) {
                                emittedAny = true
                                emit(piece)
                            }
                        }
                    } else {
                        val unary = chat.sendMessage(fullPrompt)
                        val text = unary.text
                        if (!text.isNullOrEmpty()) {
                            emittedAny = true
                            emit(text)
                        }
                    }
                }
                if (emittedAny) {
                    return@flow
                }
                if (attemptIdx < plan.lastIndex) {
                    delay(FALLBACK_DELAY_MS)
                }
            } catch (e: TimeoutCancellationException) {
                // Propagate outer coroutine cancellation, otherwise treat per-attempt hang as fallback signal.
                if (!currentCoroutineContext().isActive) throw e
                if (emittedAny) return@flow
                lastError = e
                if (attemptIdx == plan.lastIndex) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (emittedAny) return@flow
                if (personalCfg != null && isAppCheckOrAttestationError(e)) {
                    val reply = personalRestClient.generateChatReply(
                        apiKey = personalCfg.first,
                        history = history,
                        userPrompt = userPrompt,
                        pageContext = pageContext,
                    )
                    emit(reply)
                    return@flow
                }
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini returned an empty response.")
    }

    fun streamPrompt(prompt: String, pageContext: PageAiContext?): Flow<String> =
        sendChatMessageStream(history = emptyList(), userPrompt = prompt, pageContext = pageContext)

    open suspend fun runStructuredPrivacyAudit(pageContext: PageAiContext): PagePrivacyAudit {
        val personalCfg = resolvePersonalKeyConfig()
        if (personalCfg != null && personalCfg.second) {
            return personalRestClient.runStructuredPrivacyAudit(
                apiKey = personalCfg.first,
                pageContext = pageContext,
            )
        }

        val prompt = buildString {
            appendLine("Analyze this web page context for user privacy, phishing/scam signals, and security posture:")
            appendLine("URL: ${pageContext.url}")
            appendLine("Title: ${pageContext.title}")
            appendLine("Browser Mode: ${pageContext.browserModeLabel}")
            appendLine("Trackers Blocked: ${pageContext.trackersBlockedCount}")
            appendLine("Scripts Blocked: ${pageContext.scriptsBlockedCount}")
            if (pageContext.pageTextSnippet.isNotBlank()) {
                appendLine("Visible Content Excerpt:")
                appendLine(pageContext.pageTextSnippet.take(3500))
            }
        }
        val plan = listOf(
            auditModel to PRIMARY_ATTEMPT_TIMEOUT_MS,
            fallbackAuditModel to 25_000L,
        )
        var lastError: Exception? = null

        for ((attemptIdx, step) in plan.withIndex()) {
            val (candidate, attemptTimeoutMs) = step
            try {
                val jsonText = withTimeout(attemptTimeoutMs) {
                    candidate.generateContent(prompt).text
                }
                if (!jsonText.isNullOrBlank()) {
                    return parsePagePrivacyAuditJson(jsonText)
                }
                if (attemptIdx < plan.lastIndex) {
                    delay(FALLBACK_DELAY_MS)
                }
            } catch (e: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                lastError = e
                if (attemptIdx == plan.lastIndex) {
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (personalCfg != null && isAppCheckOrAttestationError(e)) {
                    return personalRestClient.runStructuredPrivacyAudit(
                        apiKey = personalCfg.first,
                        pageContext = pageContext,
                    )
                }
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini returned an empty audit response.")
    }

    open suspend fun analyzeScreenForTradeSignal(
        screenshot: Bitmap,
        pageUrl: String,
        domControlsSummary: String,
        previousScreenshot: Bitmap? = null,
        learningContext: String = "",
    ): TradeSignalDecision {
        val personalCfg = resolvePersonalKeyConfig()
        if (personalCfg != null && personalCfg.second) {
            return personalRestClient.analyzeScreenForTradeSignal(
                apiKey = personalCfg.first,
                screenshot = screenshot,
                pageUrl = pageUrl,
                domControlsSummary = domControlsSummary,
                previousScreenshot = previousScreenshot,
                learningContext = learningContext,
            )
        }

        val primaryScaled = downscaleBitmapIfNeeded(screenshot, MAX_LIVE_FRAME_PX)
        val priorScaled = previousScreenshot?.let { downscaleBitmapIfNeeded(it, MAX_PRIOR_FRAME_PX) }

        try {
            val dualPrompt = buildTradeAnalysisPrompt(
                pageUrl = pageUrl,
                domControlsSummary = domControlsSummary,
                hasTwoFrames = priorScaled != null,
                learningContext = learningContext,
            )
            val dualInput = content {
                if (priorScaled != null) {
                    text("FRAME A (~0.7s earlier):")
                    image(priorScaled)
                    text("FRAME B (CURRENT LIVE SCREEN RIGHT NOW):")
                }
                image(primaryScaled)
                text(dualPrompt)
            }
            val singlePrompt = buildTradeAnalysisPrompt(
                pageUrl = pageUrl,
                domControlsSummary = domControlsSummary,
                hasTwoFrames = false,
                learningContext = learningContext,
            )
            val singleInput = content {
                image(primaryScaled)
                text(singlePrompt)
            }

            // Tiered plan: 2-frame primary -> 2-frame lite -> 1-frame lite -> 1-frame primary
            val plan = if (priorScaled != null) {
                listOf(
                    Triple(tradeVisionModel, dualInput, 40_000L),
                    Triple(fallbackTradeVisionModel, dualInput, 40_000L),
                    Triple(fallbackTradeVisionModel, singleInput, 35_000L),
                    Triple(tradeVisionModel, singleInput, 40_000L),
                )
            } else {
                listOf(
                    Triple(tradeVisionModel, singleInput, 40_000L),
                    Triple(fallbackTradeVisionModel, singleInput, 40_000L),
                )
            }
            val backoffDelaysMs = longArrayOf(800L, 1_400L, 2_000L)
            var lastError: Exception? = null

            for ((attemptIdx, step) in plan.withIndex()) {
                val (candidate, payload, attemptTimeoutMs) = step
                try {
                    val jsonText = withTimeout(attemptTimeoutMs) {
                        candidate.generateContent(payload).text
                    }
                    if (!jsonText.isNullOrBlank()) {
                        return parseTradeDecisionJson(jsonText)
                    }
                    if (attemptIdx < plan.lastIndex) {
                        delay(backoffDelaysMs.getOrElse(attemptIdx) { 1_200L })
                    }
                } catch (e: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    Log.w(TAG, "Trade vision attempt ${attemptIdx + 1} timed out after ${attemptTimeoutMs}ms")
                    lastError = e
                    if (attemptIdx == plan.lastIndex) {
                        throw e
                    }
                    delay(backoffDelaysMs.getOrElse(attemptIdx) { 1_200L })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Trade vision attempt ${attemptIdx + 1} failed: ${e::class.java.simpleName}: ${e.message}")
                    if (personalCfg != null && isAppCheckOrAttestationError(e)) {
                        return personalRestClient.analyzeScreenForTradeSignal(
                            apiKey = personalCfg.first,
                            screenshot = primaryScaled,
                            pageUrl = pageUrl,
                            domControlsSummary = domControlsSummary,
                            previousScreenshot = priorScaled,
                            learningContext = learningContext,
                        )
                    }
                    if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                        throw e
                    }
                    lastError = e
                    delay(backoffDelaysMs.getOrElse(attemptIdx) { 1_200L })
                }
            }
            throw lastError ?: IllegalStateException("Gemini Vision returned an empty chart analysis response.")
        } finally {
            if (primaryScaled !== screenshot) {
                runCatching { primaryScaled.recycle() }
            }
            if (priorScaled != null && priorScaled !== previousScreenshot) {
                runCatching { priorScaled.recycle() }
            }
        }
    }

    open suspend fun verifyTradeOutcomeWithVision(
        screenshot: Bitmap,
        pending: PendingTradeSnapshot,
    ): TradeOutcomeVerification {
        val personalCfg = resolvePersonalKeyConfig()
        if (personalCfg != null && personalCfg.second) {
            return personalRestClient.verifyTradeOutcomeWithVision(
                apiKey = personalCfg.first,
                screenshot = screenshot,
                pending = pending,
            )
        }

        val scaled = downscaleBitmapIfNeeded(screenshot, MAX_PRIOR_FRAME_PX)
        val prompt = buildTradeOutcomeVerificationPrompt(pending)
        val payload = content {
            image(scaled)
            text(prompt)
        }
        try {
            val candidates = listOf(fallbackTradeOutcomeVisionModel, tradeOutcomeVisionModel)
            for ((idx, model) in candidates.withIndex()) {
                try {
                    val jsonText = withTimeout(30_000L) {
                        model.generateContent(payload).text
                    }
                    if (!jsonText.isNullOrBlank()) {
                        return parseTradeOutcomeVerificationJson(jsonText)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (personalCfg != null && isAppCheckOrAttestationError(e)) {
                        return personalRestClient.verifyTradeOutcomeWithVision(
                            apiKey = personalCfg.first,
                            screenshot = scaled,
                            pending = pending,
                        )
                    }
                    if (idx == candidates.lastIndex) {
                        Log.w(TAG, "Outcome vision check failed: ${e.message}")
                    } else {
                        delay(700L)
                    }
                }
            }
            return TradeOutcomeVerification(outcome = null)
        } finally {
            if (scaled !== screenshot) {
                runCatching { scaled.recycle() }
            }
        }
    }

    companion object {
        private const val TAG = "GeminiShieldRepo"
        const val MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"
        private const val PRIMARY_ATTEMPT_TIMEOUT_MS = 18_000L
        private const val FALLBACK_ATTEMPT_TIMEOUT_MS = 24_000L
        private const val FALLBACK_DELAY_MS = 400L
        private const val MAX_PRIOR_FRAME_PX = 600
        private const val MAX_LIVE_FRAME_PX = 768

        internal fun downscaleBitmapIfNeeded(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide <= maxDimensionPx || maxDimensionPx <= 0) return bitmap
            val scale = maxDimensionPx.toFloat() / maxSide.toFloat()
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }

        const val TRADE_ANALYST_SYSTEM_INSTRUCTION =
            "You are an expert short-term binary options and price-action trading analyst inspecting live chart screenshots. " +
                "Your job is to check the chart carefully and execute a decisive BUY or SELL call whenever a live chart and trading buttons are present. " +
                "Analyze: (1) Frame-to-frame direction of the active price dot/candle (rising vs falling), " +
                "(2) Recent swing structure (higher lows & upward bounce from support -> BUY; lower highs & downward rejection from resistance -> SELL), " +
                "(3) Range/oscillation extremes (bouncing off bottom support -> BUY; stalling at top resistance -> SELL), " +
                "(4) Exact normalized coordinates of the BUY/CALL or SELL/PUT button. " +
                "CRITICAL RULE: Never default to HOLD because a chart is volatile, fast, or ranging. " +
                "Whenever a live chart and BUY/SELL buttons are visible, you MUST commit to BUY or SELL with a realistic confidence between 60 and 94. " +
                "Return HOLD ONLY if no chart or Buy/Sell buttons appear on screen."

        fun isAppCheckOrAttestationError(error: Throwable): Boolean {
            val combinedMsg = generateSequence(error) { it.cause }
                .flatMap { err ->
                    sequenceOf(
                        err::class.java.simpleName.lowercase(),
                        err.message?.lowercase().orEmpty(),
                    )
                }
                .joinToString(" ")
            return combinedMsg.contains("app check") ||
                combinedMsg.contains("appcheck") ||
                combinedMsg.contains("attestation") ||
                combinedMsg.contains("403") ||
                combinedMsg.contains("permission_denied") ||
                combinedMsg.contains("permission denied") ||
                combinedMsg.contains("unauthenticated") ||
                combinedMsg.contains("recaptcha") ||
                combinedMsg.contains("play integrity") ||
                combinedMsg.contains("token is invalid")
        }

        fun isTransientHighDemandError(error: Throwable): Boolean {
            if (error is TimeoutCancellationException || error is java.net.SocketTimeoutException) return true
            if (isAppCheckOrAttestationError(error)) return false
            val className = error::class.java.simpleName.lowercase()
            if (className.contains("serviceunavailable") || className.contains("quotaexceeded")) {
                return true
            }
            val combinedMsg = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.lowercase() }
                .joinToString(" ")
            if (combinedMsg.contains("api key")) {
                return false
            }
            return combinedMsg.contains("high demand") ||
                combinedMsg.contains("spikes in demand") ||
                combinedMsg.contains("overloaded") ||
                combinedMsg.contains("unavailable") ||
                combinedMsg.contains("resource exhausted") ||
                combinedMsg.contains("rate limit") ||
                combinedMsg.contains("too many requests") ||
                combinedMsg.contains("try again later") ||
                combinedMsg.contains("timed out") ||
                combinedMsg.contains("timeout") ||
                combinedMsg.contains("503") ||
                combinedMsg.contains("504") ||
                combinedMsg.contains("502") ||
                combinedMsg.contains("500") ||
                combinedMsg.contains("429")
        }

        val PAGE_AUDIT_SCHEMA = Schema.obj(
            properties = mapOf(
                "riskLevel" to Schema.enumeration(
                    values = listOf("Low", "Medium", "High"),
                    description = "Overall privacy or phishing risk level for the page",
                ),
                "summary" to Schema.string(
                    description = "Concise 2-sentence plain-language overview of what this page does",
                ),
                "privacySignals" to Schema.array(
                    items = Schema.string(),
                    description = "Key privacy, tracker, or authenticity observations",
                ),
                "recommendedAction" to Schema.string(
                    description = "Concrete next step or hardening tip for browsing safely",
                ),
            ),
        )

        val TRADE_VISION_SCHEMA = Schema.obj(
            properties = mapOf(
                "decision" to Schema.enumeration(
                    values = listOf("BUY", "SELL", "HOLD"),
                    description = "Trade action after inspecting chart momentum, price action, and button visibility. Use HOLD if unclear or controls are absent.",
                ),
                "confidence" to Schema.integer(
                    description = "Decision confidence from 0 to 100",
                ),
                "assetName" to Schema.string(
                    description = "Visible asset pair or ticker such as EUR / USD (OTC) or Unknown",
                ),
                "accountMode" to Schema.string(
                    description = "Visible account type on screen: DEMO, REAL, or UNKNOWN",
                ),
                "marketBias" to Schema.string(
                    description = "Short market structure label e.g. Strong Uptrend, Resistance Rejection, Pullback Bounce, Sideways",
                ),
                "reasoning" to Schema.string(
                    description = "Concise 1-2 sentence technical analysis of the visible chart and why BUY, SELL, or HOLD was chosen",
                ),
                "targetLabel" to Schema.string(
                    description = "Exact text on the target button to click such as BUY 86%, SELL 86%, CALL, PUT, or NONE",
                ),
                "normalizedX" to Schema.double(
                    description = "Normalized horizontal center coordinate (0.0 left edge to 1.0 right edge) of the target BUY or SELL button, or 0.5 if HOLD",
                ),
                "normalizedY" to Schema.double(
                    description = "Normalized vertical center coordinate (0.0 top edge to 1.0 bottom edge) of the target BUY or SELL button, or 0.5 if HOLD",
                ),
            ),
        )

        val TRADE_OUTCOME_SCHEMA = Schema.obj(
            properties = mapOf(
                "outcome" to Schema.enumeration(
                    values = listOf("WIN", "LOSS", "PENDING"),
                    description = "Whether the executed binary/short-term trade clearly ended in profit (WIN), closed at a loss (LOSS), or is still counting down (PENDING)",
                ),
                "profitDeltaText" to Schema.string(
                    description = "Visible profit/payout or loss amount such as +93.00, -50.00, or NONE",
                ),
                "lessonLearned" to Schema.string(
                    description = "Concise 1-sentence technical reflection: why the entry worked or what chart mistake caused the loss so future trades avoid repeating it",
                ),
            ),
        )

        /**
         * Normalizes multi-turn conversation history for [com.google.firebase.ai.GenerativeModel.startChat].
         * Ensures only non-blank text turns are kept and removes any trailing un-answered USER turns so
         * `chat.sendMessageStream(nextUserPrompt)` always produces clean alternating turns.
         */
        fun normalizeHistoryTurns(messages: List<GeminiChatMessage>): List<Pair<ChatRole, String>> {
            val turns = messages.mapNotNull { msg ->
                val turnText = if (msg.role == ChatRole.USER) {
                    msg.promptText.ifBlank { msg.text }.trim()
                } else {
                    msg.audit?.let { audit ->
                        "${audit.summary}\nRisk: ${audit.riskLevel}. Action: ${audit.recommendedAction}"
                    } ?: msg.text.trim()
                }
                if (turnText.isBlank()) null else msg.role to turnText
            }.toMutableList()

            while (turns.isNotEmpty() && turns.last().first == ChatRole.USER) {
                turns.removeAt(turns.lastIndex)
            }
            return turns
        }

        fun buildSdkHistory(messages: List<GeminiChatMessage>): List<Content> =
            normalizeHistoryTurns(messages).map { (role, turnText) ->
                val roleString = if (role == ChatRole.USER) "user" else "model"
                content(role = roleString) { text(turnText) }
            }

        fun buildContextualPrompt(userPrompt: String, pageContext: PageAiContext?): String {
            val cleanPrompt = userPrompt.trim()
            if (pageContext == null || pageContext.url.isBlank()) {
                return cleanPrompt
            }
            return buildString {
                appendLine(cleanPrompt)
                appendLine()
                appendLine("--- Active Browser Tab Context ---")
                appendLine("URL: ${pageContext.url}")
                appendLine("Title: ${pageContext.title}")
                appendLine("Mode: ${pageContext.browserModeLabel}")
                appendLine("Blocked Trackers: ${pageContext.trackersBlockedCount}, Blocked Scripts: ${pageContext.scriptsBlockedCount}")
                if (pageContext.pageTextSnippet.isNotBlank()) {
                    appendLine("Page Text Excerpt: ${pageContext.pageTextSnippet.take(3500)}")
                }
            }
        }

        fun parsePagePrivacyAuditJson(rawJson: String): PagePrivacyAudit {
            val json = JSONObject(GeminiPersonalRestClient.stripJsonCodeFences(rawJson))
            val risk = json.optString("riskLevel", "Medium").ifBlank { "Medium" }
            val summary = json.getString("summary")
            val signalsJson = json.optJSONArray("privacySignals")
            val signals = if (signalsJson != null) {
                (0 until signalsJson.length())
                    .mapNotNull { idx -> signalsJson.optString(idx).takeIf { it.isNotBlank() } }
            } else {
                emptyList()
            }
            val recommended = json.getString("recommendedAction")
            return PagePrivacyAudit(
                riskLevel = risk,
                summary = summary,
                privacySignals = signals,
                recommendedAction = recommended,
            )
        }

        fun buildTradeAnalysisPrompt(
            pageUrl: String,
            domControlsSummary: String,
            hasTwoFrames: Boolean = false,
            learningContext: String = "",
        ): String = buildString {
            appendLine(
                if (hasTwoFrames) {
                    "You are given TWO sequential live frames (FRAME A ~0.7s earlier, FRAME B current live screen). Compare them closely and decide BUY or SELL."
                } else {
                    "Examine this live trading chart screenshot closely and decide BUY or SELL."
                },
            )
            if (pageUrl.isNotBlank()) {
                appendLine("Page URL: $pageUrl")
            }
            appendLine()
            appendLine("Trained Short-Term Execution Checklist:")
            appendLine("1. Read top bar asset ticker (e.g. EUR / USD) and REAL vs DEMO badge.")
            if (hasTwoFrames) {
                appendLine("2. Compare FRAME A vs FRAME B: did the glowing price dot/last candle tick higher (upside impulse / bounce) or lower (downside impulse / rejection)?")
            } else {
                appendLine("2. Inspect the final 3-8 ticks/candles leading directly into the glowing price marker.")
            }
            appendLine("3. Apply clear directional rules:")
            appendLine("   - Pick BUY when: price makes higher lows, breaks above a recent micro-peak, rebounds strongly off a recent floor/double-bottom, or ticks steadily upward into Frame B.")
            appendLine("   - Pick SELL when: price makes lower highs, breaks below a recent micro-trough, rejects downward from a recent ceiling/double-top, or ticks steadily downward into Frame B.")
            appendLine("   - In choppy/sideways oscillation: trade the active swing edge (BUY near lower half / support hook-up; SELL near upper half / resistance turn-down). Do NOT output HOLD 0% for active volatility!")
            appendLine("4. Output calibrated confidence 60-94 for BUY or SELL whenever chart + Buy/Sell buttons are visible. Use HOLD only if no trading chart or Buy/Sell buttons exist on screen.")
            appendLine("5. Return exact normalized center (0.0 to 1.0) of the chosen BUY (usually green/right) or SELL (usually red/left) button.")
            if (learningContext.isNotBlank()) {
                appendLine()
                appendLine(learningContext)
            }
            if (domControlsSummary.isNotBlank()) {
                appendLine()
                appendLine("Detected DOM control hints (viewport-relative):")
                appendLine(domControlsSummary)
            }
        }

        fun buildTradeOutcomeVerificationPrompt(pending: PendingTradeSnapshot): String = buildString {
            appendLine("Verify the outcome of our recently executed trade on this live screen.")
            appendLine("- Executed Action: ${pending.decision.name} on ${pending.assetName} (${pending.accountMode})")
            appendLine("- Entry Confidence: ${pending.confidence}% | Setup: ${pending.marketBias}")
            appendLine("- Entry Reasoning: ${pending.reasoning}")
            if (pending.preClickBalance != null) {
                appendLine("- Balance Before Trade: ${pending.preClickBalance}")
            }
            appendLine()
            appendLine("Inspect:")
            appendLine("1. Look for profit/payout popup, green +amount tag, red settled loss, balance jump above/below pre-click balance, or recent strike marker above/below current price line.")
            appendLine("2. Output outcome='WIN' if clearly won, 'LOSS' if clearly lost, or 'PENDING' if strike timer is still active or result is unclear.")
            appendLine("3. Provide a concrete 1-sentence technical lessonLearned: if LOSS, explain the exact chart mistake (e.g. entered into resistance/sideways chop/counter-trend spike); if WIN, note why the momentum/level timing succeeded.")
        }

        fun parseTradeOutcomeVerificationJson(rawJson: String): TradeOutcomeVerification {
            val map = com.example.browser.TradePilotHelper.parseFlatJsonObject(rawJson)
            val rawOutcome = map["outcome"]?.trim()?.uppercase().orEmpty()
            val outcome = when (rawOutcome) {
                "WIN", "WON", "PROFIT" -> TradeOutcome.WIN
                "LOSS", "LOST", "LOSE" -> TradeOutcome.LOSS
                else -> null
            }
            val profitText = map["profitDeltaText"].orEmpty()
            val profitDelta = TradeLearningEngine.extractCurrencyNumber(profitText)
                ?: Regex("""[-+]?\d[\d,]*(?:\.\d+)?""").find(profitText)?.value?.replace(",", "")?.toDoubleOrNull()
            val lesson = map["lessonLearned"]?.trim().orEmpty()
            return TradeOutcomeVerification(
                outcome = outcome,
                profitDelta = profitDelta,
                lessonLearned = lesson,
            )
        }

        fun parseTradeDecisionJson(rawJson: String): TradeSignalDecision {
            val map = com.example.browser.TradePilotHelper.parseFlatJsonObject(rawJson)
            val rawDecision = map["decision"]?.trim()?.uppercase().orEmpty().ifBlank { "HOLD" }
            val decision = when (rawDecision) {
                "BUY", "CALL", "UP" -> TradeDecision.BUY
                "SELL", "PUT", "DOWN" -> TradeDecision.SELL
                else -> TradeDecision.HOLD
            }
            val confidence = (map["confidence"]?.toDoubleOrNull()?.toInt() ?: 0).coerceIn(0, 100)
            val assetName = map["assetName"]?.ifBlank { null } ?: "Unknown"
            val accountMode = map["accountMode"]?.uppercase()?.ifBlank { null } ?: "UNKNOWN"
            val marketBias = map["marketBias"]?.ifBlank { null } ?: "Analyzing"
            val reasoning = map["reasoning"]?.ifBlank { null } ?: "No clear chart signal detected."
            val targetLabel = map["targetLabel"]?.ifBlank { null } ?: decision.name
            val rawX = map["normalizedX"]?.toFloatOrNull() ?: 0.5f
            val rawY = map["normalizedY"]?.toFloatOrNull() ?: 0.5f
            val normX = if (rawX.isNaN()) 0.5f else rawX.coerceIn(0f, 1f)
            val normY = if (rawY.isNaN()) 0.5f else rawY.coerceIn(0f, 1f)
            return TradeSignalDecision(
                decision = decision,
                confidence = confidence,
                assetName = assetName,
                accountMode = accountMode,
                marketBias = marketBias,
                reasoning = reasoning,
                targetLabel = targetLabel,
                normalizedX = normX,
                normalizedY = normY,
            )
        }
    }
}
