package com.benchmark.sales.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.QueryHints
import java.time.Instant
import java.util.UUID
import jakarta.persistence.QueryHint

interface VendaRepository : JpaRepository<Venda, UUID> {

    @QueryHints(QueryHint(name = "org.hibernate.readOnly", value = "true"))
    fun findByIdProdutoAndDataVendaBetweenOrderByDataVendaAsc(
        idProduto: Long,
        dataInicio: Instant,
        dataFim: Instant,
    ): List<Venda>

    @QueryHints(QueryHint(name = "org.hibernate.readOnly", value = "true"))
    fun findByIdLojaAndDataVendaBetweenOrderByDataVendaAsc(
        idLoja: Int,
        dataInicio: Instant,
        dataFim: Instant,
    ): List<Venda>
}
