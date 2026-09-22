package com.benchmark.sales.msgpack

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@ComponentScan(basePackages = ["com.benchmark.sales.msgpack", "com.benchmark.sales.domain"])
@EntityScan(basePackages = ["com.benchmark.sales.domain"])
@EnableJpaRepositories(basePackages = ["com.benchmark.sales.domain"])
class SalesMsgpackApplication

fun main(args: Array<String>) {
    runApplication<SalesMsgpackApplication>(*args)
}
