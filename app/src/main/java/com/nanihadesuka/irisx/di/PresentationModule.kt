package com.nanihadesuka.irisx.di

import com.nanihadesuka.irisx.feature.home.CameraViewModel
import com.nanihadesuka.irisx.feature.home.HomeViewModel
import com.nanihadesuka.irisx.feature.home.LibraryViewModel
import com.nanihadesuka.irisx.feature.pairing.PairingViewModel
import com.nanihadesuka.irisx.feature.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val presentationModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::CameraViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::PairingViewModel)
    viewModelOf(::SettingsViewModel)
}
