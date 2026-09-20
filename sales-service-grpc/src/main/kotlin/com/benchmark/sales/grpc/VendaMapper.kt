package com.benchmark.sales.grpc

import com.google.protobuf.Timestamp
import com.benchmark.sales.domain.Venda as DomainVenda
import java.time.Instant

fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

fun DomainVenda.toProto(): Venda = Venda.newBuilder()
    .setIdVenda(idVenda.toString())
    .setIdProduto(idProduto)
    .setNomeProduto(nomeProduto)
    .setCategoria(categoria)
    .setQuantidade(quantidade)
    .setValorUnitario(valorUnitario.toDouble())
    .setValorTotal(valorTotal.toDouble())
    .setDataVenda(dataVenda.toProtoTimestamp())
    .setIdLoja(idLoja)
    .setNomeLoja(nomeLoja)
    .setRegiao(regiao)
    .setFormaPagamento(formaPagamento)
    .build()
