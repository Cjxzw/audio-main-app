package com.agent.voiceassistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.agent.voiceassistant.data.StoredLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    suspend fun currentLocation(
        timeoutMs: Long = 5_000L,
        forceFresh: Boolean = false,
    ): StoredLocation? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return@withContext null

        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        val fresh = requestBestFreshLocation(manager, providers, timeoutMs)
        val usableLastKnown = lastKnown?.takeUnless {
            forceFresh && System.currentTimeMillis() - it.time > MAX_FORCE_FRESH_FALLBACK_AGE_MS
        }
        val chosen = listOfNotNull(fresh, usableLastKnown).maxByOrNull { it.time } ?: return@withContext null
        chosen.toStoredLocation()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestBestFreshLocation(
        manager: LocationManager,
        providers: List<String>,
        timeoutMs: Long,
    ): Location? {
        val perProviderTimeout = (timeoutMs / providers.size.coerceAtLeast(1)).coerceAtLeast(1_500L)
        val locations = providers.mapNotNull { provider ->
            withTimeoutOrNull(perProviderTimeout) {
                requestFreshLocation(manager, provider)
            }
        }
        return locations.maxWithOrNull(
            compareBy<Location> { it.time }
                .thenByDescending { if (it.hasAccuracy()) -it.accuracy else Float.NEGATIVE_INFINITY },
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(
        manager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            val executor = Executor { command -> command.run() }
            manager.getCurrentLocation(provider, signal, executor) { location ->
                if (cont.isActive) cont.resume(location)
            }
            cont.invokeOnCancellation { signal.cancel() }
            return@suspendCancellableCoroutine
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                manager.removeUpdates(this)
                if (cont.isActive) cont.resume(null)
            }
        }
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        cont.invokeOnCancellation { manager.removeUpdates(listener) }
    }

    private fun Location.toStoredLocation(): StoredLocation {
        val address = resolveAddress(latitude, longitude)
        return StoredLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            provider = provider,
            address = address,
            timestamp = System.currentTimeMillis(),
            sourceTimestamp = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

    private fun resolveAddress(latitude: Double, longitude: Double): String? {
        return runCatching {
            val geocoder = Geocoder(context, Locale.CHINA)
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            listOfNotNull(
                address?.adminArea,
                address?.locality,
                address?.subLocality,
                address?.thoroughfare,
            ).distinct().joinToString("").takeIf { it.isNotBlank() }
        }.onFailure {
            Timber.d("Location reverse geocode failed: ${it.message}")
        }.getOrNull()
    }

    private companion object {
        private const val MAX_FORCE_FRESH_FALLBACK_AGE_MS = 2 * 60 * 1000L
    }
}
