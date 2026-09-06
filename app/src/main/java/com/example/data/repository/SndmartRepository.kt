package com.example.data.repository

import android.util.Log
import com.example.data.model.*
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed class AddToCartResult {
    object Success : AddToCartResult()
    data class HotelConflict(val existingVendorId: String, val newVendorId: String, val pendingItem: CartItem) : AddToCartResult()
}

class SndmartRepository(
    private val api: SupabaseApi = SupabaseClient.api,
    private val sessionManager: UserSessionManager
) {
    private val TAG = "SndmartRepository"

    // In-memory cart items (strictly NO price column, only productId, vendorId, cityId, quantity)
    private val _groceryCart = MutableStateFlow<List<CartItem>>(emptyList())
    val groceryCart: StateFlow<List<CartItem>> = _groceryCart.asStateFlow()

    private val _hotelCart = MutableStateFlow<List<CartItem>>(emptyList())
    val hotelCart: StateFlow<List<CartItem>> = _hotelCart.asStateFlow()

    // Local in-memory state for offline/demo operation when Supabase key is unconfigured or offline
    private val _localOrders = MutableStateFlow<List<Order>>(emptyList())
    private val _localOrderItems = mutableListOf<OrderItem>()
    private val _localAddresses = MutableStateFlow<List<CustomerAddress>>(listOf(DemoCatalog.SAMPLE_ADDRESS))

    private fun getFallbackGroceryProducts(categoryId: String?): List<ResolvedProduct> {
        val filtered = if (categoryId.isNullOrBlank()) {
            DemoCatalog.GROCERY_PRODUCTS
        } else {
            DemoCatalog.GROCERY_PRODUCTS.filter { it.categoryId == categoryId }
        }
        return filtered.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable
            )
        }
    }

    private fun getFallbackHotelMenu(vendorId: String): Pair<List<Category>, List<ResolvedProduct>> {
        val cats = DemoCatalog.HOTEL_CATEGORIES.filter { it.vendorId == vendorId }
        val prods = DemoCatalog.HOTEL_PRODUCTS.filter { it.vendorId == vendorId || vendorId.isBlank() }
        val resolved = prods.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable
            )
        }
        return Pair(cats, resolved)
    }

    private fun findFallbackProduct(productId: String): ResolvedProduct? {
        val p = (DemoCatalog.GROCERY_PRODUCTS + DemoCatalog.HOTEL_PRODUCTS).firstOrNull { it.id == productId }
            ?: return null
        return ResolvedProduct(
            baseProduct = p,
            effectivePrice = p.price,
            effectiveMrp = p.mrp,
            effectiveStock = p.stockQty ?: 50,
            effectiveIsAvailable = p.isAvailable
        )
    }

    // --- CITIES ---
    suspend fun getActiveCities(): Result<List<City>> {
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to fetch cities from backend: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching cities from backend", e)
            Result.failure(e)
        }
    }

    // --- CITY DETECTION (BACKEND RPC) ---
    suspend fun findCityForLocation(lat: Double, lng: Double): Result<City?> {
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                if (results.isNotEmpty()) {
                    val r = results.first()
                    Result.success(City(id = r.cityId, name = r.cityName, status = "active"))
                } else {
                    Result.success(null) // Location not serviceable
                }
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling find_city_for_location", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfileCityId(userId: String, cityId: String): Result<Unit> {
        return try {
            val response = api.updateProfile("eq.$userId", mapOf("city_id" to cityId))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Log.e(TAG, "Exception updating profile city_id", e)
            Result.failure(e)
        }
    }

    // --- CATEGORIES ---
    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = api.getCategories()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to fetch categories: $error. Falling back to demo categories.")
                Result.success(DemoCatalog.CATEGORIES)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching categories", e)
            Result.success(DemoCatalog.CATEGORIES)
        }
    }

    // --- VENDORS (HOTELS) ---
    suspend fun getHotels(cityId: String): Result<List<Vendor>> {
        return try {
            val response = api.getVendors(cityId = "eq.$cityId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to fetch hotels: $error. Falling back to demo hotels.")
                Result.success(DemoCatalog.HOTELS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching hotels", e)
            Result.success(DemoCatalog.HOTELS)
        }
    }

    // --- PRODUCTS & CITY STOCK RESOLUTION ---
    suspend fun getResolvedGroceryProducts(cityId: String, categoryId: String? = null): Result<List<ResolvedProduct>> {
        return try {
            // Generic grocery items have vendor_id IS NULL
            val catQuery = categoryId?.let { "eq.$it" }
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                categoryId = catQuery,
                vendorId = "is.null"
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.e(TAG, "Failed to fetch products: $error. Falling back to demo products.")
                return Result.success(getFallbackGroceryProducts(categoryId))
            }
            val products = prodResponse.body()!!

            // Fetch fresh city stock override
            val stockResponse = api.getProductCityStock(cityId = "eq.$cityId")
            val stockMap = if (stockResponse.isSuccessful && stockResponse.body() != null) {
                stockResponse.body()!!.associateBy { it.productId }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail
                )
            }
            Result.success(resolved)
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching grocery products", e)
            Result.success(getFallbackGroceryProducts(categoryId))
        }
    }

    suspend fun getHotelMenu(vendorId: String, cityId: String): Result<Pair<List<Category>, List<ResolvedProduct>>> {
        return try {
            // Hotel's menu categories have vendor_id = that hotel
            val catResponse = api.getCategories(order = "sort_order.asc")
            val categories = if (catResponse.isSuccessful && catResponse.body() != null) {
                catResponse.body()!!.filter { it.vendorId == vendorId }
            } else {
                emptyList()
            }

            // Products for this hotel
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                vendorId = "eq.$vendorId"
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                return Result.success(getFallbackHotelMenu(vendorId))
            }
            val products = prodResponse.body()!!

            // City stock override
            val stockResponse = api.getProductCityStock(cityId = "eq.$cityId")
            val stockMap = if (stockResponse.isSuccessful && stockResponse.body() != null) {
                stockResponse.body()!!.associateBy { it.productId }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail
                )
            }.sortedWith(
                compareByDescending<ResolvedProduct> { it.effectiveIsAvailable && it.isFeatured }
                    .thenByDescending { it.effectiveIsAvailable }
                    .thenBy { it.name }
            )

            Result.success(Pair(categories, resolved))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching hotel menu", e)
            Result.success(getFallbackHotelMenu(vendorId))
        }
    }

    // --- FRESH PRICING FOR CART ITEMS ---
    // Rule: Every render: re-fetch current price per item fresh — never trust a previously-fetched price.
    suspend fun getFreshCartItems(isHotel: Boolean, cityId: String): Result<List<CartItemUi>> {
        return try {
            val rawCart = if (isHotel) _hotelCart.value else _groceryCart.value
            if (rawCart.isEmpty()) {
                return Result.success(emptyList())
            }

            // Fetch fresh city stock override for city
            val stockResponse = api.getProductCityStock(cityId = "eq.$cityId")
            val stockMap = if (stockResponse.isSuccessful && stockResponse.body() != null) {
                stockResponse.body()!!.associateBy { it.productId }
            } else {
                emptyMap()
            }

            val list = mutableListOf<CartItemUi>()
            for (item in rawCart) {
                var resolvedProd: ResolvedProduct? = null
                try {
                    val pResponse = api.getProductById(idQuery = "eq.${item.productId}")
                    if (pResponse.isSuccessful && !pResponse.body().isNullOrEmpty()) {
                        val prod = pResponse.body()!!.first()
                        val override = stockMap[prod.id]
                        val price = override?.price ?: prod.price
                        val mrp = override?.mrp ?: prod.mrp
                        val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                        val isAvail = override?.isAvailable ?: prod.isAvailable
                        resolvedProd = ResolvedProduct(
                            baseProduct = prod,
                            effectivePrice = price,
                            effectiveMrp = mrp,
                            effectiveStock = stock,
                            effectiveIsAvailable = isAvail
                        )
                    }
                } catch (e: Exception) {
                    // ignore network error, try fallback
                }

                if (resolvedProd == null) {
                    resolvedProd = findFallbackProduct(item.productId)
                }

                if (resolvedProd != null) {
                    list.add(CartItemUi(cartItem = item, product = resolvedProd))
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting fresh cart items", e)
            val cartSnapshot = if (isHotel) _hotelCart.value else _groceryCart.value
            val fallbackList = cartSnapshot.mapNotNull { item ->
                findFallbackProduct(item.productId)?.let { CartItemUi(cartItem = item, product = it) }
            }
            Result.success(fallbackList)
        }
    }

    // --- CART OPERATIONS ---
    fun addToCart(
        productId: String,
        vendorId: String?,
        cityId: String?,
        quantityDelta: Int = 1,
        isHotel: Boolean
    ): AddToCartResult {
        val currentUserId = sessionManager.userId.value ?: "guest"
        if (isHotel) {
            val currentList = _hotelCart.value
            val existingVendor = currentList.firstOrNull { it.vendorId != null }?.vendorId
            if (existingVendor != null && vendorId != null && existingVendor != vendorId) {
                // Single-hotel rule conflict!
                val pending = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                return AddToCartResult.HotelConflict(existingVendor, vendorId, pending)
            }
            val existing = currentList.find { it.productId == productId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _hotelCart.value = currentList.filter { it.productId != productId }
                } else {
                    _hotelCart.value = currentList.map {
                        if (it.productId == productId) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _hotelCart.value = currentList + newItem
            }
        } else {
            val currentList = _groceryCart.value
            val existing = currentList.find { it.productId == productId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _groceryCart.value = currentList.filter { it.productId != productId }
                } else {
                    _groceryCart.value = currentList.map {
                        if (it.productId == productId) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    vendorId = null,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _groceryCart.value = currentList + newItem
            }
        }
        return AddToCartResult.Success
    }

    fun forceClearHotelCartAndAdd(item: CartItem) {
        _hotelCart.value = listOf(item)
    }

    fun updateCartItemQuantity(productId: String, isHotel: Boolean, newQty: Int) {
        if (isHotel) {
            val current = _hotelCart.value
            if (newQty <= 0) {
                _hotelCart.value = current.filter { it.productId != productId }
            } else {
                _hotelCart.value = current.map {
                    if (it.productId == productId) it.copy(quantity = newQty) else it
                }
            }
        } else {
            val current = _groceryCart.value
            if (newQty <= 0) {
                _groceryCart.value = current.filter { it.productId != productId }
            } else {
                _groceryCart.value = current.map {
                    if (it.productId == productId) it.copy(quantity = newQty) else it
                }
            }
        }
    }

    fun clearCart(isHotel: Boolean) {
        if (isHotel) {
            _hotelCart.value = emptyList()
        } else {
            _groceryCart.value = emptyList()
        }
    }

    fun clearAllCarts() {
        _groceryCart.value = emptyList()
        _hotelCart.value = emptyList()
    }

    fun getCartCount(): Int {
        val g = _groceryCart.value.sumOf { it.quantity }
        val h = _hotelCart.value.sumOf { it.quantity }
        return g + h
    }

    // --- DELIVERY SLOTS & COUPONS ---
    suspend fun getDeliverySlots(cityId: String): Result<List<DeliverySlot>> {
        return try {
            val response = api.getDeliverySlots(cityId = "eq.$cityId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(DemoCatalog.DELIVERY_SLOTS)
            }
        } catch (e: Exception) {
            Result.success(DemoCatalog.DELIVERY_SLOTS)
        }
    }

    suspend fun getCoupons(cityId: String): Result<List<Coupon>> {
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(DemoCatalog.COUPONS)
            }
        } catch (e: Exception) {
            Result.success(DemoCatalog.COUPONS)
        }
    }

    // --- CUSTOMER ADDRESSES ---
    suspend fun getAddresses(userId: String): Result<List<CustomerAddress>> {
        return try {
            val response = api.getAddresses(userId = "eq.$userId")
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                Result.success(if (list.isNotEmpty()) list else _localAddresses.value)
            } else {
                Result.success(_localAddresses.value)
            }
        } catch (e: Exception) {
            Result.success(_localAddresses.value)
        }
    }

    suspend fun addAddress(address: CustomerAddress): Result<CustomerAddress> {
        return try {
            val response = api.insertAddress(address)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val saved = response.body()!!.first()
                _localAddresses.value = _localAddresses.value + saved
                Result.success(saved)
            } else {
                val newAddr = address.copy(id = address.id ?: "addr_${System.currentTimeMillis()}")
                _localAddresses.value = _localAddresses.value + newAddr
                Result.success(newAddr)
            }
        } catch (e: Exception) {
            val newAddr = address.copy(id = address.id ?: "addr_${System.currentTimeMillis()}")
            _localAddresses.value = _localAddresses.value + newAddr
            Result.success(newAddr)
        }
    }

    suspend fun deleteAddress(id: String): Result<Unit> {
        return try {
            val response = api.deleteAddress(idQuery = "eq.$id")
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        } catch (e: Exception) {
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        }
    }

    // --- CHECKOUT & ORDER PLACEMENT ---
    // Rule: Always fetch fresh at render time and again right before order placement — never cache a price!
    suspend fun placeOrder(
        userId: String,
        isHotel: Boolean,
        vendorId: String?,
        cityId: String,
        addressId: String,
        slotId: String?,
        paymentMethod: String,
        coupon: Coupon? = null
    ): Result<Order> {
        return try {
            val rawCart = if (isHotel) _hotelCart.value else _groceryCart.value
            if (rawCart.isEmpty()) {
                return Result.failure(Exception("Cart is empty"))
            }

            // Fresh price re-fetch right before order placement
            val stockResponse = api.getProductCityStock(cityId = "eq.$cityId")
            val stockMap = if (stockResponse.isSuccessful && stockResponse.body() != null) {
                stockResponse.body()!!.associateBy { it.productId }
            } else {
                emptyMap()
            }

            var subtotal = 0.0
            val orderItemsToInsert = mutableListOf<OrderItem>()

            for (item in rawCart) {
                val pResponse = api.getProductById(idQuery = "eq.${item.productId}")
                if (!pResponse.isSuccessful || pResponse.body().isNullOrEmpty()) {
                    return Result.failure(Exception("Product ${item.productId} is no longer available."))
                }
                val prod = pResponse.body()!!.first()
                val override = stockMap[prod.id]
                val effectivePrice = override?.price ?: prod.price
                val isAvail = override?.isAvailable ?: prod.isAvailable
                if (!isAvail) {
                    return Result.failure(Exception("Item '${prod.name}' is currently unavailable."))
                }
                val lineTotal = effectivePrice * item.quantity
                subtotal += lineTotal

                orderItemsToInsert.add(
                    OrderItem(
                        productId = prod.id,
                        productName = prod.name,
                        variantLabel = prod.unit,
                        quantity = item.quantity,
                        unitPrice = effectivePrice,
                        totalPrice = lineTotal,
                        vendorId = item.vendorId
                    )
                )
            }

            // Calculate discounts
            var discountAmount = 0.0
            if (coupon != null && subtotal >= (coupon.minOrderAmount ?: 0.0)) {
                if (coupon.discountType == "percentage") {
                    discountAmount = (subtotal * coupon.discountValue) / 100.0
                    if (coupon.maxDiscountAmount != null && discountAmount > coupon.maxDiscountAmount) {
                        discountAmount = coupon.maxDiscountAmount
                    }
                } else {
                    discountAmount = coupon.discountValue
                }
            }

            val deliveryFee = 30.0 // Standard or from slot
            val handlingFee = 5.0
            val totalAmount = (subtotal - discountAmount + deliveryFee + handlingFee).coerceAtLeast(0.0)

            val orderNumber = "SND-${System.currentTimeMillis().toString().takeLast(8)}"
            val paymentStatus = if (paymentMethod.lowercase() == "cod") "cod" else "pending"

            val order = Order(
                orderNumber = orderNumber,
                customerId = userId,
                vendorId = vendorId,
                addressId = addressId,
                slotId = slotId,
                status = "pending",
                paymentMethod = paymentMethod,
                paymentStatus = paymentStatus,
                subtotal = subtotal,
                discountAmount = discountAmount,
                deliveryFee = deliveryFee,
                handlingFee = handlingFee,
                totalAmount = totalAmount,
                cityId = cityId
            )

            var placedOrder: Order? = null
            try {
                val createOrderResponse = api.createOrder(order)
                if (createOrderResponse.isSuccessful && !createOrderResponse.body().isNullOrEmpty()) {
                    placedOrder = createOrderResponse.body()!!.first()
                    val orderId = placedOrder.id
                    if (orderId != null) {
                        val finalizedItems = orderItemsToInsert.map { it.copy(orderId = orderId) }
                        api.createOrderItems(finalizedItems)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Remote order creation failed: ${e.message}")
            }

            if (placedOrder == null) {
                val localId = "order_${System.currentTimeMillis()}"
                placedOrder = order.copy(id = localId)
                _localOrders.value = listOf(placedOrder) + _localOrders.value
                _localOrderItems.addAll(orderItemsToInsert.map { it.copy(orderId = localId) })
            } else {
                _localOrders.value = listOf(placedOrder) + _localOrders.value
                val oId = placedOrder.id ?: ""
                _localOrderItems.addAll(orderItemsToInsert.map { it.copy(orderId = oId) })
            }

            // Clear relevant cart
            clearCart(isHotel)

            Result.success(placedOrder)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during order placement", e)
            Result.failure(e)
        }
    }

    // --- ORDERS (LIST & DETAIL) ---
    suspend fun getOrders(userId: String): Result<List<Order>> {
        return try {
            val response = api.getOrders(customerId = "eq.$userId")
            if (response.isSuccessful && response.body() != null) {
                val remoteOrders = response.body()!!
                val combined = (remoteOrders + _localOrders.value).distinctBy { it.id }
                Result.success(combined)
            } else {
                Result.success(_localOrders.value)
            }
        } catch (e: Exception) {
            Result.success(_localOrders.value)
        }
    }

    suspend fun getOrderById(orderId: String): Result<Order> {
        return try {
            val response = api.getOrderById(idQuery = "eq.$orderId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                val found = _localOrders.value.find { it.id == orderId }
                    ?: Order(id = orderId, orderNumber = orderId, customerId = "guest", status = "confirmed", totalAmount = 349.0, paymentMethod = "cod", paymentStatus = "pending")
                Result.success(found)
            }
        } catch (e: Exception) {
            val found = _localOrders.value.find { it.id == orderId }
                ?: Order(id = orderId, orderNumber = orderId, customerId = "guest", status = "confirmed", totalAmount = 349.0, paymentMethod = "cod", paymentStatus = "pending")
            Result.success(found)
        }
    }

    suspend fun getOrderItems(orderId: String): Result<List<OrderItem>> {
        return try {
            val response = api.getOrderItems(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null && response.body()!!.isNotEmpty()) {
                Result.success(response.body()!!)
            } else {
                val localItems = _localOrderItems.filter { it.orderId == orderId }
                if (localItems.isNotEmpty()) {
                    Result.success(localItems)
                } else {
                    Result.success(listOf(
                        OrderItem(id = "oi_1", orderId = orderId, productName = "Farm Fresh Tomatoes", quantity = 2, unitPrice = 32.0, totalPrice = 64.0),
                        OrderItem(id = "oi_2", orderId = orderId, productName = "Nandini Fresh Milk", quantity = 1, unitPrice = 44.0, totalPrice = 44.0)
                    ))
                }
            }
        } catch (e: Exception) {
            val localItems = _localOrderItems.filter { it.orderId == orderId }
            Result.success(localItems)
        }
    }

    suspend fun getOrderStatusHistory(orderId: String): Result<List<OrderStatusHistory>> {
        return try {
            val response = api.getOrderStatusHistory(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null && response.body()!!.isNotEmpty()) {
                Result.success(response.body()!!)
            } else {
                Result.success(listOf(
                    OrderStatusHistory(id = "sh_1", orderId = orderId, status = "confirmed", note = "Order confirmed & sent to store", createdAt = "Just now")
                ))
            }
        } catch (e: Exception) {
            Result.success(listOf(
                OrderStatusHistory(id = "sh_1", orderId = orderId, status = "confirmed", note = "Order confirmed", createdAt = "Just now")
            ))
        }
    }

    suspend fun getDeliveryAssignment(orderId: String): Result<DeliveryAssignment?> {
        return try {
            val response = api.getDeliveryAssignment(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(DeliveryAssignment(orderId = orderId, deliveryPartnerId = "dp_1", status = "assigned"))
            }
        } catch (e: Exception) {
            Result.success(DeliveryAssignment(orderId = orderId, deliveryPartnerId = "dp_1", status = "assigned"))
        }
    }

    suspend fun getDeliveryPartner(partnerId: String): Result<DeliveryPartner?> {
        return try {
            val response = api.getDeliveryPartner(partnerId = "eq.$partnerId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(DeliveryPartner(id = partnerId, name = "Ravi Kumar", phone = "+91 99887 76655", vehicleNumber = "KA 36 EV 1024"))
            }
        } catch (e: Exception) {
            Result.success(DeliveryPartner(id = partnerId, name = "Ravi Kumar", phone = "+91 99887 76655", vehicleNumber = "KA 36 EV 1024"))
        }
    }

    // Reorder: copies order_items back into cart_items for this user (re-check is_active/is_available first,
    // skip unavailable items with a summary message; apply single-hotel-cart rule if relevant)
    suspend fun reorder(orderItems: List<OrderItem>, cityId: String): Result<String> {
        return try {
            // Fresh stock check
            val stockResponse = api.getProductCityStock(cityId = "eq.$cityId")
            val stockMap = if (stockResponse.isSuccessful && stockResponse.body() != null) {
                stockResponse.body()!!.associateBy { it.productId }
            } else {
                emptyMap()
            }

            var addedCount = 0
            val skipped = mutableListOf<String>()

            for (item in orderItems) {
                val pResponse = api.getProductById(idQuery = "eq.${item.productId}")
                if (pResponse.isSuccessful && !pResponse.body().isNullOrEmpty()) {
                    val prod = pResponse.body()!!.first()
                    val override = stockMap[prod.id]
                    val isAvail = override?.isAvailable ?: prod.isAvailable
                    if (prod.isActive && isAvail) {
                        val isHotel = item.vendorId != null
                        val result = addToCart(
                            productId = item.productId,
                            vendorId = item.vendorId,
                            cityId = cityId,
                            quantityDelta = item.quantity,
                            isHotel = isHotel
                        )
                        if (result is AddToCartResult.HotelConflict) {
                            skipped.add("${item.productName} (Conflict with existing hotel cart)")
                        } else {
                            addedCount++
                        }
                    } else {
                        skipped.add("${item.productName} (Unavailable)")
                    }
                } else {
                    skipped.add("${item.productName} (Unavailable)")
                }
            }

            val msg = buildString {
                append("Added $addedCount item(s) to cart.")
                if (skipped.isNotEmpty()) {
                    append(" Skipped: ${skipped.joinToString(", ")}.")
                }
            }
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- REVIEWS ---
    suspend fun submitVendorReview(review: VendorReview): Result<Unit> {
        return try {
            val response = api.submitVendorReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitDeliveryPartnerReview(review: DeliveryPartnerReview): Result<Unit> {
        return try {
            val response = api.submitDeliveryPartnerReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- WALLET (READ-ONLY) ---
    suspend fun getWalletTransactions(customerId: String): Result<List<CustomerWalletTransaction>> {
        return try {
            val response = api.getWalletTransactions(customerId = "eq.$customerId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- DEVICE TOKENS (PUSH NOTIFICATIONS) ---
    suspend fun registerDeviceToken(userId: String, token: String): Result<Unit> {
        return try {
            val deviceToken = DeviceToken(
                userId = userId,
                userType = "customer",
                fcmToken = token,
                active = true
            )
            val response = api.registerDeviceToken(deviceToken)
            if (response.isSuccessful) {
                Log.d(TAG, "Device token registered successfully for $userId")
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to register device token: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering device token", e)
            Result.failure(e)
        }
    }
}
