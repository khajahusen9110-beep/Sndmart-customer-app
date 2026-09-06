package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.data.model.Order
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onNavigateToDetail: (orderId: String) -> Unit,
    onRequireLogin: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value
    val isLoggedIn = sessionManager.isLoggedIn.collectAsState().value

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var vendorNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadOrders() {
        if (!isLoggedIn || userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val res = repository.getOrders(userId)
            if (res.isSuccess) {
                val loaded = res.getOrNull() ?: emptyList()
                orders = loaded
                // Resolve hotel names for hotel orders (unique vendor ids only).
                val hotelVendorIds = loaded.mapNotNull { it.vendorId }.distinct()
                val names = mutableMapOf<String, String>()
                for (vid in hotelVendorIds) {
                    val n = repository.getVendorName(vid)
                    n.getOrNull()?.takeIf { it.isNotBlank() }?.let { names[vid] = it }
                }
                vendorNames = names
            } else {
                errorMessage = res.exceptionOrNull()?.message
            }
            isLoading = false
        }
    }

    LaunchedEffect(userId, isLoggedIn) {
        loadOrders()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Orders", fontWeight = FontWeight.Bold) },
                actions = {
                    if (isLoggedIn) {
                        IconButton(onClick = { loadOrders() }, modifier = Modifier.testTag("refresh_orders_button")) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Orders")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isLoggedIn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ReceiptLong,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Sign in to view your orders",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Track active deliveries and view past invoices",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRequireLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("orders_signin_button")
                        ) {
                            Text("Sign In Now")
                        }
                    }
                }
            } else if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = NaturalPrimary
                )
            } else if (errorMessage != null) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadOrders() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (orders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ShoppingBag,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No orders placed yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your placed orders will appear here with live tracking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(orders, key = { it.id ?: it.orderNumber }) { order ->
                        OrderCardItem(
                            order = order,
                            hotelName = order.vendorId?.let { vendorNames[it] },
                            onClick = { order.id?.let { onNavigateToDetail(it) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OrderCardItem(
    order: Order,
    hotelName: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("order_card_${order.orderNumber}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = order.orderNumber,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (!hotelName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Store,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = hotelName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = NaturalPrimary
                            )
                        }
                    }
                    if (!order.createdAt.isNullOrBlank()) {
                        Text(
                            text = order.createdAt.take(16).replace("T", " "),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
                OrderStatusBadge(order.status)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total Amount",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "₹${"%.2f".format(order.totalAmount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = NaturalPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "View Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = NaturalPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = NaturalPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OrderStatusBadge(status: String) {
    val (bgColor, textColor, text) = when (status.lowercase()) {
        "pending" -> Triple(PastelPeach, TextPrimary, "Pending")
        "confirmed" -> Triple(PastelSage, DarkGreenText, "Confirmed")
        "preparing" -> Triple(PastelPeach, TextPrimary, "Preparing")
        "ready" -> Triple(PastelMint, DarkGreenText, "Ready")
        "out_for_delivery" -> Triple(PastelSky, NaturalPrimary, "Out for Delivery")
        "delivered" -> Triple(PastelSage, DarkGreenText, "Delivered")
        "cancelled" -> Triple(PastelCoral, NaturalBadgeRed, "Cancelled")
        "rejected" -> Triple(PastelCoral, NaturalBadgeRed, "Rejected")
        else -> Triple(PastelSand, TextPrimary, status.replaceFirstChar { it.uppercase() })
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
