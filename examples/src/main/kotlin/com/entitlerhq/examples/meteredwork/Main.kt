package com.entitlerhq.examples.meteredwork

import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.EntitlerUsageReplayedException
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.UsageMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.random.Random

private val aiCredits = Feature.Metered("ai_credits")
private const val ESTIMATE = 10L
private const val STREAMED = 3L

/**
 * Holds credits around streamed work that reports as it goes, wraps work whose cost is known only
 * afterwards in withHold, and records an observe-mode report. Run it with ENTITLER_KEY set.
 */
fun main(): Unit =
    runBlocking {
        EntitlerServer(System.getenv("ENTITLER_KEY")).use { entitler ->
            val customer = entitler.customer("metered-customer")
            customer.register(name = "Katherine Johnson")
            val message = UUID.randomUUID().toString()

            try {
                customer.startHold(aiCredits, ESTIMATE, idempotencyKey = "reply-$message").use { hold ->
                    hold.used = 0
                    var tokens = 0L
                    flowOf("Streamed ", "reply ", "chunks.").collect { chunk ->
                        tokens += 1
                        hold.used = tokens
                        print(chunk)
                    }
                    println()
                    println("Charged ${hold.finish().amount} credits for the streamed reply.")
                }
            } catch (refused: EntitlerUsageRefusedException) {
                println("Not enough credits to start: ${refused.result.refusal}.")
            } catch (replayed: EntitlerUsageReplayedException) {
                println("This reply was already streamed and charged: ${replayed.result.outcome}.")
            }

            val summary =
                customer.withHold(aiCredits, ESTIMATE, idempotencyKey = "summary-$message") { hold ->
                    val creditsUsed = Random.nextLong(1, ESTIMATE)
                    hold.used = creditsUsed
                    "A summary that cost $creditsUsed credits."
                }
            println(summary)

            val streamed = customer.recordUsage(aiCredits, STREAMED, idempotencyKey = "upload-$message", mode = UsageMode.Observe)
            println("Recorded upload usage; over the allowance by ${streamed.overBy}.")
        }
    }
