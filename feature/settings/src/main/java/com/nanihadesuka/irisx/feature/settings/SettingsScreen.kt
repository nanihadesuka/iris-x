package com.nanihadesuka.irisx.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.core.designsystem.IrisXTheme
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CompressionMode
import com.nanihadesuka.irisx.domain.model.LocationMode
import com.nanihadesuka.irisx.domain.model.estimatedCompressedSize
import kotlin.math.roundToInt
import com.nanihadesuka.irisx.domain.model.Settings
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsRoute(
    onPairCamera: () -> Unit,
    onCameraForgotten: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wakeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    SettingsScreen(
        state = state,
        onPairCamera = onPairCamera,
        onPaused = viewModel::setPaused,
        onEnableWake = {
            scope.launch { viewModel.wakeRequest()?.let { wakeLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
        },
        onDownloadMode = viewModel::setDownloadMode,
        onSquareGrid = viewModel::setSquareGrid,
        onPhotoGrouping = viewModel::setPhotoGrouping,
        onAutoOriginals = viewModel::setAutoOriginals,
        onLocationMode = viewModel::setLocationMode,
        onForget = { viewModel.forget(); onCameraForgotten() },
        onCompression = viewModel::updateCompression,
        onBatterySettings = {
            val pm = context.getSystemService(PowerManager::class.java)
            val intent = if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            }
            runCatching { context.startActivity(intent) }
        },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onPairCamera: () -> Unit,
    onPaused: (Boolean) -> Unit,
    onEnableWake: () -> Unit,
    onDownloadMode: (com.nanihadesuka.irisx.domain.model.DownloadMode) -> Unit,
    onAutoOriginals: (Boolean) -> Unit,
    onLocationMode: (LocationMode) -> Unit,
    onForget: () -> Unit,
    onBatterySettings: () -> Unit,
    onCompression: ((Settings) -> Settings) -> Unit = {},
    onSquareGrid: (Boolean) -> Unit = {},
    onPhotoGrouping: (com.nanihadesuka.irisx.domain.model.PhotoGrouping) -> Unit = {},
) {
    var confirmForget by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 6.dp, top = 6.dp))

        // What people change: all on one screen, no tap needed. Everything else folds away below it.
        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                SettingRow(
                    icon = Icons.Rounded.Sync,
                    title = "Stay connected",
                    subtitle = if (state.settings.syncPaused) "Paused: the camera is left alone" else "Reconnects when the camera is on",
                    trailing = { Switch(!state.settings.syncPaused, { onPaused(!it) }, Modifier.scale(0.85f)) },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.CloudDownload,
                    title = "Get new photos",
                    subtitle = when (state.settings.downloadMode) {
                        com.nanihadesuka.irisx.domain.model.DownloadMode.WhenOpened -> "When you open Iris X, so the camera stays free"
                        com.nanihadesuka.irisx.domain.model.DownloadMode.Automatic -> "As you shoot. Uses the most battery"
                        com.nanihadesuka.irisx.domain.model.DownloadMode.OnlyWhenAsked -> "When you refresh or download. Uses the least"
                    },
                ) {
                    Segmented(
                        options = com.nanihadesuka.irisx.domain.model.DownloadMode.entries,
                        selected = { it == state.settings.downloadMode },
                        label = {
                            when (it) {
                                com.nanihadesuka.irisx.domain.model.DownloadMode.WhenOpened -> "On open"
                                com.nanihadesuka.irisx.domain.model.DownloadMode.Automatic -> "Always"
                                com.nanihadesuka.irisx.domain.model.DownloadMode.OnlyWhenAsked -> "Ask"
                            }
                        },
                        onSelect = onDownloadMode,
                    )
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.LocationOn,
                    title = "Send location",
                    subtitle = if (state.settings.locationMode == LocationMode.Off) "Off: new photos get no location from the phone" else "Tags new photos. More often uses more battery",
                ) {
                    Segmented(
                        options = LocationMode.entries,
                        selected = { it == state.settings.locationMode },
                        label = { if (it == LocationMode.Off) "Off" else "${it.intervalSeconds / 60} min" },
                        onSelect = onLocationMode,
                    )
                }
            }
        }

        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Fold(Icons.Rounded.GridView, "Gallery", "${state.settings.photoGrouping.name}, ${if (state.settings.squareGrid) "square" else "whole photos"}") {
                    SettingRow(
                        icon = Icons.Rounded.GridView,
                        title = "Square thumbnails",
                        subtitle = if (state.settings.squareGrid) "Grids fill each cell, cropping the edges" else "Grids show the whole photo",
                        trailing = { Switch(state.settings.squareGrid, onSquareGrid, Modifier.scale(0.85f)) },
                    )
                    RowDivider()
                    SettingRow(
                        icon = Icons.Rounded.GridView,
                        title = "Group photos by",
                        subtitle = if (state.settings.photoGrouping == com.nanihadesuka.irisx.domain.model.PhotoGrouping.Days) "A very full day splits into morning, midday, evening and night" else "Sections on the Camera and Library tabs",
                    ) {
                        Segmented(
                            options = com.nanihadesuka.irisx.domain.model.PhotoGrouping.entries,
                            selected = { it == state.settings.photoGrouping },
                            label = { it.name },
                            onSelect = onPhotoGrouping,
                        )
                    }
                }
                RowDivider()
                Fold(Icons.Rounded.HighQuality, "Quality and compression", if (state.settings.autoOriginals) "Originals, compressed by your settings" else "Compressed by your settings") {
                    SettingRow(
                        icon = Icons.Rounded.HighQuality,
                        title = "Download originals automatically",
                        subtitle = "Replaces a reduced copy with the full file; uses more Wi-Fi and battery",
                        trailing = { Switch(state.settings.autoOriginals, onAutoOriginals, Modifier.scale(0.85f)) },
                    )
                    RowDivider()
                    CompressionSettings(state, onCompression)
                }
                RowDivider()
                Fold(Icons.Rounded.CameraAlt, "Camera", state.camera?.name ?: "None paired") {
                    if (state.camera == null) {
                        SettingRow(
                            icon = Icons.Rounded.CameraAlt,
                            title = "No camera paired",
                            subtitle = "Tap to set one up",
                            onClick = onPairCamera,
                        )
                    } else {
                        SettingRow(
                            icon = Icons.Rounded.CameraAlt,
                            title = state.camera.name,
                            subtitle = state.camera.wifiSsid?.let { "Wi-Fi: $it" } ?: "Wi-Fi details read from the camera",
                        )
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.Bolt,
                            title = "Wake on camera power-on",
                            subtitle = if (state.wakeActive) "On: the system wakes Iris X when it sees the camera" else "Off: tap to allow, with the camera on",
                            onClick = if (state.wakeActive) null else onEnableWake,
                        )
                        RowDivider()
                        SettingRow(
                            icon = Icons.Rounded.LinkOff,
                            title = "Unpair",
                            subtitle = "Photos stay on the camera and the phone",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmForget = true },
                        )
                    }
                }
                RowDivider()
                Fold(Icons.Rounded.BatteryChargingFull, "Reliability and diagnostics", "Battery settings, activity log") {
                    SettingRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "Unrestricted battery",
                        subtitle = "Keeps the connection alive in the background",
                        onClick = onBatterySettings,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    RowDivider()
            SettingRow(
                icon = Icons.Rounded.Terminal,
                title = "Activity log",
                subtitle = "${state.log.size} entries",
                onClick = { showLog = !showLog },
                trailing = {
                    Icon(
                        if (showLog) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (showLog) {
                RowDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (state.log.isEmpty()) {
                        Text(
                            "Nothing logged yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.log.take(60).forEach {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Unpair ${state.camera?.name ?: "camera"}?") },
            text = {
                Text(
                    "Iris X stops the connection and this phone forgets the camera. No photos are deleted.\n\n" +
                        "Then remove this phone from the camera's own Bluetooth pairing list too.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

/** One line in the "more" card, with what is set inside; a tap opens it in place. */
@Composable
private fun Fold(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chevron")
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal, color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Close $title" else "Open $title", Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(open, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(120))) {
        // A recessed panel with smaller rows, so what is inside reads as belonging to the line above.
        Column(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalNestedRow provides true) { content() }
        }
    }
}

private val LocalNestedRow = androidx.compose.runtime.compositionLocalOf { false }

/** Hairline between rows, inset to line up with the row text. */
@Composable
private fun RowDivider() = HorizontalDivider(
    Modifier.padding(start = 46.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
)

/**
 * A dense settings row: 20 dp icon, title, optional subtitle, and either a [trailing] control or a block of
 * [content] underneath (used for the segmented choices).
 */
@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val nested = LocalNestedRow.current
            Icon(icon, null, Modifier.size(if (nested) 16.dp else 20.dp), tint = if (nested && tint == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onSurfaceVariant else tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (nested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (content != null) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** How Compress behaves in Library and the photo view. Nothing changes until a photo is compressed there. */
@Composable
private fun CompressionSettings(state: SettingsUiState, onChange: ((Settings) -> Settings) -> Unit) {
    val s = state.settings
    SettingRow(
        icon = Icons.Rounded.Compress,
        title = "Compress when downloading",
        subtitle = "Replaces the phone copy once saved. The camera keeps its own",
        trailing = { Switch(s.compressOnDownload, { on -> onChange { it.copy(compressOnDownload = on) } }, Modifier.scale(0.85f)) },
    )
    RowDivider()
    SettingRow(
        icon = Icons.Rounded.Compress,
        title = "Compress when sending to Google Photos",
        subtitle = "Uses the method below before the photo is queued",
        trailing = { Switch(s.compressOnBackup, { on -> onChange { it.copy(compressOnBackup = on) } }, Modifier.scale(0.85f)) },
    )
    RowDivider()
    SettingRow(icon = Icons.Rounded.Compress, title = "Compress method", subtitle = "Used by Compress in Library and the photo view") {
        Segmented(
            CompressionMode.entries,
            selected = { it == s.compressionMode },
            label = {
                when (it) {
                    CompressionMode.TargetSize -> "Target size"
                    CompressionMode.Percent -> "Percent"
                    CompressionMode.Manual -> "Manual"
                }
            },
            onSelect = { mode -> onChange { it.copy(compressionMode = mode) } },
        )
        when (s.compressionMode) {
            CompressionMode.TargetSize -> LabeledSlider("Size per photo: ${"%.1f".format(s.compressionTargetKb / 1024f)} MB", s.compressionTargetKb, 256..5120, 256) { v ->
                onChange { it.copy(compressionTargetKb = v) }
            }
            CompressionMode.Percent -> LabeledSlider("Reduce each photo by ${s.compressionPercent}%", s.compressionPercent, 10..90, 5) { v ->
                onChange { it.copy(compressionPercent = v) }
            }
            CompressionMode.Manual -> {
                LabeledSlider("Longest side: ${s.compressionMaxEdge} px", s.compressionMaxEdge, 1024..7728, 256) { v ->
                    onChange { it.copy(compressionMaxEdge = v) }
                }
                LabeledSlider("JPEG quality: ${s.compressionQuality}", s.compressionQuality, 40..95, 5) { v ->
                    onChange { it.copy(compressionQuality = v) }
                }
            }
        }
        val before = state.photoSizes.sum()
        val after = state.photoSizes.sumOf { s.estimatedCompressedSize(it) }
        Text(
            if (state.photoSizes.isEmpty()) "No uncompressed photos on this phone"
            else "${state.photoSizes.size} uncompressed photos: ${megabytes(before)} to about ${megabytes(after)}, saving about ${megabytes(before - after)}",
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, range: IntRange, step: Int, onValue: (Int) -> Unit) {
    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = value.toFloat(),
        onValueChange = { onValue(((it - range.first) / step).roundToInt() * step + range.first) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

private fun megabytes(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

/** Full-width segmented control: denser than a chip row and it never clips on a narrow screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(36.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected(option),
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label(option), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
        }
    }
}

private val LocationMode.label: String
    get() = when (this) {
        LocationMode.Off -> "Off"
        LocationMode.BatterySaver -> "Battery saver"
        LocationMode.Balanced -> "Balanced"
        LocationMode.Precise -> "Precise"
    }

@Preview(name = "Settings", showBackground = true, heightDp = 1000)
@Composable
private fun SettingsPreview() = IrisXTheme {
    SettingsScreen(
        SettingsUiState(
            Settings(),
            Camera("AA", "X100VI", "FUJIFILM-X100VI-1A2B"),
            listOf("12:01:10  Connected to X100VI", "12:01:14  Location sent 51.50000, -0.12000"),
        ),
        {}, {}, {}, {}, {}, {}, {}, {},
    )
}
