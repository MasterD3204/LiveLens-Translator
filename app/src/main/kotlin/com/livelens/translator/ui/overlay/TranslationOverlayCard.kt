package com.livelens.translator.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livelens.translator.model.TranslationMode
import com.livelens.translator.service.TranslationManager
import com.livelens.translator.service.TranslationSentence
import kotlinx.coroutines.delay

/**
 * The expanded translation overlay card shown at the bottom of the screen.
 *
 * Shows:
 * - Last 3 translated sentences (newest on top)
 * - Mode toggle strip
 * - Active recording indicator
 * - Minimize button
 *
 * Each sentence auto-dismisses after 8 seconds.
 */
@Composable
fun TranslationOverlayCard(
    translationManager: TranslationManager,
    currentMode: TranslationMode,
    onModeChange: (TranslationMode) -> Unit,
    onMinimize: () -> Unit
) {
    val sentences by translationManager.sentences.collectAsState()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xBF000000),  // Black 75% opacity
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ── Controls strip ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode toggles
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeToggleButton(
                        label = "🎤",
                        selected = currentMode == TranslationMode.CONVERSATION,
                        onClick = { onModeChange(TranslationMode.CONVERSATION) }
                    )
                    ModeToggleButton(
                        label = "📱",
                        selected = currentMode == TranslationMode.MEDIA,
                        onClick = { onModeChange(TranslationMode.MEDIA) }
                    )
                    ModeToggleButton(
                        label = "📷",
                        selected = currentMode == TranslationMode.IMAGE,
                        onClick = { onModeChange(TranslationMode.IMAGE) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing recording indicator
                    RecordingIndicator()

                    // Minimize button
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Minimize,
                            contentDescription = "Minimize",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (sentences.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))

                // ── Sentence list ───────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sentences.takeLast(3).reversed().forEach { sentence ->
                        SentenceItem(
                            sentence = sentence,
                            onDismiss = { translationManager.dismissSentence(sentence.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) Color(0xFF1565C0) else Color(0x33FFFFFF)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(Modifier.size(width = 36.dp, height = 28.dp))
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.matchParentSize()
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = Color.White
            )
        }
    }
}

@Composable
private fun RecordingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = alpha))
    )
}

@Composable
private fun SentenceItem(
    sentence: TranslationSentence,
    onDismiss: () -> Unit
) {
    // Auto-dismiss after 8 seconds when not streaming
    if (!sentence.isStreaming) {
        LaunchedEffect(sentence.id) {
            delay(8000)
            onDismiss()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1AFFFFFF))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Speaker badge (if diarization active)
        if (sentence.speakerLabel != null) {
            SpeakerBadge(label = sentence.speakerLabel)
            Spacer(Modifier.height(2.dp))
        }

        // Vietnamese translation (main, white, 16sp)
        Text(
            text = if (sentence.translatedText.isEmpty() && sentence.isStreaming)
                "●●●" else sentence.translatedText,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        // Original English (secondary, gray, 12sp)
        Text(
            text = sentence.originalText,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpeakerBadge(label: String) {
    val color = if (label == "A") Color(0xFF2196F3) else Color(0xFF4CAF50)  // Blue / Green
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Speaker $label",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
