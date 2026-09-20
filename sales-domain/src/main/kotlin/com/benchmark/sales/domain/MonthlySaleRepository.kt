package com.benchmark.sales.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.QueryHints
import java.time.LocalDate
import java.util.UUID
import jakarta.persistence.QueryHint

interface MonthlySaleRepository : JpaRepository<MonthlySale, UUID> {

    @QueryHints(QueryHint(name = "org.hibernate.readOnly", value = "true"))
    fun findByStoreIdAndYearMonthBetweenOrderByProductIdAscYearMonthAsc(
        storeId: Int,
        yearMonthStart: LocalDate,
        yearMonthEnd: LocalDate,
    ): List<MonthlySale>
}
