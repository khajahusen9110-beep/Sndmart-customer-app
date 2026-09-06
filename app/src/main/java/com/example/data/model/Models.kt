package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class City(
    val id: String = "",
    val name: String = "",
    val state: String? = null,
    val status: String = "active" // 'active'/'inactive'/'coming_soon'
)

@JsonClass(generateAdapter = true)
data class Category(
    val id: String = "",
    val name: String = "",
    @Json(name = "name_kn") val nameKn: String? = null,
    val slug: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    @Json(name = "sort_order") val sortOrder: Int? = 0,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "vendor_type") val vendorType: String? = null, // 'grocery'/'vegetable'/'fruit'/'hotel'
    @Json(name = "vendor_id") val vendorId: String? = null, // null for grocery, set for hotel menu category
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class Vendor(
    val id: String = "",
    val name: String = "",
    @Json(name = "vendor_type") val vendorType: String = "hotel",
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "is_open") val isOpen: Boolean = true,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "approval_status") val approvalStatus: String = "approved",
    @Json(name = "banner_url") val bannerUrl: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "opening_time") val openingTime: String? = null,
    @Json(name = "closing_time") val closingTime: String? = null,
    @Json(name = "is_featured") val isFeatured: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class Product(
    val id: String = "",
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "vendor_id") val vendorId: String? = null, // null for generic grocery items
    val name: String = "",
    val description: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    val price: Double = 0.0,
    val mrp: Double? = null,
    val unit: String? = null,
    @Json(name = "stock_qty") val stockQty: Int? = null,
    @Json(name = "stock_quantity") val stockQuantity: Int? = null,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "is_featured") val isFeatured: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class ProductCityStock(
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "city_id") val cityId: String = "",
    val price: Double = 0.0,
    val mrp: Double? = null,
    @Json(name = "stock_qty") val stockQty: Int? = 0,
    @Json(name = "is_available") val isAvailable: Boolean = true
)

// UI Model: Product resolved with fresh city price & stock override
data class ResolvedProduct(
    val baseProduct: Product,
    val effectivePrice: Double,
    val effectiveMrp: Double?,
    val effectiveStock: Int,
    val effectiveIsAvailable: Boolean
) {
    val id: String get() = baseProduct.id
    val name: String get() = baseProduct.name
    val description: String? get() = baseProduct.description
    val imageUrl: String? get() = baseProduct.imageUrl
    val unit: String? get() = baseProduct.unit
    val vendorId: String? get() = baseProduct.vendorId
    val categoryId: String? get() = baseProduct.categoryId
    val isFeatured: Boolean get() = baseProduct.isFeatured == true
}

@JsonClass(generateAdapter = true)
data class CartItem(
    val id: String? = null,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "variant_id") val variantId: String? = null,
    @Json(name = "vendor_id") val vendorId: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    val quantity: Int = 1
)

// UI Model for Cart item with freshly fetched product data
data class CartItemUi(
    val cartItem: CartItem,
    val product: ResolvedProduct
) {
    val totalPrice: Double get() = product.effectivePrice * cartItem.quantity
}

@JsonClass(generateAdapter = true)
data class CustomerAddress(
    val id: String? = null,
    @Json(name = "user_id") val userId: String = "",
    val label: String = "Home", // Home, Work, Other
    @Json(name = "recipient_name") val recipientName: String = "",
    val phone: String = "",
    @Json(name = "address_line") val addressLine: String = "",
    val landmark: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @Json(name = "is_default") val isDefault: Boolean = false
)

@JsonClass(generateAdapter = true)
data class DeliverySlot(
    val id: String = "",
    val name: String = "",
    @Json(name = "start_time") val startTime: String = "",
    @Json(name = "end_time") val endTime: String = "",
    @Json(name = "min_order_amount") val minOrderAmount: Double? = 0.0,
    @Json(name = "is_free_delivery") val isFreeDelivery: Boolean? = false,
    @Json(name = "delivery_fee") val deliveryFee: Double? = 0.0,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class Coupon(
    val id: String = "",
    val code: String = "",
    val description: String? = null,
    @Json(name = "discount_type") val discountType: String = "flat", // flat / percentage
    @Json(name = "discount_value") val discountValue: Double = 0.0,
    @Json(name = "min_order_amount") val minOrderAmount: Double? = 0.0,
    @Json(name = "max_discount_amount") val maxDiscountAmount: Double? = null,
    @Json(name = "usage_limit") val usageLimit: Int? = null,
    @Json(name = "used_count") val usedCount: Int? = 0,
    @Json(name = "starts_at") val startsAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class Order(
    val id: String? = null,
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "vendor_id") val vendorId: String? = null,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String? = null,
    @Json(name = "address_id") val addressId: String? = null,
    @Json(name = "slot_id") val slotId: String? = null,
    val status: String = "pending", // pending, confirmed, preparing, ready, out_for_delivery, delivered, cancelled, rejected
    @Json(name = "payment_method") val paymentMethod: String = "cod",
    @Json(name = "payment_status") val paymentStatus: String = "pending", // pending, paid, failed, refunded, cod
    val subtotal: Double = 0.0,
    @Json(name = "discount_amount") val discountAmount: Double = 0.0,
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    @Json(name = "handling_fee") val handlingFee: Double = 0.0,
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class OrderItem(
    val id: String? = null,
    @Json(name = "order_id") val orderId: String? = null,
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "product_name") val productName: String = "",
    @Json(name = "variant_label") val variantLabel: String? = null,
    val quantity: Int = 1,
    @Json(name = "unit_price") val unitPrice: Double = 0.0,
    @Json(name = "total_price") val totalPrice: Double = 0.0,
    @Json(name = "vendor_id") val vendorId: String? = null
)

@JsonClass(generateAdapter = true)
data class OrderStatusHistory(
    val id: String? = null,
    @Json(name = "order_id") val orderId: String = "",
    val status: String = "",
    val note: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryAssignment(
    @Json(name = "order_id") val orderId: String = "",
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String? = null,
    val status: String? = null,
    @Json(name = "estimated_delivery_minutes") val estimatedDeliveryMinutes: Int? = null,
    @Json(name = "estimated_delivery_at") val estimatedDeliveryAt: String? = null,
    @Json(name = "delivery_otp") val deliveryOtp: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryPartner(
    val id: String = "",
    val name: String = "",
    val phone: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "vehicle_type") val vehicleType: String? = null,
    @Json(name = "vehicle_number") val vehicleNumber: String? = null
)

@JsonClass(generateAdapter = true)
data class VendorReview(
    @Json(name = "vendor_id") val vendorId: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val rating: Int = 5,
    val comment: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryPartnerReview(
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val rating: Int = 5,
    val comment: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerWalletTransaction(
    val id: String? = null,
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val type: String = "credit", // 'credit' / 'debit'
    val amount: Double = 0.0,
    val reason: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeviceToken(
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "user_type") val userType: String = "customer",
    @Json(name = "fcm_token") val fcmToken: String = "",
    val active: Boolean = true
)

@JsonClass(generateAdapter = true)
data class Profile(
    val id: String = "",
    val role: String = "customer",
    @Json(name = "full_name") val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @Json(name = "city_id") val cityId: String? = null
)

// Supabase Auth models
@JsonClass(generateAdapter = true)
data class SupabaseAuthUser(
    val id: String = "",
    val email: String? = null,
    val phone: String? = null,
    @Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    val user: SupabaseAuthUser? = null
)
