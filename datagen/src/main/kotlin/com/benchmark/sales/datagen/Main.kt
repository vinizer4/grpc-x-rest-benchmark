package com.benchmark.sales.datagen

import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureTimeMillis

data class Config(
    val dbHost: String = env("DB_HOST", "localhost"),
    val dbPort: String = env("DB_PORT", "5432"),
    val dbName: String = env("DB_NAME", "sales"),
    val dbUser: String = env("DB_USER", "sales"),
    val dbPassword: String = env("DB_PASSWORD", "sales"),
    val storeCount: Int = env("STORE_COUNT", "50").toInt(),
    val totalProducts: Int = env("TOTAL_PRODUCTS", "5000").toInt(),
    val months: Int = env("MONTHS", "12").toInt(),
    val promotionsPerMonth: Int = env("PROMOTIONS_PER_MONTH", "5").toInt(),
    val seed: Long = env("SEED", "42").toLong(),
    val batchSize: Int = env("BATCH_SIZE", "5000").toInt(),
    val truncateBefore: Boolean = env("TRUNCATE_BEFORE", "true").toBoolean(),
) {
    val jdbcUrl get() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

fun main() {
    val config = Config()
    println("Config: $config")

    val random = Random(config.seed)
    val stores = generateStores(config.storeCount, random)
    val products = generateProductCatalog(config.totalProducts, random)
    val yearMonths = lastNMonths(config.months)

    Class.forName("org.postgresql.Driver")
    DriverManager.getConnection(config.jdbcUrl, config.dbUser, config.dbPassword).use { connection ->
        connection.autoCommit = false

        if (config.truncateBefore) {
            connection.createStatement().use {
                it.execute("TRUNCATE TABLE monthly_sales")
                it.execute("TRUNCATE TABLE promotions")
            }
            connection.commit()
            println("Tables monthly_sales and promotions truncated.")
        }

        val elapsed = measureTimeMillis {
            insertMonthlySales(connection, config, stores, products, yearMonths, random)
            insertPromotions(connection, config, stores, yearMonths, random)
        }
        val totalRows = stores.size.toLong() * products.size * yearMonths.size +
            stores.size.toLong() * yearMonths.size * config.promotionsPerMonth
        println("Done in ${elapsed}ms ($totalRows rows across ${stores.size} stores).")
    }
}

private fun lastNMonths(count: Int): List<LocalDate> {
    val currentMonth = LocalDate.now().withDayOfMonth(1)
    return (count - 1 downTo 0).map { currentMonth.minusMonths(it.toLong()) }
}

private fun insertMonthlySales(
    connection: Connection,
    config: Config,
    stores: List<Store>,
    products: List<Product>,
    yearMonths: List<LocalDate>,
    random: Random,
) {
    val sql = """
        INSERT INTO monthly_sales (
            id, store_id, product_id, product_name, category, year_month,
            quantity_sold, total_value, region, store_name
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

    connection.prepareStatement(sql).use { statement ->
        var pending = 0
        var inserted = 0L
        for (store in stores) {
            val storeVolumeFactor = 0.6 + random.nextDouble() * 0.8
            for (product in products) {
                for (yearMonth in yearMonths) {
                    val monthlyNoise = 0.7 + random.nextDouble() * 0.6
                    val quantitySold = maxOf(1, Math.round(product.monthlyAvgQuantity * storeVolumeFactor * monthlyNoise).toInt())
                    val unitPrice = round2(product.basePrice * (0.9 + random.nextDouble() * 0.2))
                    val totalValue = round2(unitPrice * quantitySold)

                    var i = 1
                    statement.setObject(i++, UUID(random.nextLong(), random.nextLong()))
                    statement.setInt(i++, store.id)
                    statement.setLong(i++, product.id)
                    statement.setString(i++, product.name)
                    statement.setString(i++, product.category)
                    statement.setDate(i++, Date.valueOf(yearMonth))
                    statement.setInt(i++, quantitySold)
                    statement.setBigDecimal(i++, totalValue.toBigDecimal())
                    statement.setString(i++, store.region)
                    statement.setString(i++, store.name)
                    statement.addBatch()
                    pending++

                    if (pending >= config.batchSize) {
                        statement.executeBatch()
                        connection.commit()
                        inserted += pending
                        pending = 0
                        println("monthly_sales inserted: $inserted")
                    }
                }
            }
        }
        if (pending > 0) {
            statement.executeBatch()
            connection.commit()
            inserted += pending
            println("monthly_sales inserted: $inserted")
        }
    }
}

private fun insertPromotions(
    connection: Connection,
    config: Config,
    stores: List<Store>,
    yearMonths: List<LocalDate>,
    random: Random,
) {
    val sql = """
        INSERT INTO promotions (
            promotion_id, store_id, year_month, promotion_name,
            discount_percentage, start_date, end_date
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

    connection.prepareStatement(sql).use { statement ->
        var pending = 0
        for (store in stores) {
            for (yearMonth in yearMonths) {
                val daysInMonth = yearMonth.lengthOfMonth()
                repeat(config.promotionsPerMonth) {
                    val startDay = 1 + random.nextInt(daysInMonth - 2)
                    val endDay = minOf(daysInMonth, startDay + 1 + random.nextInt(5))
                    val discountPercentage = round2(5.0 + random.nextDouble() * 40.0)

                    var i = 1
                    statement.setObject(i++, UUID(random.nextLong(), random.nextLong()))
                    statement.setInt(i++, store.id)
                    statement.setDate(i++, Date.valueOf(yearMonth))
                    statement.setString(i++, randomPromotionName(random))
                    statement.setBigDecimal(i++, discountPercentage.toBigDecimal())
                    statement.setDate(i++, Date.valueOf(yearMonth.withDayOfMonth(startDay)))
                    statement.setDate(i++, Date.valueOf(yearMonth.withDayOfMonth(endDay)))
                    statement.addBatch()
                    pending++
                }
            }
        }
        statement.executeBatch()
        connection.commit()
        println("promotions inserted: $pending")
    }
}

private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
