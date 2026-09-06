package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.data.location.LocationDetector
import com.example.data.model.City
import com.example.data.repository.SndmartRepository
import com.example.service.InAppNotification
import com.example.service.SndmartMessagingService
import com.example.ui.components.InAppNotificationBanner
import com.example.ui.components.LocationDetectionState
import com.example.ui.components.LocationRequirementDialog
import com.example.ui.screens.*
import com.example.ui.theme.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object Cart : Screen("cart", "Cart")
    object Orders : Screen("orders", "Orders")
    object Profile : Screen("profile", "Profile")
    object HotelMenu : Screen("hotel_menu/{vendorId}/{vendorName}", "Menu") {
        fun createRoute(vendorId: String, vendorName: String) = "hotel_menu/$vendorId/$vendorName"
    }
    object Checkout : Screen("checkout/{isHotel}/{couponCode}/{slotId}", "Checkout") {
        fun createRoute(isHotel: Boolean, couponCode: String?, slotId: String?) =
            "checkout/$isHotel/${couponCode ?: "none"}/${slotId ?: "none"}"
    }
    object OrderDetail : Screen("order_detail/{orderId}", "Order Details") {
        fun createRoute(orderId: String) = "order_detail/$orderId"
    }
    object Wallet : Screen("wallet", "Wallet")
    object Auth : Screen("auth", "Account")
}

class MainActivity : ComponentActivity() {

    private var initialOrderId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep link / notification extra
        handleIntent(intent)

        val app = application as SndmartApp
        val sessionManager = app.sessionManager
        val repository = SndmartRepository(sessionManager = sessionManager)

        setContent {
            SndmartTheme {
                val navController = rememberNavController()
                val currentBackStack by navController.currentBackStackEntryAsState()
                val currentDestination = currentBackStack?.destination?.route

                val selectedCity by sessionManager.selectedCity.collectAsState()
                val userId by sessionManager.userId.collectAsState()
                var showCityPicker by remember { mutableStateOf(false) }
                var showSupabaseSettings by remember { mutableStateOf(false) }

                val context = LocalContext.current
                val locationDetector = remember { LocationDetector(context) }
                val coroutineScope = rememberCoroutineScope()

                var showLocationDialog by remember { mutableStateOf(false) }
                var locationDetectionState by remember { mutableStateOf(LocationDetectionState.PERMISSION_REQUIRED) }
                var detectedCityName by remember { mutableStateOf<String?>(null) }
                var assignedCityName by remember { mutableStateOf<String?>(null) }
                var activeCitiesSummary by remember { mutableStateOf("") }
                var hasDeniedLocationThisSession by remember { mutableStateOf(false) }
                var lastDetectionTimeMs by remember { mutableStateOf(0L) }
                var pendingAutoCityChange by remember { mutableStateOf<City?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }
                var snackbarMessage by remember { mutableStateOf<String?>(null) }

                fun applyDetectedCity(city: City) {
                    val currentCity = sessionManager.selectedCity.value
                    val cartCount = repository.getCartCount()

                    if (currentCity != null && currentCity.id != city.id && cartCount > 0) {
                        // Cart has items and city is changing — warn before clearing
                        showLocationDialog = false
                        pendingAutoCityChange = city
                    } else {
                        // Apply directly (same city, empty cart, or first launch)
                        if (currentCity == null || currentCity.id != city.id) {
                            sessionManager.setSelectedCity(city)
                            repository.clearAllCarts()
                            // Silently update profile city_id if logged in
                            val userId = sessionManager.userId.value
                            if (!userId.isNullOrBlank()) {
                                coroutineScope.launch {
                                    try { repository.updateProfileCityId(userId, city.id) } catch (e: Exception) {}
                                }
                            }
                        }
                        assignedCityName = city.name
                        locationDetectionState = LocationDetectionState.SUCCESS
                        showCityPicker = false
                    }
                }

                fun recheckLocationSilently() {
                    coroutineScope.launch {
                        lastDetectionTimeMs = System.currentTimeMillis()
                        if (!locationDetector.hasLocationPermission() || hasDeniedLocationThisSession) return@launch

                        val coordinates = locationDetector.getCurrentCoordinates()
                        val lat = coordinates?.latitude
                        val lng = coordinates?.longitude

                        if (lat != null && lng != null) {
                            val rpcResult = repository.findCityForLocation(lat, lng)
                            val rpcCity = rpcResult.getOrNull()

                            if (rpcResult.isSuccess && rpcCity != null) {
                                val currentCity = sessionManager.selectedCity.value
                                if (currentCity == null || currentCity.id != rpcCity.id) {
                                    if (repository.getCartCount() > 0) {
                                        pendingAutoCityChange = rpcCity
                                    } else {
                                        sessionManager.setSelectedCity(rpcCity)
                                        val userId = sessionManager.userId.value
                                        if (!userId.isNullOrBlank()) {
                                            try { repository.updateProfileCityId(userId, rpcCity.id) } catch (e: Exception) {}
                                        }
                                        snackbarMessage = "Your delivery city has been updated to ${rpcCity.name}"
                                    }
                                }
                            }
                        }
                    }
                }

                fun performLocationDetectionAndCityAssignment() {
                    coroutineScope.launch {
                        showLocationDialog = true
                        locationDetectionState = LocationDetectionState.DETECTING
                        lastDetectionTimeMs = System.currentTimeMillis()

                        val coordinates = locationDetector.getCurrentCoordinates()
                        val lat = coordinates?.latitude
                        val lng = coordinates?.longitude

                        if (lat != null && lng != null) {
                            // Primary: backend RPC for authoritative city detection
                            val rpcResult = repository.findCityForLocation(lat, lng)
                            val rpcCity = rpcResult.getOrNull()

                            if (rpcResult.isSuccess && rpcCity != null) {
                                // City found via backend RPC
                                applyDetectedCity(rpcCity)
                            } else if (rpcResult.isSuccess && rpcCity == null) {
                                // Location not serviceable — no city covers this area
                                detectedCityName = null
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                            } else {
                                // RPC failed — fall back to geocoding + client-side matching
                                val geoCity = locationDetector.getCityNameFromCoordinates(lat, lng)
                                detectedCityName = geoCity
                                val citiesResult = repository.getActiveCities()
                                val backendCities = citiesResult.getOrNull()?.filter { it.status == "active" } ?: emptyList()
                                if (backendCities.isNotEmpty()) {
                                    activeCitiesSummary = backendCities.joinToString(", ") { it.name }
                                    val matchedCity = locationDetector.matchWithBackendCities(geoCity, lat, lng, backendCities)
                                    if (matchedCity != null) {
                                        applyDetectedCity(matchedCity)
                                    } else {
                                        locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                    }
                                } else {
                                    if (selectedCity != null) {
                                        showLocationDialog = false
                                    } else {
                                        locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                    }
                                }
                            }
                        } else {
                            // Location unavailable
                            if (selectedCity != null) {
                                showLocationDialog = false
                            } else {
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                activeCitiesSummary = "Unable to determine your location."
                            }
                        }
                    }
                }

                // Kick off the first-time city detection flow (permission prompt -> GPS ->
                // find_city_for_location RPC). Sets the dialog visible synchronously so the
                // city-picker fallback doesn't race in while detection is starting.
                fun startFirstTimeCityDetection() {
                    showLocationDialog = true
                    if (!locationDetector.hasLocationPermission()) {
                        locationDetectionState = LocationDetectionState.PERMISSION_REQUIRED
                    } else {
                        performLocationDetectionAndCityAssignment()
                    }
                }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (fineGranted || coarseGranted) {
                        performLocationDetectionAndCityAssignment()
                    } else {
                        locationDetectionState = LocationDetectionState.PERMISSION_DENIED
                        hasDeniedLocationThisSession = true
                    }
                }

                // City detection runs AFTER login (or a restored session), not on every app
                // open. If the user already has a city (locally or on profiles.city_id), skip
                // straight to Home. Only when profiles.city_id is null do we run the first-time
                // GPS detection flow. Re-detection otherwise happens only on a >30min resume
                // (see lifecycle observer below) or an explicit "Update my location" tap.
                LaunchedEffect(userId) {
                    val currentUserId = userId ?: return@LaunchedEffect
                    if (selectedCity != null) return@LaunchedEffect
                    // Logged in with no city yet: prefer the city saved on the profile.
                    val profileCity = repository.resolveUserCity(currentUserId).getOrNull()
                    if (profileCity != null) {
                        sessionManager.setSelectedCity(profileCity)
                    } else {
                        startFirstTimeCityDetection()
                    }
                }

                // Re-check location when app is resumed after being backgrounded >30 minutes
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val elapsed = System.currentTimeMillis() - lastDetectionTimeMs
                            if (lastDetectionTimeMs > 0 && elapsed > 30 * 60 * 1000L) {
                                recheckLocationSilently()
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Show snackbar for city updates / notifications
                LaunchedEffect(snackbarMessage) {
                    snackbarMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        snackbarMessage = null
                    }
                }

                // In-app notification from FCM
                var activeNotification by remember { mutableStateOf<InAppNotification?>(null) }

                // Collect in-app push messages
                LaunchedEffect(Unit) {
                    SndmartMessagingService.inAppEvents.collectLatest { notification ->
                        activeNotification = notification
                    }
                }

                // Initial navigation if opened with deep link
                LaunchedEffect(initialOrderId) {
                    initialOrderId?.let { id ->
                        navController.navigate(Screen.OrderDetail.createRoute(id))
                        initialOrderId = null
                    }
                }

                // Show city picker if none selected, not currently in location dialog, and not on Auth screen
                LaunchedEffect(selectedCity, showLocationDialog, currentDestination) {
                    if (selectedCity == null && !showLocationDialog && currentDestination != Screen.Auth.route) {
                        showCityPicker = true
                    }
                }

                // Bottom bar visible only on top-level tabs
                val isTopLevelDestination = currentDestination in listOf(
                    Screen.Home.route,
                    Screen.Cart.route,
                    Screen.Orders.route,
                    Screen.Profile.route
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (isTopLevelDestination) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 0.dp
                            ) {
                                val groceryCart by repository.groceryCart.collectAsState()
                                val hotelCart by repository.hotelCart.collectAsState()
                                val cartCount = groceryCart.sumOf { it.quantity } + hotelCart.sumOf { it.quantity }

                                val navItemColors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NaturalOnPrimaryContainer,
                                    selectedTextColor = NaturalOnPrimaryContainer,
                                    indicatorColor = NaturalPrimaryContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Home.route,
                                    onClick = {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Home.route) Icons.Filled.Storefront else Icons.Outlined.Storefront,
                                            contentDescription = "Home"
                                        )
                                    },
                                    label = { Text("Home", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_home")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Cart.route,
                                    onClick = {
                                        navController.navigate(Screen.Cart.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (cartCount > 0) {
                                                    Badge(containerColor = NaturalBadgeRed, contentColor = Color.White) {
                                                        Text("$cartCount", fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                if (currentDestination == Screen.Cart.route) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart,
                                                contentDescription = "Cart"
                                            )
                                        }
                                    },
                                    label = { Text("Cart", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_cart")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Orders.route,
                                    onClick = {
                                        navController.navigate(Screen.Orders.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Orders.route) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong,
                                            contentDescription = "Orders"
                                        )
                                    },
                                    label = { Text("Orders", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_orders")
                                )

                                NavigationBarItem(
                                    selected = currentDestination == Screen.Profile.route,
                                    onClick = {
                                        navController.navigate(Screen.Profile.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentDestination == Screen.Profile.route) Icons.Filled.Person else Icons.Outlined.Person,
                                            contentDescription = "Profile"
                                        )
                                    },
                                    label = { Text("Profile", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_profile")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        val hasSavedSession = remember { sessionManager.hasSavedSession() }
                        NavHost(
                            navController = navController,
                            startDestination = if (hasSavedSession) Screen.Home.route else Screen.Auth.route
                        ) {
                            composable(Screen.Home.route) {
                                HomeScreen(
                                    repository = repository,
                                    selectedCity = selectedCity,
                                    onCityChangeRequested = { showCityPicker = true },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) },
                                    onNavigateToHotelMenu = { vendorId, vendorName ->
                                        navController.navigate(Screen.HotelMenu.createRoute(vendorId, vendorName))
                                    },
                                    onOpenSettings = { showSupabaseSettings = true }
                                )
                            }

                            composable(
                                route = Screen.HotelMenu.route,
                                arguments = listOf(
                                    navArgument("vendorId") { type = NavType.StringType },
                                    navArgument("vendorName") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val vendorId = backStackEntry.arguments?.getString("vendorId") ?: ""
                                val vendorName = backStackEntry.arguments?.getString("vendorName") ?: "Hotel"
                                HotelMenuScreen(
                                    vendorId = vendorId,
                                    vendorName = vendorName,
                                    cityId = selectedCity?.id ?: "",
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) }
                                )
                            }

                            composable(Screen.Cart.route) {
                                CartScreen(
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onProceedToCheckout = { isHotel, coupon, slot ->
                                        navController.navigate(Screen.Checkout.createRoute(isHotel, coupon, slot))
                                    }
                                )
                            }

                            composable(
                                route = Screen.Checkout.route,
                                arguments = listOf(
                                    navArgument("isHotel") { type = NavType.BoolType },
                                    navArgument("couponCode") { type = NavType.StringType },
                                    navArgument("slotId") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val isHotel = backStackEntry.arguments?.getBoolean("isHotel") ?: false
                                val coupon = backStackEntry.arguments?.getString("couponCode")?.takeIf { it != "none" }
                                val slot = backStackEntry.arguments?.getString("slotId")?.takeIf { it != "none" }
                                CheckoutScreen(
                                    isHotel = isHotel,
                                    couponCode = coupon,
                                    slotId = slot,
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() },
                                    onOrderPlacedSuccess = { orderId ->
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId)) {
                                            popUpTo(Screen.Home.route)
                                        }
                                    },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) }
                                )
                            }

                            composable(Screen.Orders.route) {
                                OrdersScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToDetail = { orderId ->
                                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                    },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) }
                                )
                            }

                            composable(
                                route = Screen.OrderDetail.route,
                                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                                OrderDetailScreen(
                                    orderId = orderId,
                                    cityId = selectedCity?.id,
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCart = { navController.navigate(Screen.Cart.route) }
                                )
                            }

                            composable(Screen.Profile.route) {
                                ProfileScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onNavigateToCityPicker = { showCityPicker = true },
                                    onNavigateToWallet = { navController.navigate(Screen.Wallet.route) },
                                    onRequireLogin = { navController.navigate(Screen.Auth.route) },
                                    onOpenSettings = { showSupabaseSettings = true },
                                    onLogoutSuccess = {
                                        navController.navigate(Screen.Auth.route) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(Screen.Wallet.route) {
                                WalletScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.Auth.route) {
                                AuthScreen(
                                    repository = repository,
                                    sessionManager = sessionManager,
                                    onAuthSuccess = {
                                        if (navController.previousBackStackEntry != null) {
                                            navController.popBackStack()
                                        } else {
                                            navController.navigate(Screen.Home.route) {
                                                popUpTo(Screen.Auth.route) { inclusive = true }
                                            }
                                        }
                                    },
                                    onBack = {
                                        if (navController.previousBackStackEntry != null) {
                                            navController.popBackStack()
                                        }
                                    },
                                    canGoBack = navController.previousBackStackEntry != null
                                )
                            }
                        }

                        // Top-floating in-app push notification banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        ) {
                            InAppNotificationBanner(
                                notification = activeNotification,
                                onDismiss = { activeNotification = null },
                                onNavigateToOrder = { orderId ->
                                    navController.navigate(Screen.OrderDetail.createRoute(orderId))
                                }
                            )
                        }

                        // Location Requirement & Auto-Detection Dialog
                        if (showLocationDialog) {
                            LocationRequirementDialog(
                                state = locationDetectionState,
                                detectedCityName = detectedCityName,
                                assignedCityName = assignedCityName,
                                activeCitiesSummary = activeCitiesSummary,
                                onRequestPermission = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onManualSelect = {
                                    showLocationDialog = false
                                    showCityPicker = true
                                },
                                onDismiss = {
                                    showLocationDialog = false
                                },
                                onNotifyMe = {
                                    showLocationDialog = false
                                    snackbarMessage = "We'll notify you when Sndmart launches in your area!"
                                    showCityPicker = true
                                }
                            )
                        }

                        // Cart-clear warning when auto-detected city differs from current
                        if (pendingAutoCityChange != null) {
                            val newCity = pendingAutoCityChange!!
                            AlertDialog(
                                onDismissRequest = { pendingAutoCityChange = null },
                                title = { Text("Switch to ${newCity.name}?", fontWeight = FontWeight.Bold) },
                                text = {
                                    Text("Your location suggests you're in ${newCity.name}. Switching your delivery city will clear your current cart items, as prices and stock are city-specific. Do you wish to continue?")
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            val c = newCity
                                            pendingAutoCityChange = null
                                            coroutineScope.launch {
                                                sessionManager.setSelectedCity(c)
                                                repository.clearAllCarts()
                                                val userId = sessionManager.userId.value
                                                if (!userId.isNullOrBlank()) {
                                                    try { repository.updateProfileCityId(userId, c.id) } catch (e: Exception) {}
                                                }
                                                snackbarMessage = "Delivery city updated to ${c.name}"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text("Switch & Clear Cart")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingAutoCityChange = null }) {
                                        Text("Stay in Current City")
                                    }
                                }
                            )
                        }

                        // City Picker Bottom Sheet
                        if (showCityPicker) {
                            CityPickerSheet(
                                repository = repository,
                                sessionManager = sessionManager,
                                onDismiss = { showCityPicker = false },
                                onCitySelected = { showCityPicker = false },
                                onTriggerLocationDetect = {
                                    showCityPicker = false
                                    if (!locationDetector.hasLocationPermission()) {
                                        locationDetectionState = LocationDetectionState.PERMISSION_REQUIRED
                                        showLocationDialog = true
                                    } else {
                                        performLocationDetectionAndCityAssignment()
                                    }
                                }
                            )
                        }

                        // Supabase Settings Dialog
                        if (showSupabaseSettings) {
                            SupabaseSettingsDialog(
                                sessionManager = sessionManager,
                                onDismiss = { showSupabaseSettings = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val orderIdFromExtra = intent.getStringExtra("order_id")
        if (!orderIdFromExtra.isNullOrBlank()) {
            initialOrderId = orderIdFromExtra
            return
        }
        val data: Uri? = intent.data
        if (data != null && data.scheme == "sndmart" && data.host == "order") {
            val orderId = data.lastPathSegment
            if (!orderId.isNullOrBlank()) {
                initialOrderId = orderId
            }
        }
    }
}
