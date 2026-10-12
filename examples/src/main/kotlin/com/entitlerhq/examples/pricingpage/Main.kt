package com.entitlerhq.examples.pricingpage

import com.entitlerhq.entitler.EntitlerPublishableClient
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.PlanKind
import com.entitlerhq.entitler.Pricing
import kotlinx.coroutines.runBlocking

private const val MINOR_UNITS = 100.0

/**
 * Prints the pricing as a pricing page would: once through a publishable client, as an app with no
 * backend route does, and once through the server client with a visitor id. Run it with ENTITLER_KEY
 * and ENTITLER_PUBLISHABLE_KEY set.
 */
fun main(): Unit =
    runBlocking {
        EntitlerPublishableClient(System.getenv("ENTITLER_PUBLISHABLE_KEY")).use { app ->
            println("Signed out, in an app:")
            printPricing(app.pricing())
        }
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val visitor = entitler.newVisitorId()
            println("On a server, for visitor $visitor:")
            printPricing(entitler.pricing(visitor = visitor))
            println("Keep the visitor id in a first-party cookie, and pass it to register() on sign-up.")
        }
    }

private fun printPricing(pricing: Pricing) {
    for (plan in pricing.plans.filter { it.kind == PlanKind.Plan }) {
        println(plan.name + if (plan.default) " (where everyone starts)" else "")
        for (period in plan.periods) {
            val listing = plan.listings.firstOrNull { it.period?.key == period.key }
            val price = listing?.channels?.firstNotNullOfOrNull { it.price }
            val shown = price?.let { "${it.amount / MINOR_UNITS} ${it.currency.uppercase()}" } ?: "Coming soon"
            println("  ${period.label}: $shown")
        }
        plan.features.forEach { (feature, value) ->
            val text =
                when (value) {
                    FeatureValue.On -> "included"
                    is FeatureValue.Amount -> value.value.toString()
                    FeatureValue.Unlimited -> "unlimited"
                }
            println("  $feature: $text")
        }
    }
}
