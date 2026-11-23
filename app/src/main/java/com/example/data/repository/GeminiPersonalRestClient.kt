package com.example.data.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.browser.TradeSignalDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Direct Google AI Gemini REST client used ONLY when the user configures their own personal
 * Gemini API key inside the app (e.g. when running an installed APK outside AI Studio Preview
 * without Firebase App Check attestation).
 */
class GeminiPersonalRestClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile
    private var cachedModelName: String? = null
    @Volatile
    private var cachedKeyFingerprint: Int = 0
    @Volatile
    private var cachedAtMs: Long = 0L

    suspend fun generateChatReply(
        apiKey: String,
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): String = withContext(Dispatchers.IO) {
        val modelName = resolveActiveModel(apiKey)
        val turns = GeminiShieldRepository.normalizeHistoryTurns(history)
        val contentsArray = JSONArray()
        for ((role, text) in turns) {
            val apiRole = if (role == ChatRole.USER) "user" else "model"
            contentsArray.put(
                JSONObject()
                    .put("role", apiRole)
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
        val contextualPrompt = GeminiShieldRepository.buildContextualPrompt(userPrompt, pageContext)
        contentsArray.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", contextualPrompt))),
        )

        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(
                JSONObject().put(
                    "text",
                    "You are OnionShield AI, a concise security, privacy, and research assistant " +
                        "built inside the OnionShield privacy browser. Explain web privacy, Tor " +
                        "networking, trackers, phishing signals, and web page summaries accurately and concisely.",
                ),
            ),
        )

        val body = JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", contentsArray)

        executeGenerateContentWithModelRetry(
            apiKey = apiKey,
            initialModel = modelName,
            payload = body,
        )
    }

    suspend fun runStructuredPrivacyAudit(
        apiKey: String,
        pageContext: PageAiContext,
    ): PagePrivacyAudit = withContext(Dispatchers.IO) {
        val modelName = resolveActiveModel(apiKey)
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
            appendLine()
            appendLine(
                "Respond strictly as valid JSON with fields: " +
                    "riskLevel ('Low'|'Medium'|'High'), summary (2 sentences), " +
                    "privacySignals (array of short strings), recommendedAction (string).",
            )
        }

        val auditSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "riskLevel",
                        JSONObject().put("type", "STRING").put("enum", JSONArray().put("Low").put("Medium").put("High")),
                    )
                    .put("summary", JSONObject().put("type", "STRING"))
                    .put(
                        "privacySignals",
                        JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")),
                    )
                    .put("recommendedAction", JSONObject().put("type", "STRING")),
            )
            .put(
                "required",
                JSONArray().put("riskLevel").put("summary").put("privacySignals").put("recommendedAction"),
            )

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", auditSchema),
            )

        val rawText = executeGenerateContentWithModelRetry(
            apiKey = apiKey,
            initialModel = modelName,
            payload = body,
        )
        GeminiShieldRepository.parsePagePrivacyAuditJson(stripJsonCodeFences(rawText))
    }

    suspend fun analyzeScreenForTradeSignal(
        apiKey: String,
        screenshot: Bitmap,
        pageUrl: String,
        domControlsSummary: String,
        previousScreenshot: Bitmap? = null,
    ): TradeSignalDecision = withContext(Dispatchers.IO) {
        val modelName = resolveActiveModel(apiKey)
        val promptText = GeminiShieldRepository.buildTradeAnalysisPrompt(
            pageUrl = pageUrl,
            domControlsSummary = domControlsSummary,
            hasTwoFrames = previousScreenshot != null,
        )
        val partsArray = JSONArray()
        if (previousScreenshot != null) {
            partsArray
                .put(JSONObject().put("text", "FRAME A (~0.9s earlier):"))
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", encodeBitmapToJpegBase64(previousScreenshot)),
                    ),
                )
                .put(JSONObject().put("text", "FRAME B (CURRENT LIVE SCREEN RIGHT NOW):"))
        }
        partsArray
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeBitmapToJpegBase64(screenshot)),
                ),
            )
            .put(JSONObject().put("text", promptText))

        val tradeSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "decision",
                        JSONObject().put("type", "STRING").put("enum", JSONArray().put("BUY").put("SELL").put("HOLD")),
                    )
                    .put("confidence", JSONObject().put("type", "INTEGER"))
                    .put("assetName", JSONObject().put("type", "STRING"))
                    .put("accountMode", JSONObject().put("type", "STRING"))
                    .put("marketBias", JSONObject().put("type", "STRING"))
                    .put("reasoning", JSONObject().put("type", "STRING"))
                    .put("targetLabel", JSONObject().put("type", "STRING"))
                    .put("normalizedX", JSONObject().put("type", "NUMBER"))
                    .put("normalizedY", JSONObject().put("type", "NUMBER")),
            )
            .put(
                "required",
                JSONArray()
                    .put("decision")
                    .put("confidence")
                    .put("assetName")
                    .put("accountMode")
                    .put("marketBias")
                    .put("reasoning")
                    .put("targetLabel")
                    .put("normalizedX")
                    .put("normalizedY"),
            )

        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(
                JSONObject().put(
                    "text",
                    GeminiShieldRepository.TRADE_ANALYST_SYSTEM_INSTRUCTION,
                ),
            ),
        )

        val body = JSONObject()
            .put("system_instruction", systemInstruction)
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", partsArray),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", tradeSchema),
            )

        val rawText = executeGenerateContentWithModelRetry(
            apiKey = apiKey,
            initialModel = modelName,
            payload = body,
        )
        GeminiShieldRepository.parseTradeDecisionJson(stripJsonCodeFences(rawText))
    }

    private fun resolveActiveModel(apiKey: String, forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        val keyFp = apiKey.hashCode()
        val cached = cachedModelName
        if (!forceRefresh && cached != null && cachedKeyFingerprint == keyFp && now - cachedAtMs < MODEL_CACHE_TTL_MS) {
            return cached
        }
        val discovered = runCatching { fetchDiscoveredModelName(apiKey) }.getOrNull()
        val resolved = discovered ?: GeminiShieldRepository.MODEL_NAME
        cachedModelName = resolved
        cachedKeyFingerprint = keyFp
        cachedAtMs = now
        return resolved
    }

    private fun fetchDiscoveredModelName(apiKey: String): String? {
        val request = Request.Builder()
            .url("$BASE_URL/models?pageSize=50")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string().orEmpty()
            return pickBestFlashModelName(bodyStr)
        }
    }

    private fun executeGenerateContentWithModelRetry(
        apiKey: String,
        initialModel: String,
        payload: JSONObject,
    ): String {
        return try {
            executeGenerateContent(apiKey, initialModel, payload)
        } catch (firstErr: GeminiRestHttpException) {
            if (firstErr.statusCode == 404 || firstErr.statusCode == 400) {
                val refreshedModel = resolveActiveModel(apiKey, forceRefresh = true)
                val fallback = if (refreshedModel != initialModel) refreshedModel else GeminiShieldRepository.FALLBACK_MODEL_NAME
                executeGenerateContent(apiKey, fallback, payload)
            } else {
                throw firstErr
            }
        }
    }

    private fun executeGenerateContent(
        apiKey: String,
        modelName: String,
        payload: JSONObject,
    ): String {
        val cleanModel = modelName.removePrefix("models/")
        val request = Request.Builder()
            .url("$BASE_URL/models/$cleanModel:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val serverMessage = extractErrorMessage(rawBody)
                    ?: "Gemini API returned HTTP ${response.code}"
                throw GeminiRestHttpException(response.code, serverMessage)
            }
            val parsedText = extractCandidateText(rawBody)
            if (parsedText.isBlank()) {
                throw IllegalStateException("Gemini returned an empty response.")
            }
            return parsedText
        }
    }

    private fun encodeBitmapToJpegBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractCandidateText(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val root = JSONObject(rawBody)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (partText.isNotEmpty()) {
                builder.append(partText)
            }
        }
        return builder.toString().trim()
    }

    private fun extractErrorMessage(rawBody: String): String? = runCatching {
        JSONObject(rawBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    class GeminiRestHttpException(
        val statusCode: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODEL_CACHE_TTL_MS = 30 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun stripJsonCodeFences(rawText: String): String {
            var cleaned = rawText.trim()
            if (cleaned.startsWith("```")) {
                val firstNewline = cleaned.indexOf('\n')
                cleaned = if (firstNewline >= 0) {
                    cleaned.substring(firstNewline + 1)
                } else {
                    cleaned.removePrefix("```json").removePrefix("```")
                }
                val lastFence = cleaned.lastIndexOf("```")
                if (lastFence >= 0) {
                    cleaned = cleaned.substring(0, lastFence)
                }
            }
            val startObj = cleaned.indexOf('{')
            val endObj = cleaned.lastIndexOf('}')
            if (startObj >= 0 && endObj > startObj) {
                cleaned = cleaned.substring(startObj, endObj + 1)
            }
            return cleaned.trim()
        }

        /**
         * Pure-Kotlin dynamic model picker over `/v1beta/models` JSON so tests pass cleanly on JVM.
         * Never relies on hardcoded legacy model names.
         */
        fun pickBestFlashModelName(modelsJson: String): String? {
            if (modelsJson.isBlank()) return null
            val modelBlocks = Regex("""\{[^{}]*"name"\s*:\s*"models/([^"]+)"[^{}]*\}""")
                .findAll(modelsJson)
                .toList()
            if (modelBlocks.isEmpty()) return null

            var bestName: String? = null
            var bestScore = Int.MIN_VALUE

            for (match in modelBlocks) {
                val block = match.value
                val rawName = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (rawName.isBlank()) continue
                val lower = rawName.lowercase()
                if (!lower.contains("gemini")) continue
                if (!block.contains("generateContent")) continue
                if (lower.contains("embedding") ||
                    lower.contains("imagen") ||
                    lower.contains("veo") ||
                    lower.contains("tts") ||
                    lower.contains("aqa") ||
                    lower.contains("robotics") ||
                    lower.contains("computer-use")
                ) {
                    continue
                }

                var score = 0
                if (lower.contains("flash")) score += 500
                if (lower.contains("flash-lite")) score -= 40
                if (lower.contains("gemini-3")) score += 300
                if (lower.contains("gemini-flash-latest")) score += 250
                if (!lower.contains("exp") && !lower.contains("preview") && !lower.contains("001")) {
                    score += 40
                }
                if (lower.contains("preview")) score += 20
                if (lower.contains("live")) score -= 300
                if (score > bestScore) {
                    bestScore = score
                    bestName = rawName
                }
            }
            return bestName
        }
    }
}
