package com.nanihadesuka.irisx.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Short confirmations, shown by the app itself so they match it, rather than as system toasts. */
class AppMessages {
    var text by mutableStateOf<String?>(null)
        private set
    var shown by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        text = message
        shown++
    }

    fun hide() {
        text = null
    }
}

val LocalAppMessages = staticCompositionLocalOf { AppMessages() }

@Composable
fun AppMessageHost(messages: AppMessages, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("") }
    messages.text?.let { last = it }
    LaunchedEffect(messages.shown) {
        delay(MESSAGE_MS)
        messages.hide()
    }
    AnimatedVisibility(
        messages.text != null,
        modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
        exit = fadeOut(tween(220)),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f), contentColor = MaterialTheme.colorScheme.onSurface) {
            Text(last, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

enum class MediaFilter(val label: String) { All("All"), Photos("Photos"), Videos("Videos") }

/** [isVideo] null means not known yet: such an item only shows under All. */
fun MediaFilter.accepts(isVideo: Boolean?) = when (this) {
    MediaFilter.All -> true
    MediaFilter.Photos -> isVideo == false
    MediaFilter.Videos -> isVideo == true
}

@Composable
fun MediaFilterRow(selected: MediaFilter, onSelect: (MediaFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaFilter.entries.forEach { option ->
            FilterChip(option == selected, { onSelect(option) }, { Text(option.label, style = MaterialTheme.typography.labelMedium) })
        }
    }
}

private const val MESSAGE_MS = 2_500L
