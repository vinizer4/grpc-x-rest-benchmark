package com.benchmark.sales.grpc

import com.benchmark.sales.domain.StoreAnnualHistoryService
import io.grpc.stub.StreamObserver
import org.springframework.grpc.server.service.GrpcService

@GrpcService
class StoreAnnualHistoryGrpcService(
    private val storeAnnualHistoryService: StoreAnnualHistoryService,
) : SalesServiceGrpc.SalesServiceImplBase() {

    override fun getStoreAnnualHistory(
        request: GetStoreAnnualHistoryRequest,
        responseObserver: StreamObserver<GetStoreAnnualHistoryResponse>,
    ) {
        val history = storeAnnualHistoryService.get(
            request.storeId,
            request.startDate.toLocalDate(),
            request.endDate.toLocalDate(),
        )
        val response = GetStoreAnnualHistoryResponse.newBuilder()
            .addAllSales(history.sales.map { it.toProto() })
            .addAllPromotions(history.promotions.map { it.toProto() })
            .setTotalSalesRecords(history.sales.size.toLong())
            .setTotalPromotionRecords(history.promotions.size.toLong())
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }
}
