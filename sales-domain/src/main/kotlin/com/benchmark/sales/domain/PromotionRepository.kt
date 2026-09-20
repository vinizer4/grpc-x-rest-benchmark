package com.benchmark.sales.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.QueryHints
import java.time.LocalDate
import java.util.UUID
import jakarta.persistence.QueryHint

interface PromotionRepository : JpaRepository<Promotion, UUID> {

    @QueryHints(QueryHint(name = "org.hibernate.readOnly", value = "true"))
    fun findByStoreIdAndYearMonthBetweenOrderByYearMonthAsc(
        storeId: Int,
        yearMonthStart: LocalDate,
        yearMonthEnd: LocalDate,
    ): List<Promotion>
}
