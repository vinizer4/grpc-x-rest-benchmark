package com.benchmark.sales.msgpack

import com.benchmark.sales.domain.StoreAnnualHistoryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class StoreAnnualHistoryController(private val storeAnnualHistoryService: StoreAnnualHistoryService) {

    @GetMapping("/stores/{storeId}/annual-history", produces = ["application/x-msgpack"])
    fun getAnnualHistory(
        @PathVariable storeId: Int,
        @RequestParam startDate: LocalDate,
        @RequestParam endDate: LocalDate,
    ): StoreAnnualHistoryResponseDto {
        val history = storeAnnualHistoryService.get(storeId, startDate, endDate)
        return StoreAnnualHistoryResponseDto(
            sales = history.sales.map(MonthlySaleDto::from),
            promotions = history.promotions.map(PromotionDto::from),
            totalSalesRecords = history.sales.size.toLong(),
            totalPromotionRecords = history.promotions.size.toLong(),
        )
    }
}
