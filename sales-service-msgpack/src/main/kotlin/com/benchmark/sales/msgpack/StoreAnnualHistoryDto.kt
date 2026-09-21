package com.benchmark.sales.msgpack

import com.benchmark.sales.domain.MonthlySale
import com.benchmark.sales.domain.Promotion
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class MonthlySaleDto(
    val storeId: Int,
    val productId: Long,
    val productName: String,
    val category: String,
    val yearMonth: String,
    val quantitySold: Int,
    val totalValue: BigDecimal,
    val region: String,
    val storeName: String,
) {
    companion object {
        fun from(sale: MonthlySale) = MonthlySaleDto(
            storeId = sale.storeId,
            productId = sale.productId,
            productName = sale.productName,
            category = sale.category,
            yearMonth = sale.yearMonth.toString(),
            quantitySold = sale.quantitySold,
            totalValue = sale.totalValue,
            region = sale.region,
            storeName = sale.storeName,
        )
    }
}

data class PromotionDto(
    val promotionId: String,
    val storeId: Int,
    val yearMonth: String,
    val promotionName: String,
    val discountPercentage: BigDecimal,
    val startDate: String,
    val endDate: String,
) {
    companion object {
        fun from(promotion: Promotion) = PromotionDto(
            promotionId = promotion.promotionId.toString(),
            storeId = promotion.storeId,
            yearMonth = promotion.yearMonth.toString(),
            promotionName = promotion.promotionName,
            discountPercentage = promotion.discountPercentage,
            startDate = promotion.startDate.toString(),
            endDate = promotion.endDate.toString(),
        )
    }
}

data class StoreAnnualHistoryResponseDto(
    val sales: List<MonthlySaleDto>,
    val promotions: List<PromotionDto>,
    val totalSalesRecords: Long,
    val totalPromotionRecords: Long,
)
