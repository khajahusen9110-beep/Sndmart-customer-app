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

    // Driver location polling while out for delivery
    LaunchedEffect(order?.status, deliveryPartner?.id) {
        val partnerId = deliveryPartner?.id
        while (order?.status?.lowercase() == "out_for_delivery" && !partnerId.isNullOrBlank()) {
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

                        // Rate Order Button (if delivered)
                        if (order!!.status.lowercase() == "delivered") {
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
                                        "Share this OTP with the delivery partner upon arrival",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // 2. Live Driver info & ETA countdown
                    if (deliveryPartner != null) {
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

                                    if (deliveryAssignment?.estimatedDeliveryAt != null) {
                                        Spacer(modifier = Modifier.height(12.dp))
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
                                                    text = "Estimated Delivery: ${deliveryAssignment!!.estimatedDeliveryAt!!.take(16).replace("T", " ")}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = DarkGreenText
                                                )
                                            }
                                        }
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val url = "https://api.whatsapp.com/send?phone=919876543210&text=Hi%20Sndmart%20Support,%20I%20need%20help%20with%20Order%20${currentOrder.orderNumber}"
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_whatsapp_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("WhatsApp")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:18001234567"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f).testTag("help_call_button"),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp), tint = NaturalPrimary)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Call Us")
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

    val isTerminalFailed = currentStatusNormalized == "cancelled" || currentStatusNormalized == "rejected"
    if (isTerminalFailed) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PastelCoral,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cancel, contentDescription = null, tint = NaturalBadgeRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "This order was $currentStatusNormalized.",
                    fontWeight = FontWeight.Bold,
                    color = NaturalBadgeRed
                )
            }
        }
        return
    }

    val stepIndexMap = mapOf(
        "pending" to 0,
        "confirmed" to 1,
        "preparing" to 2,
        "ready" to 3,
        "out_for_delivery" to 4,
        "delivered" to 5
    )

    val activeIndex = stepIndexMap[currentStatusNormalized] ?: 0

    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, (key, label) ->
            val isCompleted = index <= activeIndex
            val isCurrent = index == activeIndex

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            isCompleted -> NaturalPrimary
                            else -> TextMuted.copy(alpha = 0.3f)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isCompleted) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            } else {
                                Text("${index + 1}", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }

                    if (index < steps.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(32.dp)
                                .background(if (index < activeIndex) NaturalPrimary else TextMuted.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = label,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCompleted) TextPrimary else TextMuted,
                        fontSize = 14.sp
                    )
                    // If timestamp in history
                    val histItem = history.find { it.status.lowercase() == key }
                    if (histItem?.createdAt != null) {
                        Text(
                            text = histItem.createdAt.take(16).replace("T", " "),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}
