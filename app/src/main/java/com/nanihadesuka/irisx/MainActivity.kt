package com.nanihadesuka.irisx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.nanihadesuka.irisx.core.designsystem.IrisXTheme
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.navigation.AppNavigation
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val cameras: CameraRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            IrisXTheme {
                // null while we look up whether a camera is already paired.
                val firstRun by produceState<Boolean?>(null) { value = cameras.current() == null }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    firstRun?.let { AppNavigation(startWithPairing = it) }
                }
            }
        }
    }
}
