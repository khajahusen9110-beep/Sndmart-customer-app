package com.example.data.remote

import com.example.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface SupabaseApi {

    // --- AUTH ENDPOINTS ---

    @POST("auth/v1/signup")
    suspend fun signup(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=password")
    suspend fun loginWithPassword(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=id_token")
    suspend fun loginWithGoogleIdToken(
        @Body body: Map<String, String>
    ): Response<SupabaseAuthResponse>

    // --- CITIES ---

    @GET("rest/v1/cities")
    suspend fun getCities(
        @Query("status") status: String = "eq.active",
        @Query("order") order: String = "name.asc"
    ): Response<List<City>>

    // --- PROFILES ---

    @GET("rest/v1/profiles")
    suspend fun getProfile(
        @Query("id") idQuery: String
    ): Response<List<Profile>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/profiles")
    suspend fun createProfile(
        @Body profile: Profile
    ): Response<List<Profile>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/profiles")
    suspend fun updateProfile(
        @Query("id") idQuery: String,
        @Body profile: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<Profile>>

    // --- CATEGORIES ---

    @GET("rest/v1/categories")
    suspend fun getCategories(
        @Query("is_active") isActive: String = "eq.true",
        @Query("order") order: String = "sort_order.asc"
    ): Response<List<Category>>

    // --- VENDORS ---

    @GET("rest/v1/vendors")
    suspend fun getVendors(
        @Query("city_id") cityId: String,
        @Query("approval_status") approvalStatus: String = "eq.approved",
        @Query("order") order: String = "is_active.desc,is_featured.desc,name.asc"
    ): Response<List<Vendor>>

    // --- PRODUCTS ---

    @GET("rest/v1/products")
    suspend fun getProducts(
        @Query("is_active") isActive: String = "eq.true",
        @Query("category_id") categoryId: String? = null,
        @Query("vendor_id") vendorId: String? = null,
        @Query("order") order: String = "is_featured.desc,name.asc"
    ): Response<List<Product>>

    @GET("rest/v1/products")
    suspend fun getProductById(
        @Query("id") idQuery: String
    ): Response<List<Product>>

    // --- PRODUCT CITY STOCK ---

    @GET("rest/v1/product_city_stock")
    suspend fun getProductCityStock(
        @Query("city_id") cityId: String
    ): Response<List<ProductCityStock>>

    @GET("rest/v1/product_city_stock")
    suspend fun getSingleProductCityStock(
        @Query("product_id") productId: String,
        @Query("city_id") cityId: String
    ): Response<List<ProductCityStock>>

    // --- CART ITEMS ---

    @GET("rest/v1/cart_items")
    suspend fun getCartItems(
        @Query("user_id") userId: String
    ): Response<List<CartItem>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/cart_items")
    suspend fun insertCartItem(
        @Body item: CartItem
    ): Response<List<CartItem>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/cart_items")
    suspend fun updateCartItemQuantity(
        @Query("id") idQuery: String,
        @Body body: Map<String, Int>
    ): Response<List<CartItem>>

    @DELETE("rest/v1/cart_items")
    suspend fun deleteCartItem(
        @Query("id") idQuery: String
    ): Response<ResponseBody>

    @DELETE("rest/v1/cart_items")
    suspend fun clearCartForUser(
        @Query("user_id") userIdQuery: String
    ): Response<ResponseBody>

    // --- ADDRESSES ---

    @GET("rest/v1/customer_addresses")
    suspend fun getAddresses(
        @Query("user_id") userId: String,
        @Query("order") order: String = "is_default.desc,id.desc"
    ): Response<List<CustomerAddress>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/customer_addresses")
    suspend fun insertAddress(
        @Body address: CustomerAddress
    ): Response<List<CustomerAddress>>

    @Headers("Prefer: return=representation")
    @PATCH("rest/v1/customer_addresses")
    suspend fun updateAddress(
        @Query("id") idQuery: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<CustomerAddress>>

    @DELETE("rest/v1/customer_addresses")
    suspend fun deleteAddress(
        @Query("id") idQuery: String
    ): Response<ResponseBody>

    // --- DELIVERY SLOTS ---

    @GET("rest/v1/delivery_slots")
    suspend fun getDeliverySlots(
        @Query("city_id") cityId: String,
        @Query("is_active") isActive: String = "eq.true"
    ): Response<List<DeliverySlot>>

    // --- COUPONS ---

    @GET("rest/v1/coupons")
    suspend fun getCoupons(
        @Query("city_id") cityId: String,
        @Query("is_active") isActive: String = "eq.true"
    ): Response<List<Coupon>>

    // --- ORDERS ---

    @GET("rest/v1/orders")
    suspend fun getOrders(
        @Query("customer_id") customerId: String,
        @Query("order") order: String = "id.desc"
    ): Response<List<Order>>

    @GET("rest/v1/orders")
    suspend fun getOrderById(
        @Query("id") idQuery: String
    ): Response<List<Order>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/orders")
    suspend fun createOrder(
        @Body order: Order
    ): Response<List<Order>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/order_items")
    suspend fun createOrderItems(
        @Body items: List<OrderItem>
    ): Response<List<OrderItem>>

    @GET("rest/v1/order_items")
    suspend fun getOrderItems(
        @Query("order_id") orderId: String
    ): Response<List<OrderItem>>

    @GET("rest/v1/order_status_history")
    suspend fun getOrderStatusHistory(
        @Query("order_id") orderId: String,
        @Query("order") order: String = "created_at.asc"
    ): Response<List<OrderStatusHistory>>

    // --- DELIVERY ASSIGNMENTS & PARTNER ---

    @GET("rest/v1/delivery_assignments")
    suspend fun getDeliveryAssignment(
        @Query("order_id") orderId: String
    ): Response<List<DeliveryAssignment>>

    @GET("rest/v1/delivery_partners")
    suspend fun getDeliveryPartner(
        @Query("id") partnerId: String
    ): Response<List<DeliveryPartner>>

    // --- REVIEWS ---

    @Headers("Prefer: return=representation")
    @POST("rest/v1/vendor_reviews")
    suspend fun submitVendorReview(
        @Body review: VendorReview
    ): Response<List<VendorReview>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/delivery_partner_reviews")
    suspend fun submitDeliveryPartnerReview(
        @Body review: DeliveryPartnerReview
    ): Response<List<DeliveryPartnerReview>>

    @GET("rest/v1/vendor_reviews")
    suspend fun getMyReviews(
        @Query("customer_id") customerId: String
    ): Response<List<VendorReview>>

    // --- WALLET ---

    @GET("rest/v1/customer_wallet_transactions")
    suspend fun getWalletTransactions(
        @Query("customer_id") customerId: String,
        @Query("order") order: String = "created_at.desc"
    ): Response<List<CustomerWalletTransaction>>

    // --- DEVICE TOKENS ---

    @Headers("Prefer: resolution=merge-duplicates")
    @POST("rest/v1/device_tokens")
    suspend fun registerDeviceToken(
        @Body deviceToken: DeviceToken
    ): Response<ResponseBody>
}
