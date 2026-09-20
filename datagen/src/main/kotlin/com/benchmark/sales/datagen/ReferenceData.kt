package com.benchmark.sales.datagen

import kotlin.random.Random

data class Produto(val id: Long, val nome: String, val categoria: String, val precoBase: Double)
data class Loja(val id: Int, val nome: String, val regiao: String)

val CATEGORIAS = listOf(
    "Eletronicos",
    "Alimentos",
    "Vestuario",
    "Casa e Decoracao",
    "Esporte e Lazer",
    "Beleza e Cuidados Pessoais",
    "Livros e Papelaria",
    "Brinquedos",
)

val REGIOES = listOf("Sudeste", "Sul", "Nordeste", "Centro-Oeste", "Norte")

val FORMAS_PAGAMENTO = listOf("CARTAO_CREDITO", "CARTAO_DEBITO", "PIX", "DINHEIRO", "BOLETO")

private val CIDADES = listOf(
    "Sao Paulo", "Rio de Janeiro", "Belo Horizonte", "Curitiba", "Porto Alegre",
    "Salvador", "Recife", "Fortaleza", "Brasilia", "Goiania",
    "Manaus", "Belem", "Florianopolis", "Campinas", "Santos",
    "Vitoria", "Natal", "Joao Pessoa", "Maceio", "Cuiaba",
)

fun gerarCatalogoProdutos(quantidade: Int, random: Random): List<Produto> =
    (1..quantidade).map { id ->
        val categoria = CATEGORIAS[random.nextInt(CATEGORIAS.size)]
        val precoBase = 10.0 + random.nextDouble() * 2_000.0
        Produto(
            id = id.toLong(),
            nome = "$categoria ${randomProductSuffix(random)} #$id",
            categoria = categoria,
            precoBase = precoBase,
        )
    }

fun gerarCatalogoLojas(quantidade: Int, random: Random): List<Loja> =
    (1..quantidade).map { id ->
        val cidade = CIDADES[random.nextInt(CIDADES.size)]
        Loja(
            id = id,
            nome = "Loja $cidade #$id",
            regiao = REGIOES[random.nextInt(REGIOES.size)],
        )
    }

private val PRODUCT_SUFFIXES = listOf(
    "Premium", "Basico", "Pro", "Plus", "Standard", "Compacto", "Deluxe", "Lite",
)

private fun randomProductSuffix(random: Random): String = PRODUCT_SUFFIXES[random.nextInt(PRODUCT_SUFFIXES.size)]

/**
 * Pareto-like (Zipf) weighting: rank 1 is the most popular, weight decays as 1/rank^exponent.
 */
class ZipfSampler(size: Int, exponent: Double, random: Random) {
    private val random = random
    private val cumulativeWeights: DoubleArray

    init {
        val weights = DoubleArray(size) { i -> 1.0 / Math.pow((i + 1).toDouble(), exponent) }
        val total = weights.sum()
        var acc = 0.0
        cumulativeWeights = DoubleArray(size)
        for (i in weights.indices) {
            acc += weights[i] / total
            cumulativeWeights[i] = acc
        }
    }

    /** Returns a zero-based index sampled according to the Zipf distribution. */
    fun sample(): Int {
        val r = random.nextDouble()
        var lo = 0
        var hi = cumulativeWeights.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulativeWeights[mid] < r) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
