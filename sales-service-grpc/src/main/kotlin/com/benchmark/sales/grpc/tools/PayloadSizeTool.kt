package com.benchmark.sales.grpc.tools

import com.benchmark.sales.grpc.GetStoreAnnualHistoryRequest
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
import java.time.LocalDate
import java.time.ZoneOffset
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
    val storeId = env("STORE_ID", "1").toInt()
    val startDate = LocalDate.parse(env("START_DATE", LocalDate.now().minusMonths(12).toString()))
    val endDate = LocalDate.parse(env("END_DATE", LocalDate.now().toString()))
    val cacertPath = env("CACERT", "certs/ca/ca-cert.pem")

    val sslContext = trustingSslContext(File(cacertPath))

    val jsonBytes = fetchRestJsonBytes(restBaseUrl, apiKey, storeId, startDate, endDate, sslContext)
    val protoBytes = fetchGrpcProtoBytes(grpcHost, cacertPath, storeId, startDate, endDate)

    println("Store: $storeId | Period: $startDate -> $endDate")
    println("REST (JSON) bytes:      $jsonBytes")
    println("gRPC (Protobuf) bytes:  $protoBytes")
    if (jsonBytes > 0) {
        val reduction = 100.0 * (1.0 - protoBytes.toDouble() / jsonBytes.toDouble())
        println("Protobuf vs JSON reduction: %.1f%%".format(reduction))
    }
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

private fun fetchRestJsonBytes(
    baseUrl: String,
    apiKey: String,
    storeId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    sslContext: SSLContext,
): Int {
    val url = "$baseUrl/stores/$storeId/annual-history?startDate=$startDate&endDate=$endDate"
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
    storeId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
): Int {
    val (host, port) = hostAndPort.split(":").let { it[0] to it[1].toInt() }
    val credentials = TlsChannelCredentials.newBuilder()
        .trustManager(File(cacertPath))
        .build()
    val channel = NettyChannelBuilder.forAddress(host, port, credentials)
        .maxInboundMessageSize(20 * 1024 * 1024)
        .build()
    try {
        val stub = SalesServiceGrpc.newBlockingStub(channel)
        val request = GetStoreAnnualHistoryRequest.newBuilder()
            .setStoreId(storeId)
            .setStartDate(startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toProtoTimestamp())
            .setEndDate(endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toProtoTimestamp())
            .build()
        val response = stub.getStoreAnnualHistory(request)
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
