package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.browser.ProfitLossPoint
import com.example.browser.ProfitLossSeries
import com.example.browser.TradeLearningEngine
import com.example.browser.TradeLearningProfile
import com.example.browser.TradeOutcome
import com.example.ui.theme.LocalShieldColors
import java.util.Locale
import kotlin.math.abs

@Composable
fun TradePerformanceChart(
    learningProfile: TradeLearningProfile,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val shieldColors = LocalShieldColors.current
    val series = remember(learningProfile) {
        TradeLearningEngine.buildProfitLossSeries(learningProfile)
    }
    var selectedStepIndex by remember(series.points.size) {
        mutableIntStateOf(series.points.lastOrNull()?.stepIndex ?: 0)
    }

    val isNetPositive = series.netValue >= 0.0
    val curveColor = when {
        series.points.size <= 1 -> accentColor
        isNetPositive -> shieldColors.secureGreen
        else -> MaterialTheme.colorScheme.error
    }
    val winColor = shieldColors.secureGreen
    val lossColor = MaterialTheme.colorScheme.error
    val drawColor = shieldColors.warningAmber
    val baselineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val surfaceBg = MaterialTheme.colorScheme.surface

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        border = BorderStroke(1.dp, curveColor.copy(alpha = 0.30f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("trade_pilot_pnl_chart"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = when {
                            series.points.size <= 1 -> Icons.AutoMirrored.Rounded.ShowChart
                            isNetPositive -> Icons.AutoMirrored.Rounded.TrendingUp
                            else -> Icons.AutoMirrored.Rounded.TrendingDown
                        },
                        contentDescription = null,
                        tint = curveColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.trade_pilot_chart_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (series.points.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = curveColor.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = buildString {
                                append("Net ")
                                append(formatSeriesValue(series.netValue, series.isCurrencySeries))
                                append(" • Peak ")
                                append(formatSeriesValue(series.peakValue, series.isCurrencySeries))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = curveColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (series.points.size <= 1) {
                EmptyProfitLossChartPreview(
                    accentColor = accentColor,
                    baselineColor = baselineColor,
                )
            } else {
                val selectedPoint = series.points.firstOrNull { it.stepIndex == selectedStepIndex }
                    ?: series.points.last()
                val minVal = minOf(0.0, series.points.minOf { it.cumulativeValue })
                val maxVal = maxOf(0.0, series.points.maxOf { it.cumulativeValue })
                val chartDesc = "AI Profit/Loss line chart showing ${series.points.size - 1} trades, net ${
                    formatSeriesValue(series.netValue, series.isCurrencySeries)
                }"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.height(74.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = formatSeriesValue(maxVal, series.isCurrencySeries, compact = true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Text(
                            text = formatSeriesValue(minVal, series.isCurrencySeries, compact = true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .semantics { contentDescription = chartDesc }
                            .pointerInput(series.points) {
                                detectTapGestures { tapOffset ->
                                    if (series.points.isEmpty() || size.width <= 0) return@detectTapGestures
                                    val padH = 8.dp.toPx()
                                    val usableW = (size.width - padH * 2f).coerceAtLeast(1f)
                                    val stepX = usableW / (series.points.size - 1).coerceAtLeast(1).toFloat()
                                    val closest = series.points.minByOrNull { pt ->
                                        val px = padH + pt.stepIndex * stepX
                                        abs(px - tapOffset.x)
                                    }
                                    if (closest != null) {
                                        selectedStepIndex = closest.stepIndex
                                    }
                                }
                            },
                    ) {
                        val pts = series.points
                        if (pts.size < 2) return@Canvas

                        val padH = 8.dp.toPx()
                        val padV = 8.dp.toPx()
                        val usableW = (size.width - padH * 2f).coerceAtLeast(1f)
                        val usableH = (size.height - padV * 2f).coerceAtLeast(1f)
                        val range = (maxVal - minVal).takeIf { it > 1e-6 } ?: 2.0

                        fun toOffset(pt: ProfitLossPoint): Offset {
                            val x = padH + (pt.stepIndex.toFloat() / (pts.size - 1).toFloat()) * usableW
                            val normY = ((pt.cumulativeValue - minVal) / range).toFloat().coerceIn(0f, 1f)
                            val y = padV + usableH * (1f - normY)
                            return Offset(x, y)
                        }

                        val zeroNormY = ((0.0 - minVal) / range).toFloat().coerceIn(0f, 1f)
                        val zeroY = padV + usableH * (1f - zeroNormY)

                        // Subtle upper/lower grid guides
                        drawLine(
                            color = gridColor,
                            start = Offset(padH, padV),
                            end = Offset(size.width - padH, padV),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(padH, size.height - padV),
                            end = Offset(size.width - padH, size.height - padV),
                            strokeWidth = 1.dp.toPx(),
                        )

                        // Zero baseline dashed reference
                        drawLine(
                            color = baselineColor,
                            start = Offset(padH, zeroY),
                            end = Offset(size.width - padH, zeroY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        )

                        val coords = pts.map(::toOffset)
                        val linePath = Path().apply {
                            moveTo(coords.first().x, coords.first().y)
                            for (i in 1 until coords.size) {
                                val prev = coords[i - 1]
                                val curr = coords[i]
                                val midX = (prev.x + curr.x) * 0.5f
                                cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                            }
                        }

                        val areaPath = Path().apply {
                            addPath(linePath)
                            lineTo(coords.last().x, zeroY)
                            lineTo(coords.first().x, zeroY)
                            close()
                        }

                        drawPath(
                            path = areaPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    curveColor.copy(alpha = 0.28f),
                                    curveColor.copy(alpha = 0.03f),
                                ),
                            ),
                        )

                        drawPath(
                            path = linePath,
                            color = curveColor,
                            style = Stroke(
                                width = 2.4.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )

                        pts.forEachIndexed { idx, pt ->
                            val c = coords[idx]
                            val dotColor = when (pt.trade?.outcome) {
                                TradeOutcome.WIN -> winColor
                                TradeOutcome.LOSS -> lossColor
                                TradeOutcome.DRAW -> drawColor
                                null -> baselineColor
                            }
                            val isSelected = pt.stepIndex == selectedPoint.stepIndex
                            if (isSelected) {
                                drawCircle(
                                    color = dotColor.copy(alpha = 0.25f),
                                    radius = 7.dp.toPx(),
                                    center = c,
                                )
                            }
                            drawCircle(
                                color = dotColor,
                                radius = if (isSelected) 4.2.dp.toPx() else 2.8.dp.toPx(),
                                center = c,
                            )
                            drawCircle(
                                color = surfaceBg,
                                radius = if (isSelected) 1.6.dp.toPx() else 1.1.dp.toPx(),
                                center = c,
                            )
                        }
                    }
                }

                val selectedTrade = selectedPoint.trade
                Text(
                    text = if (selectedTrade != null) {
                        "#${selectedPoint.stepIndex} ${selectedTrade.decision.name} ${selectedTrade.confidence}% (${selectedTrade.assetName}) • ${selectedTrade.outcome.name} ${
                            formatSeriesValue(selectedPoint.stepDelta, series.isCurrencySeries)
                        } -> Total ${formatSeriesValue(selectedPoint.cumulativeValue, series.isCurrencySeries)}"
                    } else {
                        "Start baseline (0) • Tap any dot to inspect trade outcome"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyProfitLossChartPreview(
    accentColor: Color,
    baselineColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height * 0.5f
                drawLine(
                    color = baselineColor,
                    start = Offset(8.dp.toPx(), y),
                    end = Offset(size.width - 8.dp.toPx(), y),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.65f),
                    radius = 3.5.dp.toPx(),
                    center = Offset(12.dp.toPx(), y),
                )
            }
            Text(
                text = stringResource(R.string.trade_pilot_chart_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private fun formatSeriesValue(
    value: Double,
    isCurrency: Boolean,
    compact: Boolean = false,
): String {
    val sign = when {
        value > 0.005 -> "+"
        value < -0.005 -> "-"
        else -> ""
    }
    val mag = abs(value)
    return if (isCurrency) {
        if (compact && mag >= 100.0) {
            "$sign${mag.toInt()}"
        } else {
            "$sign${String.format(Locale.US, "%.2f", mag)}"
        }
    } else {
        "$sign${mag.toInt()}"
    }
}
