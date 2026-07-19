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
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.StoredLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class LocationProvider(
    private val context: Context,
    private val store: ConversationStore,
) {

    enum class RefreshState {
        IDLE,
        REQUESTING,
        COOLDOWN,
        TIMEOUT,
        FAILED,
    }

    data class RefreshSnapshot(
        val state: RefreshState,
        val location: StoredLocation?,
        val startedAt: Long? = null,
        val completedAt: Long? = null,
        val nextRefreshAt: Long? = null,
        val error: String? = null,
    ) {
        val isRefreshing: Boolean get() = state == RefreshState.REQUESTING
    }

    private val stateLock = Any()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private var refreshState = RefreshState.IDLE
    private var refreshStartedAt: Long? = null
    private var refreshCompletedAt: Long? = null
    private var refreshError: String? = null
    private var retryAfterAt: Long? = null

    fun close() {
        refreshScope.coroutineContext[Job]?.cancel()
    }

    /** Starts a best-effort refresh without blocking the caller. */
    fun refreshInBackground(reason: String): RefreshSnapshot {
        synchronized(stateLock) {
            val cached = store.lastLocation()
            val now = System.currentTimeMillis()
            refreshJob?.let { if (it.isActive) return snapshotLocked(cached) }

            val nextRefreshAt = cached?.timestamp?.plus(SUCCESS_COOLDOWN_MS)
            val failureRetryAt = retryAfterAt
            if ((nextRefreshAt != null && now < nextRefreshAt) ||
                (failureRetryAt != null && now < failureRetryAt)
            ) {
                refreshState = RefreshState.COOLDOWN
                return snapshotLocked(cached)
            }

            refreshStartedAt = now
            refreshCompletedAt = null
            refreshError = null
            refreshState = RefreshState.REQUESTING
            Timber.i("Location refresh started reason=$reason timeoutMs=$LOCATION_TIMEOUT_MS")
            refreshJob = refreshScope.launch {
                val result = runCatching {
                    currentLocation(timeoutMs = LOCATION_TIMEOUT_MS, forceFresh = true)
                }
                val location = result.getOrNull()
                if (location != null) store.setLocation(location)
                synchronized(stateLock) {
                    refreshCompletedAt = System.currentTimeMillis()
                    refreshError = result.exceptionOrNull()?.message
                    refreshState = when {
                        location != null -> {
                            retryAfterAt = null
                            RefreshState.COOLDOWN
                        }
                        result.exceptionOrNull() is LocationTimeoutException -> {
                            retryAfterAt = System.currentTimeMillis() + FAILURE_RETRY_COOLDOWN_MS
                            RefreshState.TIMEOUT
                        }
                        result.isFailure -> {
                            retryAfterAt = System.currentTimeMillis() + FAILURE_RETRY_COOLDOWN_MS
                            RefreshState.FAILED
                        }
                        else -> RefreshState.FAILED
                    }
                    refreshJob = null
                    Timber.i(
                        "Location refresh finished state=$refreshState " +
                            "elapsedMs=${refreshCompletedAt!! - (refreshStartedAt ?: refreshCompletedAt!!)} " +
                            "hasLocation=${location != null} error=${refreshError.orEmpty()}",
                    )
                }
            }
            return snapshotLocked(cached)
        }
    }

    /** Returns cached data immediately; waits only when there is no cache at all. */
    suspend fun locationForTool(reason: String): RefreshSnapshot {
        val cached = store.lastLocation()
        val started = refreshInBackground(reason)
        if (cached != null) return started.copy(location = cached)

        synchronized(stateLock) { refreshJob }?.join()
        return synchronized(stateLock) { snapshotLocked(store.lastLocation()) }
    }

    fun cachedLocation(): StoredLocation? = store.lastLocation()

    suspend fun reverseGeocode(location: StoredLocation): String? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        Timber.i("location.geocode.started")
        val result = runCatching {
            withTimeoutOrNull(REVERSE_GEOCODE_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        val geocoder = Geocoder(context, Locale.CHINA)
                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                    if (cont.isActive) cont.resume(formatAddress(addresses.firstOrNull()))
                                }

                                override fun onError(errorMessage: String?) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    formatAddress(Geocoder(context, Locale.CHINA)
                        .getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull())
                }
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        return@withContext result.fold(
            onSuccess = { address ->
                val outcome = when {
                    address != null -> "succeeded"
                    elapsedMs >= REVERSE_GEOCODE_TIMEOUT_MS - 100L -> "timeout"
                    else -> "empty"
                }
                Timber.i("location.geocode.$outcome elapsedMs=$elapsedMs")
                address
            },
            onFailure = { error ->
                Timber.w(error, "location.geocode.failed elapsedMs=$elapsedMs")
                null
            },
        )
    }

    private fun formatAddress(address: android.location.Address?): String? = listOfNotNull(
        address?.adminArea,
        address?.locality,
        address?.subLocality,
        address?.thoroughfare,
    ).distinct().joinToString("").takeIf { it.isNotBlank() }

    private fun snapshotLocked(location: StoredLocation?): RefreshSnapshot {
        val successRefreshAt = location?.timestamp?.plus(SUCCESS_COOLDOWN_MS)
        val nextRefreshAt = listOfNotNull(successRefreshAt, retryAfterAt).maxOrNull()
        return RefreshSnapshot(
            state = refreshState,
            location = location,
            startedAt = refreshStartedAt,
            completedAt = refreshCompletedAt,
            nextRefreshAt = nextRefreshAt,
            error = refreshError,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(
        timeoutMs: Long = LOCATION_TIMEOUT_MS,
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
        val chosen = if (forceFresh) {
            fresh ?: return@withContext null
        } else {
            listOfNotNull(fresh, usableLastKnown).maxByOrNull { it.time }
                ?: return@withContext null
        }
        chosen.toStoredLocation()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun availabilityIssue(): String? {
        if (!hasLocationPermission()) return "应用没有定位权限。"
        val manager = context.getSystemService(LocationManager::class.java)
        val hasEnabledProvider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .any { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        return if (hasEnabledProvider) null else "系统定位开关未开启，或没有可用定位提供器。"
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestBestFreshLocation(
        manager: LocationManager,
        providers: List<String>,
        timeoutMs: Long,
    ): Location? = coroutineScope {
        val pending = providers.map { provider ->
            async {
                runCatching { requestFreshLocation(manager, provider) }.getOrNull()
            }
        }.toMutableList()
        try {
            withTimeoutOrNull(timeoutMs) {
                while (pending.isNotEmpty()) {
                    val (completed, location) = select {
                        pending.forEach { request ->
                            request.onAwait { request to it }
                        }
                    }
                    pending.remove(completed)
                    if (location != null) return@withTimeoutOrNull location
                }
                null
            }
        } finally {
            pending.forEach { it.cancel() }
        }
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
        return StoredLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            provider = provider,
            timestamp = System.currentTimeMillis(),
            sourceTimestamp = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            altitudeMeters = if (hasAltitude()) altitude else null,
            verticalAccuracyMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) verticalAccuracyMeters else null,
            speedMps = if (hasSpeed()) speed else null,
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) bearingAccuracyDegrees else null,
            elapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0L },
            elapsedRealtimeUncertaintyNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasElapsedRealtimeUncertaintyNanos()) elapsedRealtimeUncertaintyNanos else null,
            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider,
        )
    }

    class LocationTimeoutException : Exception("定位请求超过 30 秒未返回有效位置")

    private companion object {
        private const val LOCATION_TIMEOUT_MS = 30_000L
        private const val SUCCESS_COOLDOWN_MS = 5 * 60 * 1000L
        private const val FAILURE_RETRY_COOLDOWN_MS = 30 * 1000L
        private const val REVERSE_GEOCODE_TIMEOUT_MS = 3_000L
        private const val MAX_FORCE_FRESH_FALLBACK_AGE_MS = 2 * 60 * 1000L
    }
}
