package com.benchmark.sales.rest

import com.benchmark.sales.domain.VendaQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class VendaController(private val vendaQueryService: VendaQueryService) {

    @GetMapping("/produtos/{idProduto}/vendas")
    fun consultarVendasPorProduto(
        @PathVariable idProduto: Long,
        @RequestParam dataInicio: Instant,
        @RequestParam dataFim: Instant,
    ): ConsultarVendasResponseDto {
        val vendas = vendaQueryService.consultarPorProduto(idProduto, dataInicio, dataFim)
        return ConsultarVendasResponseDto(
            vendas = vendas.map(VendaDto::from),
            totalRegistros = vendas.size.toLong(),
        )
    }

    @GetMapping("/lojas/{idLoja}/vendas")
    fun consultarVendasPorLoja(
        @PathVariable idLoja: Int,
        @RequestParam dataInicio: Instant,
        @RequestParam dataFim: Instant,
    ): ConsultarVendasResponseDto {
        val vendas = vendaQueryService.consultarPorLoja(idLoja, dataInicio, dataFim)
        return ConsultarVendasResponseDto(
            vendas = vendas.map(VendaDto::from),
            totalRegistros = vendas.size.toLong(),
        )
    }
}
