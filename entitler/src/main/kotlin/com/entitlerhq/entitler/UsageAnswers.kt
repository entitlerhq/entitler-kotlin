@file:UseSerializers(InstantSerializer::class)

package com.entitlerhq.entitler

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * The answer to a usage write: the customer's check for the feature after it, and what happened.
 *
 * A refusal is an answer, not an error: check [outcome] and [refusal].
 *
 * @property outcome What happened.
 * @property refusal Why a gated report or a hold was refused; null otherwise.
 * @property id The usage report this created, repeated, settled or cancelled, or null.
 * @property holdId The hold this created, settled, released or repeated, or null.
 * @property mode The report's mode.
 * @property amount The amount asked for, or the original's on a replay.
 * @property meterChange How much this request moved the meter.
 * @property overBy How far [used] is past the allowance after the write; 0 within it or unlimited.
 * @property late True when the report's period or window had closed: it is in the log, not on the meter.
 * @property occurredAt When the usage happened, or null.
 * @property expiresAt When the hold expires; null for a report.
 * @property reportedAs Which kind of credential reported it.
 * @property customer The customer's external id.
 * @property feature The feature's key.
 * @property type The feature's type.
 * @property entitled Whether the customer is entitled after the write.
 * @property value The customer's allowance.
 * @property sources Where the allowance comes from.
 * @property used How much is used this period or window, after the write.
 * @property held How much open holds reserve, after the write.
 * @property remaining How much remains, after the write.
 * @property resetsAt When the meter starts again, or null.
 * @property upgrades Plans the customer could move to when not entitled.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class UsageResult(
    val outcome: UsageOutcome,
    val refusal: UsageRefusal?,
    val id: String?,
    val holdId: String?,
    val mode: UsageMode,
    val amount: Long,
    val meterChange: Long,
    val overBy: Long,
    val late: Boolean,
    val occurredAt: Instant?,
    val expiresAt: Instant?,
    val reportedAs: UsageSource,
    val customer: String,
    val feature: String,
    val type: FeatureType,
    val entitled: Boolean,
    val value: FeatureValue,
    val sources: List<EntitlementSource>,
    val used: Long? = null,
    val held: Long? = null,
    val remaining: Limit? = null,
    val resetsAt: Instant? = null,
    val upgrades: List<Upgrade>,
    val asOf: Instant,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
    val replayed: Boolean = false,
)

/**
 * One usage event for [EntitlerServer.recordUsageBatch], always recorded in observe mode.
 *
 * @property customer The customer's external id.
 * @property feature The metered feature.
 * @property amount The amount used, from 1.
 * @property idempotencyKey The event's key, from your own unit of work (a message or job id), so a resend
 *   from anywhere records the event once.
 * @property occurredAt When the usage happened, or null for now.
 */
public data class UsageBatchEvent(
    val customer: String,
    val feature: Feature.Metered,
    val amount: Long,
    val idempotencyKey: String,
    val occurredAt: Instant? = null,
)

/**
 * Why a batch event was not recorded.
 *
 * @property code The code a single report would answer.
 * @property message The API's message.
 */
@Serializable
public data class UsageEventError(
    val code: ErrorCode,
    val message: String,
)

/**
 * The result of one batch event.
 *
 * @property index The event's position in the input, from 0.
 * @property outcome `recorded`, `duplicate` or `error`.
 * @property id The report recorded, or the one a duplicate repeats.
 * @property late True when the event's period or window had closed.
 * @property error Why it was not recorded, or null.
 * @property idempotencyKey The event's key.
 * @property replayed True when its request replayed an earlier one, so nothing changed.
 */
@Serializable
public data class UsageEventResult(
    val index: Int,
    val outcome: UsageOutcome,
    val id: String?,
    val late: Boolean,
    val error: UsageEventError?,
    val idempotencyKey: String? = null,
    val replayed: Boolean = false,
)

/**
 * The results of a usage batch, one per event in input order.
 *
 * @property results One result per event.
 * @property recorded How many events were recorded.
 * @property duplicates How many were replays.
 * @property errors How many were not recorded.
 */
@Serializable
public data class UsageEventsResult(
    val results: List<UsageEventResult>,
    val recorded: Int,
    val duplicates: Int,
    val errors: Int,
    internal val replayed: Boolean = false,
)

/**
 * An entry in a customer's usage log.
 *
 * @property id The usage id.
 * @property feature The feature's key.
 * @property amount For a report, the amount used; for a meter set, how much it moved the meter.
 * @property kind The kind of entry.
 * @property setTo For a meter set, the value the meter was set to; else null.
 * @property source Which kind of credential reported it.
 * @property by The name of the key or person that recorded it, or null.
 * @property actor The person or system that decided an adjustment, or the cancel of a report, or null.
 * @property reason Why the adjustment, or the cancel of a report, was made, or null.
 * @property at When it was recorded.
 * @property cancelledAt When the report was cancelled, or null while it stands.
 */
@Serializable
public data class UsageEvent(
    val id: String,
    val feature: String,
    val amount: Long,
    val kind: UsageEventKind,
    val setTo: Long?,
    val source: UsageSource,
    val by: String?,
    val actor: String?,
    val reason: String?,
    val at: Instant,
    val cancelledAt: Instant?,
)

/**
 * A page of a customer's usage log.
 *
 * @property items The entries, newest first.
 * @property next The cursor of the next page, or null on the last.
 */
@Serializable
public data class UsageLog(
    val items: List<UsageEvent>,
    val next: String?,
)

/**
 * A metered feature's meter in a customer's usage.
 *
 * @property feature The feature's key.
 * @property type The feature's type.
 * @property entitled Whether the customer is entitled.
 * @property value The customer's allowance.
 * @property sources Where the allowance comes from.
 * @property used How much is used this period or window.
 * @property held How much open holds reserve.
 * @property remaining How much remains, or null.
 * @property resetsAt When the meter starts again, or null.
 */
@Serializable
public data class FeatureUsage(
    val feature: String,
    val type: FeatureType,
    val entitled: Boolean,
    val value: FeatureValue,
    val sources: List<EntitlementSource>,
    val used: Long,
    val held: Long,
    val remaining: Limit?,
    val resetsAt: Instant?,
)

/**
 * A customer's meters and a page of their usage log.
 *
 * @property customer The customer's external id.
 * @property metersStartAgainAt When the first meter starts again, or null.
 * @property features The meters.
 * @property log A page of the usage log.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 */
@Serializable
public data class CustomerUsage(
    val customer: String,
    val metersStartAgainAt: Instant?,
    val features: List<FeatureUsage>,
    val log: UsageLog,
    val asOf: Instant,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
)
