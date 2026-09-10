package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.location.GpsState
import com.example.data.location.HighAccuracyLocationManager
import com.example.data.model.CustomerAddress
import com.example.data.repository.SndmartRepository
import com.example.ui.theme.*
import com.example.util.GeocodeAddressResult
import com.example.util.MapLocationHelper
import com.example.util.PlaceSearchResult
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val INDIA_CENTER = LatLng(20.5937, 78.9629)
private val BENGALURU_CENTER = LatLng(12.9716, 77.5946)

enum class AddressPickerUiState {
    LOCATION_PREVIEW,    // Swiggy style preview with "Use This Location" & "Adjust on Map"
    ADJUSTING_ON_MAP,    // Free map pan/drag mode to place pin at doorstep
    FORM_DETAILS         // Final address form with House/Flat no, Name, Phone & Save
}

/**
 * Swiggy-like Exact GPS Delivery Address Picker:
 * - High Accuracy GPS hardware detection (rejects approximate/poor accuracy)
 * - Maximum doorstep zoom-in (18.5f)
 * - Signature blue current-location marker & accuracy circle
 * - Live reverse-geocoding of exact coordinates
 * - "Use This Location" and "Adjust Location on Map" actions
 * - Saves confirmed exact latitude, longitude, and full address to Supabase
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressPickerDialog(
    repository: SndmartRepository,
    userId: String,
    existing: CustomerAddress? = null,
    defaultRecipientName: String = "",
    defaultPhone: String = "",
    autoDetectOnOpen: Boolean = true,
    onDismiss: () -> Unit,
    onSaved: (CustomerAddress) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Initial Coordinates: existing lat/lng -> fallback
    val initialLatLng = remember {
        if (existing?.lat != null && existing.lng != null && existing.lat != 0.0) {
            LatLng(existing.lat, existing.lng)
        } else {
            BENGALURU_CENTER
        }
    }

    var selectedLatLng by remember { mutableStateOf(initialLatLng) }
    var exactGpsLatLng by remember { mutableStateOf<LatLng?>(null) }
    var exactGpsAccuracy by remember { mutableStateOf<Float?>(null) }
    var gpsState by remember { mutableStateOf<GpsState>(GpsState.Idle) }

    var uiMode by remember {
        mutableStateOf(
            if (existing != null) AddressPickerUiState.FORM_DETAILS
            else AddressPickerUiState.LOCATION_PREVIEW
        )
    }

    // Geocoded details
    var geocodedResult by remember { mutableStateOf<GeocodeAddressResult?>(null) }
    var addressLine by remember { mutableStateOf(existing?.addressLine ?: "") }
    var landmark by remember { mutableStateOf(existing?.landmark ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "Home") }
    var recipientName by remember {
        mutableStateOf(existing?.recipientName ?: defaultRecipientName.ifBlank { "Customer" })
    }
    var phone by remember {
        mutableStateOf(existing?.phone ?: defaultPhone)
    }

    var isReverseGeocoding by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Places Autocomplete Search State
    var searchQuery by remember { mutableStateOf("") }
    var searchSuggestions by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearchingPlaces by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLatLng, if (existing?.lat != null) 17.5f else 12f)
    }

    // Reverse geocode whenever pin moves or camera settles
    fun triggerReverseGeocode(latLng: LatLng, overwriteAddress: Boolean = true) {
        selectedLatLng = latLng
        coroutineScope.launch {
            isReverseGeocoding = true
            val geo = MapLocationHelper.reverseGeocode(context, latLng.latitude, latLng.longitude)
            isReverseGeocoding = false
            if (geo != null) {
                geocodedResult = geo
                if (overwriteAddress || addressLine.isBlank()) {
                    addressLine = geo.addressLine
                }
                if (landmark.isBlank() && !geo.subLocality.isNullOrBlank()) {
                    landmark = geo.subLocality
                }
            }
        }
    }

    // High Accuracy GPS detection function
    fun detectExactCurrentLocation() {
        statusMessage = null
        val hasPermission = HighAccuracyLocationManager.hasFineLocationPermission(context)
        if (!hasPermission) {
            gpsState = GpsState.PermissionDenied
            return
        }

        if (!HighAccuracyLocationManager.isAnyLocationProviderEnabled(context)) {
            gpsState = GpsState.GpsDisabled
            return
        }

        coroutineScope.launch {
            gpsState = GpsState.Calibrating(1, null, "Detecting exact GPS coordinates...")
            val location = HighAccuracyLocationManager.getAccurateGpsLocation(context) { state ->
                gpsState = state
            }

            if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                exactGpsLatLng = userLatLng
                exactGpsAccuracy = if (location.hasAccuracy()) location.accuracy else 15f
                selectedLatLng = userLatLng

                // Swiggy behavior: Center automatically with maximum zoom-in for doorstep precision
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(userLatLng, 18.5f),
                    durationMs = 900
                )
                triggerReverseGeocode(userLatLng, overwriteAddress = true)
                uiMode = AddressPickerUiState.LOCATION_PREVIEW
            } else if (gpsState is GpsState.Failure) {
                statusMessage = (gpsState as GpsState.Failure).message
            }
        }
    }

    // Permission launcher for fine GPS location
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            detectExactCurrentLocation()
        } else {
            gpsState = GpsState.PermissionDenied
            statusMessage = "Location permission denied. Please allow permission to detect exact GPS doorstep."
        }
    }

    // Request GPS detection on first open if no existing address or autoDetect is true
    LaunchedEffect(Unit) {
        if (existing?.lat == null || existing.lat == 0.0) {
            if (autoDetectOnOpen) {
                if (HighAccuracyLocationManager.hasFineLocationPermission(context)) {
                    detectExactCurrentLocation()
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        } else {
            triggerReverseGeocode(initialLatLng, overwriteAddress = false)
        }
    }

    // Listen to camera movement when adjusting location on map
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving && uiMode == AddressPickerUiState.ADJUSTING_ON_MAP) {
            val centerTarget = cameraPositionState.position.target
            if (centerTarget.latitude != 0.0 && centerTarget.longitude != 0.0) {
                triggerReverseGeocode(centerTarget, overwriteAddress = true)
            }
        }
    }

    // Places autocomplete query debounce
    LaunchedEffect(searchQuery) {
        searchJob?.cancel()
        if (searchQuery.trim().length >= 2) {
            searchJob = coroutineScope.launch {
                delay(300)
                isSearchingPlaces = true
                val results = MapLocationHelper.searchPlaces(context, searchQuery.trim())
                searchSuggestions = results
                isSearchingPlaces = false
            }
        } else {
            searchSuggestions = emptyList()
            isSearchingPlaces = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (existing == null) "Set Delivery Location" else "Edit Delivery Address",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when (uiMode) {
                                    AddressPickerUiState.ADJUSTING_ON_MAP -> "Move map to align pin with your doorstep"
                                    AddressPickerUiState.LOCATION_PREVIEW -> "Exact GPS doorstep detection"
                                    AddressPickerUiState.FORM_DETAILS -> "Enter complete address details"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (uiMode == AddressPickerUiState.ADJUSTING_ON_MAP || uiMode == AddressPickerUiState.FORM_DETAILS) {
                                uiMode = AddressPickerUiState.LOCATION_PREVIEW
                            } else {
                                onDismiss()
                            }
                        }) {
                            Icon(
                                imageVector = if (uiMode == AddressPickerUiState.LOCATION_PREVIEW) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close"
                            )
                        }
                    },
                    actions = {
                        // Quick Detect My Location Top Action
                        IconButton(
                            onClick = {
                                if (HighAccuracyLocationManager.hasFineLocationPermission(context)) {
                                    detectExactCurrentLocation()
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            modifier = Modifier.testTag("my_location_button")
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "Detect My Location",
                                tint = NaturalPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // --- MAP SECTION ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Google Map View
                    GoogleMap(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("address_picker_map"),
                        cameraPositionState = cameraPositionState,
                        uiSettings = remember {
                            MapUiSettings(
                                zoomControlsEnabled = false,
                                myLocationButtonEnabled = false,
                                compassEnabled = true
                            )
                        },
                        properties = remember {
                            MapProperties(
                                isMyLocationEnabled = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            )
                        },
                        onMapClick = { latLng ->
                            selectedLatLng = latLng
                            triggerReverseGeocode(latLng, overwriteAddress = true)
                            coroutineScope.launch {
                                cameraPositionState.animate(CameraUpdateFactory.newLatLng(latLng))
                            }
                        }
                    ) {
                        // 1. Swiggy Blue Current-Location Marker & Accuracy Halo Circle
                        if (exactGpsLatLng != null) {
                            val accRadius = (exactGpsAccuracy?.toDouble() ?: 20.0).coerceIn(5.0, 50.0)
                            Circle(
                                center = exactGpsLatLng!!,
                                radius = accRadius,
                                fillColor = Color(0x281E88E5), // Translucent blue
                                strokeColor = Color(0x991E88E5),
                                strokeWidth = 2.5f
                            )
                            Marker(
                                state = rememberMarkerState(key = "exact_gps_dot", position = exactGpsLatLng!!).apply {
                                    position = exactGpsLatLng!!
                                },
                                title = "Your Exact GPS Location",
                                snippet = "Accuracy: ±${exactGpsAccuracy?.toInt() ?: 10}m",
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                            )
                        }

                        // 2. Selected Delivery Pin (if not in floating adjust mode)
                        if (uiMode != AddressPickerUiState.ADJUSTING_ON_MAP) {
                            Marker(
                                state = rememberMarkerState(key = "delivery_pin", position = selectedLatLng).apply {
                                    position = selectedLatLng
                                },
                                title = "Delivery Location",
                                snippet = "Doorstep Destination",
                                draggable = true,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                            )
                        }
                    }

                    // Centered Floating Pin when user is adjusting location on map
                    if (uiMode == AddressPickerUiState.ADJUSTING_ON_MAP) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(bottom = 36.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "Order will be delivered here",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = NaturalPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = "Pin Location",
                                    tint = NaturalBadgeRed,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }

                    // --- TOP SEARCH BAR & GPS BANNER ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .align(Alignment.TopCenter),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Places Search Bar
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = NaturalPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            "Search area, street, landmark...",
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("places_search_input"),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    )
                                )
                                if (isSearchingPlaces) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = NaturalPrimary
                                    )
                                } else if (searchQuery.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            searchSuggestions = emptyList()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        // Autocomplete Suggestions List
                        AnimatedVisibility(visible = searchSuggestions.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                LazyColumn {
                                    items(searchSuggestions, key = { it.placeId }) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    searchQuery = item.primaryText
                                                    searchSuggestions = emptyList()
                                                    coroutineScope.launch {
                                                        val target = MapLocationHelper.fetchPlaceLatLng(context, item)
                                                        if (target != null) {
                                                            selectedLatLng = target
                                                            cameraPositionState.animate(
                                                                CameraUpdateFactory.newLatLngZoom(target, 18.0f)
                                                            )
                                                            triggerReverseGeocode(target, overwriteAddress = true)
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Outlined.LocationOn,
                                                contentDescription = null,
                                                tint = NaturalPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.primaryText,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (item.secondaryText.isNotBlank()) {
                                                    Text(
                                                        text = item.secondaryText,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = TextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }

                        // GPS Status / Calibration Pill or Warning
                        when (val state = gpsState) {
                            is GpsState.Calibrating -> {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 3.dp,
                                    border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = NaturalPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = NaturalPrimary
                                        )
                                    }
                                }
                            }
                            is GpsState.PermissionDenied -> {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Location Permission Required",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                "Allow high accuracy GPS to pinpoint your exact doorstep.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Grant", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            is GpsState.GpsDisabled -> {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = PastelCoral),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.GpsOff,
                                            contentDescription = null,
                                            tint = NaturalBadgeRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Device GPS is Turned Off",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                            Text(
                                                "Please enable location for real doorstep delivery.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            OutlinedButton(
                                                onClick = { detectExactCurrentLocation() },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Retry", fontSize = 11.sp)
                                            }
                                            Button(
                                                onClick = { HighAccuracyLocationManager.openLocationSettings(context) },
                                                colors = ButtonDefaults.buttonColors(containerColor = NaturalBadgeRed),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Enable", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }

                    // Floating GPS "Detect My Location" Button (Bottom Right)
                    FloatingActionButton(
                        onClick = {
                            if (HighAccuracyLocationManager.hasFineLocationPermission(context)) {
                                detectExactCurrentLocation()
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(50.dp)
                            .testTag("fab_locate_me"),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = NaturalPrimary,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Detect My Location")
                    }

                    // Zoom In / Out Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    cameraPositionState.animate(CameraUpdateFactory.zoomIn())
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = TextPrimary,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Zoom In")
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    cameraPositionState.animate(CameraUpdateFactory.zoomOut())
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = TextPrimary,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
                        }
                    }
                }

                // --- BOTTOM SHEET / INTERACTIVE ACTIONS ---
                Surface(
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (uiMode) {
                        // MODE 1: SWIGGY-STYLE LOCATION PREVIEW
                        AddressPickerUiState.LOCATION_PREVIEW -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // GPS Accuracy & Locality Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (exactGpsAccuracy != null && exactGpsAccuracy!! <= 25f) PastelSage else NaturalPrimaryContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.GpsFixed,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(12.dp),
                                                    tint = NaturalPrimary
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (exactGpsAccuracy != null) "GPS ACCURACY: ±${exactGpsAccuracy!!.toInt()}m" else "DEVICE GPS",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NaturalPrimary
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = "%.5f, %.5f".format(selectedLatLng.latitude, selectedLatLng.longitude),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }

                                // Full Address Details
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = NaturalBadgeRed,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        val headerText = geocodedResult?.subLocality
                                            ?: geocodedResult?.locality
                                            ?: geocodedResult?.featureName
                                            ?: "Current Location"
                                        Text(
                                            text = headerText,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (isReverseGeocoding) "Locating address..." else addressLine.ifBlank { "Pin set at coordinates" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Two Prominent Actions (Swiggy Pattern):
                                // 1. "Use This Location"
                                // 2. "Adjust Location on Map"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            uiMode = AddressPickerUiState.ADJUSTING_ON_MAP
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("adjust_location_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.5.dp, NaturalPrimary)
                                    ) {
                                        Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Adjust on Map", color = NaturalPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }

                                    Button(
                                        onClick = {
                                            uiMode = AddressPickerUiState.FORM_DETAILS
                                        },
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .height(48.dp)
                                            .testTag("use_this_location_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Use This Location", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        // MODE 2: ADJUST PIN ON MAP MODE
                        AddressPickerUiState.ADJUSTING_ON_MAP -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = NaturalPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Drag map to place pin at exact doorstep",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = if (isReverseGeocoding) "Updating address..." else addressLine.ifBlank { "Coordinates: %.5f, %.5f".format(selectedLatLng.latitude, selectedLatLng.longitude) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Button(
                                    onClick = {
                                        uiMode = AddressPickerUiState.FORM_DETAILS
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("confirm_pin_location_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Confirm Doorstep Pin", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // MODE 3: DETAILED ADDRESS FORM & SUPABASE PERSISTENCE
                        AddressPickerUiState.FORM_DETAILS -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // GPS & Label Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PinDrop,
                                            contentDescription = null,
                                            tint = NaturalPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Lat/Lng:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "%.5f, %.5f".format(selectedLatLng.latitude, selectedLatLng.longitude),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }

                                    // Quick Label Chips (Home / Work / Other)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("Home", "Work", "Other").forEach { l ->
                                            val isSelected = label.equals(l, ignoreCase = true)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { label = l },
                                                label = { Text(l, fontSize = 11.sp) },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = NaturalPrimary,
                                                    selectedLabelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }

                                // Complete Address / Flat / Building (Pre-filled from Geocoder, fully editable)
                                OutlinedTextField(
                                    value = addressLine,
                                    onValueChange = { addressLine = it },
                                    label = { Text("Complete Address / Flat / Door No.") },
                                    placeholder = { Text("e.g. #204, Royal Palms, Main Road") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("address_line_input"),
                                    minLines = 2,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(12.dp)
                                )

                                // Landmark
                                OutlinedTextField(
                                    value = landmark,
                                    onValueChange = { landmark = it },
                                    label = { Text("Landmark / Nearby place (Optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                // Recipient Name & Phone in a Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = recipientName,
                                        onValueChange = { recipientName = it },
                                        label = { Text("Recipient Name") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("recipient_name_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    OutlinedTextField(
                                        value = phone,
                                        onValueChange = { phone = it },
                                        label = { Text("Phone Number") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("recipient_phone_input"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }

                                if (statusMessage != null) {
                                    Text(
                                        text = statusMessage!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NaturalBadgeRed
                                    )
                                }

                                // Confirm & Save Address to Supabase Button
                                Button(
                                    onClick = {
                                        if (addressLine.isBlank()) {
                                            statusMessage = "Please enter complete address"
                                            return@Button
                                        }
                                        isSaving = true
                                        statusMessage = null

                                        coroutineScope.launch {
                                            val newAddress = CustomerAddress(
                                                id = existing?.id,
                                                userId = userId,
                                                label = label.ifBlank { "Home" },
                                                recipientName = recipientName.ifBlank { "Customer" },
                                                phone = phone,
                                                addressLine = addressLine.trim(),
                                                landmark = landmark.trim().takeIf { it.isNotBlank() },
                                                lat = selectedLatLng.latitude,
                                                lng = selectedLatLng.longitude,
                                                isDefault = existing?.isDefault ?: false
                                            )

                                            val res = if (existing == null) {
                                                repository.addAddress(newAddress)
                                            } else {
                                                val updateMap = mapOf<String, Any?>(
                                                    "label" to newAddress.label,
                                                    "recipient_name" to newAddress.recipientName,
                                                    "phone" to newAddress.phone,
                                                    "address_line" to newAddress.addressLine,
                                                    "landmark" to newAddress.landmark,
                                                    "lat" to newAddress.lat,
                                                    "lng" to newAddress.lng
                                                )
                                                val updateRes = repository.updateAddress(existing.id ?: "", updateMap)
                                                if (updateRes.isSuccess) Result.success(newAddress)
                                                else Result.failure(updateRes.exceptionOrNull() ?: Exception("Update failed"))
                                            }

                                            isSaving = false
                                            if (res.isSuccess) {
                                                val saved = res.getOrNull() ?: newAddress
                                                onSaved(saved)
                                            } else {
                                                statusMessage = res.exceptionOrNull()?.message ?: "Failed to save address"
                                            }
                                        }
                                    },
                                    enabled = !isSaving && addressLine.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("save_address_button")
                                ) {
                                    if (isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Saving Address...")
                                    } else {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Save Delivery Address", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
