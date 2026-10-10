package com.entitlerhq.examples.quickstart

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.UsageOutcome
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Registers a customer, checks a feature and records usage with an idempotency key. Run it with ENTITLER_KEY set. */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("quickstart-customer")
            customer.register(name = "Ada Lovelace", email = "ada@example.com")

            if (customer.isEntitled(Feature.OnOff("export_pdf"))) {
                println("Ada can export PDFs.")
            } else {
                println("Exporting PDFs needs an upgrade.")
            }

            val job = "quickstart-" + UUID.randomUUID()
            val result = customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = job)
            if (result.outcome == UsageOutcome.Refused) {
                println("Out of AI credits until ${result.resetsAt}.")
            } else {
                println("Recorded 1 AI credit; ${result.remaining} left.")
            }
        }
    }
