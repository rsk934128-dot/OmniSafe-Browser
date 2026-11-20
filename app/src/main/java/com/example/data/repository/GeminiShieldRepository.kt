package com.example.data.repository

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

    open fun sendChatMessageStream(
        history: List<GeminiChatMessage>,
        userPrompt: String,
        pageContext: PageAiContext?,
    ): Flow<String> = flow {
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
                if (!isTransientHighDemandError(e) || attemptIdx == plan.lastIndex) {
                    throw e
                }
                lastError = e
                delay(FALLBACK_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Gemini returned an empty audit response.")
    }

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"
        private const val PRIMARY_ATTEMPT_TIMEOUT_MS = 15_000L
        private const val FALLBACK_ATTEMPT_TIMEOUT_MS = 18_000L
        private const val FALLBACK_DELAY_MS = 300L

        fun isTransientHighDemandError(error: Throwable): Boolean {
            if (error is TimeoutCancellationException) return true
            val className = error::class.java.simpleName.lowercase()
            if (className.contains("serviceunavailable") || className.contains("quotaexceeded")) {
                return true
            }
            val combinedMsg = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.lowercase() }
                .joinToString(" ")
            if (combinedMsg.contains("403") ||
                combinedMsg.contains("app check") ||
                combinedMsg.contains("api key") ||
                combinedMsg.contains("permission denied")
            ) {
                return false
            }
            return combinedMsg.contains("high demand") ||
                combinedMsg.contains("spikes in demand") ||
                combinedMsg.contains("overloaded") ||
                combinedMsg.contains("temporarily unavailable") ||
                combinedMsg.contains("service unavailable") ||
                combinedMsg.contains("resource exhausted") ||
                combinedMsg.contains("rate limit") ||
                combinedMsg.contains("too many requests") ||
                combinedMsg.contains("try again later") ||
                combinedMsg.contains("timed out") ||
                combinedMsg.contains("timeout") ||
                combinedMsg.contains("503") ||
                combinedMsg.contains("504") ||
                combinedMsg.contains("502") ||
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
            val json = JSONObject(rawJson)
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
    }
}
