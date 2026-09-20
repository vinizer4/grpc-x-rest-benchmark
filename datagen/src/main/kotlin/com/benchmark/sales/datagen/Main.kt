package com.benchmark.sales.datagen

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureTimeMillis

data class Config(
    val dbHost: String = env("DB_HOST", "localhost"),
    val dbPort: String = env("DB_PORT", "5432"),
    val dbName: String = env("DB_NAME", "sales"),
    val dbUser: String = env("DB_USER", "sales"),
    val dbPassword: String = env("DB_PASSWORD", "sales"),
    val totalRegistros: Long = env("TOTAL_REGISTROS", "8000000").toLong(),
    val totalProdutos: Int = env("TOTAL_PRODUTOS", "500").toInt(),
    val totalLojas: Int = env("TOTAL_LOJAS", "50").toInt(),
    val seed: Long = env("SEED", "42").toLong(),
    val batchSize: Int = env("BATCH_SIZE", "5000").toInt(),
    val truncateAntes: Boolean = env("TRUNCATE_ANTES", "true").toBoolean(),
) {
    val jdbcUrl get() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

fun main() {
    val config = Config()
    println("Config: $config")

    val random = Random(config.seed)
    val produtos = gerarCatalogoProdutos(config.totalProdutos, random)
    val lojas = gerarCatalogoLojas(config.totalLojas, random)

    val produtoRankToIndex = (0 until config.totalProdutos).shuffled(random)
    val lojaRankToIndex = (0 until config.totalLojas).shuffled(random)

    val produtoSampler = ZipfSampler(config.totalProdutos, exponent = 0.4, random = random)
    val lojaSampler = ZipfSampler(config.totalLojas, exponent = 0.8, random = random)

    val dataFim = Instant.now()
    val dataInicio = dataFim.minus(365, ChronoUnit.DAYS)
    val periodoSegundos = ChronoUnit.SECONDS.between(dataInicio, dataFim)

    Class.forName("org.postgresql.Driver")
    DriverManager.getConnection(config.jdbcUrl, config.dbUser, config.dbPassword).use { connection ->
        connection.autoCommit = false

        if (config.truncateAntes) {
            connection.createStatement().use { it.execute("TRUNCATE TABLE vendas") }
            connection.commit()
            println("Tabela vendas truncada.")
        }

        val sql = """
            INSERT INTO vendas (
                id_venda, id_produto, nome_produto, categoria, quantidade,
                valor_unitario, valor_total, data_venda, id_loja, nome_loja,
                regiao, forma_pagamento
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        var inseridos = 0L
        val elapsed = measureTimeMillis {
            connection.prepareStatement(sql).use { statement ->
                while (inseridos < config.totalRegistros) {
                    val loteAtual = minOf(config.batchSize.toLong(), config.totalRegistros - inseridos)
                    repeat(loteAtual.toInt()) {
                        val produto = produtos[produtoRankToIndex[produtoSampler.sample()]]
                        val loja = lojas[lojaRankToIndex[lojaSampler.sample()]]
                        bindVenda(statement, random, produto, loja, dataInicio, periodoSegundos)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                    connection.commit()
                    inseridos += loteAtual
                    if (inseridos % 500_000L == 0L || inseridos == config.totalRegistros) {
                        println("Inseridos: $inseridos / ${config.totalRegistros}")
                    }
                }
            }
        }
        println("Concluido em ${elapsed}ms (${inseridos} registros).")
    }
}

private fun bindVenda(
    statement: java.sql.PreparedStatement,
    random: Random,
    produto: Produto,
    loja: Loja,
    dataInicio: Instant,
    periodoSegundos: Long,
) {
    val idVenda = UUID(random.nextLong(), random.nextLong())
    val quantidade = 1 + random.nextInt(5)
    val valorUnitario = round2(produto.precoBase * (0.8 + random.nextDouble() * 0.4))
    val valorTotal = round2(valorUnitario * quantidade)
    val dataVenda = dataInicio.plusSeconds(random.nextLong(periodoSegundos))
    val formaPagamento = FORMAS_PAGAMENTO[random.nextInt(FORMAS_PAGAMENTO.size)]

    var i = 1
    statement.setObject(i++, idVenda)
    statement.setLong(i++, produto.id)
    statement.setString(i++, produto.nome)
    statement.setString(i++, produto.categoria)
    statement.setInt(i++, quantidade)
    statement.setBigDecimal(i++, valorUnitario.toBigDecimal())
    statement.setBigDecimal(i++, valorTotal.toBigDecimal())
    statement.setTimestamp(i++, Timestamp.from(dataVenda))
    statement.setInt(i++, loja.id)
    statement.setString(i++, loja.nome)
    statement.setString(i++, loja.regiao)
    statement.setString(i++, formaPagamento)
}

private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
