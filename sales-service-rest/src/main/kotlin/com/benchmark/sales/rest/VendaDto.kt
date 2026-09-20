package com.benchmark.sales.rest

import com.benchmark.sales.domain.Venda
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class VendaDto(
    val idVenda: UUID,
    val idProduto: Long,
    val nomeProduto: String,
    val categoria: String,
    val quantidade: Int,
    val valorUnitario: BigDecimal,
    val valorTotal: BigDecimal,
    val dataVenda: Instant,
    val idLoja: Int,
    val nomeLoja: String,
    val regiao: String,
    val formaPagamento: String,
) {
    companion object {
        fun from(venda: Venda) = VendaDto(
            idVenda = venda.idVenda,
            idProduto = venda.idProduto,
            nomeProduto = venda.nomeProduto,
            categoria = venda.categoria,
            quantidade = venda.quantidade,
            valorUnitario = venda.valorUnitario,
            valorTotal = venda.valorTotal,
            dataVenda = venda.dataVenda,
            idLoja = venda.idLoja,
            nomeLoja = venda.nomeLoja,
            regiao = venda.regiao,
            formaPagamento = venda.formaPagamento,
        )
    }
}

data class ConsultarVendasResponseDto(
    val vendas: List<VendaDto>,
    val totalRegistros: Long,
)
