package com.ar.bydlauncher.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class CarLocationProvider(
    private val context: Context
) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    companion object {
        private const val TAG = "CarLocationProvider"

        /** Курс считается валидным, если он не «шум» (0 = нет данных). */
        private const val MIN_VALID_BEARING_SPEED_KMH = 3f
    }

    /**
     * Последний известный курс машины (0..360).
     * Обновляется при каждом успешном GPS-запросе.
     */
    @Volatile
    private var lastValidBearing: Float = 0f

    /**
     * Получает свежую позицию ГУ.
     */
    suspend fun getCurrentLocation(): Location? {
        return withContext(Dispatchers.Main) {
            if (!hasLocationPermission()) {
                Log.w(TAG, "getCurrentLocation: no permission")
                return@withContext null
            }

            val gpsEnabled = try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
                false
            }

            val networkEnabled = try {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {
                false
            }

            Log.i(TAG, "providers: gps=$gpsEnabled network=$networkEnabled")

            val location = when {
                gpsEnabled -> requestFreshLocation(LocationManager.GPS_PROVIDER)
                networkEnabled -> requestFreshLocation(LocationManager.NETWORK_PROVIDER)
                else -> {
                    Log.w(TAG, "no location provider enabled")
                    null
                }
            }

            // Обновляем последний валидный bearing
            location?.let { loc ->
                if (loc.hasBearing() && loc.speed * 3.6f > MIN_VALID_BEARING_SPEED_KMH) {
                    lastValidBearing = loc.bearing
                }
            }

            location
        }
    }

    /**
     * Возвращает актуальный курс машины.
     * Если сейчас машина стоит — возвращает последний известный курс.
     * Если данных никогда не было — возвращает null.
     */
    fun getCurrentBearing(): Float? {
        return if (lastValidBearing > 0f) lastValidBearing else null
    }

    private fun hasLocationPermission(): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestFreshLocation(
        provider: String
    ): Location? {

        return withTimeoutOrNull(15_000L) {

            suspendCancellableCoroutine { continuation ->

                var finished = false

                val listener = object : LocationListener {

                    override fun onLocationChanged(location: Location) {
                        if (finished) return

                        finished = true

                        try {
                            locationManager.removeUpdates(this)
                        } catch (_: Exception) {
                        }

                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    override fun onProviderDisabled(providerName: String) {
                        if (providerName != provider || finished) return

                        finished = true

                        try {
                            locationManager.removeUpdates(this)
                        } catch (_: Exception) {
                        }

                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }

                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        1000L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )

                    continuation.invokeOnCancellation {
                        if (!finished) {
                            finished = true

                            try {
                                locationManager.removeUpdates(listener)
                            } catch (_: Exception) {
                            }
                        }
                    }

                } catch (_: SecurityException) {
                    finished = true

                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                } catch (_: Exception) {
                    finished = true

                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        } ?: run {
            Log.w(TAG, "requestFreshLocation($provider): timeout 15s")
            null
        }
    }
}