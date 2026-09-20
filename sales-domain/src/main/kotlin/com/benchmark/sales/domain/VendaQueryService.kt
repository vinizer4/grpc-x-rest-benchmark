package com.benchmark.sales.domain

import org.springframework.stereotype.Service
import java.time.Instant

@Service
class VendaQueryService(private val vendaRepository: VendaRepository) {

    fun consultarPorProduto(idProduto: Long, dataInicio: Instant, dataFim: Instant): List<Venda> =
        vendaRepository.findByIdProdutoAndDataVendaBetweenOrderByDataVendaAsc(idProduto, dataInicio, dataFim)

    fun consultarPorLoja(idLoja: Int, dataInicio: Instant, dataFim: Instant): List<Venda> =
        vendaRepository.findByIdLojaAndDataVendaBetweenOrderByDataVendaAsc(idLoja, dataInicio, dataFim)
}
