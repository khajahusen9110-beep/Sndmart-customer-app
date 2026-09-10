package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.example.data.model.CartItemUi
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    cityId: String?,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onProceedToCheckout: (isHotel: Boolean, couponCode: String?, slotId: String?) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Toggle between Grocery Cart and Hotel Cart (strictly separate checkouts!)
    var isHotelCartSelected by remember { mutableStateOf(false) }

    val rawGroceryCart by repository.groceryCart.collectAsState()
    val rawHotelCart by repository.hotelCart.collectAsState()

    // Fresh resolved items for current render
    var freshItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    var isLoadingFreshPrices by remember { mutableStateOf(true) }
    var errorLoadingPrices by remember { mutableStateOf<String?>(null) }

    // Function to re-fetch live prices at render time
    fun fetchFreshCart() {
        if (cityId == null) return
        coroutineScope.launch {
            isLoadingFreshPrices = true
            errorLoadingPrices = null
            val res = repository.getFreshCartItems(isHotel = isHotelCartSelected, cityId = cityId)
            if (res.isSuccess) {
                freshItems = res.getOrNull() ?: emptyList()
            } else {
                errorLoadingPrices = res.exceptionOrNull()?.message
            }
            isLoadingFreshPrices = false
        }
    }

    // Trigger fresh price re-fetch whenever the selected cart changes or raw cart changes
    LaunchedEffect(isHotelCartSelected, rawGroceryCart, rawHotelCart, cityId) {
        fetchFreshCart()
    }

    // Calculate totals from fresh prices
    val subtotal = freshItems.sumOf { it.totalPrice }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Cart", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (freshItems.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                repository.clearCart(isHotelCartSelected)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Cart cleared")
                                }
                            },
                            modifier = Modifier.testTag("clear_cart_button")
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Clear Cart", tint = NaturalBadgeRed)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (freshItems.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Total to Pay",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    "₹${"%.0f".format(subtotal)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NaturalPrimary
                                )
                            }
                            Button(
                                onClick = {
                                    onProceedToCheckout(isHotelCartSelected, null, null)
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("proceed_to_checkout_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text("Proceed to Checkout", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Cart Mode Selector: Grocery vs Hotel Cart
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .testTag("cart_type_tabs")
                ) {
                    val groceryCount = rawGroceryCart.sumOf { it.quantity }
                    val hotelCount = rawHotelCart.sumOf { it.quantity }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (!isHotelCartSelected) NaturalPrimaryContainer else Color.Transparent)
                            .clickable { isHotelCartSelected = false }
                            .padding(vertical = 10.dp)
                            .testTag("tab_grocery_cart"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Grocery Cart ($groceryCount)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (!isHotelCartSelected) NaturalOnPrimaryContainer else TextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isHotelCartSelected) NaturalPrimaryContainer else Color.Transparent)
                            .clickable { isHotelCartSelected = true }
                            .padding(vertical = 10.dp)
                            .testTag("tab_hotel_cart"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Hotel Cart ($hotelCount)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isHotelCartSelected) NaturalOnPrimaryContainer else TextSecondary
                        )
                    }
                }
            }

            if (isLoadingFreshPrices) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NaturalPrimary)
                }
            } else if (errorLoadingPrices != null) {
                ErrorCard(message = errorLoadingPrices!!, onRetry = { fetchFreshCart() })
            } else if (freshItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ShoppingCart,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isHotelCartSelected) "Your hotel cart is empty" else "Your grocery cart is empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Explore items in your city and add them to cart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("Start Shopping")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PastelSage,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = DarkGreenText,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Prices verified live with ${cityId ?: "your city"}'s current stock",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DarkGreenText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Cart Items List
                    items(freshItems, key = { it.cartItem.productId }) { itemUi ->
                        CartItemRow(
                            item = itemUi,
                            onIncrease = {
                                repository.updateCartItemQuantity(
                                    productId = itemUi.cartItem.productId,
                                    isHotel = isHotelCartSelected,
                                    newQty = itemUi.cartItem.quantity + 1
                                )
                            },
                            onDecrease = {
                                repository.updateCartItemQuantity(
                                    productId = itemUi.cartItem.productId,
                                    isHotel = isHotelCartSelected,
                                    newQty = itemUi.cartItem.quantity - 1
                                )
                            }
                        )
                    }

                    // Bill Breakdown
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
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                BillRow("Item Subtotal", "₹${"%.2f".format(subtotal)}")
                                BillRow("Delivery Fee", "Calculated at checkout", color = TextSecondary)
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                BillRow(
                                    "Total to Pay",
                                    "₹${"%.2f".format(subtotal)}",
                                    isBold = true,
                                    fontSize = 16.sp,
                                    color = NaturalPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CartItemRow(
    item: CartItemUi,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cart_item_${item.cartItem.productId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = item.product.imageUrl,
                    contentDescription = item.product.name,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "₹${"%.0f".format(item.product.effectivePrice)}${if (!item.product.unit.isNullOrBlank()) " / ${item.product.unit}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = "Total: ₹${"%.0f".format(item.totalPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = NaturalPrimary
                )
            }

            QuantityStepper(
                quantity = item.cartItem.quantity,
                onIncrease = onIncrease,
                onDecrease = onDecrease,
                testTagPrefix = "cart_item_${item.cartItem.productId}"
            )
        }
    }
}

