package com.benchmark.sales.grpc

import com.benchmark.sales.domain.VendaQueryService
import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService

@GrpcService
class VendaGrpcService(
    private val vendaQueryService: VendaQueryService,
) : SalesServiceGrpc.SalesServiceImplBase() {

    override fun consultarVendasPorProduto(
        request: ConsultarVendasPorProdutoRequest,
        responseObserver: StreamObserver<ConsultarVendasResponse>,
    ) {
        val vendas = vendaQueryService.consultarPorProduto(
            request.idProduto,
            request.dataInicio.toInstant(),
            request.dataFim.toInstant(),
        )
        respond(vendas, responseObserver)
    }

    override fun consultarVendasPorLoja(
        request: ConsultarVendasPorLojaRequest,
        responseObserver: StreamObserver<ConsultarVendasResponse>,
    ) {
        val vendas = vendaQueryService.consultarPorLoja(
            request.idLoja,
            request.dataInicio.toInstant(),
            request.dataFim.toInstant(),
        )
        respond(vendas, responseObserver)
    }

    private fun respond(
        vendas: List<com.benchmark.sales.domain.Venda>,
        responseObserver: StreamObserver<ConsultarVendasResponse>,
    ) {
        val response = ConsultarVendasResponse.newBuilder()
            .addAllVendas(vendas.map { it.toProto() })
            .setTotalRegistros(vendas.size.toLong())
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }
}
