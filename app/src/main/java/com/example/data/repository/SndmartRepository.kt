package com.example.data.repository

import android.util.Log
import com.example.data.model.*
import com.example.data.remote.ApiException
import com.example.data.remote.SessionExpiredException
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    val currentCity: com.example.data.model.City?
        get() = sessionManager.selectedCity.value

    val currentUserId: String?
        get() = sessionManager.userId.value

    // In-memory cart items (strictly NO price column, only productId, vendorId, cityId, quantity)
    private val _groceryCart = MutableStateFlow<List<CartItem>>(emptyList())
    val groceryCart: StateFlow<List<CartItem>> = _groceryCart.asStateFlow()

    private val _hotelCart = MutableStateFlow<List<CartItem>>(emptyList())
    val hotelCart: StateFlow<List<CartItem>> = _hotelCart.asStateFlow()

    // Background scope used to mirror the in-memory cart to the cart_items table.
    private val cartSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null

    // Local in-memory state for offline/demo operation when Supabase key is unconfigured or offline
    private val _localAddresses = MutableStateFlow<List<CustomerAddress>>(emptyList())

    // --- SESSION MANAGEMENT ---
    suspend fun refreshSession(): Result<SupabaseAuthResponse> {
        val refreshToken = sessionManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            sessionManager.notifySessionExpired("Your session expired, please log in again")
            return Result.failure(SessionExpiredException("No refresh token stored"))
        }
        return try {
            val response = api.refreshSession(mapOf("refresh_token" to refreshToken))
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                val newAccessToken = auth.accessToken
                val newRefreshToken = auth.refreshToken ?: refreshToken
                if (!newAccessToken.isNullOrBlank()) {
                    sessionManager.updateTokens(newAccessToken, newRefreshToken, auth.expiresIn)
                    Log.i(TAG, "Session refreshed successfully via SndmartRepository.")
                    Result.success(auth)
                } else {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Empty access token in refresh response"))
                }
            } else {
                val code = response.code()
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Refresh session failed HTTP $code: $error")
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Session expired: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during refreshSession: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun ensureValidSession(): Result<Boolean> {
        if (!sessionManager.isLoggedIn.value) {
            return Result.success(false)
        }
        if (sessionManager.isSessionExpiredOrExpiringSoon(bufferMs = 120_000L)) {
            Log.d(TAG, "Session token is expired or expiring soon; proactively refreshing session...")
            val res = refreshSession()
            return if (res.isSuccess) {
                Result.success(true)
            } else {
                Result.failure(res.exceptionOrNull() ?: SessionExpiredException())
            }
        }
        return Result.success(true)
    }

    private fun getFallbackGroceryProducts(categoryId: String?, searchQuery: String? = null): List<ResolvedProduct> {
        val filtered = when {
            !searchQuery.isNullOrBlank() ->
                DemoCatalog.GROCERY_PRODUCTS.filter { it.name.contains(searchQuery, ignoreCase = true) }
            categoryId.isNullOrBlank() ->
                DemoCatalog.GROCERY_PRODUCTS
            else ->
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

    // Grocery categories: vendor_type IN (grocery,vegetable,fruit), vendor_id IS NULL,
    // scoped to the customer's city. Demo fallback ignores city_id (demo cats have none).
    private val demoGroceryCategories: List<Category>
        get() = DemoCatalog.CATEGORIES.filter {
            val vt = it.vendorType?.lowercase()
            vt in listOf("grocery", "vegetable", "fruit") && it.vendorId == null && it.isActive
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
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured. Configure key to load live cities."))
        }
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Failed to fetch cities from backend: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching cities from backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- CITY DETECTION (BACKEND RPC) ---
    suspend fun findCityForLocation(lat: Double, lng: Double): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
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
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCities(): Result<List<City>> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching cities: ${e.message}", e)
            Result.success(emptyList())
        }
    }

    suspend fun findCityAndDistanceForLocation(lat: Double, lng: Double): Result<CityLocationResult?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                Result.success(results.firstOrNull())
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfileCityId(userId: String, cityId: String): Result<Unit> {
        return try {
            val response = api.updateProfile("eq.$userId", mapOf("city_id" to cityId))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Log.w(TAG, "Exception updating profile city_id: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Edit profile (name + phone) via PATCH /rest/v1/profiles?id=eq.{userId}.
    suspend fun updateProfile(userId: String, fullName: String?, phone: String?): Result<Unit> {
        return try {
            val body = mutableMapOf<String, Any?>()
            if (fullName != null) body["full_name"] = fullName
            if (phone != null) body["phone"] = phone
            val response = api.updateProfile("eq.$userId", body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads the logged-in user's profiles.city_id and resolves it to an active City
     * (with name). Returns null when the user has no city assigned yet (first-time flow)
     * or when the backend is unavailable — the caller then falls back to GPS detection.
     */
    suspend fun resolveUserCity(userId: String): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(null)
        return try {
            val profileRes = api.getProfile("eq.$userId")
            if (!profileRes.isSuccessful || profileRes.body().isNullOrEmpty()) {
                return Result.success(null)
            }
            val cityId = profileRes.body()!!.first().cityId
                ?: return Result.success(null)
            val citiesRes = api.getCities(status = "eq.active", order = "name.asc")
            if (!citiesRes.isSuccessful || citiesRes.body() == null) {
                return Result.success(null)
            }
            val city = citiesRes.body()!!.firstOrNull { it.id == cityId }
            Result.success(city)
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving user city from profile: ${e.message}", e)
            Result.success(null)
        }
    }

    // --- CATEGORIES ---
    suspend fun getCategories(): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.CATEGORIES)
        }
        return try {
            val response = api.getCategories()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch categories: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Grocery categories for a city: vendor_type IN (grocery,vegetable,fruit),
    // vendor_id IS NULL, is_active=true, city_id=eq.{cityId}, ordered by sort_order.
    suspend fun getGroceryCategories(cityId: String): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(demoGroceryCategories)
        }
        return try {
            val response = api.getCategories(
                isActive = "eq.true",
                order = "sort_order.asc",
                vendorType = "in.(grocery,vegetable,fruit)",
                vendorId = "is.null",
                cityId = "eq.$cityId"
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch grocery categories: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching grocery categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- VENDORS (HOTELS) ---
    // Fetches hotels (vendor_type=hotel) for a city. When searchQuery is blank both
    // active and inactive approved hotels are returned (inactive appended at bottom
    // by the caller's sort). When searching, filters by name=ilike server-side.
    suspend fun getHotels(cityId: String, searchQuery: String? = null): Result<List<Vendor>> {
        if (!SupabaseClient.isKeyConfigured()) {
            val demo = if (!searchQuery.isNullOrBlank())
                DemoCatalog.HOTELS.filter { it.name.contains(searchQuery, ignoreCase = true) }
            else DemoCatalog.HOTELS
            return Result.success(demo)
        }
        return try {
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val response = api.getVendors(
                cityId = "eq.$cityId",
                vendorType = "eq.hotel",
                name = nameQuery
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch hotels: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotels: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Average vendor rating computed client-side from vendor_reviews (select=rating).
    // Returns 0.0 when there are no reviews yet or the backend is unavailable.
    suspend fun getVendorAverageRating(vendorId: String): Result<Double> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(0.0)
        return try {
            val response = api.getVendorReviews(vendorId = "eq.$vendorId", select = "rating")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val ratings = response.body()!!.mapNotNull { it.rating.takeIf { r -> r > 0 } }
                Result.success(if (ratings.isNotEmpty()) ratings.average() else 0.0)
            } else {
                Result.success(0.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching vendor rating: ${e.message}", e)
            Result.success(0.0)
        }
    }

    // Fetch a single vendor's display name (used to label hotel orders in the list).
    suspend fun getVendorName(vendorId: String): Result<String?> {
        return try {
            val response = api.getVendorById(idQuery = "eq.$vendorId")
            Result.success(response.body()?.firstOrNull()?.name)
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun getVendor(vendorId: String): Result<Vendor?> {
        return try {
            val response = api.getVendorById(idQuery = "eq.$vendorId")
            Result.success(response.body()?.firstOrNull())
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    // --- PRODUCTS & CITY STOCK RESOLUTION ---
    suspend fun getResolvedGroceryProducts(
        cityId: String,
        categoryId: String? = null,
        searchQuery: String? = null
    ): Result<List<ResolvedProduct>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getFallbackGroceryProducts(categoryId, searchQuery))
        }
        return try {
            // When searching, look across ALL city groceries (ignore category) using
            // name=ilike.*query*; otherwise filter by the selected category.
            val searching = !searchQuery.isNullOrBlank()
            val catQuery = if (searching) null else categoryId?.let { "eq.$it" }
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                categoryId = catQuery,
                vendorId = "is.null",
                name = nameQuery
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.w(TAG, "Could not fetch products: $error")
                if (prodResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(prodResponse.code(), error))
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
            Log.w(TAG, "Exception fetching grocery products: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getHotelMenu(vendorId: String, cityId: String): Result<Pair<List<Category>, List<ResolvedProduct>>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getFallbackHotelMenu(vendorId))
        }
        return try {
            // Hotel's menu categories have vendor_id = that hotel
            val catResponse = api.getCategories(order = "sort_order.asc")
            if (!catResponse.isSuccessful) {
                val error = SupabaseClient.parseErrorMessage(catResponse)
                Log.w(TAG, "Could not fetch hotel categories: $error")
                if (catResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(catResponse.code(), error))
            }
            val categories = catResponse.body()?.filter { it.vendorId == vendorId } ?: emptyList()

            // Products for this hotel
            val prodResponse = api.getProducts(
                isActive = "eq.true",
                vendorId = "eq.$vendorId"
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.w(TAG, "Could not fetch hotel products: $error")
                if (prodResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(prodResponse.code(), error))
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
            Result.failure(e)
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

    // --- CART BACKEND SYNC ---
    // The in-memory StateFlows are the UI source of truth. When a Supabase key is
    // configured we mirror the cart to the cart_items table (so it survives across
    // sessions / is visible to the backend) and rehydrate it on session start.
    // Prices are NEVER stored in cart_items — they are always re-fetched fresh
    // (see getFreshCartItems); cart_items only holds user_id/product_id/variant_id/
    // vendor_id/city_id/quantity.
    private fun persistCartToBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        val snapshot = _groceryCart.value + _hotelCart.value
        persistJob?.cancel()
        persistJob = cartSyncScope.launch {
            try {
                delay(300) // coalesce rapid stepper taps into one write
                api.clearCartForUser(userIdQuery = "eq.$userId")
                for (item in snapshot) {
                    api.insertCartItem(item.copy(id = null))
                }
            } catch (e: CancellationException) {
                // Debounce cancellation when user rapidly updates cart - normal lifecycle, ignore
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cart to backend: ${e.message}")
            }
        }
    }

    suspend fun syncCartFromBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        try {
            val res = api.getCartItems(userId = "eq.$userId")
            if (res.isSuccessful && res.body() != null) {
                val items = res.body()!!
                _groceryCart.value = items.filter { it.vendorId == null }
                _hotelCart.value = items.filter { it.vendorId != null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cart from backend", e)
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
        persistCartToBackend()
        return AddToCartResult.Success
    }

    fun forceClearHotelCartAndAdd(item: CartItem) {
        _hotelCart.value = listOf(item)
        persistCartToBackend()
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
        persistCartToBackend()
    }

    fun clearCart(isHotel: Boolean) {
        if (isHotel) {
            _hotelCart.value = emptyList()
        } else {
            _groceryCart.value = emptyList()
        }
        persistCartToBackend()
    }

    fun clearAllCarts() {
        _groceryCart.value = emptyList()
        _hotelCart.value = emptyList()
        persistCartToBackend()
    }

    fun getCartCount(): Int {
        val g = _groceryCart.value.sumOf { it.quantity }
        val h = _hotelCart.value.sumOf { it.quantity }
        return g + h
    }

    // --- DELIVERY SLOTS & COUPONS ---
    // Fallback slot configurations matching the city's delivery slot setup if backend table query lacks select permissions
    fun getCityDefaultSlots(cityId: String): List<DeliverySlot> {
        val cleanCityId = cityId.removePrefix("eq.")
        return listOf(
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000001",
                cityId = cleanCityId,
                name = "Morning (9 AM - 12 PM)",
                startTime = "09:00",
                endTime = "12:00",
                start = "09:00",
                end = "12:00",
                minOrderAmount = 0.0,
                isFreeDelivery = false,
                deliveryFee = 30.0,
                isActive = true
            ),
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000002",
                cityId = cleanCityId,
                name = "Afternoon (12 PM - 4 PM)",
                startTime = "12:00",
                endTime = "16:00",
                start = "12:00",
                end = "16:00",
                minOrderAmount = 0.0,
                isFreeDelivery = false,
                deliveryFee = 30.0,
                isActive = true
            ),
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000003",
                cityId = cleanCityId,
                name = "Evening (4 PM - 9 PM)",
                startTime = "16:00",
                endTime = "21:00",
                start = "16:00",
                end = "21:00",
                minOrderAmount = 199.0,
                isFreeDelivery = true,
                deliveryFee = 30.0,
                isActive = true
            )
        )
    }

    suspend fun getDeliverySlots(cityId: String): Result<List<DeliverySlot>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getCityDefaultSlots(cityId))
        }
        return try {
            val queryCityId = if (cityId.startsWith("eq.")) cityId else "eq.$cityId"
            val response = api.getDeliverySlots(cityId = queryCityId, order = "start_time.asc")
            if (response.isSuccessful && response.body() != null) {
                val slots = response.body()!!.filter { it.isActive != false }
                Result.success(slots)
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.w(TAG, "Delivery slots unavailable from backend ($err)")
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching delivery slots: ${e.message}")
            Result.success(emptyList())
        }
    }

    suspend fun getExpressDeliverySettings(cityId: String): Result<ExpressDeliverySettings?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(null)
        }
        return try {
            val queryCityId = if (cityId.startsWith("eq.")) cityId else "eq.$cityId"
            val response = api.getExpressDeliverySettings(cityId = queryCityId)
            if (response.isSuccessful && response.body() != null) {
                val settings = response.body()!!.firstOrNull { it.isActive }
                Result.success(settings)
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.w(TAG, "Express delivery settings unavailable: $err")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching express delivery settings: ${e.message}", e)
            Result.success(null)
        }
    }

    suspend fun resolveDeliveryDistanceKm(
        address: CustomerAddress?,
        cityId: String?,
        vendorId: String?,
        isHotel: Boolean
    ): Double {
        if (address == null || address.lat == null || address.lng == null) {
            return 1.0
        }
        val userLat = address.lat
        val userLng = address.lng

        if (isHotel && !vendorId.isNullOrBlank()) {
            val vendor = getVendor(vendorId).getOrNull()
            if (vendor != null && vendor.latitude != null && vendor.longitude != null) {
                return calculateHaversineDistanceKm(userLat, userLng, vendor.latitude, vendor.longitude)
            }
        }

        // For grocery, check city center coordinates
        val cleanCityId = cityId?.removePrefix("eq.")
        val cities = getCities().getOrNull() ?: emptyList()
        val city = cities.find { it.id == cleanCityId } ?: cities.firstOrNull()
        if (city != null && city.centerLat != null && city.centerLng != null) {
            return calculateHaversineDistanceKm(userLat, userLng, city.centerLat, city.centerLng)
        }

        // Fallback to RPC findCityAndDistanceForLocation if available
        val locRes = findCityAndDistanceForLocation(userLat, userLng).getOrNull()
        if (locRes != null && locRes.distanceKm != null && locRes.distanceKm > 0.0) {
            return locRes.distanceKm
        }

        // Fallback to regional hub coordinates (e.g. Sindhanur fulfillment warehouse: 15.7667, 76.7583)
        val knownCoords = mapOf(
            "sindhanur" to Pair(15.7667, 76.7583),
            "raichur" to Pair(16.2120, 77.3439),
            "bellary" to Pair(15.1394, 76.9214),
            "ballari" to Pair(15.1394, 76.9214),
            "gangavathi" to Pair(15.4326, 76.5312),
            "manvi" to Pair(15.9922, 77.0506),
            "koppal" to Pair(15.3524, 76.1557)
        )
        val cityName = city?.name?.trim()?.lowercase(java.util.Locale.ROOT) ?: "sindhanur"
        val hubCoords = knownCoords[cityName] ?: knownCoords["sindhanur"]
        if (hubCoords != null) {
            return calculateHaversineDistanceKm(userLat, userLng, hubCoords.first, hubCoords.second)
        }

        return 1.0
    }

    suspend fun getCustomerDeliveryOptions(cityId: String): Result<CustomerDeliveryOptions> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
            val response = api.getCustomerDeliveryOptions(mapOf("p_city_id" to cleanCityId))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Failed to get customer delivery options: $err")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting customer delivery options: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun calculateCityDeliveryCharge(
        cityId: String,
        deliveryType: String,
        distanceKm: Double,
        orderAmount: Double
    ): Result<DeliveryChargeResult> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
            val payload = mapOf(
                "p_city_id" to cleanCityId,
                "p_delivery_type" to deliveryType,
                "p_distance_km" to distanceKm,
                "p_order_amount" to orderAmount
            )
            val response = api.calculateCityDeliveryCharge(payload)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "calculate_city_delivery_charge failed: $err")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in calculate_city_delivery_charge: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun validateAndApplyCoupon(
        code: String,
        cityId: String,
        subtotal: Double,
        userId: String?
    ): Result<CouponValidationResult> {
        val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.isBlank()) {
            return Result.success(CouponValidationResult(isValid = false, errorMessage = "Please enter a coupon code"))
        }

        // 1. Authoritative check with Supabase coupons table
        return try {
            val response = api.getCoupons(cityId = "eq.$cleanCityId", code = "eq.$trimmedCode")
            val coupon = response.body()?.firstOrNull()
            if (coupon == null) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        errorMessage = "Invalid or inactive coupon for this city"
                    )
                )
            }

            if (!coupon.isActive) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "This coupon is no longer active")
                )
            }

            val now = System.currentTimeMillis()
            val starts = parseIsoTime(coupon.startsAt)
            if (starts != null && now < starts) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon is not active yet")
                )
            }

            val expires = parseIsoTime(coupon.expiresAt)
            if (expires != null && now > expires) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon has expired")
                )
            }

            val minOrder = coupon.minOrderAmount ?: 0.0
            if (subtotal < minOrder) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        coupon = coupon,
                        errorMessage = "Minimum order of ₹${"%.0f".format(minOrder)} required for this coupon"
                    )
                )
            }

            val limit = coupon.usageLimit
            if (limit != null && (coupon.usedCount ?: 0) >= limit) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon usage limit reached")
                )
            }

            var discount = if (coupon.discountType == "percentage") {
                (subtotal * coupon.discountValue) / 100.0
            } else {
                coupon.discountValue
            }
            if (coupon.maxDiscountAmount != null && discount > coupon.maxDiscountAmount) {
                discount = coupon.maxDiscountAmount
            }
            discount = discount.coerceAtMost(subtotal)

            Result.success(
                CouponValidationResult(
                    isValid = true,
                    coupon = coupon,
                    discountAmount = discount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Coupon validation failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCoupons(cityId: String): Result<List<Coupon>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.COUPONS)
        }
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

    // Look up a single active coupon by code for a city (server-side filter).
    suspend fun getCouponByCode(code: String, cityId: String): Result<Coupon?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        }
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId", code = "eq.$code")
            val remote = response.body()?.firstOrNull()
            Result.success(remote ?: DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        } catch (e: Exception) {
            Result.success(DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        }
    }

    // Full client-side validation before applying/using a coupon. Returns an error
    // message when invalid, or null when the coupon may be applied.
    fun validateCoupon(coupon: Coupon, subtotal: Double): String? {
        val now = System.currentTimeMillis()
        val starts = parseIsoTime(coupon.startsAt)
        if (starts != null && now < starts) return "Coupon is not active yet"
        val expires = parseIsoTime(coupon.expiresAt)
        if (expires != null && now > expires) return "Coupon has expired"
        val limit = coupon.usageLimit
        if (limit != null && (coupon.usedCount ?: 0) >= limit) return "Coupon usage limit reached"
        val min = coupon.minOrderAmount ?: 0.0
        if (subtotal < min) return "Minimum order ₹${"%.0f".format(min)} required"
        return null
    }

    private fun parseIsoTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val core = if (s.length >= 19) s.substring(0, 19) else s
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(core)?.time
        } catch (e: Exception) { null }
    }

    suspend fun recordCouponUsage(couponId: String, userId: String, orderId: String) {
        if (!SupabaseClient.isKeyConfigured()) return
        try {
            api.insertCouponUsage(CouponUsage(couponId = couponId, userId = userId, orderId = orderId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record coupon usage", e)
        }
    }

    // --- CUSTOMER ADDRESSES ---
    suspend fun getAddresses(userId: String): Result<List<CustomerAddress>> {
        return try {
            val response = api.getAddresses(userId = "eq.$userId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                Result.success(_localAddresses.value)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAddressById(addressId: String): Result<CustomerAddress?> {
        return try {
            val response = api.getAddressById(idQuery = "eq.$addressId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                Result.success(_localAddresses.value.find { it.id == addressId })
            }
        } catch (e: Exception) {
            Result.success(_localAddresses.value.find { it.id == addressId })
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

    // Edit an existing address.
    suspend fun updateAddress(id: String, fields: Map<String, Any?>): Result<Unit> {
        return try {
            val response = api.updateAddress(idQuery = "eq.$id", body = fields)
            if (response.isSuccessful) {
                response.body()?.firstOrNull()?.let { updated ->
                    _localAddresses.value = _localAddresses.value.map { if (it.id == id) updated else it }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Set one address as default: unset the previous default first so only one stays true.
    suspend fun setDefaultAddress(userId: String, addressId: String): Result<Unit> {
        return try {
            val res = api.getAddresses(userId = "eq.$userId")
            val current = res.body().orEmpty()
            // 1. Unset any existing default (other than the one being promoted).
            current.filter { it.isDefault && it.id != addressId }.forEach { prev ->
                prev.id?.let { api.updateAddress(idQuery = "eq.$it", body = mapOf("is_default" to false)) }
            }
            // 2. Set the chosen address as default.
            api.updateAddress(idQuery = "eq.$addressId", body = mapOf("is_default" to true))
            _localAddresses.value = _localAddresses.value.map {
                it.copy(isDefault = (it.id == addressId))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
        coupon: Coupon? = null,
        deliveryType: String = "free_slot",
        deliveryDistanceKm: Double? = null,
        deliveryOptionSnapshot: Map<String, Any?>? = null
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

            // 1. Authoritative delivery charge calculation based on delivery_slots and express_delivery_settings
            val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
            val distance = deliveryDistanceKm ?: 0.0
            val isExpress = deliveryType == "express"

            var authoritativeDeliveryFee = 0.0
            var authoritativeSlotId: String? = null
            val finalDeliveryType: String

            if (isExpress) {
                val expressSettings = getExpressDeliverySettings(cleanCityId).getOrNull()
                if (expressSettings == null || !expressSettings.isActive) {
                    return Result.failure(Exception("Express delivery is currently not available in this area"))
                }
                if (!expressSettings.isSubtotalEligible(subtotal)) {
                    val minOrd = expressSettings.minOrderAmount ?: 0.0
                    return Result.failure(Exception("Minimum order ₹${"%.0f".format(minOrd)} required for Express Delivery"))
                }
                val (fee, _) = expressSettings.calculateCharge(distance, subtotal)
                authoritativeDeliveryFee = fee
                authoritativeSlotId = null
                finalDeliveryType = "express"
            } else {
                val slots = getDeliverySlots(cleanCityId).getOrNull() ?: emptyList()
                if (slots.isEmpty()) {
                    return Result.failure(Exception("Scheduled delivery is currently not available in this area"))
                }
                val matchedSlot = slots.find { it.id == slotId } ?: slots.first()
                authoritativeDeliveryFee = matchedSlot.getEffectiveDeliveryFee(subtotal)
                authoritativeSlotId = matchedSlot.id
                finalDeliveryType = "scheduled"
            }

            // 3. Authoritative coupon validation via Supabase
            var authoritativeDiscount = 0.0
            var couponApplied = false
            var authoritativeCoupon: Coupon? = null
            if (coupon != null && coupon.code.isNotBlank()) {
                val couponValidation = validateAndApplyCoupon(
                    code = coupon.code,
                    cityId = cleanCityId,
                    subtotal = subtotal,
                    userId = userId
                ).getOrNull()

                if (couponValidation != null && couponValidation.isValid) {
                    authoritativeDiscount = couponValidation.discountAmount
                    couponApplied = true
                    authoritativeCoupon = couponValidation.coupon ?: coupon
                } else {
                    Log.w(TAG, "Coupon validation failed at placement: ${couponValidation?.errorMessage}")
                }
            }

            // 4. Final total calculated from backend-authoritative values
            val handlingFee = 5.0
            val totalAmount = (subtotal - authoritativeDiscount + authoritativeDeliveryFee + handlingFee).coerceAtLeast(0.0)

            // 5. Construct snapshot & create order via existing Supabase order flow
            val resolvedSnapshot = deliveryOptionSnapshot ?: mapOf(
                "delivery_type" to deliveryType,
                "delivery_fee" to authoritativeDeliveryFee,
                "distance_km" to distance,
                "slot_id" to authoritativeSlotId
            )

            val orderNumber = "SND-${System.currentTimeMillis().toString().takeLast(8)}"
            val mappedPaymentMethod = when (paymentMethod.lowercase()) {
                "cod" -> "cash"
                "upi" -> "upi"
                "card" -> "card"
                "cash" -> "cash"
                "online" -> "online"
                else -> "cash"
            }
            val paymentStatus = if (paymentMethod.lowercase() == "cod" || mappedPaymentMethod == "cash") "cod" else "pending"

            val order = Order(
                orderNumber = orderNumber,
                customerId = userId,
                vendorId = vendorId,
                addressId = addressId,
                slotId = authoritativeSlotId?.takeIf { it.isNotBlank() },
                status = "pending",
                paymentMethod = mappedPaymentMethod,
                paymentStatus = paymentStatus,
                subtotal = subtotal,
                discountAmount = authoritativeDiscount,
                deliveryFee = authoritativeDeliveryFee,
                handlingFee = handlingFee,
                totalAmount = totalAmount,
                cityId = cleanCityId,
                deliveryType = finalDeliveryType,
                deliveryDistanceKm = distance,
                deliveryOptionSnapshot = resolvedSnapshot
            )

            val placedOrder: Order = try {
                var createOrderResponse = api.createOrder(order)
                if (!createOrderResponse.isSuccessful && order.slotId != null) {
                    val err = createOrderResponse.errorBody()?.string().orEmpty()
                    if (err.contains("slot_id") || err.contains("foreign key") || err.contains("fkey")) {
                        Log.w(TAG, "Retrying order creation without slot_id due to foreign key constraint: $err")
                        createOrderResponse = api.createOrder(order.copy(slotId = null))
                    }
                }
                if (createOrderResponse.isSuccessful && !createOrderResponse.body().isNullOrEmpty()) {
                    val created = createOrderResponse.body()!!.first()
                    val orderId = created.id
                    if (orderId != null) {
                        val finalizedItems = orderItemsToInsert.map { it.copy(orderId = orderId) }
                        api.createOrderItems(finalizedItems)
                        if (couponApplied && authoritativeCoupon != null) {
                            recordCouponUsage(authoritativeCoupon.id, userId, orderId)
                        }
                    }
                    created
                } else {
                    val err = createOrderResponse.errorBody()?.string() ?: "Code ${createOrderResponse.code()}"
                    Log.e(TAG, "Remote order creation failed: $err")
                    return Result.failure(Exception("Failed to place order: $err"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Remote order creation failed", e)
                return Result.failure(e)
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
            val response = api.getOrders(customerId = "eq.$userId", order = "placed_at.desc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else if (response.code() == 400) {
                // If placed_at column is not recognized, fallback to created_at.desc
                val fallback = api.getOrders(customerId = "eq.$userId", order = "created_at.desc")
                if (fallback.isSuccessful && fallback.body() != null) {
                    Result.success(fallback.body()!!)
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Failed to fetch orders: ${fallback.code()} ${fallback.message()}"))
                }
            } else {
                Result.failure(Exception("Failed to fetch orders: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderById(orderId: String): Result<Order> {
        return try {
            val selectJoin = "*,delivery_partners(id,name,phone,latitude,longitude,vehicle_type,vehicle_number)"
            val response = api.getOrderById(idQuery = "eq.$orderId", select = selectJoin)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                // Fallback without join
                val fallback = api.getOrderById(idQuery = "eq.$orderId")
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Order not found: $orderId"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderItems(orderId: String): Result<List<OrderItem>> {
        return try {
            val response = api.getOrderItems(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderStatusHistory(orderId: String): Result<List<OrderStatusHistory>> {
        return try {
            val response = api.getOrderStatusHistory(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryAssignment(orderId: String): Result<DeliveryAssignment?> {
        return try {
            val selectFields = "estimated_delivery_minutes,estimated_delivery_at,delivery_otp"
            val response = api.getDeliveryAssignment(
                orderId = "eq.$orderId",
                order = "created_at.desc",
                limit = 1,
                select = selectFields
            )
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                val fallback = api.getDeliveryAssignment(orderId = "eq.$orderId", order = "created_at.desc", limit = 1)
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryPartner(partnerId: String): Result<DeliveryPartner?> {
        return try {
            val response = api.getDeliveryPartner(partnerId = "eq.$partnerId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
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
                            skipped.add("'${item.productName}' conflicts with your current hotel cart.")
                        } else {
                            addedCount++
                        }
                    } else {
                        skipped.add("'${item.productName}' is no longer available.")
                    }
                } else {
                    skipped.add("'${item.productName}' is no longer available.")
                }
            }

            val total = orderItems.size
            val msg = buildString {
                append("$addedCount of $total items added to cart.")
                if (skipped.isNotEmpty()) {
                    append(" ${skipped.joinToString(" ")}")
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

    // Reviews the customer has written (read-only lists for "My Reviews").
    suspend fun getMyVendorReviews(customerId: String): Result<List<VendorReview>> {
        return try {
            val response = api.getMyReviews(customerId = "eq.$customerId")
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyDeliveryPartnerReviews(customerId: String): Result<List<DeliveryPartnerReview>> {
        return try {
            val response = api.getMyDeliveryPartnerReviews(customerId = "eq.$customerId")
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Order ids the customer has already reviewed (vendor + delivery partner), used to
    // decide whether to show the "Rate this order" prompt on a delivered order.
    suspend fun getReviewedOrderIds(customerId: String): Result<Set<String>> {
        return try {
            val ids = mutableSetOf<String>()
            getMyVendorReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            getMyDeliveryPartnerReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            Result.success(ids)
        } catch (e: Exception) {
            Result.success(emptySet())
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

fun calculateHaversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0 // Earth's radius in kilometers
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}
