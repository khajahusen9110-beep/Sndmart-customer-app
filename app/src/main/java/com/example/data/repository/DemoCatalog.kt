package com.example.data.repository

import com.example.data.model.*

object DemoCatalog {

    val CATEGORIES = listOf(
        Category(
            id = "cat_veg",
            name = "Vegetables",
            nameKn = "ತರಕಾರಿಗಳು",
            slug = "vegetables",
            vendorType = "vegetable",
            isActive = true,
            sortOrder = 1,
            imageUrl = "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=400&q=80"
        ),
        Category(
            id = "cat_fruits",
            name = "Fresh Fruits",
            nameKn = "ಹಣ್ಣುಗಳು",
            slug = "fruits",
            vendorType = "fruit",
            isActive = true,
            sortOrder = 2,
            imageUrl = "https://images.unsplash.com/photo-1619566636858-adf3ef46400b?w=400&q=80"
        ),
        Category(
            id = "cat_dairy",
            name = "Dairy & Breakfast",
            nameKn = "ಹಾಲು ಮತ್ತು ಉಪಾಹಾರ",
            slug = "dairy",
            vendorType = "grocery",
            isActive = true,
            sortOrder = 3,
            imageUrl = "https://images.unsplash.com/photo-1550583724-b2692b85b150?w=400&q=80"
        ),
        Category(
            id = "cat_staples",
            name = "Atta, Rice & Dal",
            nameKn = "ಅಕ್ಕಿ ಮತ್ತು ಬೇಳೆಕಾಳು",
            slug = "staples",
            vendorType = "grocery",
            isActive = true,
            sortOrder = 4,
            imageUrl = "https://images.unsplash.com/photo-1586201375761-83865001e31c?w=400&q=80"
        ),
        Category(
            id = "cat_snacks",
            name = "Snacks & Munchies",
            nameKn = "ತಿಂಡಿಗಳು",
            slug = "snacks",
            vendorType = "grocery",
            isActive = true,
            sortOrder = 5,
            imageUrl = "https://images.unsplash.com/photo-1566478989037-eec170784d0b?w=400&q=80"
        ),
        Category(
            id = "cat_drinks",
            name = "Cold Drinks & Juices",
            nameKn = "ಪಾನೀಯಗಳು",
            slug = "beverages",
            vendorType = "grocery",
            isActive = true,
            sortOrder = 6,
            imageUrl = "https://images.unsplash.com/photo-1622483767028-3f66f32aef97?w=400&q=80"
        )
    )

    val GROCERY_PRODUCTS = listOf(
        Product(
            id = "p_tomato",
            categoryId = "cat_veg",
            name = "Farm Fresh Tomatoes",
            description = "Locally grown juicy hybrid tomatoes, perfect for curries and salads.",
            price = 32.0,
            mrp = 45.0,
            unit = "1 kg",
            stockQty = 60,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1546470427-e26264be0b11?w=500&q=80"
        ),
        Product(
            id = "p_banana",
            categoryId = "cat_fruits",
            name = "Robusta Bananas",
            description = "Freshly ripened premium quality sweet bananas.",
            price = 45.0,
            mrp = 60.0,
            unit = "1 kg (approx 6-8 pcs)",
            stockQty = 45,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1571771894821-ce9b6c11b08e?w=500&q=80"
        ),
        Product(
            id = "p_nandini_milk",
            categoryId = "cat_dairy",
            name = "Nandini Toned Fresh Milk",
            description = "Pasteurized, wholesome fresh cow milk packet.",
            price = 44.0,
            mrp = 44.0,
            unit = "1 Litre",
            stockQty = 80,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1550583724-b2692b85b150?w=500&q=80"
        ),
        Product(
            id = "p_sona_rice",
            categoryId = "cat_staples",
            name = "Sindhanur Sona Masoori Raw Rice",
            description = "World famous authentic aged Sindhanur Sona Masoori rice.",
            price = 540.0,
            mrp = 620.0,
            unit = "10 kg Bag",
            stockQty = 30,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1586201375761-83865001e31c?w=500&q=80"
        ),
        Product(
            id = "p_onion",
            categoryId = "cat_veg",
            name = "Fresh Red Onions",
            description = "Crisp, pungent Grade-A dry red onions.",
            price = 28.0,
            mrp = 40.0,
            unit = "1 kg",
            stockQty = 90,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1618512496248-a07fe83aa8cb?w=500&q=80"
        ),
        Product(
            id = "p_potato",
            categoryId = "cat_veg",
            name = "Fresh Jyoti Potatoes",
            description = "Evenly shaped, clean soil-free potatoes.",
            price = 35.0,
            mrp = 45.0,
            unit = "1 kg",
            stockQty = 75,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1518977676601-b53f82aba655?w=500&q=80"
        ),
        Product(
            id = "p_paneer",
            categoryId = "cat_dairy",
            name = "Fresh Malai Paneer",
            description = "Soft, creamy and protein-rich dairy fresh cottage cheese.",
            price = 85.0,
            mrp = 95.0,
            unit = "200 gm",
            stockQty = 25,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1631452180519-c014fe946bc7?w=500&q=80"
        ),
        Product(
            id = "p_ghee",
            categoryId = "cat_dairy",
            name = "Amul Pure Cow Ghee",
            description = "Aromatic granular pure cow ghee for authentic taste.",
            price = 315.0,
            mrp = 340.0,
            unit = "500 ml Jar",
            stockQty = 20,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1608797178974-15b35a61dd75?w=500&q=80"
        ),
        Product(
            id = "p_atta",
            categoryId = "cat_staples",
            name = "Aashirvaad Sharbati Whole Wheat Atta",
            description = "100% whole wheat grain ground flour for soft rotis.",
            price = 265.0,
            mrp = 310.0,
            unit = "5 kg",
            stockQty = 40,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=500&q=80"
        ),
        Product(
            id = "p_mango",
            categoryId = "cat_fruits",
            name = "Carb-free Fresh Alphonso Mangoes",
            description = "Naturally ripened sweet aromatic Alphonso mangoes.",
            price = 180.0,
            mrp = 240.0,
            unit = "1 kg (approx 3-4 pcs)",
            stockQty = 35,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1553279768-865429fa0078?w=500&q=80"
        )
    )

    val HOTELS = listOf(
        Vendor(
            id = "vendor_royal_palace",
            name = "Hotel Royal Palace",
            vendorType = "hotel",
            address = "Kushtagi Road, Near Bus Stand, Sindhanur",
            cityId = "city_sindhanur",
            isOpen = true,
            isActive = true,
            isFeatured = true,
            openingTime = "11:00 AM",
            closingTime = "11:00 PM",
            bannerUrl = "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=600&q=80"
        ),
        Vendor(
            id = "vendor_krishna_bhavan",
            name = "Sri Krishna Bhavan Pure Veg",
            vendorType = "hotel",
            address = "Gandhi Chowk, Sindhanur",
            cityId = "city_sindhanur",
            isOpen = true,
            isActive = true,
            isFeatured = true,
            openingTime = "06:30 AM",
            closingTime = "10:00 PM",
            bannerUrl = "https://images.unsplash.com/photo-1552566626-52f8b828add9?w=600&q=80"
        ),
        Vendor(
            id = "vendor_green_garden",
            name = "Green Garden Family Restaurant",
            vendorType = "hotel",
            address = "Gangavathi Road, Sindhanur",
            cityId = "city_sindhanur",
            isOpen = true,
            isActive = true,
            isFeatured = false,
            openingTime = "12:00 PM",
            closingTime = "11:30 PM",
            bannerUrl = "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=600&q=80"
        ),
        Vendor(
            id = "vendor_udupi_grand",
            name = "New Udupi Grand",
            vendorType = "hotel",
            address = "Main Market Road, Sindhanur",
            cityId = "city_sindhanur",
            isOpen = true,
            isActive = true,
            isFeatured = false,
            openingTime = "07:00 AM",
            closingTime = "10:30 PM",
            bannerUrl = "https://images.unsplash.com/photo-1414235077428-338989a2e8c0?w=600&q=80"
        )
    )

    val HOTEL_CATEGORIES = listOf(
        Category(id = "hcat_biryani", name = "Biryani & Rice", vendorType = "hotel", vendorId = "vendor_royal_palace", isActive = true),
        Category(id = "hcat_curries", name = "Gravies & Curries", vendorType = "hotel", vendorId = "vendor_royal_palace", isActive = true),
        Category(id = "hcat_tiffin", name = "South Indian Tiffins", vendorType = "hotel", vendorId = "vendor_krishna_bhavan", isActive = true),
        Category(id = "hcat_meals", name = "Thali & Meals", vendorType = "hotel", vendorId = "vendor_krishna_bhavan", isActive = true),
        Category(id = "hcat_tandoor", name = "Tandoor & Starters", vendorType = "hotel", vendorId = "vendor_green_garden", isActive = true)
    )

    val HOTEL_PRODUCTS = listOf(
        Product(
            id = "hp_royal_biryani",
            vendorId = "vendor_royal_palace",
            categoryId = "hcat_biryani",
            name = "Royal Special Dum Biryani",
            description = "Aromatic basmati rice layered with rich spices and served with mirchi ka salan & raita.",
            price = 240.0,
            mrp = 260.0,
            unit = "1 Portion",
            stockQty = 25,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=500&q=80"
        ),
        Product(
            id = "hp_paneer_butter",
            vendorId = "vendor_royal_palace",
            categoryId = "hcat_curries",
            name = "Paneer Butter Masala",
            description = "Fresh cottage cheese simmered in a rich tomato, butter, and cashew gravy.",
            price = 180.0,
            mrp = 200.0,
            unit = "Full Gravy",
            stockQty = 30,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1631452180519-c014fe946bc7?w=500&q=80"
        ),
        Product(
            id = "hp_butter_naan",
            vendorId = "vendor_royal_palace",
            categoryId = "hcat_curries",
            name = "Butter Naan",
            description = "Clay oven baked fluffy bread glazed with pure dairy butter.",
            price = 35.0,
            mrp = 40.0,
            unit = "1 pc",
            stockQty = 50,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500&q=80"
        ),
        Product(
            id = "hp_masala_dosa",
            vendorId = "vendor_krishna_bhavan",
            categoryId = "hcat_tiffin",
            name = "Crispy Mysore Masala Dosa",
            description = "Crispy golden crepe with spicy red chutney smear and spiced potato filling.",
            price = 65.0,
            mrp = 75.0,
            unit = "1 Plate (with 2 Chutneys & Sambar)",
            stockQty = 40,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1668236543090-82eba5ee5976?w=500&q=80"
        ),
        Product(
            id = "hp_filter_coffee",
            vendorId = "vendor_krishna_bhavan",
            categoryId = "hcat_tiffin",
            name = "South Indian Filter Coffee",
            description = "Freshly brewed degree chicory blend with frothy whole milk.",
            price = 25.0,
            mrp = 30.0,
            unit = "1 Cup",
            stockQty = 60,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1514432324607-a09d9b4aefdd?w=500&q=80"
        ),
        Product(
            id = "hp_south_meals",
            vendorId = "vendor_krishna_bhavan",
            categoryId = "hcat_meals",
            name = "Special South Indian Veg Meals",
            description = "Sona Masoori Rice, Sambar, Rasam, Palya, Curd, Papad and Sweet.",
            price = 130.0,
            mrp = 150.0,
            unit = "1 Thali",
            stockQty = 35,
            isAvailable = true,
            isActive = true,
            isFeatured = true,
            imageUrl = "https://images.unsplash.com/photo-1610057099443-fde8c4d50f91?w=500&q=80"
        ),
        Product(
            id = "hp_tandoori_roti",
            vendorId = "vendor_green_garden",
            categoryId = "hcat_tandoor",
            name = "Tandoori Roti with Butter",
            description = "Whole wheat tandoori roti with generous butter.",
            price = 25.0,
            mrp = 30.0,
            unit = "1 pc",
            stockQty = 40,
            isAvailable = true,
            isActive = true,
            isFeatured = false,
            imageUrl = "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=500&q=80"
        )
    )

    val DELIVERY_SLOTS = listOf(
        DeliverySlot(
            id = "slot_morning",
            name = "Morning Express",
            startTime = "07:00 AM",
            endTime = "09:00 AM",
            minOrderAmount = 0.0,
            isFreeDelivery = false,
            deliveryFee = 25.0,
            isActive = true
        ),
        DeliverySlot(
            id = "slot_midday",
            name = "Mid-Day Slot",
            startTime = "11:00 AM",
            endTime = "01:00 PM",
            minOrderAmount = 0.0,
            isFreeDelivery = false,
            deliveryFee = 25.0,
            isActive = true
        ),
        DeliverySlot(
            id = "slot_evening",
            name = "Evening Express",
            startTime = "05:00 PM",
            endTime = "07:00 PM",
            minOrderAmount = 0.0,
            isFreeDelivery = true,
            deliveryFee = 0.0,
            isActive = true
        ),
        DeliverySlot(
            id = "slot_night",
            name = "Dinner / Night Slot",
            startTime = "08:00 PM",
            endTime = "10:00 PM",
            minOrderAmount = 0.0,
            isFreeDelivery = false,
            deliveryFee = 30.0,
            isActive = true
        )
    )

    val COUPONS = listOf(
        Coupon(
            id = "c_sndmart50",
            code = "SNDMART50",
            description = "Flat ₹50 OFF on orders above ₹299",
            discountType = "flat",
            discountValue = 50.0,
            minOrderAmount = 299.0,
            isActive = true
        ),
        Coupon(
            id = "c_freedel",
            code = "FREEDEL",
            description = "Free Delivery on orders above ₹199",
            discountType = "free_delivery",
            discountValue = 25.0,
            minOrderAmount = 199.0,
            isActive = true
        )
    )

    val WALLET_BALANCE = 150.0

    val WALLET_TRANSACTIONS = listOf(
        CustomerWalletTransaction(
            id = "tx_1",
            customerId = "guest",
            amount = 150.0,
            type = "credit",
            reason = "Sndmart Welcome & Referral Bonus",
            createdAt = "Today, 10:30 AM"
        )
    )

    val SAMPLE_ADDRESS = CustomerAddress(
        id = "addr_demo",
        userId = "guest",
        label = "Home",
        recipientName = "Customer",
        phone = "+91 98765 43210",
        addressLine = "#42, Vidya Nagar, Kushtagi Road, Sindhanur",
        landmark = "Near Old Bus Stand",
        isDefault = true
    )
}
