package com.entitlerhq.examples.meteredwork

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.UsageMode
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.random.Random

private val aiCredits = Feature.Metered("ai_credits")
private const val HELD = 10L
private const val STREAMED = 3L

/** Holds credits around work whose cost is known only afterwards, then reports streamed usage. Run it with ENTITLER_KEY set. */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("metered-customer")
            customer.register(name = "Katherine Johnson")
            val job = UUID.randomUUID().toString()

            try {
                val summary =
                    customer.withHold(aiCredits, HELD, idempotencyKey = "summary-$job") { hold ->
                        val creditsUsed = Random.nextLong(1, HELD)
                        hold.used = creditsUsed
                        "A summary that cost $creditsUsed credits."
                    }
                println(summary)
            } catch (refused: EntitlerUsageRefusedException) {
                println("Not enough credits: ${refused.result.refusal}.")
            }

            val streamed = customer.recordUsage(aiCredits, STREAMED, mode = UsageMode.Observe, idempotencyKey = "stream-$job")
            println("Recorded streamed usage; over the allowance by ${streamed.overBy}.")
        }
    }
