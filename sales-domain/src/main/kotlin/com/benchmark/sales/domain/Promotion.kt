package com.benchmark.sales.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "promotions")
class Promotion(
    @Id
    @Column(name = "promotion_id")
    val promotionId: UUID,

    @Column(name = "store_id", nullable = false)
    val storeId: Int,

    @Column(name = "year_month", nullable = false)
    val yearMonth: LocalDate,

    @Column(name = "promotion_name", nullable = false)
    val promotionName: String,

    @Column(name = "discount_percentage", nullable = false)
    val discountPercentage: BigDecimal,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    val endDate: LocalDate,
)
