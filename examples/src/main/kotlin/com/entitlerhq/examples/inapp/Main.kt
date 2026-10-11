package com.entitlerhq.examples.inapp

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.Scope
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The server mints a customer token with entitlements:read and usage:write; an in-app client with a
 * token provider checks a feature and records usage for the signed-in customer, and is closed at sign-out.
 * Run it with ENTITLER_KEY set.
 */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { server ->
            val customer = server.customer("in-app-customer")
            customer.register(name = "Grace Hopper")
            customer.adjustMeter(
                Feature.Metered("ai_credits"),
                idempotencyKey = "in-app-reset-" + UUID.randomUUID(),
                to = 0,
                reason = "A fresh run",
            )
            val mint = suspend { customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageWrite)).token }

            val app = EntitlerClient(mint)
            val me = app.me
            println("Export to PDF: ${me.isEntitled(Feature.OnOff("export_pdf"))}")
            val report = me.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "message-" + UUID.randomUUID())
            println("AI credits: ${report.outcome}")
            println("Signed in as ${me.id}, visitor ${app.visitor()}.")

            app.close()
            println("Signed out: the client is closed, and the next sign-in creates a new one.")
        }
    }
