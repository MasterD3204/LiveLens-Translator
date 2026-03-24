package com.livelens.translator.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The collapsed floating bubble shown when overlay is minimized.
 * 56dp circular FAB with the app icon. Pulses when expanded (recording).
 */
@Composable
fun FloatingBubble(
    onTap: () -> Unit,
    isExpanded: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isExpanded) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = if (isExpanded) Color(0xFF1565C0) else Color(0xFF0D47A1),
        animationSpec = tween(300),
        label = "bg_color"
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bgColor)
            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Translate,
            contentDescription = "LiveLens",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}
