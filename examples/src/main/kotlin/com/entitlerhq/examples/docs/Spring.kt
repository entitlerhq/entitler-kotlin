package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.examples.generatedfeatures.Features
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Spring: one shared client, closed with the application context. */
@Configuration
class EntitlerConfiguration {
    /** The server client. */
    @Bean(destroyMethod = "close")
    fun entitler(): EntitlerServer = EntitlerServer(System.getenv("ENTITLER_KEY"))
}

/** Spring: a controller that gates a feature. */
@RestController
class ReportController(
    private val entitler: EntitlerServer,
) {
    /** Exports a report when the customer is entitled to it. */
    @GetMapping("/reports/pdf")
    suspend fun exportPdf(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<String> {
        if (!entitler.customer(userId).isEntitled(Features.exportPdf)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body("Upgrade to export PDFs.")
        }
        return ResponseEntity.ok("Your PDF is ready.")
    }
}
