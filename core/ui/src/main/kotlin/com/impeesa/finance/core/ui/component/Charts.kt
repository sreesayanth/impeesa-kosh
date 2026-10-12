package com.impeesa.finance.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

private val ChartEasing = FastOutSlowInEasing

/** Runs a chart's entrance animation once, or jumps straight to the end when animations are off (§11.2). */
@Composable
private fun Entrance(anim: Animatable<Float, AnimationVector1D>, millis: Int) {
    val reduce = LocalReduceMotion.current
    LaunchedEffect(Unit) { if (reduce) anim.snapTo(1f) else anim.animateTo(1f, tween(millis, easing = ChartEasing)) }
}
/** One slice of a [DonutChart]. */
@Immutable
data class DonutSlice(val value: Float, val color: Color)

/**
 * Category donut (§10.4). Slices sweep in on first show and morph when values change.
 * [description] is what TalkBack reads instead of the drawing.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    thickness: Dp = 22.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val colors = ImpeesaTheme.colors
    val sweep = remember { Animatable(0f) }
    Entrance(sweep, 900)
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    // Each slice's share animates on its own so a period switch morphs instead of jumping.
    val shares = slices.mapIndexed { i, s ->
        animateFloatAsState(if (total > 0) s.value / total else 0f, tween(600, easing = ChartEasing), label = "slice$i").value
    }
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(colors.raised, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            var start = -90f
            val gap = if (slices.count { it.value > 0 } > 1) 1.6f else 0f
            slices.forEachIndexed { i, slice ->
                val angle = shares[i] * 360f * sweep.value
                if (angle > gap) drawArc(slice.color, start + gap / 2, angle - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                start += angle
            }
        }
        center()
    }
}

/**
 * Single-series bars (§10.4): Home week and Habits weekdays. [highlight] is drawn in lime with its
 * value above; [dashed] marks a day still in progress.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    highlight: Int? = values.indices.maxByOrNull { values[it] }?.takeIf { values[it] > 0 },
    dashed: Int? = null,
    highlightLabel: String? = null,
    height: Dp = 150.dp,
) {
    val colors = ImpeesaTheme.colors
    val rise = remember { Animatable(0f) }
    Entrance(rise, 800)
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, value ->
                val fraction by animateFloatAsState(value.toFloat() / max, tween(500, easing = ChartEasing), label = "bar$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (i == highlight && highlightLabel != null) {
                        Text(
                            highlightLabel,
                            style = ImpeesaTheme.type.caption,
                            color = colors.ink,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.textPrimary).padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    val barColor = if (i == highlight) colors.lime else colors.line
                    val h = (height - 28.dp) * (fraction * rise.value).coerceAtLeast(if (value > 0) 0.04f else 0.02f)
                    if (i == dashed) {
                        Canvas(Modifier.fillMaxWidth().height(h.coerceAtLeast(18.dp))) {
                            drawRoundRect(
                                colors.lime,
                                cornerRadius = CornerRadius(9.dp.toPx()),
                                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(9.dp)).background(barColor))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = ImpeesaTheme.type.caption,
                    color = if (i == highlight || i == dashed) colors.textPrimary else colors.textTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


/** Income vs spending per period (§10.4 GroupedBarChart). The last pair may be in progress. */
@Composable
fun GroupedBarChart(
    pairs: List<Pair<Long, Long>>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    firstColor: Color = ImpeesaTheme.colors.lime,
    secondColor: Color = ImpeesaTheme.colors.coral,
    height: Dp = 150.dp,
) {
    val colors = ImpeesaTheme.colors
    val rise = remember { Animatable(0f) }
    Entrance(rise, 900)
    val max = pairs.maxOfOrNull { maxOf(it.first, it.second) }?.coerceAtLeast(1L) ?: 1L
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            pairs.forEachIndexed { i, (a, b) ->
                val fa by animateFloatAsState(a.toFloat() / max, tween(500), label = "a$i")
                val fb by animateFloatAsState(b.toFloat() / max, tween(500), label = "b$i")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.size(width = 14.dp, height = height * (fa * rise.value).coerceAtLeast(0.02f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(firstColor))
                    Box(Modifier.size(width = 14.dp, height = height * (fb * rise.value).coerceAtLeast(0.02f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(secondColor))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { i, label ->
                Text(label, style = ImpeesaTheme.type.caption, color = if (i == labels.lastIndex) colors.textPrimary else colors.textTertiary)
            }
        }
    }
}

/** Budget ring (§10.4) with an optional pace marker, both 0..1+. */
@Composable
fun ProgressRing(
    fraction: Float,
    description: String,
    modifier: Modifier = Modifier,
    color: Color = ImpeesaTheme.colors.lime,
    marker: Float? = null,
    size: Dp = 140.dp,
    thickness: Dp = 14.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val colors = ImpeesaTheme.colors
    val grow = remember { Animatable(0f) }
    Entrance(grow, 1100)
    val value by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(colors.raised, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(color, -90f, 360f * value * grow.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            marker?.coerceIn(0f, 1f)?.let { m ->
                drawArc(colors.textPrimary, -90f + 360f * m - 1f, 2f, false, Offset(inset, inset), arc, style = Stroke(stroke * 0.45f))
            }
        }
        center()
    }
}

/** Thin progress bar with an optional pace marker (§10.4). Over-budget shows striped coral. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = ImpeesaTheme.colors.lime,
    marker: Float? = null,
    height: Dp = 8.dp,
) {
    val colors = ImpeesaTheme.colors
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val value by animateFloatAsState(if (started) fraction.coerceIn(0f, 1f) else 0f, tween(900, easing = ChartEasing), label = "bar")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(this.size.height / 2)
        drawRoundRect(colors.raised, cornerRadius = r)
        if (value > 0f) drawRoundRect(color, size = Size(this.size.width * value, this.size.height), cornerRadius = r)
        marker?.coerceIn(0f, 1f)?.let { m ->
            val x = this.size.width * m
            drawLine(colors.textPrimary, Offset(x, -2f), Offset(x, this.size.height + 2f), strokeWidth = 2.dp.toPx())
        }
    }
}

/** One day in a [CalendarHeatmap]; null cells pad the first week. */
@Immutable
data class HeatCell(val day: Int, val level: Int, val isToday: Boolean, val isFuture: Boolean, val description: String)

/** Daily-spend calendar (§10.4): five coral levels, Monday-first weeks. */
@Composable
fun CalendarHeatmap(cells: List<HeatCell?>, description: String, modifier: Modifier = Modifier) {
    val colors = ImpeesaTheme.colors
    val fills = listOf(colors.raised, colors.coral.copy(alpha = 0.22f), colors.coral.copy(alpha = 0.45f), colors.coral.copy(alpha = 0.7f), colors.coral)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, style = ImpeesaTheme.type.caption, color = colors.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 7).forEach { i ->
                    val cell = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (cell != null) {
                            val shape = RoundedCornerShape(10.dp)
                            var m = Modifier.fillMaxSize().clip(shape)
                            m = when {
                                cell.isFuture -> m.border(1.dp, colors.line, shape)
                                else -> m.background(fills[cell.level.coerceIn(0, 4)])
                            }
                            if (cell.isToday) m = m.border(2.dp, colors.textPrimary, shape)
                            Box(m, contentAlignment = Alignment.Center) {
                                Text(
                                    cell.day.toString(),
                                    style = ImpeesaTheme.type.caption,
                                    color = when {
                                        cell.isFuture -> colors.textTertiary
                                        cell.level >= 3 -> colors.ink
                                        else -> colors.textPrimary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
