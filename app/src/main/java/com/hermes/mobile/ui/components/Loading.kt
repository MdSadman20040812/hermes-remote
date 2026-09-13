package com.hermes.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Waiting, made legible.
 *
 * A phone driving a desktop agent is a distributed system, and CAP is not
 * negotiable: when the link is partitioned or the desktop is mid-inference, the
 * client cannot have a fresh answer AND an instant one. That latency is
 * structural, so the honest move is to make the wait *readable* rather than
 * pretend it is not happening.
 *
 * A stalled spinner communicates "possibly broken". These communicate
 * "working, and here is roughly where" — motion that is clearly alive, plus a
 * label that names the phase. Every animation below is:
 *
 *  - **Cheap.** Pure Canvas draw on values Compose is already animating. No
 *    layout pass per frame, no bitmap, no recomposition of the transcript.
 *  - **Non-blocking.** Purely decorative; it never gates the UI thread and it
 *    never blocks a result arriving early.
 *  - **Endless-safe.** No animation implies a completion percentage it cannot
 *    know — a fake progress bar that sticks at 90% is worse than no bar.
 */

/** Shared timing so every waiting state in the app feels like one system. */
private const val PULSE_MS = 1500
private const val ORBIT_MS = 2600
private const val SHIMMER_MS = 1400

/**
 * The signature waiter: three dots that breathe along a sine offset.
 *
 * Used inline wherever a short reply is expected. The phase offset per dot is
 * what stops it reading as a mechanical loop.
 */
@Composable
fun BreathingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dot: androidx.compose.ui.unit.Dp = 7.dp,
) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
        ),
        label = "phase",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val s = (sin(phase - i * 0.7f) + 1f) / 2f          // 0..1
            Canvas(Modifier.size(dot)) {
                drawCircle(
                    color = color.copy(alpha = 0.35f + 0.65f * s),
                    radius = size.minDimension / 2f * (0.62f + 0.38f * s),
                )
            }
        }
    }
}

/**
 * A slow orbital sweep for longer waits (inference, a large transfer).
 *
 * Two counter-rotating arcs on a faint track. Counter-rotation is deliberate:
 * a single spinning arc at low speed is ambiguous with a frozen frame, whereas
 * two arcs crossing each other is unmistakably live even at a glance.
 */
@Composable
fun OrbitSpinner(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val t = rememberInfiniteTransition(label = "orbit")
    val a by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(ORBIT_MS, easing = LinearEasing)),
        label = "outer",
    )
    val b by t.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween((ORBIT_MS * 1.45f).toInt(), easing = LinearEasing)),
        label = "inner",
    )
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.075f
        drawCircle(
            color = color.copy(alpha = 0.13f),
            radius = this.size.minDimension / 2f - stroke,
            style = Stroke(width = stroke),
        )
        rotate(a) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.9f), Color.Transparent),
                ),
                startAngle = 0f, sweepAngle = 110f, useCenter = false,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(stroke, stroke),
                size = Size(this.size.width - stroke * 2, this.size.height - stroke * 2),
            )
        }
        rotate(b) {
            val inset = stroke * 3.4f
            drawArc(
                color = color.copy(alpha = 0.45f),
                startAngle = 40f, sweepAngle = 70f, useCenter = false,
                style = Stroke(width = stroke * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
            )
        }
    }
}

/**
 * Skeleton shimmer for content whose SHAPE is known but whose bytes are not
 * (a transcript being restored, a file listing, an artifact compiling).
 *
 * Showing the coming layout instead of a spinner removes the layout jump when
 * data lands, which is the part users actually perceive as slowness.
 */
@Composable
fun ShimmerLine(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 12.dp,
    widthFraction: Float = 1f,
) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -1f, 2f,
        infiniteRepeatable(tween(SHIMMER_MS, easing = FastOutSlowInEasing)),
        label = "sweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val hi = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Canvas(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height),
    ) {
        drawRoundRect(
            color = base,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, hi, Color.Transparent),
                startX = size.width * (x - 0.35f),
                endX = size.width * (x + 0.35f),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
    }
}

/** A paragraph-shaped skeleton — ragged widths so it reads as prose, not bars. */
@Composable
fun ShimmerParagraph(lines: Int = 3, modifier: Modifier = Modifier) {
    val widths = remember(lines) { listOf(1f, 0.92f, 0.66f, 0.85f, 0.55f) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i -> ShimmerLine(widthFraction = widths[i % widths.size]) }
    }
}

/**
 * The full waiting state: motion plus a phase label.
 *
 * [phase] should name what is actually happening ("compiling", "thinking",
 * "uploading"). A label that changes as the work progresses is what separates
 * "slow but fine" from "hung" in the user's head — and it is honest, because
 * it reports a real state transition rather than an invented percentage.
 */
@Composable
fun WaitingState(
    phase: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    compact: Boolean = false,
) {
    if (compact) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            BreathingDots()
            Spacer(Modifier.width(10.dp))
            Text(
                phase,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitSpinner()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                phase,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Determinate progress for transfers, where a real fraction exists.
 *
 * Kept visually distinct from the indeterminate states above: a filling track
 * means "this number is measured". Never use it for inference.
 */
@Composable
fun GradientProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) { anim.animateTo(clamped, tween(320, easing = FastOutSlowInEasing)) }

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        if (anim.value > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(c2, c1)),
                size = Size(size.width * anim.value, size.height),
                cornerRadius = r,
            )
        }
    }
}

/**
 * Connection heartbeat for the app bar: a dot whose pulse rate encodes health.
 *
 * Steady slow pulse = connected. Fast anxious pulse = reconnecting. Static =
 * offline. Encoding state in *motion* rather than colour alone keeps it
 * readable for colour-blind users and at a glance in peripheral vision.
 */
@Composable
fun StatusPulse(
    connected: Boolean,
    reconnecting: Boolean = false,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val color = when {
        reconnecting -> MaterialTheme.colorScheme.tertiary
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    if (!connected && !reconnecting) {
        Canvas(modifier.size(size)) { drawCircle(color) }
        return
    }
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(if (reconnecting) 620 else 2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "beat",
    )
    Canvas(modifier.size(size * 2f)) {
        val r = this.size.minDimension / 4f
        drawCircle(color = color.copy(alpha = 0.22f * (1f - p)), radius = r * (1f + p * 1.5f))
        drawCircle(color = color.copy(alpha = 0.55f + 0.45f * abs(p)), radius = r)
    }
}
