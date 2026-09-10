package com.example.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.City
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.*

class LocationDetector(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun getCurrentCoordinates(): Location? {
        return HighAccuracyLocationManager.getAccurateGpsLocation(context) {}
    }

    suspend fun getCityNameFromCoordinates(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.ENGLISH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val addr = addresses.firstOrNull()
                            val city = addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea
                            cont.resume(city)
                        }
                        override fun onError(errorMessage: String?) {
                            Log.e(TAG, "Geocoder error: $errorMessage")
                            cont.resume(null)
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                val addr = addresses?.firstOrNull()
                addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in reverse geocoding", e)
            null
        }
    }

    /**
     * Matches the detected user coordinates/locality with backend fetched active cities.
     * Never returns dummy cities — only matches against actual backend cities.
     */
    fun matchWithBackendCities(
        detectedCityName: String?,
        latitude: Double?,
        longitude: Double?,
        backendCities: List<City>
    ): City? {
        if (backendCities.isEmpty()) return null

        // 1. Text-based match against city name or state
        if (!detectedCityName.isNullOrBlank()) {
            val cleanDetected = detectedCityName.trim().lowercase(Locale.ROOT)
            // Exact match
            val exact = backendCities.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == cleanDetected }
            if (exact != null) return exact

            // Contains / Substring match (e.g. "Sindhanuru", "Sindhanur Town", "Sindhnur")
            val containsMatch = backendCities.firstOrNull {
                val bName = it.name.trim().lowercase(Locale.ROOT)
                cleanDetected.contains(bName) || bName.contains(cleanDetected)
            }
            if (containsMatch != null) return containsMatch
        }

        // 2. Proximity calculation if coordinates are present
        if (latitude != null && longitude != null) {
            // Known coordinates for regional hubs in Karnataka/AP/TS
            val knownCoords = mapOf(
                "sindhanur" to Pair(15.7667, 76.7583),
                "raichur" to Pair(16.2120, 77.3439),
                "bellary" to Pair(15.1394, 76.9214),
                "ballari" to Pair(15.1394, 76.9214),
                "gangavathi" to Pair(15.4326, 76.5312),
                "manvi" to Pair(15.9922, 77.0506),
                "koppal" to Pair(15.3524, 76.1557)
            )

            var nearestCity: City? = null
            var minDistanceKm = Double.MAX_VALUE

            for (city in backendCities) {
                val key = city.name.trim().lowercase(Locale.ROOT)
                val coords = knownCoords[key]
                if (coords != null) {
                    val dist = calculateDistanceKm(latitude, longitude, coords.first, coords.second)
                    if (dist < 60.0 && dist < minDistanceKm) { // within 60km range
                        minDistanceKm = dist
                        nearestCity = city
                    }
                }
            }
            if (nearestCity != null) return nearestCity
        }

        // 3. If only one active city exists in the backend database (e.g. Sindhanur), default to it
        if (backendCities.size == 1) {
            return backendCities.first()
        }

        return null
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Radius of earth in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    companion object {
        private const val TAG = "LocationDetector"
    }
}
