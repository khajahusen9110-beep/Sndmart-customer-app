package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.example.data.model.CartItem
import com.example.data.model.Category
import com.example.data.model.ResolvedProduct
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelMenuScreen(
    vendorId: String,
    vendorName: String,
    cityId: String,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onNavigateToCart: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var allProducts by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val hotelCart by repository.hotelCart.collectAsState()
    var pendingConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    fun loadMenu() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val res = repository.getHotelMenu(vendorId = vendorId, cityId = cityId)
            if (res.isSuccess) {
                val pair = res.getOrNull()!!
                categories = pair.first
                allProducts = pair.second
            } else {
                errorMessage = res.exceptionOrNull()?.message
            }
            isLoading = false
        }
    }

    LaunchedEffect(vendorId, cityId) {
        loadMenu()
    }

    // Single-hotel conflict dialog
    if (pendingConflict != null) {
        val conflict = pendingConflict!!
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Clear existing items and add from $vendorName?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart cleared and item added")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vendorName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Hotel Menu",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCart) {
                        val count = hotelCart.sumOf { it.quantity }
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge(containerColor = NaturalPrimary, contentColor = Color.White) {
                                        Text("$count", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "View Cart")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val count = hotelCart.sumOf { it.quantity }
            if (count > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "$count item(s) in Hotel Cart",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Single hotel checkout",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = onNavigateToCart,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.testTag("hotel_view_cart_button")
                        ) {
                            Text("View Cart")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    onRetry = { loadMenu() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (allProducts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No menu items currently listed for this hotel.", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // If categories exist, group items or show all items with category headers
                    if (categories.isNotEmpty()) {
                        categories.forEach { category ->
                            val prodsForCat = allProducts.filter { it.categoryId == category.id }
                            if (prodsForCat.isNotEmpty()) {
                                item(key = "cat_${category.id}") {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NaturalPrimary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(prodsForCat, key = { it.id }) { product ->
                                    val inCart = hotelCart.find { it.productId == product.id }
                                    val qty = inCart?.quantity ?: 0
                                    MenuItemCard(
                                        product = product,
                                        quantityInCart = qty,
                                        onIncrease = {
                                            val res = repository.addToCart(
                                                productId = product.id,
                                                vendorId = vendorId,
                                                cityId = cityId,
                                                quantityDelta = 1,
                                                isHotel = true
                                            )
                                            if (res is AddToCartResult.HotelConflict) {
                                                pendingConflict = res
                                            }
                                        },
                                        onDecrease = {
                                            repository.addToCart(
                                                productId = product.id,
                                                vendorId = vendorId,
                                                cityId = cityId,
                                                quantityDelta = -1,
                                                isHotel = true
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // Also check items without category
                        val uncategorized = allProducts.filter { prod -> categories.none { it.id == prod.categoryId } }
                        if (uncategorized.isNotEmpty()) {
                            item(key = "cat_other") {
                                Text(
                                    text = "Other Special Dishes",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NaturalPrimary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(uncategorized, key = { it.id }) { product ->
                                val inCart = hotelCart.find { it.productId == product.id }
                                val qty = inCart?.quantity ?: 0
                                MenuItemCard(
                                    product = product,
                                    quantityInCart = qty,
                                    onIncrease = {
                                        val res = repository.addToCart(
                                            productId = product.id,
                                            vendorId = vendorId,
                                            cityId = cityId,
                                            quantityDelta = 1,
                                            isHotel = true
                                        )
                                        if (res is AddToCartResult.HotelConflict) {
                                            pendingConflict = res
                                        }
                                    },
                                    onDecrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = vendorId,
                                            cityId = cityId,
                                            quantityDelta = -1,
                                            isHotel = true
                                        )
                                    }
                                )
                            }
                        }
                    } else {
                        items(allProducts, key = { it.id }) { product ->
                            val inCart = hotelCart.find { it.productId == product.id }
                            val qty = inCart?.quantity ?: 0
                            MenuItemCard(
                                product = product,
                                quantityInCart = qty,
                                onIncrease = {
                                    val res = repository.addToCart(
                                        productId = product.id,
                                        vendorId = vendorId,
                                        cityId = cityId,
                                        quantityDelta = 1,
                                        isHotel = true
                                    )
                                    if (res is AddToCartResult.HotelConflict) {
                                        pendingConflict = res
                                    }
                                },
                                onDecrease = {
                                    repository.addToCart(
                                        productId = product.id,
                                        vendorId = vendorId,
                                        cityId = cityId,
                                        quantityDelta = -1,
                                        isHotel = true
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItemCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    val isAvailable = product.effectiveIsAvailable && product.effectiveStock > 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("menu_item_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAvailable) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
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
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.fillMaxSize()
                )
                if (product.isFeatured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = PastelPeach,
                        contentColor = DarkGreenText
                    ) {
                        Text(
                            "POPULAR",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isAvailable) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "UNAVAILABLE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAvailable) TextPrimary else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!product.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = product.description!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                PriceDisplay(
                    price = product.effectivePrice,
                    mrp = product.effectiveMrp,
                    unit = product.unit
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isAvailable) {
                QuantityStepper(
                    quantity = quantityInCart,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    testTagPrefix = "hotel_item_${product.id}"
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Sold out",
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
