package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
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

    var addresses by remember { mutableStateOf<List<CustomerAddress>>(emptyList()) }
    var selectedAddressId by remember { mutableStateOf<String?>(null) }
    var isLoadingAddresses by remember { mutableStateOf(true) }

    var isPlacingOrder by remember { mutableStateOf(false) }
    var placementError by remember { mutableStateOf<String?>(null) }

    // Resolve the coupon code chosen in the cart into a Coupon object so the discount
    // is actually applied (and re-validated) at order placement.
    var appliedCoupon by remember { mutableStateOf<Coupon?>(null) }

    // Payment methods
    val paymentMethods = listOf("cod" to "Cash on Delivery (COD)", "upi" to "UPI / Instant Pay", "card" to "Credit / Debit Card")
    var selectedPaymentMethod by remember { mutableStateOf("cod") }

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

    LaunchedEffect(couponCode, cityId) {
        if (!couponCode.isNullOrBlank() && !cityId.isNullOrBlank()) {
            val res = repository.getCouponByCode(couponCode, cityId)
            appliedCoupon = res.getOrNull()
        } else {
            appliedCoupon = null
        }
    }

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
                        if (cityId == null) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Please select a delivery city")
                            }
                            return@Button
                        }

                        coroutineScope.launch {
                            isPlacingOrder = true
                            placementError = null

                            val hotelVendorId = if (isHotel) repository.hotelCart.value.firstOrNull()?.vendorId else null
                            val res = repository.placeOrder(
                                userId = userId,
                                isHotel = isHotel,
                                vendorId = hotelVendorId,
                                cityId = cityId,
                                addressId = selectedAddressId!!,
                                slotId = slotId,
                                paymentMethod = selectedPaymentMethod,
                                coupon = appliedCoupon
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
                        .padding(16.dp)
                        .height(50.dp)
                        .testTag("place_order_button"),
                    enabled = !isPlacingOrder,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
                ) {
                    if (isPlacingOrder) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("Confirm & Place Order", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    ErrorCard(message = placementError!!, onRetry = {})
                }
            }

            // Addresses section
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

                        if (isLoadingAddresses) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                                color = NaturalPrimary
                            )
                        } else if (addresses.isEmpty()) {
                            Text(
                                "No address added yet. Please add a delivery address.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            addresses.forEach { addr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    RadioButton(
                                        selected = selectedAddressId == addr.id,
                                        onClick = { selectedAddressId = addr.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = NaturalPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(addr.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("•  ${addr.recipientName}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
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
                                    .padding(vertical = 4.dp),
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
        }
    }

    // Add Address Dialog
    if (showAddAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddAddressDialog = false },
            title = { Text("Add Delivery Address", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newRecipientName,
                        onValueChange = { newRecipientName = it },
                        label = { Text("Recipient Name") },
                        modifier = Modifier.fillMaxWidth().testTag("addr_name_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = newPhone,
                        onValueChange = { newPhone = it },
                        label = { Text("Contact Phone") },
                        modifier = Modifier.fillMaxWidth().testTag("addr_phone_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = newAddressLine,
                        onValueChange = { newAddressLine = it },
                        label = { Text("House / Flat / Street / Area") },
                        modifier = Modifier.fillMaxWidth().testTag("addr_line_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = newLandmark,
                        onValueChange = { newLandmark = it },
                        label = { Text("Landmark (Optional)") },
                        modifier = Modifier.fillMaxWidth().testTag("addr_landmark_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newLat,
                            onValueChange = { newLat = it },
                            label = { Text("Latitude") },
                            modifier = Modifier.weight(1f).testTag("addr_lat_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = newLng,
                            onValueChange = { newLng = it },
                            label = { Text("Longitude") },
                            modifier = Modifier.weight(1f).testTag("addr_lng_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Home", "Work", "Other").forEach { label ->
                            FilterChip(
                                selected = newLabel == label,
                                onClick = { newLabel = label },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NaturalPrimaryContainer,
                                    selectedLabelColor = NaturalOnPrimaryContainer
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAddressLine.isBlank() || newRecipientName.isBlank() || newPhone.isBlank()) {
                            return@Button
                        }
                        coroutineScope.launch {
                            val newAddr = CustomerAddress(
                                userId = userId ?: "user",
                                label = newLabel,
                                recipientName = newRecipientName,
                                phone = newPhone,
                                addressLine = newAddressLine,
                                landmark = newLandmark.takeIf { it.isNotBlank() },
                                lat = newLat.trim().toDoubleOrNull(),
                                lng = newLng.trim().toDoubleOrNull(),
                                isDefault = addresses.isEmpty()
                            )
                            val res = repository.addAddress(newAddr)
                            if (res.isSuccess) {
                                val saved = res.getOrNull()
                                showAddAddressDialog = false
                                loadAddresses()
                                selectedAddressId = saved?.id
                            } else {
                                snackbarHostState.showSnackbar("Failed to save address: ${res.exceptionOrNull()?.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Save Address")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAddressDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
