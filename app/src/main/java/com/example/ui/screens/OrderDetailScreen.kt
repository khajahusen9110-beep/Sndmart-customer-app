package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.SndmartRepository
import com.example.ui.components.BillRow
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
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
                order = oRes.getOrNull()
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
                    val partnerId = deliveryAssignment?.deliveryPartnerId
                    if (!partnerId.isNullOrBlank()) {
                        val partRes = repository.getDeliveryPartner(partnerId)
                        if (partRes.isSuccess) {
                            deliveryPartner = partRes.getOrNull()
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
    // order is still active (confirmed → out_for_delivery). Stops once delivered/cancelled.
    LaunchedEffect(order?.status, deliveryPartner?.id) {
        val partnerId = deliveryPartner?.id
        val activeStatuses = listOf("confirmed", "preparing", "ready", "out_for_delivery")
        while (order != null && activeStatuses.contains(order!!.status.lowercase()) && !partnerId.isNullOrBlank()) {
            delay(12000)
            val partRes = repository.getDeliveryPartner(partnerId)
            if (partRes.isSuccess) {
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
                    // 1. Delivery OTP Card (Prominently displayed when out_for_delivery)
                    if (currentOrder.status.lowercase() == "out_for_delivery" && !deliveryAssignment?.deliveryOtp.isNullOrBlank()) {
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
                                    Text(
                                        "DELIVERY VERIFICATION OTP",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = NaturalPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = deliveryAssignment!!.deliveryOtp!!,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 6.sp,
                                        color = NaturalPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Share this code with your delivery partner to confirm delivery.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // 2. Live Driver info & ETA countdown — only while a partner is assigned
                    //    and the order is still active (not delivered/cancelled).
                    val activeForTracking = listOf("confirmed", "preparing", "ready", "out_for_delivery")
                    if (deliveryPartner != null && activeForTracking.contains(currentOrder.status.lowercase())) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("driver_info_card"),
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = CircleShape,
                                                color = PastelSky,
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.DeliveryDining, contentDescription = null, tint = NaturalPrimary)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(deliveryPartner!!.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                if (!deliveryPartner!!.vehicleNumber.isNullOrBlank()) {
                                                    Text(
                                                        "${deliveryPartner!!.vehicleType ?: "Vehicle"}: ${deliveryPartner!!.vehicleNumber}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                        }

                                        if (!deliveryPartner!!.phone.isNullOrBlank()) {
                                            IconButton(
                                                onClick = {
                                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${deliveryPartner!!.phone}"))
                                                    context.startActivity(intent)
                                                },
                                                modifier = Modifier
                                                    .background(NaturalPrimary, CircleShape)
                                                    .size(40.dp)
                                                    .testTag("call_driver_button")
                                            ) {
                                                Icon(Icons.Default.Phone, contentDescription = "Call Driver", tint = Color.White, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }

                                    if (!deliveryAssignment?.estimatedDeliveryAt.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        EtaCountdown(
                                            estimatedDeliveryAt = deliveryAssignment!!.estimatedDeliveryAt!!,
                                            isDelivered = currentOrder.status.lowercase() == "delivered"
                                        )
                                    }

                                    // Live coordinates indicator
                                    if (deliveryPartner?.latitude != null && deliveryPartner?.longitude != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.MyLocation, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Driver Live Location: ${"%.4f".format(deliveryPartner!!.latitude)}, ${"%.4f".format(deliveryPartner!!.longitude)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
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
                                BillRow("Payment Method", currentOrder.paymentMethod.uppercase())
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
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(estimatedDeliveryAt) {
        while (true) {
            delay(30000)
            now = System.currentTimeMillis()
        }
    }
    val etaMillis = remember(estimatedDeliveryAt) { parseIsoTimestamp(estimatedDeliveryAt) }
    if (etaMillis <= 0L) return

    val remainingMs = etaMillis - now
    val label = when {
        remainingMs <= 0 && isDelivered -> "Delivered"
        remainingMs <= 0 -> "Arriving any moment"
        else -> {
            val totalMin = (remainingMs / 60000).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            if (h > 0) "Arriving in ${h}h ${m}m" else "Arriving in ${m}m"
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PastelSage,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = DarkGreenText, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = DarkGreenText
            )
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
