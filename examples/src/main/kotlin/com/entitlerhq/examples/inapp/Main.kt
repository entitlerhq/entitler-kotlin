package com.entitlerhq.examples.inapp

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.Feature
import kotlinx.coroutines.runBlocking

/**
 * The server mints a customer token; an in-app client with a token provider checks a feature and
 * records usage for the signed-in customer. Run it with ENTITLER_KEY set.
 */
fun main(): Unit =
    runBlocking {
        val server = EntitlerServer(System.getenv("ENTITLER_KEY"))
        val customer = server.customer("in-app-customer")
        customer.register(name = "Grace Hopper")

        val app = EntitlerClient({ customer.token().token })
        val me = app.me
        println("Export to PDF: ${me.isEntitled(Feature.OnOff("export_pdf"))}")
        println("AI credits: ${me.recordUsage(Feature.Metered("ai_credits"), 1).outcome}")
        println("Signed in as ${me.id}, visitor ${app.visitor}.")

        app.close()
        server.close()
    }
