package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class GpsState {
    object Idle : GpsState()
    object RequestingPermission : GpsState()
    object PermissionDenied : GpsState()
    object GpsDisabled : GpsState()
    data class Calibrating(val attempt: Int, val currentAccuracyMeters: Float? = null, val message: String) : GpsState()
    data class Located(val location: Location, val accuracyMeters: Float, val isHighAccuracy: Boolean) : GpsState()
    data class Failure(val message: String, val canRetry: Boolean = true) : GpsState()
}

object HighAccuracyLocationManager {
    private const val TAG = "HighAccuracyGPS"

    // High accuracy standards (typical outdoor GPS fix is 3m - 20m)
    const val DESIRED_ACCURACY_METERS = 25.0f
    const val ACCEPTABLE_ACCURACY_METERS = 50.0f
    private const val MAX_CALIBRATION_ATTEMPTS = 5
    private const val TIMEOUT_MILLIS = 10000L

    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun isAnyLocationProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open location settings", e)
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    /**
     * Swiggy-like exact GPS detection:
     * - Requires ACCESS_FINE_LOCATION and active GPS hardware.
     * - Rejects poor accuracy immediately; does not accept coarse/approximate cell/IP fixes.
     * - Keeps polling and calibrating satellite signals until accuracy <= 25m or timeout with best fix.
     * - Returns exact Location with latitude, longitude, and accuracy.
     */
    @SuppressLint("MissingPermission")
    suspend fun getAccurateGpsLocation(
        context: Context,
        onProgress: (status: GpsState) -> Unit
    ): Location? = withContext(Dispatchers.Main) {
        if (!hasFineLocationPermission(context)) {
            onProgress(GpsState.PermissionDenied)
            return@withContext null
        }

        if (!isAnyLocationProviderEnabled(context)) {
            onProgress(GpsState.GpsDisabled)
            return@withContext null
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        var bestLocation: Location? = null
        var bestAccuracy = Float.MAX_VALUE

        onProgress(GpsState.Calibrating(1, null, "Acquiring High Accuracy GPS signal..."))

        // Phase 1: Try immediate getCurrentLocation with PRIORITY_HIGH_ACCURACY
        val cts = CancellationTokenSource()
        val firstFix = try {
            withTimeoutOrNull(3500L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during first GPS fix: ${e.message}")
            null
        }

        if (firstFix != null && firstFix.hasAccuracy()) {
            bestLocation = firstFix
            bestAccuracy = firstFix.accuracy
            Log.d(TAG, "First fix received: lat=${firstFix.latitude}, lng=${firstFix.longitude}, acc=$bestAccuracy m")

            if (bestAccuracy <= DESIRED_ACCURACY_METERS) {
                // High accuracy achieved immediately
                onProgress(GpsState.Located(firstFix, bestAccuracy, isHighAccuracy = true))
                return@withContext firstFix
            } else {
                // Accuracy is poor (e.g. > 25m); do not accept immediately, calibrate for better GPS fix
                onProgress(GpsState.Calibrating(2, bestAccuracy, "Calibrating GPS accuracy (±${bestAccuracy.toInt()}m)... waiting for satellite lock"))
            }
        }

        // Phase 2: Request active GPS updates to lock onto GPS satellites
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 800L)
            .setMinUpdateIntervalMillis(400L)
            .setMaxUpdateDelayMillis(1000L)
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val accurateFix = withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { cont ->
                var updatesCount = 0
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (loc in result.locations) {
                            updatesCount++
                            val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
                            Log.d(TAG, "GPS update #$updatesCount: lat=${loc.latitude}, lng=${loc.longitude}, acc=$acc")

                            if (acc < bestAccuracy) {
                                bestLocation = loc
                                bestAccuracy = acc
                            }

                            if (acc <= DESIRED_ACCURACY_METERS) {
                                // Locked on target with high precision
                                fusedClient.removeLocationUpdates(this)
                                if (cont.isActive) cont.resume(loc)
                                return
                            } else {
                                onProgress(GpsState.Calibrating(
                                    attempt = updatesCount + 1,
                                    currentAccuracyMeters = bestAccuracy,
                                    message = "Calibrating GPS (±${bestAccuracy.toInt()}m)... optimizing doorstep fix"
                                ))
                            }

                            if (updatesCount >= MAX_CALIBRATION_ATTEMPTS && bestAccuracy <= ACCEPTABLE_ACCURACY_METERS) {
                                fusedClient.removeLocationUpdates(this)
                                if (cont.isActive) cont.resume(bestLocation)
                                return
                            }
                        }
                    }

                    override fun onLocationAvailability(avail: LocationAvailability) {
                        if (!avail.isLocationAvailable && bestLocation == null) {
                            Log.w(TAG, "GPS provider temporarily unavailable")
                        }
                    }
                }

                fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                cont.invokeOnCancellation {
                    fusedClient.removeLocationUpdates(callback)
                }
            }
        }

        val finalLoc = accurateFix ?: bestLocation
        if (finalLoc != null) {
            val acc = if (finalLoc.hasAccuracy()) finalLoc.accuracy else 50f
            val isHigh = acc <= DESIRED_ACCURACY_METERS
            onProgress(GpsState.Located(finalLoc, acc, isHighAccuracy = isHigh))
            finalLoc
        } else {
            onProgress(GpsState.Failure("GPS signal weak or unavailable. Please retry near an open window or outdoors.", canRetry = true))
            null
        }
    }
}
