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
import com.example.data.repository.SndmartRepository
import com.example.service.InAppNotification
import com.example.service.SndmartMessagingService
import com.example.ui.components.InAppNotificationBanner
import com.example.ui.components.LocationDetectionState
import com.example.ui.components.LocationRequirementDialog
import com.example.ui.screens.*
import com.example.ui.theme.*
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

                fun performLocationDetectionAndCityAssignment() {
                    coroutineScope.launch {
                        showLocationDialog = true
                        locationDetectionState = LocationDetectionState.DETECTING

                        val coordinates = locationDetector.getCurrentCoordinates()
                        val lat = coordinates?.latitude
                        val lng = coordinates?.longitude

                        val geoCity = if (lat != null && lng != null) {
                            locationDetector.getCityNameFromCoordinates(lat, lng)
                        } else null
                        detectedCityName = geoCity

                        // Strictly fetch from backend — no dummy cities
                        val citiesResult = repository.getActiveCities()
                        val backendCities = citiesResult.getOrNull()?.filter { it.status == "active" } ?: emptyList()

                        if (backendCities.isNotEmpty()) {
                            activeCitiesSummary = backendCities.joinToString(", ") { it.name }
                            val matchedCity = locationDetector.matchWithBackendCities(geoCity, lat, lng, backendCities)
                            if (matchedCity != null) {
                                sessionManager.setSelectedCity(matchedCity)
                                assignedCityName = matchedCity.name
                                locationDetectionState = LocationDetectionState.SUCCESS
                                showCityPicker = false
                            } else {
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                            }
                        } else {
                            if (selectedCity != null) {
                                showLocationDialog = false
                            } else {
                                locationDetectionState = LocationDetectionState.UNSUPPORTED_AREA
                                activeCitiesSummary = "No active cities loaded from backend."
                            }
                        }
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
                    }
                }

                // Require location on app startup and auto-detect city
                LaunchedEffect(Unit) {
                    if (!locationDetector.hasLocationPermission()) {
                        locationDetectionState = LocationDetectionState.PERMISSION_REQUIRED
                        showLocationDialog = true
                    } else {
                        performLocationDetectionAndCityAssignment()
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

                // Show city picker if none selected and not currently in location dialog
                LaunchedEffect(selectedCity, showLocationDialog) {
                    if (selectedCity == null && !showLocationDialog) {
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
                                    label = { Text("Account", fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag("nav_item_profile")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route
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
                                    onOpenSettings = { showSupabaseSettings = true }
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
                                    onAuthSuccess = { hasCity ->
                                        navController.popBackStack()
                                        if (!hasCity) {
                                            showCityPicker = true
                                        }
                                    },
                                    onBack = { navController.popBackStack() }
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
