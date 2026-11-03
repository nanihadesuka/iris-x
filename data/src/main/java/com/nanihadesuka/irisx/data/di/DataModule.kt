package com.nanihadesuka.irisx.data.di

import com.nanihadesuka.irisx.data.background.CompanionPresence
import com.nanihadesuka.irisx.data.background.SyncEngine
import com.nanihadesuka.irisx.data.background.SyncNotifications
import com.nanihadesuka.irisx.data.background.SyncServiceController
import com.nanihadesuka.irisx.data.ble.FujiCameraConnection
import com.nanihadesuka.irisx.data.ble.FujiCameraPairing
import com.nanihadesuka.irisx.data.location.FusedLocationSource
import com.nanihadesuka.irisx.data.storage.DataStoreCameraRepository
import com.nanihadesuka.irisx.data.storage.DataStoreDownloadLedger
import com.nanihadesuka.irisx.data.storage.DataStoreSettingsRepository
import com.nanihadesuka.irisx.data.storage.FileCatalogCache
import com.nanihadesuka.irisx.data.storage.InMemoryDiagnosticsLog
import com.nanihadesuka.irisx.data.storage.InMemorySyncActivity
import com.nanihadesuka.irisx.data.storage.MediaStorePhotoStore
import com.nanihadesuka.irisx.data.wifi.CameraWifi
import com.nanihadesuka.irisx.data.wifi.WifiPhotoTransferGateway
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraPairing
import com.nanihadesuka.irisx.domain.repository.CameraPresence
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.CatalogCache
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.domain.repository.DownloadLedger
import com.nanihadesuka.irisx.domain.repository.LocationProvider
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.domain.repository.PhotoTransferGateway
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.repository.SyncController
import com.nanihadesuka.irisx.domain.usecase.CameraBrowser
import com.nanihadesuka.irisx.domain.usecase.PhotoCompressor
import com.nanihadesuka.irisx.domain.usecase.VideoCompressor
import com.nanihadesuka.irisx.domain.usecase.DownloadNewPhotosUseCase
import com.nanihadesuka.irisx.domain.usecase.ForgetCameraUseCase
import com.nanihadesuka.irisx.domain.usecase.SyncLocationUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val dataModule = module {
    single<CameraRepository> { DataStoreCameraRepository(androidContext()) }
    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }
    single<DownloadLedger> { DataStoreDownloadLedger(androidContext()) }
    single<PhotoStore> { MediaStorePhotoStore(androidContext()) }
    single<SyncActivity> { InMemorySyncActivity() }
    single<DiagnosticsLog> { InMemoryDiagnosticsLog() }
    single<LocationProvider> { FusedLocationSource(androidContext(), get()) }

    single { CameraWifi(androidContext(), get()) }
    single<CameraConnection> { FujiCameraConnection(androidContext(), get(), get()) }
    single<CameraPairing> { FujiCameraPairing(androidContext(), get(), get(), get()) }
    single<PhotoTransferGateway> {
        WifiPhotoTransferGateway(androidContext(), get(), get(), get(), com.nanihadesuka.irisx.camera.sdk.KotlinCameraBackend())
    }

    single { SyncNotifications(androidContext()) }
    single<SyncController> { SyncServiceController(androidContext()) }
    single<CameraPresence> { CompanionPresence(androidContext(), get()) }

    factory { DownloadNewPhotosUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { SyncLocationUseCase(get(), get()) }
    single<CatalogCache> { FileCatalogCache(androidContext()) }
    single { CameraBrowser(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PhotoCompressor(get(), get()) }
    single { VideoCompressor(get()) }
    factory { ForgetCameraUseCase(get(), get(), get(), get(), get(), get()) }
    single { SyncEngine(get(), get(), get(), get(), get(), get(), get()) }
}
