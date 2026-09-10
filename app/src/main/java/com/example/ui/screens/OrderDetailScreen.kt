package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.ui.components.BillRow
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    cityId: String?,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onNavigateToCart: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var order by remember { mutableStateOf<Order?>(null) }
    var orderItems by remember { mutableStateOf<List<OrderItem>>(emptyList()) }
    var statusHistory by remember { mutableStateOf<List<OrderStatusHistory>>(emptyList()) }
    var deliveryAssignment by remember { mutableStateOf<DeliveryAssignment?>(null) }
    var deliveryPartner by remember { mutableStateOf<DeliveryPartner?>(null) }
    var deliveryAddress by remember { mutableStateOf<CustomerAddress?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Rating modal state
    var showReviewDialog by remember { mutableStateOf(false) }
    var vendorRating by remember { mutableStateOf(5) }
    var vendorComment by remember { mutableStateOf("") }
    var partnerRating by remember { mutableStateOf(5) }
    var partnerComment by remember { mutableStateOf("") }
    var reviewSubmitted by remember { mutableStateOf(false) }
    var reviewedOrderIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Reorder loading state
    var isReordering by remember { mutableStateOf(false) }

    // Polling function for active orders
    fun loadOrderData(isSilent: Boolean = false) {
        coroutineScope.launch {
            if (!isSilent) isLoading = true
            errorMessage = null

            val oRes = repository.getOrderById(orderId)
            if (oRes.isSuccess) {
                val fetchedOrder = oRes.getOrNull()
                order = fetchedOrder
                val itemsRes = repository.getOrderItems(orderId)
                if (itemsRes.isSuccess) {
                    orderItems = itemsRes.getOrNull() ?: emptyList()
                }
                val histRes = repository.getOrderStatusHistory(orderId)
                if (histRes.isSuccess) {
                    statusHistory = histRes.getOrNull() ?: emptyList()
                }
                val assignRes = repository.getDeliveryAssignment(orderId)
                if (assignRes.isSuccess) {
                    deliveryAssignment = assignRes.getOrNull()
                }
                if (fetchedOrder?.deliveryPartner != null) {
                    deliveryPartner = fetchedOrder.deliveryPartner
                } else {
                    val partnerId = fetchedOrder?.deliveryPartnerId ?: deliveryAssignment?.deliveryPartnerId
                    if (!partnerId.isNullOrBlank()) {
                        val partRes = repository.getDeliveryPartner(partnerId)
                        if (partRes.isSuccess) {
                            deliveryPartner = partRes.getOrNull()
                        }
                    } else {
                        deliveryPartner = null
                    }
                }

                // Resolve delivery address for map pin
                val addrId = fetchedOrder?.addressId
                if (!addrId.isNullOrBlank()) {
                    val addrRes = repository.getAddressById(addrId)
                    if (addrRes.isSuccess && addrRes.getOrNull() != null) {
                        deliveryAddress = addrRes.getOrNull()
                    } else if (!fetchedOrder.customerId.isNullOrBlank()) {
                        val allAddrs = repository.getAddresses(fetchedOrder.customerId)
                        if (allAddrs.isSuccess) {
                            deliveryAddress = allAddrs.getOrNull()?.find { it.id == addrId }
                                ?: allAddrs.getOrNull()?.firstOrNull()
                        }
                    }
                }
            } else {
                errorMessage = oRes.exceptionOrNull()?.message
            }
            // Resolve which orders the customer has already reviewed (for the delivered
            // "Rate this order" prompt — only show it if no review exists yet).
            val custId = order?.customerId
            if (!custId.isNullOrBlank()) {
                val reviewedRes = repository.getReviewedOrderIds(custId)
                if (reviewedRes.isSuccess) reviewedOrderIds = reviewedRes.getOrNull() ?: emptySet()
            }
            if (!isSilent) isLoading = false
        }
    }

    // Initial load
    LaunchedEffect(orderId) {
        loadOrderData(isSilent = false)
    }

    // Polling effect: poll every 15s while active
    LaunchedEffect(order?.status) {
        val activeStatuses = listOf("pending", "confirmed", "preparing", "ready", "out_for_delivery")
        while (order != null && activeStatuses.contains(order!!.status.lowercase())) {
            delay(15000)
            loadOrderData(isSilent = true)
        }
    }

    // Driver location polling: re-fetch the delivery_partners row every ~12s while the
    // order is still active (confirmed → out_for_delivery) and partner is assigned. Stops once delivered/cancelled.
    val activeTrackingStatuses = remember { listOf("confirmed", "preparing", "ready", "out_for_delivery") }
    val effectivePartnerId = order?.deliveryPartnerId ?: deliveryAssignment?.deliveryPartnerId ?: deliveryPartner?.id
    val showLiveTracking = !effectivePartnerId.isNullOrBlank() && activeTrackingStatuses.contains(order?.status?.lowercase())

    LaunchedEffect(effectivePartnerId, order?.status) {
        val partnerId = effectivePartnerId
        while (showLiveTracking && !partnerId.isNullOrBlank()) {
            delay(12000) // Poll partner position every 12 seconds
            val partRes = repository.getDeliveryPartner(partnerId)
            if (partRes.isSuccess && partRes.getOrNull() != null) {
                deliveryPartner = partRes.getOrNull()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(order?.orderNumber ?: "Order Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadOrderData(isSilent = false) }, modifier = Modifier.testTag("refresh_detail_button")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (order != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Reorder Button
                        Button(
                            onClick = {
                                val currentCity = cityId ?: order?.cityId ?: ""
                                coroutineScope.launch {
                                    isReordering = true
                                    val res = repository.reorder(orderItems, currentCity)
                                    isReordering = false
                                    if (res.isSuccess) {
                                        snackbarHostState.showSnackbar(res.getOrNull() ?: "Items added to cart")
                                        onNavigateToCart()
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to reorder: ${res.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("reorder_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(24.dp),
                            enabled = !isReordering
                        ) {
                            if (isReordering) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reorder Items", fontWeight = FontWeight.Bold)
                            }
                        }

                        // Rate Order Button (delivered + no review submitted yet for this order)
                        val hasReviewed = reviewSubmitted || reviewedOrderIds.contains(orderId)
                        if (order!!.status.lowercase() == "delivered" && !hasReviewed) {
                            OutlinedButton(
                                onClick = { showReviewDialog = true },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("rate_order_button"),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rate Order")
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = NaturalPrimary
                )
            } else if (errorMessage != null) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadOrderData(isSilent = false) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (order == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Order details not found")
                }
            } else {
                val currentOrder = order!!

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Delivery OTP Card (Prominently displayed ONLY when out_for_delivery and partner is assigned)
                    if (showLiveTracking && currentOrder.status.lowercase() == "out_for_delivery" && !deliveryAssignment?.deliveryOtp.isNullOrBlank()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("delivery_otp_card"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PastelPeach),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.2f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = NaturalPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "DELIVERY VERIFICATION OTP",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = NaturalPrimary,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = deliveryAssignment!!.deliveryOtp!!,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 8.sp,
                                        color = NaturalPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Share this code with your delivery partner to confirm delivery.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 2. Live Tracking Section (ETA + Delivery Partner + Live Map)
                    // Shown ONLY when BOTH are true:
                    // - orders.delivery_partner_id is not null (partner assigned)
                    // - orders.status is one of: confirmed, preparing, ready, out_for_delivery
                    if (showLiveTracking) {
                        item {
                            if (deliveryPartner == null) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("live_tracking_loading_card"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = NaturalPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            "Loading delivery partner details...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("live_tracking_card"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        // A. Live ETA countdown
                                        LiveEtaCountdownBanner(
                                            estimatedDeliveryAt = deliveryAssignment?.estimatedDeliveryAt,
                                            estimatedDeliveryMinutes = deliveryAssignment?.estimatedDeliveryMinutes,
                                            isDelivered = currentOrder.status.lowercase() == "delivered"
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // B. Delivery Partner Card
                                        DeliveryPartnerCardContent(
                                            deliveryPartner = deliveryPartner!!,
                                            onCallClick = { phone ->
                                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                                context.startActivity(intent)
                                            }
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // C. Live Map
                                        LiveDeliveryMap(
                                            deliveryPartner = deliveryPartner!!,
                                            deliveryAddress = deliveryAddress
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Live Progress Stepper
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("order_stepper_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Live Order Tracking",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                OrderStatusStepper(
                                    currentStatus = currentOrder.status,
                                    history = statusHistory
                                )
                            }
                        }
                    }

                    // 4. Order Items
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Items Ordered (${orderItems.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (orderItems.isEmpty()) {
                                    Text(
                                        "No items recorded for this order.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    orderItems.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.productName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                Text(
                                                    "${item.quantity} x ₹${"%.0f".format(item.unitPrice)}${if (!item.variantLabel.isNullOrBlank()) " (${item.variantLabel})" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                "₹${"%.0f".format(item.totalPrice)}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Bill Summary & Payment Info
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Payment Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                val displayPaymentMethod = when (currentOrder.paymentMethod.lowercase()) {
                                    "cash" -> if (currentOrder.paymentStatus.lowercase() == "cod") "Cash on Delivery (COD)" else "Cash"
                                    "upi" -> "UPI"
                                    "card" -> "Card"
                                    "online" -> "Online"
                                    else -> currentOrder.paymentMethod.uppercase()
                                }
                                BillRow("Payment Method", displayPaymentMethod)
                                BillRow("Payment Status", currentOrder.paymentStatus.capitalize())
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                BillRow("Subtotal", "₹${"%.2f".format(currentOrder.subtotal)}")
                                if (currentOrder.discountAmount > 0) {
                                    BillRow("Discount", "-₹${"%.2f".format(currentOrder.discountAmount)}", color = SuccessGreen)
                                }
                                BillRow("Delivery Fee", "₹${"%.2f".format(currentOrder.deliveryFee)}")
                                BillRow("Handling Fee", "₹${"%.2f".format(currentOrder.handlingFee)}")
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                BillRow("Total Paid / Due", "₹${"%.2f".format(currentOrder.totalAmount)}", isBold = true, fontSize = 16.sp, color = NaturalPrimary)
                            }
                        }
                    }

                    // 6. Need Help? / Customer Support Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Need Help with this Order?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(10.dp))
                                // Placeholder support contacts — swap for real ones before launch.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+910000000000"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_call_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Call", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val text = "I need help with order ${currentOrder.orderNumber}"
                                            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                                            val url = "https://wa.me/910000000000?text=$encoded"
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_whatsapp_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("WhatsApp", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@sndmart.in?subject=${Uri.encode("Help with Order ${currentOrder.orderNumber}")}"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_email_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Email", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rate Order Dialog
    if (showReviewDialog) {
        AlertDialog(
            onDismissRequest = { showReviewDialog = false },
            title = { Text("Rate Your Experience", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hotel / Vendor Rating", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            Icon(
                                imageVector = if (star <= vendorRating) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star stars",
                                tint = AmberAccent,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { vendorRating = star }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = vendorComment,
                        onValueChange = { vendorComment = it },
                        label = { Text("Food / Item Feedback") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Delivery Partner Rating", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            Icon(
                                imageVector = if (star <= partnerRating) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star stars",
                                tint = AmberAccent,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { partnerRating = star }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = partnerComment,
                        onValueChange = { partnerComment = it },
                        label = { Text("Delivery Feedback") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentCustId = order?.customerId ?: ""
                        coroutineScope.launch {
                            if (!order?.vendorId.isNullOrBlank()) {
                                repository.submitVendorReview(
                                    VendorReview(
                                        vendorId = order!!.vendorId!!,
                                        customerId = currentCustId,
                                        orderId = orderId,
                                        rating = vendorRating,
                                        comment = vendorComment.takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                            if (!order?.deliveryPartnerId.isNullOrBlank()) {
                                repository.submitDeliveryPartnerReview(
                                    DeliveryPartnerReview(
                                        deliveryPartnerId = order!!.deliveryPartnerId!!,
                                        customerId = currentCustId,
                                        orderId = orderId,
                                        rating = partnerRating,
                                        comment = partnerComment.takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                            showReviewDialog = false
                            reviewSubmitted = true
                            snackbarHostState.showSnackbar("Thank you for your rating!")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Submit Review")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun OrderStatusStepper(
    currentStatus: String,
    history: List<OrderStatusHistory>
) {
    val steps = listOf(
        "pending" to "Order Placed",
        "confirmed" to "Order Confirmed",
        "preparing" to "Preparing Food / Packing",
        "ready" to "Ready for Dispatch",
        "out_for_delivery" to "Out for Delivery",
        "delivered" to "Delivered"
    )

    val currentStatusNormalized = currentStatus.lowercase()

    // Cancelled / rejected: distinct red state with the cancellation timestamp (if any).
    val isTerminalFailed = currentStatusNormalized == "cancelled" || currentStatusNormalized == "rejected"
    if (isTerminalFailed) {
        val cancelEntry = history.find {
            val s = it.status.lowercase()
            s == "cancelled" || s == "rejected"
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PastelCoral,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cancel, contentDescription = null, tint = NaturalBadgeRed)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        "This order was $currentStatusNormalized.",
                        fontWeight = FontWeight.Bold,
                        color = NaturalBadgeRed
                    )
                    if (!cancelEntry?.createdAt.isNullOrBlank()) {
                        Text(
                            cancelEntry!!.createdAt.take(16).replace("T", " "),
                            fontSize = 11.sp,
                            color = NaturalBadgeRed
                        )
                    }
                }
            }
        }
        return
    }

    // Done-state is driven ENTIRELY by order_status_history rows (the backend writes a
    // row on every status change) — never inferred from orders.status. The history is
    // fetched ordered by created_at.asc, so the last entry is the current/latest step.
    val historyByStatus = remember(history) { history.associateBy { it.status.lowercase() } }
    val latestStatus = history.lastOrNull()?.status?.lowercase() ?: currentStatusNormalized

    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, (key, label) ->
            val histItem = historyByStatus[key]
            val isDone = histItem != null
            val isCurrent = key == latestStatus

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = if (isDone) NaturalPrimary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDone) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            } else {
                                Text("${index + 1}", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }

                    if (index < steps.size - 1) {
                        val nextDone = historyByStatus[steps[index + 1].first] != null
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(32.dp)
                                .background(if (nextDone) NaturalPrimary else TextMuted.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = label,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isDone) TextPrimary else TextMuted,
                        fontSize = 14.sp
                    )
                    if (!histItem?.createdAt.isNullOrBlank()) {
                        Text(
                            text = histItem!!.createdAt.take(16).replace("T", " "),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

// Live ETA countdown: estimated_delivery_at - now(), ticking every 30s. Shows
// "Arriving any moment" once the ETA has passed but the order isn't delivered yet.
@Composable
fun EtaCountdown(estimatedDeliveryAt: String, isDelivered: Boolean) {
    LiveEtaCountdownBanner(
        estimatedDeliveryAt = estimatedDeliveryAt,
        estimatedDeliveryMinutes = null,
        isDelivered = isDelivered
    )
}

@Composable
fun LiveEtaCountdownBanner(
    estimatedDeliveryAt: String?,
    estimatedDeliveryMinutes: Int?,
    isDelivered: Boolean
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(estimatedDeliveryAt) {
        while (true) {
            delay(30000) // Ticks down every 30s
            now = System.currentTimeMillis()
        }
    }

    val label: String = when {
        isDelivered -> "Delivered"
        !estimatedDeliveryAt.isNullOrBlank() -> {
            val etaMillis = parseIsoTimestamp(estimatedDeliveryAt)
            if (etaMillis <= 0L) {
                if (estimatedDeliveryMinutes != null && estimatedDeliveryMinutes > 0) {
                    "Arriving in ~$estimatedDeliveryMinutes min"
                } else {
                    "Calculating arrival time..."
                }
            } else {
                val remainingMs = etaMillis - now
                if (remainingMs <= 0) {
                    "Arriving any moment"
                } else {
                    val remainingMin = kotlin.math.ceil(remainingMs / 60000.0).toInt().coerceAtLeast(1)
                    val h = remainingMin / 60
                    val m = remainingMin % 60
                    if (h > 0) "Arriving in ~${h}h ${m}m" else "Arriving in ~$m min"
                }
            }
        }
        estimatedDeliveryMinutes != null && estimatedDeliveryMinutes > 0 -> {
            "Arriving in ~$estimatedDeliveryMinutes min"
        }
        else -> {
            "Calculating arrival time..."
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PastelSage,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("eta_countdown_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.AccessTime,
                        contentDescription = "Estimated Delivery Time",
                        tint = DarkGreenText,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ESTIMATED ARRIVAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkGreenText.copy(alpha = 0.8f),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DarkGreenText
                    )
                }
            }

            // Live indicator badge
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(SuccessGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = SuccessGreen
                    )
                }
            }
        }
    }
}

@Composable
fun DeliveryPartnerCardContent(
    deliveryPartner: DeliveryPartner,
    onCallClick: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Photo / Icon placeholder
                Surface(
                    shape = CircleShape,
                    color = NaturalPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DeliveryDining,
                            contentDescription = "Delivery Partner Avatar",
                            tint = NaturalPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = deliveryPartner.name.ifBlank { "Delivery Partner" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val vehicleInfo = listOfNotNull(
                        deliveryPartner.vehicleType?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
                        deliveryPartner.vehicleNumber?.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")

                    Text(
                        text = if (vehicleInfo.isNotBlank()) vehicleInfo else "Delivery Partner",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (!deliveryPartner.phone.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onCallClick(deliveryPartner.phone) },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("call_partner_button")
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = "Call Partner",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Call",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun LiveDeliveryMap(
    deliveryPartner: DeliveryPartner,
    deliveryAddress: CustomerAddress?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lat = deliveryPartner.latitude
    val lng = deliveryPartner.longitude

    if (lat == null || lng == null) {
        // "Waiting for delivery partner to start sharing location..."
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("map_waiting_location")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.LocationSearching,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Waiting for delivery partner to start sharing location...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Live GPS updates will appear automatically",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    } else {
        val partnerLatLng = remember(lat, lng) { LatLng(lat, lng) }
        val destLatLng = remember(deliveryAddress?.lat, deliveryAddress?.lng) {
            if (deliveryAddress?.lat != null && deliveryAddress.lng != null && deliveryAddress.lat != 0.0) {
                LatLng(deliveryAddress.lat, deliveryAddress.lng)
            } else null
        }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(partnerLatLng, 15f)
        }

        // Auto-fit camera when partner or destination location updates
        LaunchedEffect(partnerLatLng, destLatLng) {
            try {
                if (destLatLng != null) {
                    val bounds = LatLngBounds.builder()
                        .include(partnerLatLng)
                        .include(destLatLng)
                        .build()
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                } else {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(partnerLatLng, 15f))
                }
            } catch (e: Exception) {
                // If map not yet laid out, fallback to centering on partner
                cameraPositionState.position = CameraPosition.fromLatLngZoom(partnerLatLng, 15f)
            }
        }

        // Live Map Container
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_delivery_map")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(SuccessGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Delivery Tracking",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SuccessGreen.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Live GPS (12s)",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SuccessGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Interactive Google Map View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                ) {
                    GoogleMap(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("google_tracking_map"),
                        cameraPositionState = cameraPositionState,
                        uiSettings = remember {
                            MapUiSettings(
                                zoomControlsEnabled = false,
                                myLocationButtonEnabled = false,
                                compassEnabled = true
                            )
                        }
                    ) {
                        // Marker 1: Delivery Partner
                        Marker(
                            state = rememberMarkerState(position = partnerLatLng).apply {
                                position = partnerLatLng
                            },
                            title = "Delivery Partner: ${deliveryPartner.name}",
                            snippet = "On the way to deliver your order"
                        )

                        // Marker 2: Delivery Address Destination (if coords available)
                        if (destLatLng != null) {
                            Marker(
                                state = rememberMarkerState(position = destLatLng),
                                title = "Delivery Address (${deliveryAddress?.label ?: "Home"})",
                                snippet = deliveryAddress?.addressLine ?: ""
                            )

                            // Route Polyline connecting Rider to Destination
                            Polyline(
                                points = listOf(partnerLatLng, destLatLng),
                                color = NaturalPrimary,
                                width = 8f
                            )
                        }
                    }

                    // Floating Recenter Button
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (destLatLng != null) {
                                    val bounds = LatLngBounds.builder()
                                        .include(partnerLatLng)
                                        .include(destLatLng)
                                        .build()
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                                } else {
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(partnerLatLng, 16f))
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(36.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = NaturalPrimary,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recenter Map", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom bar inside card: GPS details & Open Maps button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Rider: ${deliveryPartner.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        if (!deliveryAddress?.addressLine.isNullOrBlank()) {
                            Text(
                                text = "To: ${deliveryAddress!!.addressLine}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val mapUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Delivery+Partner)")
                            val intent = Intent(Intent.ACTION_VIEW, mapUri)
                            intent.setPackage("com.google.android.apps.maps")
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))
                                context.startActivity(browserIntent)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("open_maps_button")
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = "Open in Google Maps",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Open in Maps",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun parseIsoTimestamp(s: String): Long {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s.replace("Z", "+00:00"))?.time ?: 0L
    } catch (e: Exception) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(s.take(19))?.time ?: 0L
        } catch (e2: Exception) {
            0L
        }
    }
}
