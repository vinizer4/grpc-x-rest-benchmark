package com.benchmark.sales.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vendas")
class Venda(
    @Id
    @Column(name = "id_venda")
    val idVenda: UUID,

    @Column(name = "id_produto", nullable = false)
    val idProduto: Long,

    @Column(name = "nome_produto", nullable = false)
    val nomeProduto: String,

    @Column(name = "categoria", nullable = false)
    val categoria: String,

    @Column(name = "quantidade", nullable = false)
    val quantidade: Int,

    @Column(name = "valor_unitario", nullable = false)
    val valorUnitario: BigDecimal,

    @Column(name = "valor_total", nullable = false)
    val valorTotal: BigDecimal,

    @Column(name = "data_venda", nullable = false)
    val dataVenda: Instant,

    @Column(name = "id_loja", nullable = false)
    val idLoja: Int,

    @Column(name = "nome_loja", nullable = false)
    val nomeLoja: String,

    @Column(name = "regiao", nullable = false)
    val regiao: String,

    @Column(name = "forma_pagamento", nullable = false)
    val formaPagamento: String,
)
