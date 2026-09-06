package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class BrowsingMode {
    GROCERY,
    HOTELS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: SndmartRepository,
    selectedCity: City?,
    onCityChangeRequested: () -> Unit,
    onNavigateToCart: () -> Unit,
    onNavigateToHotelMenu: (vendorId: String, vendorName: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var browsingMode by remember { mutableStateOf(BrowsingMode.GROCERY) }
    var searchQuery by remember { mutableStateOf("") }

    // Grocery data
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var groceryProducts by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var isGroceryLoading by remember { mutableStateOf(true) }
    var groceryError by remember { mutableStateOf<String?>(null) }

    // Hotels data
    var hotels by remember { mutableStateOf<List<Vendor>>(emptyList()) }
    var isHotelsLoading by remember { mutableStateOf(true) }
    var hotelsError by remember { mutableStateOf<String?>(null) }

    // Cart state
    val groceryCart by repository.groceryCart.collectAsState()
    val hotelCart by repository.hotelCart.collectAsState()
    val totalCartCount = repository.getCartCount()

    // Conflict Dialog state
    var pendingHotelConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    // Snackbar host
    val snackbarHostState = remember { SnackbarHostState() }

    // Refresh function for grocery
    fun loadGroceryData(cityId: String) {
        coroutineScope.launch {
            isGroceryLoading = true
            groceryError = null
            // Categories where vendor_type IN ('grocery','vegetable','fruit') AND vendor_id IS NULL AND is_active=true
            val catRes = repository.getCategories()
            if (catRes.isSuccess) {
                val filteredCats = catRes.getOrNull()?.filter {
                    val vt = it.vendorType?.lowercase()
                    (vt == "grocery" || vt == "vegetable" || vt == "fruit" || vt == null) &&
                            it.vendorId == null && it.isActive
                } ?: emptyList()
                categories = filteredCats
            } else {
                groceryError = catRes.exceptionOrNull()?.message
            }

            // Products
            val prodRes = repository.getResolvedGroceryProducts(cityId = cityId, categoryId = selectedCategoryId)
            if (prodRes.isSuccess) {
                groceryProducts = prodRes.getOrNull() ?: emptyList()
            } else {
                groceryError = prodRes.exceptionOrNull()?.message
            }
            isGroceryLoading = false
        }
    }

    // Refresh function for hotels
    fun loadHotelsData(cityId: String) {
        coroutineScope.launch {
            isHotelsLoading = true
            hotelsError = null
            val res = repository.getHotels(cityId)
            if (res.isSuccess) {
                // Rule: ordered is_active DESC, is_featured DESC, name ASC (active+featured first, inactive last)
                hotels = res.getOrNull()?.sortedWith(
                    compareByDescending<Vendor> { it.isActive }
                        .thenByDescending { it.isFeatured == true }
                        .thenBy { it.name }
                ) ?: emptyList()
            } else {
                hotelsError = res.exceptionOrNull()?.message
            }
            isHotelsLoading = false
        }
    }

    // Trigger load on city change or category change
    LaunchedEffect(selectedCity?.id, selectedCategoryId) {
        val cityId = selectedCity?.id
        if (cityId != null) {
            loadGroceryData(cityId)
            loadHotelsData(cityId)
        }
    }

    // Handle single-hotel rule conflict dialog
    if (pendingHotelConflict != null) {
        val conflict = pendingHotelConflict!!
        AlertDialog(
            onDismissRequest = { pendingHotelConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Sndmart orders items from one hotel at a time. Would you like to clear the existing hotel cart and add this item?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingHotelConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart updated with new hotel item")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TangerineOrange)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHotelConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            SndmartTopBar(
                currentCity = selectedCity,
                cartCount = totalCartCount,
                onCityClick = onCityChangeRequested,
                onCartClick = onNavigateToCart,
                onSettingsClick = onOpenSettings
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Natural Tones Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_search_input"),
                placeholder = {
                    Text(
                        if (browsingMode == BrowsingMode.GROCERY) "Search groceries or hotels..."
                        else "Search hotels, restaurants, cuisines...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariantLight,
                    unfocusedContainerColor = SurfaceVariantLight,
                    focusedBorderColor = NaturalPrimary,
                    unfocusedBorderColor = OutlineBorder
                ),
                singleLine = true
            )

            // Natural Tones Segmented Mode Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = NaturalPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_mode_toggle")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isGrocery = browsingMode == BrowsingMode.GROCERY
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isGrocery) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.GROCERY }
                            .testTag("toggle_grocery")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Fresh Groceries",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (isGrocery) Color.White else TextSecondary
                            )
                        }
                    }

                    val isHotel = browsingMode == BrowsingMode.HOTELS
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isHotel) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.HOTELS }
                            .testTag("toggle_hotels")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Hotel Food",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (isHotel) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (selectedCity == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Please select your delivery city to view live stock & pricing")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onCityChangeRequested) {
                            Text("Choose City")
                        }
                    }
                }
                return@Scaffold
            }

            // --- GROCERY VIEW ---
            if (browsingMode == BrowsingMode.GROCERY) {
                if (groceryError != null && groceryProducts.isEmpty()) {
                    ErrorCard(
                        message = groceryError!!,
                        onRetry = { selectedCity.id.let { loadGroceryData(it) } }
                    )
                }

                if (isGroceryLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NaturalPrimary)
                    }
                } else {
                    val filteredProducts = groceryProducts.filter {
                        if (searchQuery.isBlank()) true
                        else it.name.contains(searchQuery, ignoreCase = true) ||
                                (it.description?.contains(searchQuery, ignoreCase = true) == true)
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchQuery.isBlank()) {
                            // Featured Deals Banner from Natural Tones
                            item(span = { GridItemSpan(2) }) {
                                NaturalFeaturedDealsBanner(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            // Explore Categories with Natural Tones pastels
                            item(span = { GridItemSpan(2) }) {
                                ExploreCategoriesQuickBar(
                                    selectedCategoryId = selectedCategoryId,
                                    categories = categories,
                                    onCategorySelected = { selectedCategoryId = it }
                                )
                            }

                            // Category Filter Chips
                            item(span = { GridItemSpan(2) }) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    item {
                                        FilterChip(
                                            selected = selectedCategoryId == null,
                                            onClick = { selectedCategoryId = null },
                                            label = { Text("All Items", fontWeight = FontWeight.Medium) },
                                            leadingIcon = {
                                                if (selectedCategoryId == null) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NaturalPrimary,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                    items(categories) { cat ->
                                        FilterChip(
                                            selected = selectedCategoryId == cat.id,
                                            onClick = {
                                                selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id
                                            },
                                            label = { Text(cat.name, fontWeight = FontWeight.Medium) },
                                            leadingIcon = {
                                                if (selectedCategoryId == cat.id) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NaturalPrimary,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredProducts.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No grocery products found.",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        } else {
                            items(filteredProducts, key = { it.id }) { product ->
                                val inCartItem = groceryCart.find { it.productId == product.id }
                                val quantityInCart = inCartItem?.quantity ?: 0

                                GroceryProductCard(
                                    product = product,
                                    quantityInCart = quantityInCart,
                                    onIncrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = 1,
                                            isHotel = false
                                        )
                                    },
                                    onDecrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = -1,
                                            isHotel = false
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --- HOTELS VIEW ---
            if (browsingMode == BrowsingMode.HOTELS) {
                if (hotelsError != null && hotels.isEmpty()) {
                    ErrorCard(
                        message = hotelsError!!,
                        onRetry = { selectedCity.id.let { loadHotelsData(it) } }
                    )
                }

                if (isHotelsLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NaturalPrimary)
                    }
                } else {
                    val filteredHotels = hotels.filter {
                        if (searchQuery.isBlank()) true
                        else it.name.contains(searchQuery, ignoreCase = true) ||
                                (it.address?.contains(searchQuery, ignoreCase = true) == true)
                    }

                    if (filteredHotels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No hotels or restaurants available in this city.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredHotels, key = { it.id }) { hotel ->
                                HotelCard(
                                    vendor = hotel,
                                    onClick = {
                                        onNavigateToHotelMenu(hotel.id, hotel.name)
                                    }
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
fun NaturalFeaturedDealsBanner(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NaturalOceanBlue),
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Silhouette decorations from Natural Tones design
            Text(
                text = "🥬",
                fontSize = 58.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 8.dp)
                    .alpha(0.25f)
            )
            Text(
                text = "🍅",
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-36).dp, y = 4.dp)
                    .alpha(0.20f)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "UP TO 40% OFF",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Organic Farm\nFresh Picks",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Text(
                        text = "SHOP NOW",
                        color = NaturalOceanBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ExploreCategoriesQuickBar(
    selectedCategoryId: String?,
    categories: List<Category>,
    onCategorySelected: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Explore Categories",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (selectedCategoryId != null) {
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NaturalPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCategorySelected(null) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 4 Pastel Category Tiles from Natural Tones design
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val pastelTiles = listOf(
                Triple("Vegetables", "🥦", PastelSage),
                Triple("Fruits", "🍎", PastelPeach),
                Triple("Dairy", "🥛", PastelSlate),
                Triple("Meats", "🥩", PastelMist)
            )

            pastelTiles.forEach { (name, emoji, pastelBg) ->
                val matchedCategory = categories.find { it.name.contains(name, ignoreCase = true) }
                val isSelected = matchedCategory != null && selectedCategoryId == matchedCategory.id

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (matchedCategory != null) {
                                onCategorySelected(if (isSelected) null else matchedCategory.id)
                            }
                        }
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = pastelBg,
                        border = if (isSelected) BorderStroke(2.dp, NaturalPrimary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 28.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = name,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) NaturalPrimary else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun GroceryProductCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
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
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = NaturalOceanBlue,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = "FEATURED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!product.effectiveIsAvailable || product.effectiveStock <= 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        color = Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text(
                                    text = "OUT OF STOCK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            PriceDisplay(
                price = product.effectivePrice,
                mrp = product.effectiveMrp,
                unit = product.unit,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (product.effectiveIsAvailable && product.effectiveStock > 0) {
                QuantityStepper(
                    quantity = quantityInCart,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    modifier = Modifier.fillMaxWidth(),
                    testTagPrefix = "grocery_${product.id}"
                )
            } else {
                Text(
                    text = "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun HotelCard(
    vendor: Vendor,
    onClick: () -> Unit
) {
    val isOpen = vendor.isOpen && vendor.isActive

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("hotel_card_${vendor.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (vendor.isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
            ) {
                ProductImage(
                    url = vendor.bannerUrl,
                    contentDescription = vendor.name,
                    modifier = Modifier.fillMaxSize()
                )
                // Status chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (vendor.isFeatured == true) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NaturalOceanBlue,
                            contentColor = Color.White
                        ) {
                            Text(
                                "FEATURED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isOpen) SuccessGreen else TextMuted,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (isOpen) "OPEN NOW" else "CLOSED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vendor.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (vendor.isActive) TextPrimary else TextMuted
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "View Menu",
                        tint = NaturalPrimary
                    )
                }

                if (!vendor.address.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = vendor.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!vendor.openingTime.isNullOrBlank() && !vendor.closingTime.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Hours: ${vendor.openingTime} - ${vendor.closingTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
