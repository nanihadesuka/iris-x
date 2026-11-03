package com.nanihadesuka.irisx.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What the orb should look like. */
enum class OrbMood { Connected, Busy, Away, Attention }

fun OrbMood.color(): Color = when (this) {
    OrbMood.Connected -> StatusColors.Connected
    OrbMood.Busy -> StatusColors.Busy
    OrbMood.Away -> StatusColors.Away
    OrbMood.Attention -> StatusColors.Attention
}

/**
 * The large, living status indicator. Pulses while [pulsing], glows softly when idle-connected.
 * Progress (0..1) draws a ring around the orb while downloading.
 */
@Composable
fun StatusOrb(
    mood: OrbMood,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    pulsing: Boolean = mood == OrbMood.Busy,
    progress: Float? = null,
) {
    val color by animateColorAsState(mood.color(), spring(stiffness = Spring.StiffnessLow), label = "orb")
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = if (pulsing) 1.08f else 0.96f,
        animationSpec = infiniteRepeatable(tween(if (pulsing) 900 else 2400), RepeatMode.Reverse),
        label = "pulseScale",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val radius = this.size.minDimension / 2f
            drawCircle(SolidColor(color.copy(alpha = 0.12f)), radius = radius * pulse)
            drawCircle(SolidColor(color.copy(alpha = 0.22f)), radius = radius * 0.78f * pulse)
            drawCircle(SolidColor(color), radius = radius * 0.56f, center = Offset(center.x, center.y))
            if (progress != null) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(radius * 0.06f, radius * 0.06f),
                    size = androidx.compose.ui.geometry.Size(this.size.width * 0.94f, this.size.height * 0.94f),
                )
            }
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.26f))
    }
}

/** Rounded card with a leading icon, used for stats and hints. */
@Composable
fun InfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(44.dp)) { drawCircle(accent.copy(alpha = 0.16f)) }
                Icon(icon, null, tint = accent)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Centered headline + supporting text used on onboarding-style screens. */
@Composable
fun HeroText(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
    }
}
