package com.dm.dow

import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Semaphore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class Config {
    @Bean
    fun webclient() = WebClient.builder().exchangeStrategies { builder ->
        builder.codecs {
            it.defaultCodecs().maxInMemorySize(20 * 1024 * 1024)
        }
    }
            .build()


}

object Disp {
    val DOWN = newFixedThreadPoolContext(4,"down-context-thread")
    val MERGE = newSingleThreadContext("merge-context-thread")

}

