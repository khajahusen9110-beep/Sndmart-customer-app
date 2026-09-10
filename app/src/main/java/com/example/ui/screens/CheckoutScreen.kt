package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.AddressPickerDialog
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    isHotel: Boolean,
    couponCode: String?,
    slotId: String?,
    cityId: String?,
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit,
    onOrderPlacedSuccess: (orderId: String) -> Unit,
    onRequireLogin: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val userId = sessionManager.userId.collectAsState().value
    val isLoggedIn = sessionManager.isLoggedIn.collectAsState().value

    // Address & City resolution
    var addresses by remember { mutableStateOf<List<CustomerAddress>>(emptyList()) }
    var selectedAddressId by remember { mutableStateOf<String?>(null) }
    var isLoadingAddresses by remember { mutableStateOf(true) }

    val selectedAddress = addresses.find { it.id == selectedAddressId }
    var effectiveCityId by remember { mutableStateOf(cityId ?: sessionManager.selectedCity.value?.id) }
    var effectiveCityName by remember { mutableStateOf(sessionManager.selectedCity.value?.name ?: "Current City") }
    var addressDistanceKm by remember { mutableStateOf<Double?>(null) }

    // Live Cart items and Subtotal (fresh fetch)
    var freshItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    var isLoadingFreshCart by remember { mutableStateOf(true) }
    val subtotal = freshItems.sumOf { it.totalPrice }

    // Delivery Options (delivery_slots and express_delivery_settings)
    var scheduledSlots by remember { mutableStateOf<List<DeliverySlot>>(emptyList()) }
    var expressSettings by remember { mutableStateOf<ExpressDeliverySettings?>(null) }
    var isLoadingDeliverySettings by remember { mutableStateOf(true) }
    var deliverySettingsError by remember { mutableStateOf<String?>(null) }

    // Selected delivery type ("scheduled" or "express" or "none")
    var selectedDeliveryType by remember { mutableStateOf(if (slotId != null) "scheduled" else "scheduled") }
    var selectedSlotId by remember { mutableStateOf<String?>(slotId) }

    // Coupon handling (authoritative Supabase validation)
    var couponInput by remember { mutableStateOf(couponCode ?: "") }
    var appliedCoupon by remember { mutableStateOf<Coupon?>(null) }
    var authoritativeDiscount by remember { mutableStateOf(0.0) }
    var isValidatingCoupon by remember { mutableStateOf(false) }
    var couponErrorMessage by remember { mutableStateOf<String?>(null) }

    // Payment methods
    val paymentMethods = listOf(
        "cod" to "Cash on Delivery (COD)",
        "upi" to "UPI / Instant Pay",
        "card" to "Credit / Debit Card"
    )
    var selectedPaymentMethod by remember { mutableStateOf("cod") }

    // Order placement state
    var isPlacingOrder by remember { mutableStateOf(false) }
    var placementError by remember { mutableStateOf<String?>(null) }

    // Add Address Modal state
    var showAddAddressDialog by remember { mutableStateOf(false) }
    var newRecipientName by remember { mutableStateOf(sessionManager.userName.value ?: "") }
    var newPhone by remember { mutableStateOf(sessionManager.userPhone.value ?: "") }
    var newAddressLine by remember { mutableStateOf("") }
    var newLandmark by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("Home") }
    var newLat by remember { mutableStateOf("") }
    var newLng by remember { mutableStateOf("") }

    fun loadAddresses() {
        if (userId.isNullOrBlank()) {
            isLoadingAddresses = false
            return
        }
        coroutineScope.launch {
            isLoadingAddresses = true
            val res = repository.getAddresses(userId)
            if (res.isSuccess) {
                addresses = res.getOrNull() ?: emptyList()
                if (addresses.isNotEmpty() && selectedAddressId == null) {
                    val defaultAddr = addresses.find { it.isDefault } ?: addresses.first()
                    selectedAddressId = defaultAddr.id
                }
            }
            isLoadingAddresses = false
        }
    }

    LaunchedEffect(userId) {
        loadAddresses()
    }

    // When selected address changes, resolve city & distance
    LaunchedEffect(selectedAddress, freshItems, effectiveCityId) {
        if (selectedAddress != null) {
            val addrCityId = selectedAddress.cityId
            if (!addrCityId.isNullOrBlank()) {
                effectiveCityId = addrCityId
            }
            val hotelVendorId = freshItems.firstOrNull { 
                !it.cartItem.vendorId.isNullOrBlank() || !it.product.vendorId.isNullOrBlank() 
            }?.let { it.cartItem.vendorId ?: it.product.vendorId }
            val dist = repository.resolveDeliveryDistanceKm(
                address = selectedAddress,
                cityId = effectiveCityId,
                vendorId = hotelVendorId,
                isHotel = isHotel
            )
            addressDistanceKm = dist
        }
    }

    // Fresh price re-fetch
    fun fetchFreshCart() {
        val cid = effectiveCityId ?: return
        coroutineScope.launch {
            isLoadingFreshCart = true
            val res = repository.getFreshCartItems(isHotel = isHotel, cityId = cid)
            if (res.isSuccess) {
                freshItems = res.getOrNull() ?: emptyList()
            }
            isLoadingFreshCart = false
        }
    }

    LaunchedEffect(effectiveCityId, isHotel) {
        fetchFreshCart()
    }

    // Load delivery options (delivery_slots and express_delivery_settings) from Supabase
    fun loadDeliveryOptions() {
        val cid = effectiveCityId ?: return
        coroutineScope.launch {
            isLoadingDeliverySettings = true
            deliverySettingsError = null

            val slotsRes = repository.getDeliverySlots(cid)
            val expressRes = repository.getExpressDeliverySettings(cid)

            val slots = slotsRes.getOrNull() ?: emptyList()
            val express = expressRes.getOrNull()

            scheduledSlots = slots
            expressSettings = express

            val hasSlots = slots.isNotEmpty()
            val hasExpress = express != null && express.isActive

            if (hasSlots && hasExpress) {
                // Both available -> preserve choice or default to scheduled
                if (selectedDeliveryType == "express") {
                    selectedDeliveryType = "express"
                    selectedSlotId = null
                } else {
                    selectedDeliveryType = "scheduled"
                    if (selectedSlotId == null || slots.none { it.id == selectedSlotId }) {
                        selectedSlotId = slots.firstOrNull()?.id
                    }
                }
            } else if (hasSlots && !hasExpress) {
                // Only slots available -> no toggle needed
                selectedDeliveryType = "scheduled"
                if (selectedSlotId == null || slots.none { it.id == selectedSlotId }) {
                    selectedSlotId = slots.firstOrNull()?.id
                }
            } else if (!hasSlots && hasExpress) {
                // Only express available -> no toggle needed
                selectedDeliveryType = "express"
                selectedSlotId = null
            } else {
                // Neither available
                selectedDeliveryType = "none"
                selectedSlotId = null
            }

            isLoadingDeliverySettings = false
        }
    }

    LaunchedEffect(effectiveCityId) {
        if (!effectiveCityId.isNullOrBlank()) {
            loadDeliveryOptions()
        }
    }

    // Coupon validation function
    fun applyCouponCode(code: String) {
        val cid = effectiveCityId ?: return
        if (code.isBlank()) return

        coroutineScope.launch {
            isValidatingCoupon = true
            couponErrorMessage = null
            val res = repository.validateAndApplyCoupon(code, cid, subtotal, userId)
            if (res.isSuccess) {
                val validation = res.getOrNull()!!
                if (validation.isValid) {
                    appliedCoupon = validation.coupon ?: Coupon(code = code.trim().uppercase(), discountValue = validation.discountAmount)
                    authoritativeDiscount = validation.discountAmount
                    couponErrorMessage = null
                } else {
                    appliedCoupon = null
                    authoritativeDiscount = 0.0
                    couponErrorMessage = validation.errorMessage ?: "Coupon is not valid for this order"
                }
            } else {
                appliedCoupon = null
                authoritativeDiscount = 0.0
                couponErrorMessage = res.exceptionOrNull()?.message ?: "Coupon validation failed"
            }
            isValidatingCoupon = false
        }
    }

    // Initial coupon validation if passed from cart
    LaunchedEffect(couponCode, effectiveCityId, subtotal) {
        if (!couponCode.isNullOrBlank() && !effectiveCityId.isNullOrBlank() && subtotal > 0.0 && appliedCoupon == null) {
            applyCouponCode(couponCode)
        }
    }

    val distanceKm = addressDistanceKm ?: 1.0
    val hasActiveSlots = scheduledSlots.isNotEmpty()
    val hasActiveExpress = expressSettings != null && expressSettings!!.isActive
    val isDeliveryAvailable = hasActiveSlots || hasActiveExpress

    val matchedSlot = scheduledSlots.find { it.id == selectedSlotId } ?: scheduledSlots.firstOrNull()
    val scheduledFee = matchedSlot?.getEffectiveDeliveryFee(subtotal) ?: 0.0
    val isSlotFree = matchedSlot?.isFreeDeliveryEligible(subtotal) == true

    val (expressFee, isExpressFree) = if (expressSettings != null) {
        expressSettings!!.calculateCharge(distanceKm, subtotal)
    } else {
        Pair(0.0, false)
    }

    val calculatedDeliveryFee: Double? = when {
        !isDeliveryAvailable -> null
        selectedDeliveryType == "express" && hasActiveExpress -> expressFee
        selectedDeliveryType == "scheduled" && hasActiveSlots -> scheduledFee
        else -> null
    }

    val isExpressMinOrderNotMet = selectedDeliveryType == "express" &&
            expressSettings?.minOrderAmount != null &&
            subtotal < expressSettings!!.minOrderAmount!!

    val handlingFee = 5.0
    val totalAmount = if (calculatedDeliveryFee != null) {
        (subtotal - authoritativeDiscount + calculatedDeliveryFee + handlingFee).coerceAtLeast(0.0)
    } else null

    val isCheckoutDisabled = isPlacingOrder ||
            isLoadingDeliverySettings ||
            !isDeliveryAvailable ||
            (selectedDeliveryType == "scheduled" && matchedSlot == null) ||
            isExpressMinOrderNotMet ||
            selectedAddressId == null ||
            effectiveCityId == null ||
            subtotal <= 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!isDeliveryAvailable && !isLoadingDeliverySettings) {
                        Text(
                            text = "Delivery isn't currently available in your area",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (isExpressMinOrderNotMet) {
                        Text(
                            text = "Minimum order ₹${"%.0f".format(expressSettings!!.minOrderAmount!!)} required for Express Delivery",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = {
                            if (!isLoggedIn || userId.isNullOrBlank()) {
                                onRequireLogin()
                                return@Button
                            }
                            if (selectedAddressId == null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Please select or add a delivery address")
                                }
                                return@Button
                            }
                            if (effectiveCityId == null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Please select a serviceable city")
                                }
                                return@Button
                            }
                            if (!isDeliveryAvailable) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Delivery isn't currently available in your area")
                                }
                                return@Button
                            }
                            if (isExpressMinOrderNotMet) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Minimum order ₹${"%.0f".format(expressSettings!!.minOrderAmount!!)} required for Express Delivery")
                                }
                                return@Button
                            }

                            coroutineScope.launch {
                                isPlacingOrder = true
                                placementError = null

                                val hotelVendorId = if (isHotel) repository.hotelCart.value.firstOrNull()?.vendorId else null
                                val deliverySnapshot = mapOf(
                                    "delivery_type" to selectedDeliveryType,
                                    "delivery_fee" to (calculatedDeliveryFee ?: 0.0),
                                    "distance_km" to distanceKm,
                                    "slot_id" to if (selectedDeliveryType == "scheduled") selectedSlotId else null
                                )

                                val res = repository.placeOrder(
                                    userId = userId,
                                    isHotel = isHotel,
                                    vendorId = hotelVendorId,
                                    cityId = effectiveCityId!!,
                                    addressId = selectedAddressId!!,
                                    slotId = if (selectedDeliveryType == "scheduled") selectedSlotId else null,
                                    paymentMethod = selectedPaymentMethod,
                                    coupon = appliedCoupon,
                                    deliveryType = selectedDeliveryType,
                                    deliveryDistanceKm = distanceKm,
                                    deliveryOptionSnapshot = deliverySnapshot
                                )

                                if (res.isSuccess) {
                                    val order = res.getOrNull()!!
                                    isPlacingOrder = false
                                    onOrderPlacedSuccess(order.id ?: "")
                                } else {
                                    isPlacingOrder = false
                                    placementError = res.exceptionOrNull()?.message ?: "Failed to place order"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("place_order_button"),
                        enabled = !isCheckoutDisabled,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NaturalPrimary,
                            disabledContainerColor = NaturalPrimary.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isPlacingOrder || isLoadingDeliverySettings) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isPlacingOrder) "Placing Order..." else "Loading Options...")
                        } else {
                            val totalText = if (totalAmount != null) " • ₹${"%.0f".format(totalAmount)}" else ""
                            Text("Confirm & Place Order$totalText", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isLoggedIn) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = NaturalPrimaryContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = NaturalPrimary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sign in to complete order", fontWeight = FontWeight.Bold, color = NaturalOnPrimaryContainer)
                                Text("Verify your mobile/email for live order tracking", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Button(
                                onClick = onRequireLogin,
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.testTag("checkout_signin_button")
                            ) {
                                Text("Sign In")
                            }
                        }
                    }
                }
            }

            if (placementError != null) {
                item {
                    ErrorCard(message = placementError!!, onRetry = { placementError = null })
                }
            }

            // City Indicator
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delivering in: $effectiveCityName",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (addressDistanceKm != null && addressDistanceKm!! > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(~${"%.1f".format(addressDistanceKm)} km away)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Delivery Address Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Delivery Address",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showAddAddressDialog = true },
                                    modifier = Modifier.testTag("detect_address_button"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = NaturalPrimary)
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Detect GPS", fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { showAddAddressDialog = true },
                                    modifier = Modifier.testTag("add_address_button"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = NaturalPrimary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add New", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (isLoadingAddresses) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                                color = NaturalPrimary
                            )
                        } else if (addresses.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(
                                    "No address added yet. Use GPS to detect exact doorstep location.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { showAddAddressDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Detect My Location", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            addresses.forEach { addr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAddressId = addr.id }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    RadioButton(
                                        selected = selectedAddressId == addr.id,
                                        onClick = { selectedAddressId = addr.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary),
                                        modifier = Modifier.testTag("address_radio_${addr.id}")
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = NaturalPrimaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = addr.label.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NaturalOnPrimaryContainer
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(addr.recipientName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(addr.addressLine, style = MaterialTheme.typography.bodySmall)
                                        if (!addr.landmark.isNullOrBlank()) {
                                            Text("Landmark: ${addr.landmark}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        }
                                        Text("Phone: ${addr.phone}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Delivery Method Section (Dynamically powered by Supabase RPC)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delivery_method_section"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Delivery Option",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (isLoadingDeliverySettings) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NaturalPrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Updating...", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isLoadingDeliverySettings) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                )
                            }
                        } else if (!isDeliveryAvailable) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "Delivery isn't currently available in your area",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            // If BOTH Scheduled slots and Express settings exist: Show Tab Toggle!
                            if (hasActiveSlots && hasActiveExpress) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val isScheduledTab = selectedDeliveryType == "scheduled"
                                        val isExpressTab = selectedDeliveryType == "express"

                                        // Scheduled Delivery Tab
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isScheduledTab) NaturalPrimary else Color.Transparent,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedDeliveryType = "scheduled"
                                                    if (selectedSlotId == null) {
                                                        selectedSlotId = scheduledSlots.firstOrNull()?.id
                                                    }
                                                }
                                                .testTag("tab_scheduled_delivery")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.CalendarToday,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp),
                                                    tint = if (isScheduledTab) Color.White else TextPrimary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    "Scheduled Delivery",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isScheduledTab) Color.White else TextPrimary,
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        // Express Delivery Tab
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isExpressTab) NaturalPrimary else Color.Transparent,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { selectedDeliveryType = "express" }
                                                .testTag("tab_express_delivery")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(17.dp),
                                                    tint = if (isExpressTab) Color.White else Color(0xFFE65100)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                val mins = expressSettings?.estimatedMinutes ?: 30
                                                Text(
                                                    "Express (~$mins min)",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isExpressTab) Color.White else TextPrimary,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 1. SCHEDULED DELIVERY CONTENT (when scheduled is selected or it's the only option)
                            if (selectedDeliveryType == "scheduled" && hasActiveSlots) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!hasActiveExpress) {
                                        Text(
                                            "Scheduled Delivery Slots",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    }

                                    scheduledSlots.forEach { slot ->
                                        val isPicked = selectedSlotId == slot.id
                                        val isSlotFree = slot.isFreeDeliveryEligible(subtotal)
                                        val effectiveFee = slot.getEffectiveDeliveryFee(subtotal)
                                        val neededMore = slot.amountNeededForFreeDelivery(subtotal)

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isPicked) NaturalPrimaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(
                                                width = if (isPicked) 2.dp else 1.dp,
                                                color = if (isPicked) NaturalPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { selectedSlotId = slot.id }
                                                .testTag("slot_radio_${slot.id}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = isPicked,
                                                    onClick = { selectedSlotId = slot.id },
                                                    colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = slot.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                    val times = listOfNotNull(
                                                        slot.displayStartTime.takeIf { it.isNotBlank() },
                                                        slot.displayEndTime.takeIf { it.isNotBlank() }
                                                    ).joinToString(" - ")
                                                    if (times.isNotBlank()) {
                                                        Text(
                                                            text = times,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = TextSecondary
                                                        )
                                                    }
                                                    if (slot.isFreeDelivery == true && slot.minOrderAmount != null && slot.minOrderAmount > 0.0) {
                                                        val helperText = if (isSlotFree) {
                                                            "Free delivery unlocked (Min order ₹${"%.0f".format(slot.minOrderAmount)})"
                                                        } else {
                                                            "Add ₹${"%.0f".format(neededMore)} more for free delivery (Min order ₹${"%.0f".format(slot.minOrderAmount)})"
                                                        }
                                                        Text(
                                                            text = helperText,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (isSlotFree) NaturalPrimary else TextSecondary
                                                        )
                                                    } else if (slot.minOrderAmount != null && slot.minOrderAmount > 0.0) {
                                                        Text(
                                                            text = "Min order ₹${"%.0f".format(slot.minOrderAmount)}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = TextSecondary
                                                        )
                                                    }
                                                }
                                                if (isSlotFree) {
                                                    Surface(
                                                        color = NaturalPrimary.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            "FREE",
                                                            color = NaturalPrimary,
                                                            fontWeight = FontWeight.Bold,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                } else {
                                                    Text(
                                                        text = "₹${"%.0f".format(effectiveFee)}",
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = TextPrimary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. EXPRESS DELIVERY CONTENT (when express is selected or it's the only option)
                            if (selectedDeliveryType == "express" && hasActiveExpress && expressSettings != null) {
                                val settings = expressSettings!!
                                val minOrderMet = settings.isSubtotalEligible(subtotal)
                                val minOrder = settings.minOrderAmount ?: 0.0

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (minOrderMet) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.5.dp,
                                            color = if (minOrderMet) Color(0xFFFF9800) else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("express_delivery_card")
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Color(0xFFFF9800).copy(alpha = 0.15f),
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                Icons.Default.Bolt,
                                                                contentDescription = null,
                                                                tint = Color(0xFFE65100),
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(
                                                            "Express Delivery",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            "Delivered within ${settings.estimatedMinutes} minutes",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = TextSecondary
                                                        )
                                                    }
                                                }

                                                if (minOrderMet) {
                                                    if (isExpressFree) {
                                                        Surface(
                                                            color = NaturalPrimary.copy(alpha = 0.15f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "FREE",
                                                                color = NaturalPrimary,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            "₹${"%.0f".format(expressFee)}",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = Color(0xFFE65100)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Distance & Fee Breakdown details
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Navigation,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "Distance: ~${"%.1f".format(distanceKm)} km from fulfillment point",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }

                                            if (!minOrderMet) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Outlined.Info,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            "Minimum order ₹${"%.0f".format(minOrder)} for Express Delivery",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            } else if (isExpressFree) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Free delivery unlocked! (Order subtotal > ₹${"%.0f".format(settings.freeDeliveryMinOrder ?: 0.0)} within ${"%.0f".format(settings.freeDeliveryMaxKm ?: 0.0)} km)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = NaturalPrimary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            } else if (settings.baseKm != null && settings.baseCharge != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                val breakdown = if (distanceKm <= settings.baseKm!!) {
                                                    "Base fee ₹${"%.0f".format(settings.baseCharge!!)} up to ${"%.0f".format(settings.baseKm!!)} km"
                                                } else {
                                                    "Base ₹${"%.0f".format(settings.baseCharge!!)} + ₹${"%.0f".format(settings.perKmChargeBeyond ?: 0.0)}/km beyond ${"%.0f".format(settings.baseKm!!)} km"
                                                }
                                                Text(
                                                    breakdown,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Coupon Code Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Have a Coupon?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (appliedCoupon != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = NaturalPrimaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                "Coupon ${appliedCoupon!!.code} Applied!",
                                                fontWeight = FontWeight.Bold,
                                                color = NaturalOnPrimaryContainer,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "You save ₹${"%.0f".format(authoritativeDiscount)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NaturalPrimary
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            appliedCoupon = null
                                            authoritativeDiscount = 0.0
                                            couponInput = ""
                                        },
                                        modifier = Modifier.testTag("remove_coupon_button")
                                    ) {
                                        Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = couponInput,
                                    onValueChange = { couponInput = it.uppercase() },
                                    placeholder = { Text("Enter Coupon Code") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("coupon_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    trailingIcon = {
                                        if (isValidatingCoupon) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                )
                                Button(
                                    onClick = { applyCouponCode(couponInput) },
                                    enabled = couponInput.isNotBlank() && !isValidatingCoupon,
                                    modifier = Modifier.testTag("apply_coupon_checkout_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Apply")
                                }
                            }
                            if (couponErrorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = couponErrorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Payment Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Payment Method",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        paymentMethods.forEach { (methodKey, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPaymentMethod = methodKey }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedPaymentMethod == methodKey,
                                    onClick = { selectedPaymentMethod = methodKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary),
                                    modifier = Modifier.testTag("payment_radio_$methodKey")
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedPaymentMethod == methodKey) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // Bill Summary
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Bill Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Items Subtotal", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            Text("₹${"%.0f".format(subtotal)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (authoritativeDiscount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Coupon Discount", color = NaturalPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("-₹${"%.0f".format(authoritativeDiscount)}", color = NaturalPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Delivery Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            if (isLoadingDeliverySettings) {
                                Text("Calculating...", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            } else if (!isDeliveryAvailable) {
                                Text("Unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            } else if (calculatedDeliveryFee == 0.0) {
                                Text("FREE", color = NaturalPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            } else if (calculatedDeliveryFee != null) {
                                Text("₹${"%.0f".format(calculatedDeliveryFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("--", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Handling Fee", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            Text("₹${"%.0f".format(handlingFee)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("To Pay", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            if (totalAmount != null && isDeliveryAvailable) {
                                Text(
                                    "₹${"%.0f".format(totalAmount)}",
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = NaturalPrimary
                                )
                            } else {
                                Text("--", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Address Dialog with Google Maps and Places Autocomplete
    if (showAddAddressDialog) {
        val userName = sessionManager.userName.collectAsState().value ?: ""
        val userPhone = sessionManager.userPhone.collectAsState().value ?: ""
        AddressPickerDialog(
            repository = repository,
            userId = userId ?: "user",
            defaultRecipientName = newRecipientName.ifBlank { userName },
            defaultPhone = newPhone.ifBlank { userPhone },
            onDismiss = { showAddAddressDialog = false },
            onSaved = { saved ->
                showAddAddressDialog = false
                loadAddresses()
                selectedAddressId = saved.id
            }
        )
    }
}
