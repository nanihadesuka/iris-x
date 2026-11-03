package com.nanihadesuka.irisx.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.core.designsystem.IrisXTheme
import com.nanihadesuka.irisx.core.designsystem.HeroText
import com.nanihadesuka.irisx.core.designsystem.InfoCard
import com.nanihadesuka.irisx.core.designsystem.OrbMood
import com.nanihadesuka.irisx.core.designsystem.StatusOrb
import com.nanihadesuka.irisx.domain.model.CameraCandidate
import com.nanihadesuka.irisx.domain.model.PairingStep
import org.koin.androidx.compose.koinViewModel

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
}.toTypedArray()

@Composable
fun PairingRoute(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    viewModel: PairingViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    fun basicGranted() = requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun backgroundGranted() = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    var basic by remember { mutableStateOf(basicGranted()) }
    var background by remember { mutableStateOf(backgroundGranted()) }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = backgroundGranted() }
    val basicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        basic = basicGranted()
        // Background location must be requested separately, after foreground location.
        if (basic && !backgroundGranted()) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { basic = basicGranted(); background = backgroundGranted() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(basic) {
        when {
            !basic -> viewModel.onPermissionsLost()
            viewModel.alreadyPaired() -> onFinished()
            else -> viewModel.onPermissionsReady()
        }
    }
    LaunchedEffect(state.stage) { if (state.stage == PairingStage.Done) { kotlinx.coroutines.delay(1_600); onFinished() } }

    PairingScreen(
        state = state,
        backgroundMissing = basic && !background,
        onAllow = { basicLauncher.launch(requiredPermissions()) },
        onAllowBackground = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        onPick = viewModel::pair,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    backgroundMissing: Boolean,
    onAllow: () -> Unit,
    onAllowBackground: () -> Unit,
    onPick: (CameraCandidate) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val (mood, icon) = when (state.stage) {
        PairingStage.Permissions -> OrbMood.Away to Icons.Rounded.Lock
        PairingStage.Searching -> OrbMood.Busy to Icons.Rounded.BluetoothSearching
        PairingStage.Pairing -> OrbMood.Busy to Icons.Rounded.CameraAlt
        PairingStage.Done -> OrbMood.Connected to Icons.Rounded.CheckCircle
        PairingStage.Failed -> OrbMood.Attention to Icons.Rounded.Warning
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SetupStepper(state.stage)
        Spacer(Modifier.height(16.dp))
        StatusOrb(mood = mood, icon = icon, size = 104.dp, pulsing = state.stage == PairingStage.Searching || state.stage == PairingStage.Pairing)
        Spacer(Modifier.height(16.dp))

        AnimatedContent(state.stage, transitionSpec = { (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 24 }) togetherWith fadeOut(tween(140)) }, label = "stage") { stage ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (stage) {
                    PairingStage.Permissions -> {
                        HeroText(
                            "Let Iris X work in the background",
                            "Bluetooth to talk to the camera, location to tag your photos (set it to \"Allow all the time\"), notifications for new photos, and nearby Wi-Fi for fast downloads.",
                        )
                        Button(onClick = if (backgroundMissing) onAllowBackground else onAllow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(if (backgroundMissing) "Allow location all the time" else "Allow access")
                        }
                    }
                    PairingStage.Searching -> {
                        HeroText("Put the camera in pairing mode", "Looking for your Fujifilm camera…")
                        InfoCard(
                            icon = Icons.Rounded.CameraAlt,
                            title = "On the camera",
                            subtitle = "MENU → Connection Setting → Bluetooth Settings → Pairing Register",
                        )
                        state.candidates.forEach { c -> CandidateRow(c) { onPick(c) } }
                        if (state.candidates.isEmpty()) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            var slow by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { kotlinx.coroutines.delay(SLOW_SEARCH_MS); slow = true }
                            AnimatedVisibility(slow) {
                                Text(
                                    "Still looking. Check the camera is on and showing the pairing screen, and keep the phone close.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            TextButton(onClick = onBack) { Text("Not now") }
                        }
                    }
                    PairingStage.Pairing -> {
                        HeroText("Pairing", "Confirm on the camera if it asks. Keep the phone close.")
                        PairingSteps(state.step)
                    }
                    PairingStage.Done -> HeroText("All set", "${state.camera?.name ?: "Camera"} is paired. Just shoot; I'll take it from here.")
                    PairingStage.Failed -> {
                        HeroText("That didn't work", state.error ?: "Something went wrong.")
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Try again") }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                    }
                }
            }
        }
    }
}

/** Permissions, camera, link, done: the same four steps every time. */
@Composable
private fun SetupStepper(stage: PairingStage) {
    val labels = listOf("Access", "Camera", "Link", "Done")
    val current = when (stage) {
        PairingStage.Permissions -> 0
        PairingStage.Searching, PairingStage.Failed -> 1
        PairingStage.Pairing -> 2
        PairingStage.Done -> 3
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val color by animateColorAsState(
                if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                tween(300),
                label = "step",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.fillMaxWidth().height(3.dp).background(color, MaterialTheme.shapes.small))
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val SLOW_SEARCH_MS = 20_000L

@Composable
private fun CandidateRow(candidate: CameraCandidate, onClick: () -> Unit) {
    InfoCard(
        icon = Icons.Rounded.CameraAlt,
        title = candidate.name,
        subtitle = "Tap to pair · signal ${candidate.signalDbm} dBm",
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PairingSteps(current: PairingStep?) {
    val steps = listOf(
        PairingStep.Bluetooth to "Bluetooth link",
        PairingStep.Identify to "Introducing this phone",
        PairingStep.Subscribe to "Setting up notifications",
        PairingStep.Wifi to "Remembering the camera Wi-Fi",
    )
    val currentIndex = steps.indexOfFirst { it.first == current }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, (_, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    i < currentIndex -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    i == currentIndex -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.size(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---- Previews -------------------------------------------------------------------------------

@Preview(name = "Pairing – permissions", showBackground = true, heightDp = 800)
@Composable
private fun PermissionsPreview() = IrisXTheme { PairingScreen(PairingUiState(PairingStage.Permissions), false, {}, {}, {}, {}, {}) }

@Preview(name = "Pairing – searching", showBackground = true, heightDp = 800)
@Composable
private fun SearchingPreview() = IrisXTheme {
    PairingScreen(PairingUiState(PairingStage.Searching, listOf(CameraCandidate("AA", "X100VI-1A2B", -48))), false, {}, {}, {}, {}, {})
}

@Preview(name = "Pairing – in progress", showBackground = true, heightDp = 800)
@Composable
private fun PairingPreview() = IrisXTheme { PairingScreen(PairingUiState(PairingStage.Pairing, step = PairingStep.Identify), false, {}, {}, {}, {}, {}) }

@Preview(name = "Pairing – failed", showBackground = true, heightDp = 800)
@Composable
private fun FailedPreview() = IrisXTheme { PairingScreen(PairingUiState(PairingStage.Failed, error = "Pairing was declined"), false, {}, {}, {}, {}, {}) }
