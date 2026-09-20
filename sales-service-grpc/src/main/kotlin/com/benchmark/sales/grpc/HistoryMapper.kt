package com.benchmark.sales.grpc

import com.google.protobuf.Timestamp
import com.benchmark.sales.domain.MonthlySale as DomainMonthlySale
import com.benchmark.sales.domain.Promotion as DomainPromotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

fun LocalDate.toProtoTimestamp(): Timestamp = atStartOfDay(ZoneOffset.UTC).toInstant().toProtoTimestamp()

fun Timestamp.toLocalDate(): LocalDate = toInstant().atZone(ZoneOffset.UTC).toLocalDate()

fun DomainMonthlySale.toProto(): MonthlySale = MonthlySale.newBuilder()
    .setStoreId(storeId)
    .setProductId(productId)
    .setProductName(productName)
    .setCategory(category)
    .setYearMonth(yearMonth.toProtoTimestamp())
    .setQuantitySold(quantitySold)
    .setTotalValue(totalValue.toDouble())
    .setRegion(region)
    .setStoreName(storeName)
    .build()

fun DomainPromotion.toProto(): Promotion = Promotion.newBuilder()
    .setPromotionId(promotionId.toString())
    .setStoreId(storeId)
    .setYearMonth(yearMonth.toProtoTimestamp())
    .setPromotionName(promotionName)
    .setDiscountPercentage(discountPercentage.toDouble())
    .setStartDate(startDate.toProtoTimestamp())
    .setEndDate(endDate.toProtoTimestamp())
    .build()
