package com.example.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.example.data.remote.GoogleMapsConfig
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class PlaceSearchResult(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val fullText: String,
    val directLatLng: LatLng? = null
)

data class GeocodeAddressResult(
    val addressLine: String,
    val featureName: String? = null,
    val subLocality: String? = null,
    val locality: String? = null,
    val postalCode: String? = null
)

object MapLocationHelper {

    /**
     * Searches for place predictions via Places SDK if initialized and configured.
     * Falls back to Android's built-in Geocoder if Places is unconfigured or returns no results.
     */
    suspend fun searchPlaces(context: Context, query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Try Google Places SDK if configured
        if (GoogleMapsConfig.isConfigured) {
            try {
                GoogleMapsConfig.initializePlaces(context)
                if (Places.isInitialized()) {
                    val client = Places.createClient(context)
                    val request = FindAutocompletePredictionsRequest.builder()
                        .setQuery(query)
                        .setCountries("IN")
                        .build()

                    val response = suspendCancellableCoroutine { cont ->
                        client.findAutocompletePredictions(request)
                            .addOnSuccessListener { resp ->
                                cont.resume(resp.autocompletePredictions)
                            }
                            .addOnFailureListener { exc ->
                                Log.w("MapLocationHelper", "Places search failed: ${exc.message}")
                                cont.resume(emptyList<AutocompletePrediction>())
                            }
                    }

                    if (response.isNotEmpty()) {
                        return@withContext response.map { pred ->
                            PlaceSearchResult(
                                placeId = pred.placeId,
                                primaryText = pred.getPrimaryText(null).toString(),
                                secondaryText = pred.getSecondaryText(null).toString(),
                                fullText = pred.getFullText(null).toString()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MapLocationHelper", "Error with Places SDK search: ${e.message}")
            }
        }

        // 2. Fallback: Built-in Android Geocoder
        try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(query, 5) ?: emptyList()
            return@withContext results.mapIndexed { index, addr ->
                val primary = addr.featureName ?: addr.subLocality ?: addr.locality ?: query
                val full = (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }
                    .ifBlank { listOfNotNull(addr.subLocality, addr.locality, addr.adminArea).joinToString(", ") }
                PlaceSearchResult(
                    placeId = "geo_${addr.latitude}_${addr.longitude}_$index",
                    primaryText = primary,
                    secondaryText = full,
                    fullText = full.ifBlank { primary },
                    directLatLng = LatLng(addr.latitude, addr.longitude)
                )
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Geocoder search fallback failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves LatLng for a place ID (either via Places SDK or from fallback Geocoder).
     */
    suspend fun fetchPlaceLatLng(context: Context, place: PlaceSearchResult): LatLng? = withContext(Dispatchers.IO) {
        if (place.directLatLng != null) {
            return@withContext place.directLatLng
        }

        if (GoogleMapsConfig.isConfigured) {
            try {
                GoogleMapsConfig.initializePlaces(context)
                if (Places.isInitialized()) {
                    val client = Places.createClient(context)
                    val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
                    val request = FetchPlaceRequest.builder(place.placeId, fields).build()

                    val latLng = suspendCancellableCoroutine<LatLng?> { cont ->
                        client.fetchPlace(request)
                            .addOnSuccessListener { resp ->
                                cont.resume(resp.place.latLng)
                            }
                            .addOnFailureListener { exc ->
                                Log.w("MapLocationHelper", "Fetch place failed: ${exc.message}")
                                cont.resume(null)
                            }
                    }
                    if (latLng != null) return@withContext latLng
                }
            } catch (e: Exception) {
                Log.w("MapLocationHelper", "Error fetching place LatLng: ${e.message}")
            }
        }

        // Try Geocoder by fullText
        try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocationName(place.fullText, 1)
            addrs?.firstOrNull()?.let { return@withContext LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            // ignore
        }
        null
    }

    /**
     * Reverse-geocodes (lat, lng) to a human-readable street address.
     */
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): GeocodeAddressResult? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addrs = suspendCancellableCoroutine<List<Address>> { cont ->
                    try {
                        geocoder.getFromLocation(lat, lng, 1) { addresses ->
                            cont.resume(addresses)
                        }
                    } catch (e: Exception) {
                        cont.resume(emptyList())
                    }
                }
                val addr = addrs.firstOrNull() ?: return@withContext null
                formatAddress(addr)
            } else {
                @Suppress("DEPRECATION")
                val addrs = geocoder.getFromLocation(lat, lng, 1)
                val addr = addrs?.firstOrNull() ?: return@withContext null
                formatAddress(addr)
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Reverse geocoding failed: ${e.message}")
            null
        }
    }

    private fun formatAddress(addr: Address): GeocodeAddressResult {
        val lines = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
        val full = if (lines.isNotEmpty()) lines.joinToString(", ") else {
            listOfNotNull(addr.featureName, addr.subLocality, addr.locality, addr.adminArea, addr.postalCode)
                .joinToString(", ")
        }
        return GeocodeAddressResult(
            addressLine = full,
            featureName = addr.featureName,
            subLocality = addr.subLocality,
            locality = addr.locality,
            postalCode = addr.postalCode
        )
    }
}
