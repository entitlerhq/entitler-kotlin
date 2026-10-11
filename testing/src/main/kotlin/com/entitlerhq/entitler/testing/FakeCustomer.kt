package com.entitlerhq.entitler.testing

import com.entitlerhq.entitler.BillingSync
import com.entitlerhq.entitler.Check
import com.entitlerhq.entitler.CustomerBilling
import com.entitlerhq.entitler.CustomerDetail
import com.entitlerhq.entitler.CustomerPlans
import com.entitlerhq.entitler.CustomerSummary
import com.entitlerhq.entitler.CustomerTrack
import com.entitlerhq.entitler.CustomerUsage
import com.entitlerhq.entitler.Entitlements
import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.EntitlerUsageReplayedException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.FeatureType
import com.entitlerhq.entitler.FeatureUsage
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.GrantChange
import com.entitlerhq.entitler.Hold
import com.entitlerhq.entitler.IssuedCustomerToken
import com.entitlerhq.entitler.IssuedSnapshot
import com.entitlerhq.entitler.MeteredCheck
import com.entitlerhq.entitler.PlanBilling
import com.entitlerhq.entitler.PlanChange
import com.entitlerhq.entitler.PlanChoice
import com.entitlerhq.entitler.PlanVersion
import com.entitlerhq.entitler.Pricing
import com.entitlerhq.entitler.ProviderPage
import com.entitlerhq.entitler.RegisteredCustomer
import com.entitlerhq.entitler.Scope
import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.entitler.Sku
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.Timing
import com.entitlerhq.entitler.UsageEvent
import com.entitlerhq.entitler.UsageEventKind
import com.entitlerhq.entitler.UsageMode
import com.entitlerhq.entitler.UsageOutcome
import com.entitlerhq.entitler.UsageRefusal
import com.entitlerhq.entitler.UsageResult
import com.entitlerhq.entitler.UsageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.time.Instant
import kotlin.time.Duration

/**
 * A meter for [fakeCustomer]: a metered feature's allowance and how much is used.
 *
 * @property value The allowance: an amount or [FeatureValue.Unlimited].
 * @property used How much is used this period.
 */
public data class FakeMeter(
    val value: FeatureValue,
    val used: Long = 0,
)

/** A metered feature with an allowance of [value], [used] of it already used, for [fakeCustomer]. */
public fun metered(
    value: Long,
    used: Long = 0,
): FakeMeter = FakeMeter(FeatureValue.Amount(value), used)

/**
 * A write a [FakeCustomer] received.
 *
 * @property method The method's name, such as `recordUsage`.
 * @property arguments Its arguments by name, as passed.
 */
public data class FakeWrite(
    val method: String,
    val arguments: Map<String, Any?>,
)

/**
 * A [ServerCustomer] that makes no request, for tests of an app's own gating and billing code.
 *
 * ```kotlin
 * val customer = fakeCustomer("export_pdf" to true, "ai_credits" to metered(value = 100, used = 97))
 * assertTrue(customer.isEntitled(Features.exportPdf))
 * exporter.run(customer)
 * assertEquals("recordUsage", customer.writes.single().method)
 * ```
 */
public interface FakeCustomer : ServerCustomer {
    /** Every write received, in order. */
    public val writes: List<FakeWrite>

    /**
     * Replaces the answer of [method], such as `subscribe`, with what [answer] gives for the write; it may
     * also throw, to test an app's handling of an error.
     */
    public fun answer(
        method: String,
        answer: (FakeWrite) -> Any,
    )
}

/**
 * A [FakeCustomer] holding [values]: each feature key to `true` or `false` (on/off), a whole number (an
 * amount), a [FeatureValue], or a [FakeMeter] from [metered].
 *
 * - Reads answer complete checks and entitlement lists from [values]; a key it does not hold answers
 *   `404 feature_not_found`, so `isEntitled` answers its default. [plans] and [pricing] answer `plans()`
 *   and `pricing()`, built with [Answers].
 * - Usage writes move the meters as Entitler does: a gated report past the allowance answers `refused`,
 *   holds settle and release, and a reused key replays.
 * - Writes it does not model answer a plain success (`subscribe` a `Done` step made now, the plan changes
 *   a [PlanChange] made now), which [FakeCustomer.answer] replaces.
 */
public fun fakeCustomer(
    vararg values: Pair<String, Any>,
    id: String = "user_1",
    plans: CustomerPlans = Answers.customerPlans(customer = id),
    pricing: Pricing = Answers.pricing(customer = id),
): FakeCustomer = Fake(id, values.associate { (key, value) -> key to valueOf(key, value) }.toMutableMap(), plans, pricing)

private sealed interface Held {
    val value: FeatureValue

    data class Plain(
        override val value: FeatureValue,
    ) : Held

    data class Meter(
        override val value: FeatureValue,
        var used: Long,
        var held: Long = 0,
    ) : Held
}

private fun valueOf(
    key: String,
    value: Any,
): Held =
    when (value) {
        true -> Held.Plain(FeatureValue.On)
        false -> Held.Plain(FeatureValue.Amount(0))
        is Int -> Held.Plain(FeatureValue.Amount(value.toLong()))
        is Long -> Held.Plain(FeatureValue.Amount(value))
        is FeatureValue -> Held.Plain(value)
        is FakeMeter -> Held.Meter(value.value, value.used)
        else -> throw IllegalArgumentException("Pass $key as true, false, a whole number, a FeatureValue or metered(…).")
    }

private class OpenHold(
    val feature: String,
    val amount: Long,
    var state: UsageOutcome = UsageOutcome.Held,
    var settled: Long? = null,
)

@Suppress("TooManyFunctions", "LargeClass")
private class Fake(
    override val id: String,
    private val values: MutableMap<String, Held>,
    private val plans: CustomerPlans,
    private val pricing: Pricing,
) : FakeCustomer {
    private val recorded = mutableListOf<FakeWrite>()
    private val answers = mutableMapOf<String, (FakeWrite) -> Any>()
    private val replays = mutableMapOf<String, UsageResult>()
    private val holds = mutableMapOf<String, OpenHold>()
    private val log = mutableListOf<UsageEvent>()

    override val writes: List<FakeWrite> get() = synchronized(recorded) { recorded.toList() }

    override fun answer(
        method: String,
        answer: (FakeWrite) -> Any,
    ) {
        synchronized(answers) { answers[method] = answer }
    }

    private fun <T> write(
        method: String,
        vararg arguments: Pair<String, Any?>,
        modelled: () -> T,
    ): T {
        val write = FakeWrite(method, arguments.toMap())
        synchronized(recorded) { recorded += write }
        val replaced = synchronized(answers) { answers[method] }
        @Suppress("UNCHECKED_CAST")
        return if (replaced != null) replaced(write) as T else modelled()
    }

    private fun held(key: String): Held =
        synchronized(values) { values[key] }
            ?: throw EntitlerApiException(NOT_FOUND, ErrorCode.FeatureNotFound, "No feature has the key $key in this environment.")

    private fun meterOf(key: String): Held.Meter =
        held(key) as? Held.Meter ?: throw EntitlerApiException(BAD_REQUEST, ErrorCode.NotMetered, "The feature $key is not metered.")

    private fun checkOf(key: String): Check =
        when (val feature = held(key)) {
            is Held.Plain -> {
                Answers.check(feature = key, entitled = isEntitled(feature.value), value = feature.value, customer = id)
            }

            is Held.Meter -> {
                val meter = meteredOf(key, feature)
                Answers.check(
                    feature = key,
                    entitled = meter.entitled,
                    value = feature.value,
                    type = FeatureType.Metered,
                    customer = id,
                    used = meter.used,
                    held = meter.held,
                    remaining = meter.remaining,
                )
            }
        }

    private fun meteredOf(
        key: String,
        meter: Held.Meter,
    ): MeteredCheck = Answers.meteredCheck(feature = key, value = meter.value, used = meter.used, held = meter.held, customer = id)

    private fun isEntitled(value: FeatureValue) = value != FeatureValue.Amount(0)

    override suspend fun check(
        feature: Feature.Metered,
        revalidate: Boolean,
        timeout: Duration?,
    ): MeteredCheck = meteredOf(feature.key, meterOf(feature.key))

    override suspend fun check(
        feature: Feature,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = checkOf(feature.key)

    override suspend fun check(
        key: String,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = checkOf(key)

    override suspend fun check(
        feature: Feature.Metered,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): MeteredCheck = check(feature)

    override suspend fun check(
        feature: Feature,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = checkOf(feature.key)

    override suspend fun check(
        key: String,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = checkOf(key)

    override suspend fun isEntitled(
        feature: Feature,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(feature.key, default, revalidate)

    override suspend fun isEntitled(
        key: String,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean {
        require(key.isNotBlank()) { "Name the feature by its key." }
        return runCatching { checkOf(key).entitled }.getOrDefault(default)
    }

    override suspend fun isEntitled(
        feature: Feature,
        asOf: Instant,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(feature.key, default, revalidate)

    override suspend fun isEntitled(
        key: String,
        asOf: Instant,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(key, default, revalidate)

    override suspend fun entitlements(
        revalidate: Boolean,
        timeout: Duration?,
    ): Entitlements {
        val keys = synchronized(values) { values.keys.sorted() }
        val items =
            keys.map { key ->
                val check = checkOf(key)
                Answers.entitlement(key, check.entitled, check.value, check.type, check.used, check.held, check.remaining)
            }
        return Answers.entitlements(items, id)
    }

    override suspend fun entitlements(
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Entitlements = entitlements(revalidate, timeout)

    override suspend fun plans(
        revalidate: Boolean,
        timeout: Duration?,
    ): CustomerPlans = plans

    override suspend fun plans(
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): CustomerPlans = plans

    override suspend fun pricing(
        visitor: String?,
        revalidate: Boolean,
        timeout: Duration?,
    ): Pricing = pricing

    override suspend fun usage(
        cursor: String?,
        timeout: Duration?,
    ): CustomerUsage {
        val meters =
            synchronized(values) { values.filterValues { it is Held.Meter } }.map { (key, held) ->
                val meter = meteredOf(key, held as Held.Meter)
                FeatureUsage(
                    key,
                    FeatureType.Metered,
                    meter.entitled,
                    meter.value,
                    meter.sources,
                    meter.used,
                    meter.held,
                    meter.remaining,
                    null,
                )
            }
        return Answers.customerUsage(meters, synchronized(log) { log.toList() }, customer = id)
    }

    override suspend fun usage(
        asOf: Instant,
        cursor: String?,
        timeout: Duration?,
    ): CustomerUsage = usage(cursor, timeout)

    override fun usageLog(
        cursor: String?,
        timeout: Duration?,
    ): Flow<UsageEvent> = synchronized(log) { log.toList() }.asFlow()

    override fun usageLog(
        asOf: Instant,
        cursor: String?,
        timeout: Duration?,
    ): Flow<UsageEvent> = usageLog(cursor, timeout)

    override fun detailsLog(
        cursor: String?,
        asOf: Instant?,
        timeout: Duration?,
    ): Flow<UsageEvent> = usageLog(cursor, timeout)

    private fun result(
        outcome: UsageOutcome,
        key: String,
        amount: Long,
        mode: UsageMode = UsageMode.Gate,
        refusal: UsageRefusal? = null,
        holdId: String? = null,
        meterChange: Long = 0,
    ): UsageResult {
        val meter = meterOf(key)
        val overBy = (meter.value as? FeatureValue.Amount)?.let { (meter.used - it.value).coerceAtLeast(0) } ?: 0
        if (meterChange != 0L) {
            synchronized(log) {
                val kind = if (outcome == UsageOutcome.Adjusted) UsageEventKind.Adjust else UsageEventKind.Use
                log +=
                    UsageEvent(
                        "u_${log.size + 1}",
                        key,
                        meterChange,
                        kind,
                        null,
                        UsageSource.Api,
                        "Test key",
                        null,
                        null,
                        Answers.AS_OF,
                        null,
                    )
            }
        }
        return Answers.usageResult(
            outcome = outcome,
            feature = key,
            amount = amount,
            value = meter.value,
            used = meter.used,
            held = meter.held,
            mode = mode,
            refusal = refusal,
            holdId = holdId,
            meterChange = meterChange,
            overBy = overBy,
            customer = id,
        )
    }

    private fun fits(
        meter: Held.Meter,
        amount: Long,
    ): Boolean = (meter.value as? FeatureValue.Amount)?.let { meter.used + meter.held + amount <= it.value } ?: true

    private fun replayOr(
        key: String,
        record: () -> UsageResult,
    ): UsageResult =
        synchronized(replays) {
            replays[key]?.let { it.copy(outcome = replayedOutcome(it), replayed = true) }
                ?: record().also { if (it.outcome != UsageOutcome.Refused) replays[key] = it }
        }

    private fun replayedOutcome(first: UsageResult): UsageOutcome =
        when {
            first.holdId != null -> holds[first.holdId]?.state?.takeIf { it != UsageOutcome.Held } ?: UsageOutcome.Duplicate
            else -> UsageOutcome.Duplicate
        }

    override suspend fun recordUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        mode: UsageMode?,
        occurredAt: Instant?,
        register: Boolean,
        timeout: Duration?,
    ): UsageResult =
        write("recordUsage", "feature" to feature, "amount" to amount, "idempotencyKey" to idempotencyKey, "mode" to mode) {
            require(amount in 1..MAX_AMOUNT) { "Pass amount as a whole number from 1 to $MAX_AMOUNT." }
            replayOr(idempotencyKey) {
                val meter = meterOf(feature.key)
                val observe = mode == UsageMode.Observe
                if (!observe && !fits(meter, amount)) {
                    result(UsageOutcome.Refused, feature.key, amount, refusal = UsageRefusal.OverAllowance)
                } else {
                    meter.used += amount
                    result(UsageOutcome.Recorded, feature.key, amount, mode ?: UsageMode.Gate, meterChange = amount)
                }
            }
        }

    override suspend fun holdUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
    ): UsageResult =
        write("holdUsage", "feature" to feature, "amount" to amount, "idempotencyKey" to idempotencyKey) {
            hold(feature.key, amount, idempotencyKey)
        }

    private fun hold(
        key: String,
        amount: Long,
        idempotencyKey: String,
    ): UsageResult =
        replayOr(idempotencyKey) {
            val meter = meterOf(key)
            if (!fits(meter, amount)) {
                result(UsageOutcome.Refused, key, amount, refusal = UsageRefusal.OverAllowance)
            } else {
                meter.held += amount
                val holdId = "h_${holds.size + 1}"
                holds[holdId] = OpenHold(key, amount)
                result(UsageOutcome.Held, key, amount, holdId = holdId)
            }
        }

    override suspend fun startHold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
    ): Hold {
        val held = holdUsage(feature, amount, idempotencyKey, ttl, timeout)
        return when (held.outcome) {
            UsageOutcome.Held, UsageOutcome.Duplicate -> FakeHold(this, held, feature, idempotencyKey)
            UsageOutcome.Settled, UsageOutcome.Released -> throw EntitlerUsageReplayedException(held)
            else -> throw EntitlerUsageRefusedException(held)
        }
    }

    override suspend fun <T> withHold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
        work: suspend (hold: Hold) -> T,
    ): T {
        val hold = startHold(feature, amount, idempotencyKey, ttl, timeout) as FakeHold
        var finished = false
        val result =
            try {
                work(hold).also { finished = true }
            } finally {
                if (!finished) hold.dispose()
            }
        hold.finish()
        return result
    }

    private fun openHold(holdId: String): OpenHold =
        holds[holdId] ?: throw EntitlerApiException(NOT_FOUND, ErrorCode.NotFound, "There is no hold $holdId.")

    override suspend fun settleUsage(
        holdId: String,
        amount: Long,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult =
        write("settleUsage", "holdId" to holdId, "amount" to amount) {
            synchronized(replays) {
                val hold = openHold(holdId)
                val meter = meterOf(hold.feature)
                if (hold.state == UsageOutcome.Settled && hold.settled == amount) {
                    return@synchronized result(UsageOutcome.Duplicate, hold.feature, amount, holdId = holdId)
                }
                if (hold.state != UsageOutcome.Held) {
                    throw EntitlerApiException(CONFLICT, ErrorCode.HoldSettled, "The hold was already ${hold.state}.")
                }
                require(amount in 0..hold.amount) { "Pass amount as a whole number from 0 to the held amount." }
                hold.state = UsageOutcome.Settled
                hold.settled = amount
                meter.held -= hold.amount
                meter.used += amount
                result(UsageOutcome.Settled, hold.feature, amount, holdId = holdId, meterChange = amount)
            }
        }

    override suspend fun releaseUsage(
        holdId: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult =
        write("releaseUsage", "holdId" to holdId) {
            synchronized(replays) {
                val hold = openHold(holdId)
                if (hold.state == UsageOutcome.Held) {
                    hold.state = UsageOutcome.Released
                    meterOf(hold.feature).held -= hold.amount
                }
                result(UsageOutcome.Released, hold.feature, hold.amount, holdId = holdId)
            }
        }

    override suspend fun snapshot(
        ttl: Duration?,
        timeout: Duration?,
    ): IssuedSnapshot = write("snapshot", "ttl" to ttl) { Answers.issuedSnapshot() }

    override suspend fun subscribe(
        plan: String,
        period: String?,
        quantity: Long?,
        returnUrl: String?,
        register: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): SubscribeStep =
        write("subscribe", "plan" to plan, "period" to period, "quantity" to quantity, "returnUrl" to returnUrl) {
            SubscribeStep.Done(Answers.planChange(plan, quantity))
        }

    override suspend fun cancel(
        addOn: String?,
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange = write("cancel", "addOn" to addOn, "product" to product) { Answers.planChange(plan = null) }

    override suspend fun undoPendingChange(
        addOn: String?,
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange = write("undoPendingChange", "addOn" to addOn, "product" to product) { Answers.planChange() }

    override suspend fun billingPortal(
        returnUrl: String,
        timeout: Duration?,
    ): ProviderPage = write("billingPortal", "returnUrl" to returnUrl) { Answers.providerPage() }

    override suspend fun syncBilling(timeout: Duration?): BillingSync = write("syncBilling") { Answers.billingSync() }

    override suspend fun register(
        name: String?,
        email: String?,
        metadata: Map<String, String?>?,
        visitor: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): RegisteredCustomer =
        write("register", "name" to name, "email" to email, "metadata" to metadata, "visitor" to visitor) {
            Answers.registeredCustomer(id)
        }

    override suspend fun details(
        cursor: String?,
        asOf: Instant?,
        timeout: Duration?,
    ): CustomerDetail = Answers.customerDetail(Answers.customerSummary(id), entitlements(false, null).entitlements)

    override suspend fun update(
        name: String?,
        email: String?,
        metadata: Map<String, String?>?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerSummary =
        write("update", "name" to name, "email" to email, "metadata" to metadata) {
            Answers.customerSummary(id, name = name ?: "Ada Lovelace", email = email ?: "ada@example.com")
        }

    override suspend fun erase(
        idempotencyKey: String?,
        timeout: Duration?,
    ) {
        write("erase") { }
    }

    override suspend fun token(
        scopes: List<Scope>?,
        ttl: Duration?,
        timeout: Duration?,
    ): IssuedCustomerToken =
        write("token", "scopes" to scopes, "ttl" to ttl) {
            Answers.issuedCustomerToken(customer = id, scopes = scopes ?: listOf(Scope.EntitlementsRead))
        }

    override suspend fun setTrack(
        track: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerTrack =
        write("setTrack", "track" to track) {
            Answers.customerTrack(track?.let { com.entitlerhq.entitler.Track("t_$it", it) } ?: Answers.TRACK, id)
        }

    override suspend fun billing(timeout: Duration?): CustomerBilling = Answers.customerBilling()

    override suspend fun setPlan(
        plan: String,
        period: String?,
        timing: Timing?,
        billing: PlanBilling?,
        until: Instant?,
        version: PlanVersion?,
        register: Boolean,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange =
        setPlan(
            com.entitlerhq.entitler.Plan(plan),
            period,
            timing,
            billing,
            until,
            version,
            register,
            actor,
            reason,
            idempotencyKey,
            timeout,
        )

    override suspend fun setPlan(
        choice: PlanChoice,
        period: String?,
        timing: Timing?,
        billing: PlanBilling?,
        until: Instant?,
        version: PlanVersion?,
        register: Boolean,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange =
        write(
            "setPlan",
            "choice" to choice,
            "period" to period,
            "timing" to timing,
            "billing" to billing,
            "until" to until,
            "version" to version,
        ) {
            Answers.planChange((choice as? com.entitlerhq.entitler.Plan)?.plan, until = until)
        }

    override suspend fun setAddOn(
        addOn: String,
        quantity: Long,
        sku: Sku?,
        replaces: String?,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange =
        write("setAddOn", "addOn" to addOn, "quantity" to quantity, "sku" to sku, "replaces" to replaces) {
            Answers.planChange(addOn, quantity)
        }

    override suspend fun grant(
        feature: Feature,
        value: FeatureValue?,
        days: Int?,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): GrantChange =
        write("grant", "feature" to feature, "value" to value, "days" to days, "actor" to actor, "reason" to reason) {
            Answers.grantChange(feature.key, value ?: FeatureValue.On, actor = actor)
        }

    override suspend fun revokeGrant(
        grantId: String,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): GrantChange = write("revokeGrant", "grantId" to grantId, "actor" to actor) { Answers.grantChange(revokedAt = Answers.AS_OF) }

    override suspend fun adjustMeter(
        feature: Feature.Metered,
        idempotencyKey: String,
        by: Long?,
        to: Long?,
        actor: String?,
        reason: String?,
        timeout: Duration?,
    ): UsageResult =
        write("adjustMeter", "feature" to feature, "by" to by, "to" to to, "idempotencyKey" to idempotencyKey) {
            require((by != null && by != 0L) != (to != null)) {
                "Pass either by, a whole number other than 0, or to, a whole number of 0 or more."
            }
            replayOr(idempotencyKey) {
                val meter = meterOf(feature.key)
                val before = meter.used
                meter.used = (to ?: (meter.used + checkNotNull(by))).coerceAtLeast(0)
                result(UsageOutcome.Adjusted, feature.key, 0, meterChange = meter.used - before)
            }
        }

    override suspend fun cancelUsage(
        usageId: String,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult =
        write("cancelUsage", "usageId" to usageId, "actor" to actor) {
            throw EntitlerApiException(NOT_FOUND, ErrorCode.NotFound, "There is no usage report $usageId.")
        }

    override fun toString(): String = "FakeCustomer(id=$id)"

    private companion object {
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val MAX_AMOUNT = 9_007_199_254_740_991L
    }
}

private class FakeHold(
    private val customer: Fake,
    override val result: UsageResult,
    private val feature: Feature.Metered,
    private val key: String,
) : Hold {
    override val id: String = checkNotNull(result.holdId)
    override val amount: Long = result.amount
    override val expiresAt: Instant? = result.expiresAt
    override val isDuplicate: Boolean = result.outcome == UsageOutcome.Duplicate
    private var first: UsageResult? = null

    override var used: Long? = null
        set(value) {
            require(value == null || value >= 0) { "Pass the amount used as a whole number of 0 or more." }
            field = value
        }

    private suspend fun act(action: suspend () -> UsageResult): UsageResult = first ?: action().also { first = it }

    override suspend fun finish(): UsageResult = act { settle(used ?: amount) }

    private suspend fun settle(total: Long): UsageResult {
        val settled = customer.settleUsage(id, minOf(total, amount))
        if (total > amount) customer.recordUsage(feature, total - amount, idempotencyKey = "$key:excess", mode = UsageMode.Observe)
        return settled
    }

    override suspend fun release(): UsageResult = act { customer.releaseUsage(id) }

    suspend fun dispose() {
        act { used?.let { settle(it) } ?: customer.releaseUsage(id) }
    }

    override fun close() {
        if (first == null) kotlinx.coroutines.runBlocking { dispose() }
    }

    override fun toString(): String = "FakeHold(id=$id, amount=$amount, used=$used)"
}
