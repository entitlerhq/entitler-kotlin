package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.EntitlerUsageReplayedException
import com.entitlerhq.entitler.UsageBatchEvent
import com.entitlerhq.entitler.UsageMode
import com.entitlerhq.entitler.UsageOutcome
import com.entitlerhq.examples.generatedfeatures.Features
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** Recording usage: gate mode. */
suspend fun gateMode(
    customer: Customer,
    jobId: String,
) {
    val result = customer.recordUsage(Features.aiCredits, 5, idempotencyKey = "job-$jobId")
    when (result.outcome) {
        UsageOutcome.Recorded, UsageOutcome.Duplicate -> println("${result.remaining} credits left")
        UsageOutcome.Refused -> showUpgradePrompt()
    }
}

/** Recording usage: observe mode. */
suspend fun observeMode(
    customer: Customer,
    streamId: String,
    tokens: Long,
    startedAt: Instant,
) {
    val result =
        customer.recordUsage(
            Features.aiCredits,
            tokens,
            mode = UsageMode.Observe,
            occurredAt = startedAt,
            idempotencyKey = "stream-$streamId",
        )
    if (result.overBy > 0) println("Over the allowance by ${result.overBy}")
}

/** Recording usage: holds by hand. */
suspend fun holdsByHand(
    customer: Customer,
    jobId: String,
) {
    val hold = customer.holdUsage(Features.aiCredits, 100, ttl = 10.minutes, idempotencyKey = "hold-$jobId")
    val holdId = hold.holdId ?: return showUpgradePrompt()
    var settled = false
    try {
        val summary = summarise("…")
        customer.settleUsage(holdId, summary.creditsUsed)
        settled = true
    } finally {
        if (!settled) customer.releaseUsage(holdId)
    }
}

/** Recording usage: a hold around streamed work. */
suspend fun streamReply(
    customer: Customer,
    messageId: String,
    chunks: Flow<String>,
) {
    try {
        customer.startHold(Features.aiCredits, 500, idempotencyKey = "reply-$messageId").use { hold ->
            hold.used = 0
            var tokens = 0L
            chunks.collect { chunk ->
                tokens += chunk.length
                hold.used = tokens
                print(chunk)
            }
            hold.finish()
        }
    } catch (refused: EntitlerUsageRefusedException) {
        println("Refused: ${refused.result.refusal}")
    } catch (replayed: EntitlerUsageReplayedException) {
        println("Already streamed: ${replayed.result.outcome}")
    }
}

/** Recording usage: withHold. */
suspend fun withHoldExample(
    customer: Customer,
    jobId: String,
) {
    try {
        val summary =
            customer.withHold(Features.aiCredits, 100, idempotencyKey = "summary-$jobId") { hold ->
                summarise("…").also { hold.used = it.creditsUsed }
            }
        println(summary.text)
    } catch (refused: EntitlerUsageRefusedException) {
        println("Refused: ${refused.result.refusal}")
    }
}

/** Recording usage: batches. */
suspend fun batch(
    entitler: EntitlerServer,
    rows: List<MeterRow>,
) {
    val events =
        rows.map { row ->
            UsageBatchEvent(row.customerId, Features.aiCredits, row.amount, idempotencyKey = "meter-row-${row.rowId}", occurredAt = row.at)
        }
    val result = entitler.recordUsageBatch(events)
    println("${result.recorded} recorded, ${result.duplicates} already recorded, ${result.errors} not recorded")
}

/** A row of metered usage your app collected. */
data class MeterRow(
    /** The row's id, used as the idempotency key. */
    val rowId: String,
    /** The customer's id. */
    val customerId: String,
    /** The amount used. */
    val amount: Long,
    /** When it was used. */
    val at: Instant,
)
