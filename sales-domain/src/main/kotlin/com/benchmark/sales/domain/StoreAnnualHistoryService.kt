package com.benchmark.sales.domain

import org.springframework.stereotype.Service
import java.time.LocalDate

data class StoreAnnualHistory(
    val sales: List<MonthlySale>,
    val promotions: List<Promotion>,
)

@Service
class StoreAnnualHistoryService(
    private val monthlySaleRepository: MonthlySaleRepository,
    private val promotionRepository: PromotionRepository,
) {

    fun get(storeId: Int, yearMonthStart: LocalDate, yearMonthEnd: LocalDate): StoreAnnualHistory {
        val sales = monthlySaleRepository.findByStoreIdAndYearMonthBetweenOrderByProductIdAscYearMonthAsc(
            storeId, yearMonthStart, yearMonthEnd,
        )
        val promotions = promotionRepository.findByStoreIdAndYearMonthBetweenOrderByYearMonthAsc(
            storeId, yearMonthStart, yearMonthEnd,
        )
        return StoreAnnualHistory(sales, promotions)
    }
}
