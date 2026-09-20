package com.benchmark.sales.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "monthly_sales")
class MonthlySale(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    val id: UUID? = null,

    @Column(name = "store_id", nullable = false)
    val storeId: Int,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "product_name", nullable = false)
    val productName: String,

    @Column(name = "category", nullable = false)
    val category: String,

    @Column(name = "year_month", nullable = false)
    val yearMonth: LocalDate,

    @Column(name = "quantity_sold", nullable = false)
    val quantitySold: Int,

    @Column(name = "total_value", nullable = false)
    val totalValue: BigDecimal,

    @Column(name = "region", nullable = false)
    val region: String,

    @Column(name = "store_name", nullable = false)
    val storeName: String,
)
