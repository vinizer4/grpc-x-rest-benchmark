package com.benchmark.sales.msgpack

import com.fasterxml.jackson.databind.ObjectMapper
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers a binary MessagePack converter (application/x-msgpack) as the
 * response format for this service, instead of the default JSON converter
 * spring-boot-starter-web ships with.
 */
@Configuration
class MessagePackConfig : WebMvcConfigurer {

    @Bean
    fun messagePackObjectMapper(): ObjectMapper = ObjectMapper(MessagePackFactory())

    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        val msgpackMediaType = MediaType("application", "x-msgpack")
        val converter = MappingJackson2HttpMessageConverter(messagePackObjectMapper())
        converter.supportedMediaTypes = listOf(msgpackMediaType)
        converters.add(0, converter)
    }
}
