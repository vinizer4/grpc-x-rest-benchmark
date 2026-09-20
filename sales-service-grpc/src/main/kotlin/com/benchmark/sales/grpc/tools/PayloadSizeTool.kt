package com.benchmark.sales.grpc.tools

import com.benchmark.sales.grpc.ConsultarVendasPorProdutoRequest
import com.benchmark.sales.grpc.SalesServiceGrpc
import com.benchmark.sales.grpc.toProtoTimestamp
import io.grpc.TlsChannelCredentials
import io.grpc.netty.NettyChannelBuilder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.CertificateFactory
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Compares the exact on-wire payload size for the same query, JSON (REST)
 * vs Protobuf (gRPC), using the real serializer on each side rather than an
 * approximation (e.g. grpcurl's JSON-rendered output, which is not the
 * actual wire format).
 */
fun main() {
    val grpcHost = env("GRPC_HOST", "localhost:9443")
    val restBaseUrl = env("REST_BASE_URL", "https://localhost:8443")
    val apiKey = env("API_KEY", "benchmark-poc-api-key")
    val produtoId = env("PRODUTO_ID", "311").toLong()
    val dataInicio = Instant.parse(env("DATA_INICIO", Instant.now().minusSeconds(380L * 86400).toString()))
    val dataFim = Instant.parse(env("DATA_FIM", Instant.now().toString()))
    val cacertPath = env("CACERT", "certs/ca/ca-cert.pem")

    val sslContext = trustingSslContext(File(cacertPath))

    val jsonBytes = fetchRestJsonBytes(restBaseUrl, apiKey, produtoId, dataInicio, dataFim, sslContext)
    val protoBytes = fetchGrpcProtoBytes(grpcHost, cacertPath, produtoId, dataInicio, dataFim)

    println("Produto: $produtoId | Periodo: $dataInicio -> $dataFim")
    println("REST (JSON) bytes:      $jsonBytes")
    println("gRPC (Protobuf) bytes:  $protoBytes")
    if (jsonBytes > 0) {
        val reducao = 100.0 * (1.0 - protoBytes.toDouble() / jsonBytes.toDouble())
        println("Reducao Protobuf vs JSON: %.1f%%".format(reducao))
    }
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

private fun fetchRestJsonBytes(
    baseUrl: String,
    apiKey: String,
    produtoId: Long,
    dataInicio: Instant,
    dataFim: Instant,
    sslContext: SSLContext,
): Int {
    val url = "$baseUrl/produtos/$produtoId/vendas?dataInicio=$dataInicio&dataFim=$dataFim"
    val client = HttpClient.newBuilder().sslContext(sslContext).build()
    val request = HttpRequest.newBuilder(URI.create(url))
        .header("X-Api-Key", apiKey)
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    check(response.statusCode() == 200) { "REST call failed with status ${response.statusCode()}" }
    return response.body().size
}

private fun fetchGrpcProtoBytes(
    hostAndPort: String,
    cacertPath: String,
    produtoId: Long,
    dataInicio: Instant,
    dataFim: Instant,
): Int {
    val (host, port) = hostAndPort.split(":").let { it[0] to it[1].toInt() }
    val credentials = TlsChannelCredentials.newBuilder()
        .trustManager(File(cacertPath))
        .build()
    val channel = NettyChannelBuilder.forAddress(host, port, credentials).build()
    try {
        val stub = SalesServiceGrpc.newBlockingStub(channel)
        val request = ConsultarVendasPorProdutoRequest.newBuilder()
            .setIdProduto(produtoId)
            .setDataInicio(dataInicio.toProtoTimestamp())
            .setDataFim(dataFim.toProtoTimestamp())
            .build()
        val response = stub.consultarVendasPorProduto(request)
        return response.serializedSize
    } finally {
        channel.shutdownNow()
    }
}

private fun trustingSslContext(caCertFile: File): SSLContext {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val caCert = caCertFile.inputStream().use { certificateFactory.generateCertificate(it) }

    val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("ca", caCert)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(keyStore)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustManagerFactory.trustManagers, null)
    return sslContext
}
