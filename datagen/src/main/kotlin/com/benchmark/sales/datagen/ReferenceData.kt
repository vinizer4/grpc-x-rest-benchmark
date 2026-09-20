package com.benchmark.sales.datagen

import kotlin.random.Random

data class Product(val id: Long, val name: String, val category: String, val basePrice: Double, val monthlyAvgQuantity: Double)
data class Store(val id: Int, val name: String, val region: String)

val CATEGORIES = listOf(
    "Electronics",
    "Groceries",
    "Apparel",
    "Home and Decor",
    "Sports and Leisure",
    "Beauty and Personal Care",
    "Books and Stationery",
    "Toys",
)

val REGIONS = listOf("Southeast", "South", "Northeast", "Midwest", "North")

private val CITIES = listOf(
    "Sao Paulo", "Rio de Janeiro", "Belo Horizonte", "Curitiba", "Porto Alegre",
    "Salvador", "Recife", "Fortaleza", "Brasilia", "Goiania",
)

private val PRODUCT_SUFFIXES = listOf(
    "Premium", "Basic", "Pro", "Plus", "Standard", "Compact", "Deluxe", "Lite",
)

private val PROMOTION_NAMES = listOf(
    "Flash Sale", "Seasonal Clearance", "Buy One Get One", "Loyalty Discount",
    "End of Month Special", "Category Blowout", "Weekend Deal", "New Arrivals Discount",
)

fun generateProductCatalog(quantity: Int, random: Random): List<Product> {
    val rankOrder = (0 until quantity).shuffled(random)
    val zipfExponent = 0.4
    val maxMonthlyQuantity = 500.0
    return (1..quantity).map { id ->
        val category = CATEGORIES[random.nextInt(CATEGORIES.size)]
        val basePrice = 10.0 + random.nextDouble() * 2_000.0
        val rank = rankOrder[id - 1] + 1
        val monthlyAvgQuantity = maxMonthlyQuantity / Math.pow(rank.toDouble(), zipfExponent)
        Product(
            id = id.toLong(),
            name = "$category ${randomProductSuffix(random)} #$id",
            category = category,
            basePrice = basePrice,
            monthlyAvgQuantity = monthlyAvgQuantity,
        )
    }
}

fun generateStore(storeId: Int, random: Random): Store {
    val city = CITIES[random.nextInt(CITIES.size)]
    return Store(
        id = storeId,
        name = "Store $city #$storeId",
        region = REGIONS[random.nextInt(REGIONS.size)],
    )
}

fun generateStores(count: Int, random: Random): List<Store> =
    (1..count).map { generateStore(it, random) }

fun randomPromotionName(random: Random): String = PROMOTION_NAMES[random.nextInt(PROMOTION_NAMES.size)]

private fun randomProductSuffix(random: Random): String = PRODUCT_SUFFIXES[random.nextInt(PRODUCT_SUFFIXES.size)]
