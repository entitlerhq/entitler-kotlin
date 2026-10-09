package com.entitlerhq.examples.pricingpage

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.FeatureValue
import kotlinx.coroutines.runBlocking

private const val MINOR_UNITS = 100.0

/** Prints the pricing as a pricing page would. Run it with ENTITLER_KEY set. */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val visitor = entitler.newVisitorId()
            val pricing = entitler.pricing(visitor = visitor)
            for (plan in pricing.plans.filter { it.kind == "plan" }) {
                println(plan.name + if (plan.default) " (where everyone starts)" else "")
                for (period in plan.periods) {
                    val listing = plan.listings.firstOrNull { it.period == period.label }
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
            println("Keep the visitor id $visitor in a first-party cookie, and pass it on sign-up.")
        }
    }
