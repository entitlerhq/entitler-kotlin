package com.entitlerhq.examples.billing

import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.Feature
import kotlinx.coroutines.runBlocking

/** Subscribes self-serve, shows the plan space, starts a checkout and grants a feature. Run it with ENTITLER_KEY set. */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("billing-customer")
            customer.register(name = "Mary Jackson")

            customer.subscribe("pro")
            val space = customer.planSpace()
            println("Holds: ${space.held.joinToString { it.plan.name }}")
            println("Could move to: ${space.options.filter { it.selfServe }.joinToString { it.plan.name }}")

            try {
                val checkout =
                    customer.checkout(
                        "pro",
                        successUrl = "https://example.com/thanks",
                        cancelUrl = "https://example.com/pricing",
                    )
                println("Send the customer to ${checkout.url}")
            } catch (error: EntitlerApiException) {
                if (error.code != ErrorCode.Stale) throw error
                println("Checkout needs a payment provider connection: ${error.message}")
            }

            customer.vendor.grant(Feature.OnOff("sso"), days = 14, reason = "Pilot")
            println("Granted single sign-on for 14 days.")
            customer.subscribe("free")
        }
    }
