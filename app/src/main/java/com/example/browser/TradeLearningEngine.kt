package com.example.browser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TradeOutcome {
    WIN,
    LOSS,
    DRAW,
}

data class TradeRecord(
    val id: String,
    val assetName: String,
    val accountMode: String,
    val decision: TradeDecision,
    val confidence: Int,
    val marketBias: String,
    val reasoning: String,
    val outcome: TradeOutcome,
    val profitDelta: Double? = null,
    val lessonLearned: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PendingTradeSnapshot(
    val id: String,
    val tabId: String,
    val assetName: String,
    val accountMode: String,
    val decision: TradeDecision,
    val confidence: Int,
    val marketBias: String,
    val reasoning: String,
    val preClickBalance: Double? = null,
    val expirySeconds: Int = 20,
    val clickedAtMs: Long = System.currentTimeMillis(),
    val checkAtMs: Long = System.currentTimeMillis() + 20_000L,
)

data class TradeDomSnapshot(
    val balance: Double? = null,
    val expirySeconds: Int? = null,
    val outcomeHint: String? = null, // "WIN" | "LOSS" | null
    val profitDelta: Double? = null,
)

data class TradeOutcomeVerification(
    val outcome: TradeOutcome?, // null means still active / pending
    val profitDelta: Double? = null,
    val lessonLearned: String = "",
)

data class PatternWinLoss(
    val wins: Int = 0,
    val losses: Int = 0,
) {
    val total: Int get() = wins + losses
    val winRatePercent: Int
        get() = if (total == 0) 0 else ((wins.toDouble() / total.toDouble()) * 100.0).roundToInt()
}

data class TradeLearningProfile(
    val totalWins: Int = 0,
    val totalLosses: Int = 0,
    val totalDraws: Int = 0,
    val buyWins: Int = 0,
    val buyLosses: Int = 0,
    val sellWins: Int = 0,
    val sellLosses: Int = 0,
    val currentWinStreak: Int = 0,
    val currentLossStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val netProfitDelta: Double = 0.0,
    val selfAdaptEnabled: Boolean = true,
    val adaptedMinConfidence: Int = 60,
    val adaptedCooldownSec: Int = 8,
    val patternStats: Map<String, PatternWinLoss> = emptyMap(),
    val learnedLessons: List<String> = emptyList(),
    val recentTrades: List<TradeRecord> = emptyList(),
    val lastOutcomeMessage: String? = null,
) {
    val totalDecided: Int get() = totalWins + totalLosses

    val winRatePercent: Int
        get() = if (totalDecided == 0) 0 else ((totalWins.toDouble() / totalDecided.toDouble()) * 100.0).roundToInt()

    val buyTotal: Int get() = buyWins + buyLosses
    val buyWinRatePercent: Int
        get() = if (buyTotal == 0) 0 else ((buyWins.toDouble() / buyTotal.toDouble()) * 100.0).roundToInt()

    val sellTotal: Int get() = sellWins + sellLosses
    val sellWinRatePercent: Int
        get() = if (sellTotal == 0) 0 else ((sellWins.toDouble() / sellTotal.toDouble()) * 100.0).roundToInt()

    val maturityStageLabel: String
        get() = when {
            totalDecided == 0 -> "Ready to Learn"
            totalDecided < 3 -> "Calibrating ($totalDecided/3)"
            currentLossStreak >= 2 -> "Self-Correcting (${adaptedMinConfidence}%)"
            winRatePercent >= 68 && totalDecided >= 6 -> "Refined ($winRatePercent% WR)"
            else -> "Adapting ($winRatePercent% WR)"
        }
}

data class ProfitLossPoint(
    val stepIndex: Int,
    val cumulativeValue: Double,
    val stepDelta: Double,
    val trade: TradeRecord? = null,
)

data class ProfitLossSeries(
    val points: List<ProfitLossPoint> = emptyList(),
    val isCurrencySeries: Boolean = false,
    val netValue: Double = 0.0,
    val peakValue: Double = 0.0,
    val maxDrawdown: Double = 0.0,
)

object TradeLearningEngine {

    private const val MAX_STORED_TRADES = 250
    private const val MAX_STORED_LESSONS = 6
    private const val MIN_ADAPTED_CONF = 58
    private const val MAX_ADAPTED_CONF = 82
    private const val MIN_COOLDOWN_SEC = 6
    private const val MAX_COOLDOWN_SEC = 18

    /**
     * Extracts visible top-bar account balance, expiry timer duration (e.g. 00:00:30),
     * and any visible WIN/LOSS outcome toast text from the trading webpage DOM.
     */
    val TRADE_OUTCOME_PROBE_SCRIPT: String = """
        (function() {
            try {
                var vw = window.innerWidth || 1;
                var vh = window.innerHeight || 1;
                var topText = [];
                var timerText = [];
                var winLossHint = '';
                var profitDelta = null;
                var nodes = document.querySelectorAll('div, span, p, strong, b, button');
                for (var i = 0; i < nodes.length && i < 280; i++) {
                    var el = nodes[i];
                    if (!el || !el.getBoundingClientRect) continue;
                    var rect = el.getBoundingClientRect();
                    if (!rect || rect.width <= 0 || rect.height <= 0) continue;
                    var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (!t || t.length > 48) continue;
                    if (rect.top <= vh * 0.22 && /[৳$€£₹]\s*-?\d[\d,]*(\.\d+)?/.test(t)) {
                        topText.push(t);
                    }
                    if (/^\d{2}:\d{2}(:\d{2})?$/.test(t) || /^\d+\s*(s|sec|m|min)$/i.test(t)) {
                        if (rect.top >= vh * 0.45 && rect.top <= vh * 0.92) {
                            timerText.push(t);
                        }
                    }
                    if (/\+\s*[৳$€£₹]?\s*\d+(\.\d+)?/.test(t) || /\b(you won|profit|deal won)\b/i.test(t)) {
                        var pm = t.match(/\+\s*[৳$€£₹]?\s*(\d[\d,]*(\.\d+)?)/);
                        if (pm) {
                            profitDelta = parseFloat(pm[1].replace(/,/g, ''));
                        }
                        if (rect.top >= vh * 0.10 && rect.top <= vh * 0.70) {
                            winLossHint = 'WIN';
                        }
                    }
                }
                return JSON.stringify({
                    balanceText: topText.join(' | ').slice(0, 80),
                    timerText: timerText.join(' | ').slice(0, 40),
                    outcomeHint: winLossHint,
                    profitDelta: profitDelta === null ? '' : String(profitDelta)
                });
            } catch (e) {
                return '{}';
            }
        })();
    """.trimIndent()

    fun parseDomSnapshot(rawJsResult: String?): TradeDomSnapshot {
        val unwrapped = TradePilotHelper.unwrapJsEvalString(rawJsResult).trim()
        if (!unwrapped.startsWith("{")) return TradeDomSnapshot()
        val map = TradePilotHelper.parseFlatJsonObject(unwrapped)
        val balance = extractCurrencyNumber(map["balanceText"])
        val expirySeconds = parseExpirySeconds(map["timerText"])
        val hint = map["outcomeHint"]?.trim()?.uppercase()?.takeIf { it == "WIN" || it == "LOSS" }
        val delta = map["profitDelta"]?.toDoubleOrNull()
        return TradeDomSnapshot(
            balance = balance,
            expirySeconds = expirySeconds,
            outcomeHint = hint,
            profitDelta = delta,
        )
    }

    internal fun extractCurrencyNumber(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""[৳$€£₹]\s*(-?\d[\d,]*(?:\.\d+)?)""").find(text) ?: return null
        val normalized = match.groupValues.getOrNull(1)?.replace(",", "") ?: return null
        return normalized.toDoubleOrNull()
    }

    internal fun parseExpirySeconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("""(\d{2}):(\d{2}):(\d{2})""").find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val sec = m.groupValues[3].toIntOrNull() ?: 0
            val total = h * 3600 + min * 60 + sec
            if (total in 3..300) return total
        }
        Regex("""(\d{2}):(\d{2})""").find(text)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: 0
            val sec = m.groupValues[2].toIntOrNull() ?: 0
            val total = min * 60 + sec
            if (total in 3..300) return total
        }
        Regex("""(\d+)\s*(s|sec|m|min)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@let
            val unit = m.groupValues[2].lowercase()
            val total = if (unit.startsWith("m")) num * 60 else num
            if (total in 3..300) return total
        }
        return null
    }

    fun evaluateOutcomeFromDom(
        preClickBalance: Double?,
        snapshot: TradeDomSnapshot,
    ): Pair<TradeOutcome, Double?>? {
        if (snapshot.outcomeHint == "WIN") {
            val delta = snapshot.profitDelta
                ?: if (preClickBalance != null && snapshot.balance != null) {
                    (snapshot.balance - preClickBalance).takeIf { it > 0.01 }
                } else {
                    null
                }
            return TradeOutcome.WIN to delta
        }
        if (snapshot.outcomeHint == "LOSS") {
            return TradeOutcome.LOSS to snapshot.profitDelta
        }
        if (preClickBalance != null && snapshot.balance != null) {
            val diff = snapshot.balance - preClickBalance
            if (diff > 0.01) return TradeOutcome.WIN to diff
            if (diff < -0.01) return TradeOutcome.LOSS to diff
        }
        return null
    }

    fun normalizePatternKey(marketBias: String): String {
        val lower = marketBias.trim().lowercase()
        return when {
            lower.contains("uptrend") || lower.contains("bull") -> "uptrend"
            lower.contains("downtrend") || lower.contains("bear") -> "downtrend"
            lower.contains("sideways") || lower.contains("range") || lower.contains("chop") -> "sideways"
            lower.contains("pullback") -> "pullback"
            lower.contains("bounce") || lower.contains("support") -> "support_bounce"
            lower.contains("rejection") || lower.contains("resistance") -> "resistance_reject"
            lower.contains("breakout") -> "breakout"
            lower.contains("reversal") -> "reversal"
            lower.isBlank() -> "general"
            else -> lower.replace(Regex("""[^a-z0-9]+"""), "_").trim('_').take(24).ifBlank { "general" }
        }
    }

    fun buildAdaptiveLesson(
        decision: TradeDecision,
        confidence: Int,
        marketBias: String,
        outcome: TradeOutcome,
        aiReflectionLesson: String?,
        updatedProfile: TradeLearningProfile,
    ): String {
        val cleanedAiLesson = aiReflectionLesson?.trim().orEmpty()
        if (cleanedAiLesson.length >= 12 &&
            !cleanedAiLesson.equals("none", ignoreCase = true) &&
            !cleanedAiLesson.equals("unknown", ignoreCase = true)
        ) {
            return cleanedAiLesson.take(150)
        }
        val pattern = normalizePatternKey(marketBias).replace('_', ' ')
        return when (outcome) {
            TradeOutcome.WIN -> {
                "Confirmed ${decision.name} edge on $pattern ($confidence% conf) — repeat clean Frame A->B follow-through."
            }
            TradeOutcome.LOSS -> {
                val patternLosses = updatedProfile.patternStats[normalizePatternKey(marketBias)]?.losses ?: 1
                if (pattern == "sideways" || patternLosses >= 2) {
                    "Mistake on $pattern ${decision.name} ($confidence%): avoid mid-range chop; require stronger Frame A->B breakout >=${updatedProfile.adaptedMinConfidence}%."
                } else if (decision == TradeDecision.BUY) {
                    "BUY lost on $pattern ($confidence%): wait for verified higher-low bounce before buying."
                } else {
                    "SELL lost on $pattern ($confidence%): avoid shorting before clear resistance rejection confirms downward tick."
                }
            }
            TradeOutcome.DRAW -> "Flat expiry on $pattern ${decision.name} ($confidence%) — wait for clearer volatility."
        }
    }

    fun recordOutcome(
        profile: TradeLearningProfile,
        assetName: String,
        accountMode: String,
        decision: TradeDecision,
        confidence: Int,
        marketBias: String,
        reasoning: String,
        outcome: TradeOutcome,
        profitDelta: Double? = null,
        aiReflectionLesson: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
        recordId: String = "${timestampMs}_${decision.name}",
    ): TradeLearningProfile {
        if (decision == TradeDecision.HOLD) return profile

        val newWins = profile.totalWins + if (outcome == TradeOutcome.WIN) 1 else 0
        val newLosses = profile.totalLosses + if (outcome == TradeOutcome.LOSS) 1 else 0
        val newDraws = profile.totalDraws + if (outcome == TradeOutcome.DRAW) 1 else 0

        val newBuyWins = profile.buyWins + if (decision == TradeDecision.BUY && outcome == TradeOutcome.WIN) 1 else 0
        val newBuyLosses = profile.buyLosses + if (decision == TradeDecision.BUY && outcome == TradeOutcome.LOSS) 1 else 0
        val newSellWins = profile.sellWins + if (decision == TradeDecision.SELL && outcome == TradeOutcome.WIN) 1 else 0
        val newSellLosses = profile.sellLosses + if (decision == TradeDecision.SELL && outcome == TradeOutcome.LOSS) 1 else 0

        val newWinStreak = when (outcome) {
            TradeOutcome.WIN -> profile.currentWinStreak + 1
            TradeOutcome.LOSS -> 0
            TradeOutcome.DRAW -> profile.currentWinStreak
        }
        val newLossStreak = when (outcome) {
            TradeOutcome.LOSS -> profile.currentLossStreak + 1
            TradeOutcome.WIN -> 0
            TradeOutcome.DRAW -> profile.currentLossStreak
        }
        val newBestStreak = maxOf(profile.bestWinStreak, newWinStreak)

        val patternKey = normalizePatternKey(marketBias)
        val existingPattern = profile.patternStats[patternKey] ?: PatternWinLoss()
        val updatedPattern = existingPattern.copy(
            wins = existingPattern.wins + if (outcome == TradeOutcome.WIN) 1 else 0,
            losses = existingPattern.losses + if (outcome == TradeOutcome.LOSS) 1 else 0,
        )
        val updatedPatternMap = profile.patternStats.toMutableMap().apply {
            this[patternKey] = updatedPattern
        }

        // Gradual self-adaptation of thresholds
        var nextMinConf = profile.adaptedMinConfidence
        var nextCooldown = profile.adaptedCooldownSec
        if (profile.selfAdaptEnabled) {
            when (outcome) {
                TradeOutcome.LOSS -> {
                    val stepUp = if (newLossStreak >= 2) 4 else 2
                    nextMinConf = (nextMinConf + stepUp).coerceIn(MIN_ADAPTED_CONF, MAX_ADAPTED_CONF)
                    nextCooldown = (nextCooldown + 2).coerceIn(MIN_COOLDOWN_SEC, MAX_COOLDOWN_SEC)
                }
                TradeOutcome.WIN -> {
                    val totalDecided = newWins + newLosses
                    val winRate = if (totalDecided > 0) (newWins * 100.0 / totalDecided) else 100.0
                    if (newWinStreak >= 2 && winRate >= 60.0) {
                        nextMinConf = (nextMinConf - 2).coerceIn(60, MAX_ADAPTED_CONF)
                    } else if (nextMinConf > 65) {
                        nextMinConf = (nextMinConf - 1).coerceIn(MIN_ADAPTED_CONF, MAX_ADAPTED_CONF)
                    }
                    nextCooldown = (nextCooldown - 1).coerceIn(MIN_COOLDOWN_SEC, MAX_COOLDOWN_SEC)
                }
                TradeOutcome.DRAW -> Unit
            }
        }

        val preliminaryProfile = profile.copy(
            totalWins = newWins,
            totalLosses = newLosses,
            totalDraws = newDraws,
            buyWins = newBuyWins,
            buyLosses = newBuyLosses,
            sellWins = newSellWins,
            sellLosses = newSellLosses,
            currentWinStreak = newWinStreak,
            currentLossStreak = newLossStreak,
            bestWinStreak = newBestStreak,
            adaptedMinConfidence = nextMinConf,
            adaptedCooldownSec = nextCooldown,
            patternStats = updatedPatternMap,
        )

        val lesson = buildAdaptiveLesson(
            decision = decision,
            confidence = confidence,
            marketBias = marketBias,
            outcome = outcome,
            aiReflectionLesson = aiReflectionLesson,
            updatedProfile = preliminaryProfile,
        )

        val updatedLessons = if (outcome != TradeOutcome.DRAW && lesson.isNotBlank()) {
            (listOf(lesson) + profile.learnedLessons.filterNot { it.equals(lesson, ignoreCase = true) })
                .take(MAX_STORED_LESSONS)
        } else {
            profile.learnedLessons
        }

        val record = TradeRecord(
            id = recordId,
            assetName = assetName.ifBlank { "Chart" },
            accountMode = accountMode.ifBlank { "UNKNOWN" },
            decision = decision,
            confidence = confidence,
            marketBias = marketBias,
            reasoning = reasoning,
            outcome = outcome,
            profitDelta = profitDelta,
            lessonLearned = lesson,
            timestampMs = timestampMs,
        )

        val updatedTrades = (listOf(record) + profile.recentTrades).take(MAX_STORED_TRADES)
        val newNetProfit = profile.netProfitDelta + (profitDelta ?: 0.0)
        val deltaBanner = profitDelta?.let {
            val sign = if (it >= 0) "+" else "-"
            " ($sign${String.format(java.util.Locale.US, "%.2f", abs(it))})"
        }.orEmpty()
        val outcomeMsg = "${outcome.name}: ${decision.name} on ${record.assetName}$deltaBanner"

        return preliminaryProfile.copy(
            netProfitDelta = newNetProfit,
            learnedLessons = updatedLessons,
            recentTrades = updatedTrades,
            lastOutcomeMessage = outcomeMsg,
        )
    }

    fun buildPromptMemoryContext(profile: TradeLearningProfile): String {
        if (profile.totalDecided == 0 && profile.learnedLessons.isEmpty()) {
            return ""
        }
        return buildString {
            appendLine("Self-Learning Performance Memory (apply past experience carefully):")
            appendLine(
                "- Track Record: ${profile.totalWins} Wins / ${profile.totalLosses} Losses " +
                    "(${profile.winRatePercent}% Win Rate, Win Streak=${profile.currentWinStreak}, Loss Streak=${profile.currentLossStreak})",
            )
            if (profile.buyTotal > 0 || profile.sellTotal > 0) {
                appendLine(
                    "- Direction Split: BUY=${profile.buyWins}W/${profile.buyLosses}L (${profile.buyWinRatePercent}%) | " +
                        "SELL=${profile.sellWins}W/${profile.sellLosses}L (${profile.sellWinRatePercent}%)",
                )
            }
            val caution = profile.patternStats.entries
                .filter { it.value.losses > it.value.wins && it.value.losses >= 1 }
                .sortedByDescending { it.value.losses - it.value.wins }
                .take(3)
            if (caution.isNotEmpty()) {
                appendLine(
                    "- High-Risk Setups to Filter Strictly: " +
                        caution.joinToString("; ") { "${it.key} (${it.value.wins}W/${it.value.losses}L)" },
                )
            }
            val strong = profile.patternStats.entries
                .filter { it.value.wins > it.value.losses && it.value.wins >= 1 }
                .sortedByDescending { it.value.wins - it.value.losses }
                .take(3)
            if (strong.isNotEmpty()) {
                appendLine(
                    "- Proven Winning Setups: " +
                        strong.joinToString("; ") { "${it.key} (${it.value.wins}W/${it.value.losses}L)" },
                )
            }
            if (profile.currentLossStreak >= 2) {
                appendLine(
                    "- ADAPTATION ACTIVE: ${profile.currentLossStreak} recent losses — be extra selective, favor higher-confidence >=${profile.adaptedMinConfidence}% entries only when Frame A->B confirms clear direction.",
                )
            }
            if (profile.learnedLessons.isNotEmpty()) {
                appendLine("- Learned Rules from Previous Trades:")
                profile.learnedLessons.take(5).forEachIndexed { idx, lesson ->
                    appendLine("  ${idx + 1}. $lesson")
                }
            }
        }.trim()
    }

    fun buildProfitLossSeries(profile: TradeLearningProfile): ProfitLossSeries {
        val chronological = profile.recentTrades.asReversed()
        if (chronological.isEmpty()) {
            return ProfitLossSeries()
        }
        val useCurrency = chronological.all { it.profitDelta != null }
        val points = mutableListOf(
            ProfitLossPoint(
                stepIndex = 0,
                cumulativeValue = 0.0,
                stepDelta = 0.0,
                trade = null,
            ),
        )
        var running = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        chronological.forEachIndexed { idx, record ->
            val delta = if (useCurrency) {
                record.profitDelta ?: 0.0
            } else {
                when (record.outcome) {
                    TradeOutcome.WIN -> 1.0
                    TradeOutcome.LOSS -> -1.0
                    TradeOutcome.DRAW -> 0.0
                }
            }
            running += delta
            if (running > peak) peak = running
            val drawdown = peak - running
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
            points += ProfitLossPoint(
                stepIndex = idx + 1,
                cumulativeValue = running,
                stepDelta = delta,
                trade = record,
            )
        }
        return ProfitLossSeries(
            points = points,
            isCurrencySeries = useCurrency,
            netValue = running,
            peakValue = peak,
            maxDrawdown = maxDrawdown,
        )
    }

    fun buildCsvExportFileName(timestampMs: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ai_trades_${formatter.format(Date(timestampMs))}.csv"
    }

    fun exportTradesToCsv(profile: TradeLearningProfile): String {
        val series = buildProfitLossSeries(profile)
        val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.appendLine(
            "Trade #,Timestamp,Asset,Account Mode,Decision,Confidence %,Market Bias,Outcome,Profit/Loss,Cumulative P/L,Lesson Learned,Reasoning",
        )
        // Skip baseline stepIndex 0; points 1..N correspond chronologically to each trade
        val tradePoints = series.points.filter { it.trade != null }
        tradePoints.forEach { pt ->
            val rec = pt.trade ?: return@forEach
            val formattedDelta = rec.profitDelta?.let {
                String.format(Locale.US, "%.2f", it)
            } ?: when (rec.outcome) {
                TradeOutcome.WIN -> "1"
                TradeOutcome.LOSS -> "-1"
                TradeOutcome.DRAW -> "0"
            }
            val formattedCum = if (series.isCurrencySeries) {
                String.format(Locale.US, "%.2f", pt.cumulativeValue)
            } else {
                String.format(Locale.US, "%.0f", pt.cumulativeValue)
            }
            val timestampStr = utcFormat.format(Date(rec.timestampMs))
            sb.append(pt.stepIndex).append(',')
                .append(escapeCsvCell(timestampStr)).append(',')
                .append(escapeCsvCell(rec.assetName)).append(',')
                .append(escapeCsvCell(rec.accountMode)).append(',')
                .append(escapeCsvCell(rec.decision.name)).append(',')
                .append(rec.confidence).append(',')
                .append(escapeCsvCell(rec.marketBias)).append(',')
                .append(escapeCsvCell(rec.outcome.name)).append(',')
                .append(formattedDelta).append(',')
                .append(formattedCum).append(',')
                .append(escapeCsvCell(rec.lessonLearned)).append(',')
                .append(escapeCsvCell(rec.reasoning))
                .append('\n')
        }
        return sb.toString()
    }

    private fun escapeCsvCell(value: String): String {
        val sanitized = value.replace("\r", " ").replace('\n', ' ')
        val needsQuotes = sanitized.contains(',') || sanitized.contains('"') || sanitized.contains('\n')
        return if (needsQuotes) {
            "\"${sanitized.replace("\"", "\"\"")}\""
        } else {
            sanitized
        }
    }

    // Pure-Kotlin compact pipe/line serialization so it works cleanly in DataStore & plain JVM unit tests
    fun serializeProfile(profile: TradeLearningProfile): String = buildString {
        append("V1|")
        append(profile.totalWins).append('|')
        append(profile.totalLosses).append('|')
        append(profile.totalDraws).append('|')
        append(profile.buyWins).append('|')
        append(profile.buyLosses).append('|')
        append(profile.sellWins).append('|')
        append(profile.sellLosses).append('|')
        append(profile.currentWinStreak).append('|')
        append(profile.currentLossStreak).append('|')
        append(profile.bestWinStreak).append('|')
        append(profile.netProfitDelta).append('|')
        append(profile.selfAdaptEnabled).append('|')
        append(profile.adaptedMinConfidence).append('|')
        append(profile.adaptedCooldownSec).append('|')
        append(encodeSafeText(profile.lastOutcomeMessage.orEmpty()))
        append('\n')
        profile.patternStats.forEach { (key, wl) ->
            append("P|")
                .append(encodeSafeText(key)).append('|')
                .append(wl.wins).append('|')
                .append(wl.losses)
                .append('\n')
        }
        profile.learnedLessons.forEach { lesson ->
            append("L|").append(encodeSafeText(lesson)).append('\n')
        }
        profile.recentTrades.forEach { r ->
            append("T|")
                .append(encodeSafeText(r.id)).append('|')
                .append(encodeSafeText(r.assetName)).append('|')
                .append(encodeSafeText(r.accountMode)).append('|')
                .append(r.decision.name).append('|')
                .append(r.confidence).append('|')
                .append(encodeSafeText(r.marketBias)).append('|')
                .append(encodeSafeText(r.reasoning)).append('|')
                .append(r.outcome.name).append('|')
                .append(r.profitDelta?.toString().orEmpty()).append('|')
                .append(encodeSafeText(r.lessonLearned)).append('|')
                .append(r.timestampMs)
                .append('\n')
        }
    }

    fun deserializeProfile(raw: String?): TradeLearningProfile {
        if (raw.isNullOrBlank()) return TradeLearningProfile()
        val lines = raw.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return TradeLearningProfile()

        var profile = TradeLearningProfile()
        val patterns = linkedMapOf<String, PatternWinLoss>()
        val lessons = mutableListOf<String>()
        val trades = mutableListOf<TradeRecord>()

        for (line in lines) {
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "V1" -> {
                    if (parts.size >= 16) {
                        profile = profile.copy(
                            totalWins = parts[1].toIntOrNull() ?: 0,
                            totalLosses = parts[2].toIntOrNull() ?: 0,
                            totalDraws = parts[3].toIntOrNull() ?: 0,
                            buyWins = parts[4].toIntOrNull() ?: 0,
                            buyLosses = parts[5].toIntOrNull() ?: 0,
                            sellWins = parts[6].toIntOrNull() ?: 0,
                            sellLosses = parts[7].toIntOrNull() ?: 0,
                            currentWinStreak = parts[8].toIntOrNull() ?: 0,
                            currentLossStreak = parts[9].toIntOrNull() ?: 0,
                            bestWinStreak = parts[10].toIntOrNull() ?: 0,
                            netProfitDelta = parts[11].toDoubleOrNull() ?: 0.0,
                            selfAdaptEnabled = parts[12].toBooleanStrictOrNull() ?: true,
                            adaptedMinConfidence = (parts[13].toIntOrNull() ?: 60).coerceIn(55, 85),
                            adaptedCooldownSec = (parts[14].toIntOrNull() ?: 8).coerceIn(5, 30),
                            lastOutcomeMessage = decodeSafeText(parts[15]).ifBlank { null },
                        )
                    }
                }
                "P" -> {
                    if (parts.size >= 4) {
                        val key = decodeSafeText(parts[1])
                        if (key.isNotBlank()) {
                            patterns[key] = PatternWinLoss(
                                wins = parts[2].toIntOrNull() ?: 0,
                                losses = parts[3].toIntOrNull() ?: 0,
                            )
                        }
                    }
                }
                "L" -> {
                    if (parts.size >= 2) {
                        val lesson = decodeSafeText(parts[1])
                        if (lesson.isNotBlank()) lessons.add(lesson)
                    }
                }
                "T" -> {
                    if (parts.size >= 12) {
                        val dec = runCatching { TradeDecision.valueOf(parts[4]) }.getOrNull() ?: TradeDecision.BUY
                        val out = runCatching { TradeOutcome.valueOf(parts[8]) }.getOrNull() ?: TradeOutcome.WIN
                        trades.add(
                            TradeRecord(
                                id = decodeSafeText(parts[1]),
                                assetName = decodeSafeText(parts[2]),
                                accountMode = decodeSafeText(parts[3]),
                                decision = dec,
                                confidence = parts[5].toIntOrNull() ?: 60,
                                marketBias = decodeSafeText(parts[6]),
                                reasoning = decodeSafeText(parts[7]),
                                outcome = out,
                                profitDelta = parts[9].toDoubleOrNull(),
                                lessonLearned = decodeSafeText(parts[10]),
                                timestampMs = parts[11].toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                }
            }
        }

        return profile.copy(
            patternStats = patterns,
            learnedLessons = lessons.take(MAX_STORED_LESSONS),
            recentTrades = trades.take(MAX_STORED_TRADES),
        )
    }

    private fun encodeSafeText(text: String): String =
        text.replace("%", "%25")
            .replace("|", "%7C")
            .replace("\n", "%0A")
            .replace("\r", "")

    private fun decodeSafeText(encoded: String): String =
        encoded.replace("%0A", "\n")
            .replace("%7C", "|")
            .replace("%25", "%")
}
