package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.examples.generatedfeatures.Features
import java.time.Instant

/** As-of: reading the API at another instant. */
suspend fun lastMonth(customerId: String) {
    EntitlerServer(System.getenv("ENTITLER_KEY")) { asOf = Instant.parse("2026-09-01T00:00:00Z") }.use { past ->
        val check = past.customer(customerId).check(Features.aiCredits)
        println("On 1 September: ${check.used} used")
    }
}
