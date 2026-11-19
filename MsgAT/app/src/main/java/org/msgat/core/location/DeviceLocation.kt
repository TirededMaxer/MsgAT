// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.msgat.core.network.LocationPoint
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal fun locationFromDegrees(latitude: Double, longitude: Double): LocationPoint? {
    if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
        !longitude.isFinite() || longitude !in -180.0..180.0) return null
    return LocationPoint((latitude * 1_000_000).roundToInt(), (longitude * 1_000_000).roundToInt())
}

internal fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal suspend fun currentLocation(context: Context, providerTimeoutMs: Long = 12_000): Location? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    if (!manager.isLocationEnabled) return null
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val candidates = if (fine) listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf("fused", LocationManager.NETWORK_PROVIDER)
    val providers = manager.getProviders(true)
    val executor = ContextCompat.getMainExecutor(context)
    for (provider in candidates.filter(providers::contains)) {
        val location = withTimeoutOrNull(providerTimeoutMs) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellation = androidx.core.os.CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, executor) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
        if (location != null) return location
    }
    return null
}
