package com.entitlerhq.entitler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A customer, as every client sees them: from [EntitlerServer.customer] on a server, or
 * [EntitlerClient.me] in an app. Gate features, record usage and offer the customer's own billing
 * choices with one function that takes a `Customer`.
 *
 * Every method that takes a `timeout` overrides the client's per-attempt timeout for that call. Reads
 * through the cache take `revalidate`, which skips a fresh kept answer and revalidates it, for a page
 * that knows the customer just changed. Usage writes require an `idempotencyKey` from your own unit of
 * work (a message, job or request id), never an object whose state changes: the API keeps each key for
 * ever, so a resend from anywhere records the event once.
 */
public interface Customer {
    /**
     * The id your app uses for the customer: given on the server; in an app, the customer the latest
     * answer named, or null before the first answer.
     */
    public val id: String?

    /**
     * Checks a metered feature, `GET /customers/{id}/entitlements/{feature}`, through the cache.
     *
     * ```kotlin
     * val credits = customer.check(Features.aiCredits)
     * println("${credits.used} used, ${credits.remaining} left")
     * ```
     */
    public suspend fun check(
        feature: Feature.Metered,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): MeteredCheck

    /** Checks a feature, `GET /customers/{id}/entitlements/{feature}`, through the cache. */
    public suspend fun check(
        feature: Feature,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Check

    /** Checks the feature with [key], through the cache. */
    public suspend fun check(
        key: String,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Check

    /**
     * Whether the customer is entitled to [feature], answering [default] when the check fails for any
     * reason and passing that error to `onError`. It never fails because of Entitler.
     *
     * Fail closed (`default = false`) for paid features; fail open only where losing a sale is worse
     * than giving the feature away. For a metered feature, entitled means allowance remains.
     *
     * ```kotlin
     * if (customer.isEntitled(Features.exportPdf)) exportPdf()
     * ```
     */
    public suspend fun isEntitled(
        feature: Feature,
        default: Boolean = false,
        revalidate: Boolean = false,
    ): Boolean

    /** Whether the customer is entitled to the feature with [key], as `isEntitled(Feature)`. */
    public suspend fun isEntitled(
        key: String,
        default: Boolean = false,
        revalidate: Boolean = false,
    ): Boolean

    /** The customer's entitlements, groups included, `GET /customers/{id}/entitlements`, through the cache. */
    public suspend fun entitlements(
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Entitlements

    /**
     * The plans the customer holds and the plans they can move to, `GET /customers/{id}/plans`, through the
     * cache: the one read for paywalls and billing pages. Each option's `action` decides its button, its
     * `skus` are the store products, and a held plan's `billedBy` decides between the store's own page and
     * [billingPortal]. On the in-app client it needs the organisation's customer portal capability.
     */
    public suspend fun plans(
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): CustomerPlans

    /** Pricing as this customer sees it, `GET /customers/{id}/pricing`, through the cache. */
    public suspend fun pricing(
        visitor: String? = null,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Pricing

    /** The customer's meters and a page of their usage log from [cursor], `GET /customers/{id}/usage`. */
    public suspend fun usage(
        cursor: String? = null,
        timeout: Duration? = null,
    ): CustomerUsage

    /** Every entry of the customer's usage log, fetching each page only when collection reaches it. */
    public fun usageLog(timeout: Duration? = null): Flow<UsageEvent>

    /**
     * Records usage of a metered feature, `POST /customers/{id}/usage`. A refusal is an answer, not an
     * error: check [UsageResult.outcome]. A resend with the same key answers `duplicate`, recording nothing.
     *
     * ```kotlin
     * customer.recordUsage(Features.aiCredits, 5, idempotencyKey = job.id, mode = UsageMode.Observe)
     * ```
     *
     * @param amount The amount used, a whole number from 1.
     * @param idempotencyKey The event's key, such as a message or job id.
     * @param mode [UsageMode.Gate] (the API's default) or [UsageMode.Observe].
     * @param occurredAt When the usage happened, so it counts in that period.
     * @param register Register the customer first if they are not registered yet.
     */
    public suspend fun recordUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        mode: UsageMode? = null,
        occurredAt: Instant? = null,
        register: Boolean = false,
        timeout: Duration? = null,
    ): UsageResult

    /**
     * Holds [amount] before work starts and answers the [Hold] that settles it, so a route can answer a
     * refusal before it streams. The hold counts against `remaining` until it is finished, released or
     * expires.
     *
     * ```kotlin
     * customer.startHold(Features.aiCredits, estimate, idempotencyKey = messageId).use { hold ->
     *     hold.used = 0
     *     stream.collect { chunk -> hold.used = chunk.totalTokens; send(chunk) }
     *     hold.finish()
     * }
     * ```
     *
     * @param idempotencyKey The hold's key, 1 to 193 printable ASCII characters.
     * @param ttl How long the hold lasts, in whole seconds from 1 to 3,600; the API's default is 5 minutes.
     * @throws EntitlerUsageRefusedException when no allowance remains or the customer is not entitled.
     * @throws EntitlerUsageReplayedException when the key replays a hold already settled, released or
     *   expired: the work already happened.
     */
    public suspend fun startHold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration? = null,
        timeout: Duration? = null,
    ): Hold

    /**
     * Holds [amount], runs [work] with the hold, and finishes it, answering [work]'s result.
     *
     * [work] sets [Hold.used] to the total it really used (the held amount is settled when it never does).
     * When [work] throws or is cancelled, the hold settles the amount reported so far, or is released when
     * nothing was reported, and the error propagates: a disconnect after 3,000 tokens charges for 3,000
     * tokens. When finishing fails, the call throws [EntitlerUsageSettlementException] carrying [work]'s
     * result.
     *
     * The accounting happens once, but [work] does not: read [Hold.isDuplicate] to learn that another
     * caller holds the same key and may be doing the same work.
     *
     * ```kotlin
     * val summary =
     *     customer.withHold(Features.aiCredits, 100, idempotencyKey = job.id) { hold ->
     *         model.summarise(text).also { hold.used = it.creditsUsed }
     *     }
     * ```
     *
     * @param idempotencyKey The hold's key, 1 to 193 printable ASCII characters.
     */
    public suspend fun <T> withHold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration? = null,
        timeout: Duration? = null,
        work: suspend (hold: Hold) -> T,
    ): T

    /**
     * Holds [amount], `POST /customers/{id}/usage/holds`, answering the hold's [UsageResult], for a hold
     * settled from another process with [settleUsage]. Prefer [startHold] in one process.
     */
    public suspend fun holdUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Settles a hold with the real [amount], from 0 to the amount held, `POST …/usage/holds/{holdId}/settle`. */
    public suspend fun settleUsage(
        holdId: String,
        amount: Long,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Releases a hold, recording nothing, `DELETE …/usage/holds/{holdId}`. Releasing twice is safe. */
    public suspend fun releaseUsage(
        holdId: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /**
     * Signs the customer's entitlements for offline use, `POST /customers/{id}/snapshots`. Verify it with
     * [verifySnapshot]. Snapshots are the only answers that survive a relaunch.
     *
     * @param ttl How long it lasts, in whole seconds (rounded up); by default, and at most, the project's offline days.
     */
    public suspend fun snapshot(
        ttl: Duration? = null,
        timeout: Duration? = null,
    ): IssuedSnapshot

    /**
     * Subscribes the customer to a plan or add-on they chose, `POST /customers/{id}/subscription`, under
     * self-serve rules, and answers the next step. The project's policies decide when the change takes
     * effect. In an app the credential needs `billing:self`.
     *
     * ```kotlin
     * when (val step = me.subscribe("pro", period = "monthly", returnUrl = "https://example.com/billing/return")) {
     *     is SubscribeStep.Done -> showPlan(step.change)
     *     is SubscribeStep.Pay -> openCustomTab(step.url)
     *     SubscribeStep.Confirming -> showPending()
     *     is SubscribeStep.Manage -> openStoreSubscriptions(step.billedBy)
     *     is SubscribeStep.Unknown -> showPending()
     * }
     * ```
     *
     * @param plan The plan or add-on, by key or id.
     * @param period The period, by its key, such as `monthly`.
     * @param quantity For a countable add-on, how many.
     * @param returnUrl Where the provider sends the customer back, paid or not: a page of yours, or an
     *   Android App Link. Needed only when the next step is a page (`400 return_url_required` otherwise), so
     *   pass one whenever Stripe may bill the customer.
     * @param register Register the customer first if they are not registered yet.
     */
    public suspend fun subscribe(
        plan: String,
        period: String? = null,
        quantity: Long? = null,
        returnUrl: String? = null,
        register: Boolean = false,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): SubscribeStep

    /**
     * Cancels the customer's plan, or the add-on [addOn] names, under self-serve rules,
     * `DELETE /customers/{id}/subscription`. Nothing to cancel answers `changed` false.
     *
     * @param addOn The add-on to cancel, by key or id, or null for the plan.
     * @param product The product whose plan to cancel, when the customer holds plans in several.
     */
    public suspend fun cancel(
        addOn: String? = null,
        product: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): PlanChange

    /**
     * Undoes the change booked for renewal, on the plan or the add-on [addOn] names,
     * `DELETE /customers/{id}/subscription/pending`. Nothing pending answers `changed` false. A
     * [BookedChange.Billing] change is booked only by the company, so this answers `403 not_self_serve` for
     * it; the company undoes it with [ServerCustomer.setPlan].
     */
    public suspend fun undoPendingChange(
        addOn: String? = null,
        product: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): PlanChange

    /**
     * Opens the provider's page for payment details and invoices, `POST /customers/{id}/billing-portal`. It
     * changes no plan. A customer the provider has never billed answers `404 not_found`, and an environment
     * with no payment provider connection answers `409 stale`.
     *
     * @param returnUrl Where the portal sends the customer back.
     */
    public suspend fun billingPortal(
        returnUrl: String,
        timeout: Duration? = null,
    ): ProviderPage

    /**
     * Reads the customer's state from the providers that bill them and follows it,
     * `POST /customers/{id}/billing/sync`. Call it on the page the provider returns to, then
     * `plans(revalidate = true)`, so the customer sees the plan they paid for at once.
     */
    public suspend fun syncBilling(timeout: Duration? = null): BillingSync
}

/**
 * A usage hold from [Customer.startHold]: report the amount used as the work goes, then [finish] it.
 *
 * Only the first of [finish], [release] and [close] acts; a later call answers the first one's result
 * without a request. Each runs to the end even when the caller is cancelled, so work cancelled halfway
 * still settles or releases.
 */
public interface Hold : Closeable {
    /** The hold's id. */
    public val id: String

    /** The amount held. */
    public val amount: Long

    /** When the hold expires on its own, recording nothing, or null when the answer did not say. */
    public val expiresAt: Instant?

    /** The hold's answer. */
    public val result: UsageResult

    /** True when another caller holds the same key and may be doing the same work. */
    public val isDuplicate: Boolean

    /**
     * The total really used so far, from 0, with no request; a later value replaces an earlier one. Work
     * that reports as it goes sets 0 before it starts, so finishing after a failure that used nothing frees
     * the whole hold.
     */
    public var used: Long?

    /**
     * Ends the work however it ended and charges for the work that happened: settles [used] (the held
     * amount when it was never set), up to the held amount, and records any excess in observe mode under
     * the hold's key plus `:excess`. When the hold expired first, the whole amount is recorded that way.
     * Answers the settlement, or the excess report when the hold had expired.
     *
     * @throws EntitlerUsageSettlementException when settling or recording the excess fails after its retries.
     */
    public suspend fun finish(): UsageResult

    /** Frees the hold, recording nothing, for work that never ran. */
    public suspend fun release(): UsageResult

    /**
     * Disposes of the hold when neither [finish] nor [release] ran: settles [used] when it was set, else
     * releases, in the client's own scope without blocking. A failure goes to `onError`; a hold left
     * unsettled expires on its own.
     */
    override fun close()
}

internal class HoldHandle(
    private val customer: CustomerImpl,
    private val feature: Feature.Metered,
    private val key: String,
    override val result: UsageResult,
) : Hold {
    override val id: String = checkNotNull(result.holdId)
    override val amount: Long = result.amount
    override val expiresAt: Instant? = result.expiresAt
    override val isDuplicate: Boolean = result.outcome == UsageOutcome.Duplicate

    private val started = AtomicBoolean(false)
    private val outcome = CompletableDeferred<Result<UsageResult>>()

    @Volatile
    override var used: Long? = null
        set(value) {
            require(value == null || value in 0..MAX_AMOUNT) { "Pass the amount used as a whole number of 0 or more." }
            field = value
        }

    override suspend fun finish(): UsageResult = act { settle() }

    override suspend fun release(): UsageResult = act { customer.releaseUsage(id, timeout = customer.engine.timeout) }

    override fun close() {
        if (started.get()) return
        customer.engine.scope.launch { dispose() }
    }

    suspend fun dispose() {
        runCatching { act { if (used == null) customer.releaseUsage(id, timeout = customer.engine.timeout) else settle() } }
            .onFailure { if (it !is CancellationException) customer.engine.report(it) }
    }

    private suspend fun act(action: suspend () -> UsageResult): UsageResult {
        if (started.compareAndSet(false, true)) {
            outcome.complete(withContext(NonCancellable) { runCatching { action() } })
        }
        return outcome.await().getOrThrow()
    }

    private suspend fun settle(): UsageResult {
        val reported = used ?: amount
        val settled = minOf(reported, amount)
        var excess = (reported - amount).takeIf { it > 0 }
        val timeout = customer.engine.timeout
        try {
            val settlement =
                try {
                    customer.settleUsage(id, settled, timeout = timeout)
                } catch (error: EntitlerApiException) {
                    if (error.code != ErrorCode.HoldExpired) throw error
                    excess = reported.takeIf { it > 0 }
                    null
                }
            val report = excess?.let { customer.recordUsage(feature, it, key + EXCESS, UsageMode.Observe, timeout = timeout) }
            return settlement ?: report ?: result
        } catch (error: EntitlerException) {
            throw EntitlerUsageSettlementException(id, settled, excess, null, error)
        }
    }

    override fun toString(): String = "Hold(id=$id, amount=$amount, used=$used)"

    private companion object {
        const val EXCESS = ":excess"
    }
}

internal const val MAX_AMOUNT = 9_007_199_254_740_991L
internal const val PLAN_REQUIRED = "Name the plan by its id or its key."
private const val MAX_HOLD_KEY_LENGTH = 193

internal fun checkPlan(plan: String): String = plan.also { require(it.isNotBlank()) { PLAN_REQUIRED } }

internal fun checkId(
    id: String,
    message: String,
): String = id.also { require(it.isNotBlank()) { message } }

internal fun checkCustomerId(id: String): String =
    id
        .also {
            require(it.isNotBlank()) { "Provide the id your app uses for the customer." }
        }.also(::pathSegment)

internal fun checkAmount(amount: Long): Long =
    amount.also {
        require(it in 1..MAX_AMOUNT) { "Pass amount as a whole number from 1 to $MAX_AMOUNT." }
    }

internal fun seconds(ttl: Duration): Long = (ttl.inWholeMilliseconds + MILLIS - 1).floorDiv(MILLIS).coerceAtLeast(1)

private const val MILLIS = 1000L

internal fun checkVisitor(visitor: String?): String? {
    if (visitor != null) {
        require(VISITOR_ID_REGEX.matches(visitor)) { "Pass visitor as an id of 16 to 64 letters, numbers, hyphens or underscores." }
    }
    return visitor
}

internal fun asOfOf(asOf: Instant): String {
    require(isInRange(asOf)) { "Pass asOf as a valid date." }
    return formatInstant(asOf)
}

internal open class CustomerImpl(
    val engine: Engine,
    protected val pathId: String,
    given: String?,
) : Customer {
    protected val base: String = "/customers/" + pathSegment(pathId)

    @Volatile private var latest: String? = given

    override val id: String? get() = latest

    fun named(id: String) {
        if (pathId == "me") latest = id
    }

    protected suspend fun <T> read(
        path: String,
        deserializer: DeserializationStrategy<T>,
        timeout: Duration?,
        query: List<Pair<String, String>> = emptyList(),
        asOf: String? = null,
    ): T =
        engine
            .call(ApiRequest("GET", base + path, query, timeout = timeout, asOf = asOf), deserializer)
            .also(::noteCustomer)

    protected suspend fun <T> cachedRead(
        path: String,
        deserializer: DeserializationStrategy<T>,
        revalidate: Boolean,
        timeout: Duration?,
        visitor: String? = null,
        asOf: String? = null,
    ): T =
        engine
            .cached(ApiRequest("GET", base + path, visitor = visitor, timeout = timeout, asOf = asOf), pathId, deserializer, revalidate)
            .also(::noteCustomer)

    @Suppress("LongParameterList")
    protected suspend fun <T> write(
        method: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        idempotencyKey: String?,
        timeout: Duration?,
        body: JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(),
        visitor: String? = null,
        changesAnswers: Boolean = true,
        maxKeyLength: Int = MAX_KEY_LENGTH,
    ): T {
        val request = ApiRequest(method, base + path, query, body, checkIdempotencyKey(idempotencyKey, maxKeyLength), visitor, timeout)
        engine.ensureOpen()
        if (changesAnswers) engine.bump(pathId)
        return engine.call(request, deserializer).also(::noteCustomer)
    }

    private fun noteCustomer(answer: Any?) {
        val customer =
            when (answer) {
                is Check -> answer.customer
                is MeteredCheck -> answer.customer
                is Entitlements -> answer.customer
                is CustomerPlans -> answer.customer
                is Pricing -> answer.customer
                is CustomerUsage -> answer.customer
                is UsageResult -> answer.customer
                else -> null
            }
        customer?.let(::named)
    }

    private fun featurePath(key: String) = "/entitlements/" + pathSegment(checkId(key, FEATURE_REQUIRED))

    protected suspend fun meteredCheck(
        feature: Feature.Metered,
        revalidate: Boolean,
        timeout: Duration?,
        asOf: String?,
    ): MeteredCheck = cachedRead(featurePath(feature.key), MeteredCheck.serializer(), revalidate, timeout, asOf = asOf)

    protected suspend fun check(
        key: String,
        revalidate: Boolean,
        timeout: Duration?,
        asOf: String?,
    ): Check = cachedRead(featurePath(key), Check.serializer(), revalidate, timeout, asOf = asOf)

    protected suspend fun isEntitled(
        key: String,
        default: Boolean,
        revalidate: Boolean,
        asOf: String?,
    ): Boolean {
        checkId(key, FEATURE_REQUIRED)
        pathSegment(key)
        return try {
            check(key, revalidate, null, asOf).entitled
        } catch (error: CancellationException) {
            throw error
        } catch (error: EntitlerException) {
            engine.report(error)
            default
        }
    }

    override suspend fun check(
        feature: Feature.Metered,
        revalidate: Boolean,
        timeout: Duration?,
    ): MeteredCheck = meteredCheck(feature, revalidate, timeout, null)

    override suspend fun check(
        feature: Feature,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = check(feature.key, revalidate, timeout, null)

    override suspend fun check(
        key: String,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = check(key, revalidate, timeout, null)

    override suspend fun isEntitled(
        feature: Feature,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(feature.key, default, revalidate, null)

    override suspend fun isEntitled(
        key: String,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(key, default, revalidate, null)

    override suspend fun entitlements(
        revalidate: Boolean,
        timeout: Duration?,
    ): Entitlements = cachedRead("/entitlements", Entitlements.serializer(), revalidate, timeout)

    override suspend fun plans(
        revalidate: Boolean,
        timeout: Duration?,
    ): CustomerPlans = cachedRead("/plans", CustomerPlans.serializer(), revalidate, timeout)

    override suspend fun pricing(
        visitor: String?,
        revalidate: Boolean,
        timeout: Duration?,
    ): Pricing = cachedRead("/pricing", Pricing.serializer(), revalidate, timeout, checkVisitor(visitor))

    override suspend fun usage(
        cursor: String?,
        timeout: Duration?,
    ): CustomerUsage = read("/usage", CustomerUsage.serializer(), timeout, listOfNotNull(cursor?.let { "cursor" to it }))

    override fun usageLog(timeout: Duration?): Flow<UsageEvent> =
        flow {
            var cursor: String? = null
            do {
                val log = usage(cursor, timeout).log
                log.items.forEach { emit(it) }
                cursor = log.next
            } while (cursor != null)
        }

    override suspend fun recordUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        mode: UsageMode?,
        occurredAt: Instant?,
        register: Boolean,
        timeout: Duration?,
    ): UsageResult {
        checkId(feature.key, FEATURE_REQUIRED)
        checkAmount(amount)
        val body =
            buildJsonObject {
                put("feature", feature.key)
                put("amount", amount)
                mode?.let { put("mode", it.value) }
                occurredAt?.let { put("occurredAt", formatInstant(it)) }
                if (register) put("register", true)
            }
        return write("POST", "/usage", UsageResult.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun holdUsage(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
    ): UsageResult = hold(feature, amount, idempotencyKey, ttl, timeout, MAX_KEY_LENGTH)

    private suspend fun hold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
        maxKeyLength: Int,
    ): UsageResult {
        checkId(feature.key, FEATURE_REQUIRED)
        checkIdempotencyKey(idempotencyKey, maxKeyLength)
        checkAmount(amount)
        val body =
            buildJsonObject {
                put("feature", feature.key)
                put("amount", amount)
                ttl?.let { put("ttlSeconds", seconds(it)) }
            }
        return write("POST", "/usage/holds", UsageResult.serializer(), idempotencyKey, timeout, body, maxKeyLength = maxKeyLength)
    }

    override suspend fun startHold(
        feature: Feature.Metered,
        amount: Long,
        idempotencyKey: String,
        ttl: Duration?,
        timeout: Duration?,
    ): Hold {
        val held = hold(feature, amount, idempotencyKey, ttl, timeout, MAX_HOLD_KEY_LENGTH)
        val open = held.outcome == UsageOutcome.Held || held.outcome == UsageOutcome.Duplicate
        return when {
            open && held.holdId != null -> HoldHandle(this, feature, idempotencyKey, held)
            held.outcome == UsageOutcome.Settled || held.outcome == UsageOutcome.Released -> throw EntitlerUsageReplayedException(held)
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
        val hold = startHold(feature, amount, idempotencyKey, ttl, timeout) as HoldHandle
        var finished = false
        val result =
            try {
                work(hold).also { finished = true }
            } finally {
                if (!finished) withContext(NonCancellable) { hold.dispose() }
            }
        try {
            hold.finish()
        } catch (error: EntitlerUsageSettlementException) {
            throw error.withResult(result)
        }
        return result
    }

    private fun holdPath(holdId: String) = "/usage/holds/" + pathSegment(checkId(holdId, "Provide the id of the hold."))

    override suspend fun settleUsage(
        holdId: String,
        amount: Long,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult {
        require(amount in 0..MAX_AMOUNT) { "Pass amount as a whole number from 0 to the held amount." }
        val body = buildJsonObject { put("amount", amount) }
        return write("POST", holdPath(holdId) + "/settle", UsageResult.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun releaseUsage(
        holdId: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult = write("DELETE", holdPath(holdId), UsageResult.serializer(), idempotencyKey, timeout)

    override suspend fun snapshot(
        ttl: Duration?,
        timeout: Duration?,
    ): IssuedSnapshot {
        val body = buildJsonObject { ttl?.let { put("ttlSeconds", seconds(it)) } }
        return write("POST", "/snapshots", IssuedSnapshot.serializer(), null, timeout, body, changesAnswers = false)
    }

    override suspend fun subscribe(
        plan: String,
        period: String?,
        quantity: Long?,
        returnUrl: String?,
        register: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): SubscribeStep {
        val body =
            buildJsonObject {
                put("plan", checkPlan(plan))
                period?.let { put("period", it) }
                quantity?.let { put("quantity", it) }
                returnUrl?.let { put("returnUrl", it) }
                if (register) put("register", true)
            }
        return write("POST", "/subscription", SubscribeStepSerializer, idempotencyKey, timeout, body)
    }

    private fun changePath(
        addOn: String?,
        product: String?,
        suffix: String,
    ): Pair<String, List<Pair<String, String>>> {
        require(addOn == null || product == null) { "Pass either addOn or product, not both." }
        return if (addOn != null) {
            "/subscription/add-ons/" + pathSegment(checkPlan(addOn)) + suffix to emptyList()
        } else {
            "/subscription$suffix" to listOfNotNull(product?.let { "product" to it })
        }
    }

    override suspend fun cancel(
        addOn: String?,
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange {
        val (path, query) = changePath(addOn, product, "")
        return write("DELETE", path, PlanChange.serializer(), idempotencyKey, timeout, query = query)
    }

    override suspend fun undoPendingChange(
        addOn: String?,
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange {
        val (path, query) = changePath(addOn, product, "/pending")
        return write("DELETE", path, PlanChange.serializer(), idempotencyKey, timeout, query = query)
    }

    override suspend fun billingPortal(
        returnUrl: String,
        timeout: Duration?,
    ): ProviderPage {
        val body = buildJsonObject { put("returnUrl", returnUrl) }
        return write("POST", "/billing-portal", ProviderPage.serializer(), null, timeout, body, changesAnswers = false)
    }

    override suspend fun syncBilling(timeout: Duration?): BillingSync =
        write("POST", "/billing/sync", BillingSync.serializer(), null, timeout)

    override fun toString(): String = "Customer(id=$id)"
}
