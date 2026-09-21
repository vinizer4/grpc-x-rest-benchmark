package com.benchmark.sales.resth2

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@ComponentScan(basePackages = ["com.benchmark.sales.resth2", "com.benchmark.sales.domain"])
@EntityScan(basePackages = ["com.benchmark.sales.domain"])
@EnableJpaRepositories(basePackages = ["com.benchmark.sales.domain"])
class SalesRestH2Application

fun main(args: Array<String>) {
    runApplication<SalesRestH2Application>(*args)
}
