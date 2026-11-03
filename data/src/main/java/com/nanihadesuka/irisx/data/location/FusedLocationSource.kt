package com.nanihadesuka.irisx.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nanihadesuka.irisx.domain.model.LocationFix
import com.nanihadesuka.irisx.domain.repository.LocationProvider
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Battery-friendly location: reuses a recent cached fix when it is fresh enough for the chosen
 * [com.nanihadesuka.irisx.domain.model.LocationMode] and only wakes the GPS when it is stale.
 */
@SuppressLint("MissingPermission")
class FusedLocationSource(
    private val context: Context,
    private val settings: SettingsRepository,
) : LocationProvider {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun currentFix(): LocationFix? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null

        val maxAgeMs = settings.settings.first().locationMode.intervalSeconds * 1_000L
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(15_000)
            .build()
        val location = runCatching { client.getCurrentLocation(request, null).await() }.getOrNull()
            ?: runCatching { client.lastLocation.await() }.getOrNull()
            ?: return null
        return LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }
}
