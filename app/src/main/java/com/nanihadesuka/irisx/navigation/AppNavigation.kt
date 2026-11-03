package com.nanihadesuka.irisx.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import com.nanihadesuka.irisx.feature.home.MainShell
import com.nanihadesuka.irisx.feature.pairing.PairingRoute
import com.nanihadesuka.irisx.feature.settings.SettingsRoute
import kotlinx.serialization.Serializable

@Serializable data object MainKey : NavKey
@Serializable data object PairingKey : NavKey

/**
 * Navigation 3: the back stack is plain state we own. The bottom-bar tabs live inside [MainShell], with the photo viewer; pairing
 * covers it. First run (no camera yet) starts on the pairing flow so a new user never has to find a button.
 */
@Composable
fun AppNavigation(startWithPairing: Boolean) {
    val backStack = rememberNavBackStack(*(if (startWithPairing) arrayOf<NavKey>(PairingKey) else arrayOf<NavKey>(MainKey)))

    fun goMainClearing() {
        backStack.clear()
        backStack.add(MainKey)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else if (backStack.lastOrNull() == PairingKey) goMainClearing() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(240)) },
        popTransitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(240)) },
        entryProvider = entryProvider {
            entry<MainKey> {
                MainShell(
                    onPair = { backStack.add(PairingKey) },
                    settings = {
                        SettingsRoute(onPairCamera = { backStack.add(PairingKey) }, onCameraForgotten = {})
                    },
                )
            }
            entry<PairingKey> {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    PairingRoute(onFinished = ::goMainClearing, onBack = ::goMainClearing)
                }
            }
        },
    )
}
