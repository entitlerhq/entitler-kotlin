package com.entitlerhq.entitler

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A customer, as both clients see them: from [EntitlerServer.customer] on a server, or
 * [EntitlerClient.me] in an app. Gate features and record usage with one function that takes a
 * `Customer`.
 *
 * Every method that takes a `timeout` overrides the client's per-attempt timeout for that call. Every
 * write takes an `idempotencyKey`: pass one derived from your own unit of work (a job id, a message id)
 * so a retry from another process is recognised; otherwise the SDK generates one, which protects only
 * its own retries.
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
        timeout: Duration? = null,
    ): MeteredCheck

    /** Checks a feature, `GET /customers/{id}/entitlements/{feature}`, through the cache. */
    public suspend fun check(
        feature: Feature,
        timeout: Duration? = null,
    ): Check

    /** Checks the feature with [key], through the cache. */
    public suspend fun check(
        key: String,
        timeout: Duration? = null,
    ): Check

    /**
     * Whether the customer is entitled to [feature], answering [default] when the check fails for any
     * reason and passing that error to `onError`. It never fails because of Entitler.
     *
     * Fail closed (`default = false`) for paid features; fail open only where losing a sale is worse
     * than giving the feature away.
     *
     * ```kotlin
     * if (customer.isEntitled(Features.exportPdf)) exportPdf()
     * ```
     */
    public suspend fun isEntitled(
        feature: Feature,
        default: Boolean = false,
    ): Boolean

    /** Whether the customer is entitled to the feature with [key], as `isEntitled(Feature)`. */
    public suspend fun isEntitled(
        key: String,
        default: Boolean = false,
    ): Boolean

    /** The customer's entitlements, groups included, `GET /customers/{id}/entitlements`, through the cache. */
    public suspend fun entitlements(timeout: Duration? = null): Entitlements

    /**
     * The plans the customer holds and the plans they can move to, `GET /customers/{id}/plans`, through the
     * cache. On the in-app client it needs the organisation's customer portal capability.
     */
    public suspend fun plans(timeout: Duration? = null): CustomerPlans

    /** Pricing as this customer sees it, `GET /customers/{id}/pricing`, through the cache. */
    public suspend fun pricing(
        visitor: String? = null,
        timeout: Duration? = null,
    ): Pricing

    /** The customer's meters and a page of their usage log from [cursor], `GET /customers/{id}/usage`. */
    public suspend fun usage(
        cursor: String? = null,
        timeout: Duration? = null,
    ): CustomerUsage

    /** Every entry of the customer's usage log, fetching the next page only when collection reaches it. */
    public fun usageLog(timeout: Duration? = null): Flow<UsageEvent>

    /**
     * Records usage of a metered feature, `POST /customers/{id}/usage`. A refusal is an answer, not an
     * error: check [UsageResult.outcome].
     *
     * ```kotlin
     * customer.recordUsage(Features.aiCredits, 5, mode = UsageMode.Observe, idempotencyKey = job.id)
     * ```
     *
     * @param amount The amount used, a whole number from 1.
     * @param mode [UsageMode.Gate] (the API's default) or [UsageMode.Observe].
     * @param occurredAt When the usage happened, so it counts in that period.
     * @param register Register the customer first if they are not registered yet.
     */
    public suspend fun recordUsage(
        feature: Feature.Metered,
        amount: Long,
        mode: UsageMode? = null,
        occurredAt: Instant? = null,
        register: Boolean = false,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /**
     * Holds [amount] against the allowance until it is settled, released or expires,
     * `POST /customers/{id}/usage/holds`.
     *
     * @param ttl How long the hold lasts, in whole seconds from 1 to 3,600; the API's default is 5 minutes.
     */
    public suspend fun holdUsage(
        feature: Feature.Metered,
        amount: Long,
        ttl: Duration? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Settles a hold with the real [amount], from 0 to the amount held, `POST …/usage/holds/{holdId}/settle`. */
    public suspend fun settleUsage(
        holdId: String,
        amount: Long,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Releases a hold, `DELETE …/usage/holds/{holdId}`. Releasing twice is safe. */
    public suspend fun releaseUsage(
        holdId: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Reads a hold back, `GET …/usage/holds/{holdId}`. Needs `usage:read`. */
    public suspend fun hold(
        holdId: String,
        timeout: Duration? = null,
    ): UsageHold

    /**
     * Holds [amount], runs [work] with the hold, and settles the amount [work] reports on it.
     *
     * [work] sets [ActiveHold.used] to the total it really used (the held amount is settled when it never
     * does) and answers whatever the app needs, which `withHold` answers.
     *
     * - [work] runs only when the hold answers `held`, or replays one still open. A refused hold, or a key
     *   that replays a hold already settled, released or expired, throws [EntitlerUsageRefusedException].
     * - More than was held is recorded in observe mode under the hold's key plus `:excess`; when the hold
     *   expired while [work] ran, the whole amount is recorded that way.
     * - When [work] throws or is cancelled, the hold is released (a failed release goes to `onError`) and the
     *   error propagates.
     * - When settling or recording the excess fails, the call throws [EntitlerUsageSettlementException] with
     *   [work]'s result, so the app keeps its output and can settle again.
     *
     * The accounting happens exactly once, but [work] does not: two callers sharing a key at the same time
     * may both run it. Apps that need [work] itself to run once coordinate it themselves.
     *
     * ```kotlin
     * val summary =
     *     customer.withHold(Features.aiCredits, 100) { hold ->
     *         model.summarise(text).also { hold.used = it.creditsUsed }
     *     }
     * ```
     *
     * @param idempotencyKey The hold's key, 1 to 193 printable ASCII characters.
     */
    public suspend fun <T> withHold(
        feature: Feature.Metered,
        amount: Long,
        ttl: Duration? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
        work: suspend (hold: ActiveHold) -> T,
    ): T

    /**
     * Signs the customer's entitlements for offline use, `POST /customers/{id}/snapshots`.
     * Verify it with [verifySnapshot].
     *
     * @param ttl How long it lasts, in whole seconds (rounded up); by default, and at most, the project's offline days.
     */
    public suspend fun snapshot(
        ttl: Duration? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): IssuedSnapshot
}

/**
 * The hold `withHold` passes its work.
 *
 * @property holdId The hold's id.
 * @property amount The amount held.
 * @property expiresAt When the hold expires, or null when the answer did not say.
 */
public class ActiveHold internal constructor(
    public val holdId: String,
    public val amount: Long,
    public val expiresAt: Instant?,
) {
    /** The total the work really used, from 0; the held amount is settled when it is never set. */
    public var used: Long? = null
        set(value) {
            require(value == null || value in 0..MAX_AMOUNT) { "Pass the amount used as a whole number of 0 or more." }
            field = value
        }

    /** A string form naming the hold. */
    override fun toString(): String = "ActiveHold(holdId=$holdId, amount=$amount, used=$used)"
}

/** When a subscription change takes effect. */
public enum class ChangeTiming(
    internal val value: String,
) {
    /** Now. */
    Now("now"),

    /** At the end of the current period. */
    End("end"),
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
    if (visitor !=
        null
    ) {
        require(VISITOR_ID_REGEX.matches(visitor)) { "Pass visitor as an id of 16 to 64 letters, numbers, hyphens or underscores." }
    }
    return visitor
}

internal open class CustomerImpl(
    protected val engine: Engine,
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
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        timeout: Duration?,
        query: List<Pair<String, String>> = emptyList(),
        visitor: String? = null,
    ): T = engine.call(ApiRequest("GET", base + path, query, visitor = visitor, timeout = timeout), deserializer).also(::noteCustomer)

    protected suspend fun <T> cachedRead(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        timeout: Duration?,
        visitor: String? = null,
    ): T = engine.cached(ApiRequest("GET", base + path, visitor = visitor, timeout = timeout), pathId, deserializer).also(::noteCustomer)

    protected suspend fun <T> write(
        method: String,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        idempotencyKey: String?,
        timeout: Duration?,
        body: kotlinx.serialization.json.JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(),
        visitor: String? = null,
        changesAnswers: Boolean = true,
    ): T {
        val request = ApiRequest(method, base + path, query, body, checkIdempotencyKey(idempotencyKey), visitor, timeout)
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
                is UsageHold -> answer.customer
                else -> null
            }
        customer?.let(::named)
    }

    private fun featurePath(key: String) = "/entitlements/" + pathSegment(key)

    override suspend fun check(
        feature: Feature.Metered,
        timeout: Duration?,
    ): MeteredCheck = cachedRead(featurePath(feature.key), MeteredCheck.serializer(), timeout)

    override suspend fun check(
        feature: Feature,
        timeout: Duration?,
    ): Check = check(feature.key, timeout)

    override suspend fun check(
        key: String,
        timeout: Duration?,
    ): Check = cachedRead(featurePath(checkId(key, FEATURE_REQUIRED)), Check.serializer(), timeout)

    override suspend fun isEntitled(
        feature: Feature,
        default: Boolean,
    ): Boolean = isEntitled(feature.key, default)

    override suspend fun isEntitled(
        key: String,
        default: Boolean,
    ): Boolean {
        checkId(key, FEATURE_REQUIRED)
        pathSegment(key)
        return try {
            check(key).entitled
        } catch (error: CancellationException) {
            throw error
        } catch (error: EntitlerException) {
            engine.report(error)
            default
        }
    }

    override suspend fun entitlements(timeout: Duration?): Entitlements = cachedRead("/entitlements", Entitlements.serializer(), timeout)

    override suspend fun plans(timeout: Duration?): CustomerPlans = cachedRead("/plans", CustomerPlans.serializer(), timeout)

    override suspend fun pricing(
        visitor: String?,
        timeout: Duration?,
    ): Pricing = cachedRead("/pricing", Pricing.serializer(), timeout, checkVisitor(visitor))

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
        mode: UsageMode?,
        occurredAt: Instant?,
        register: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult {
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
        ttl: Duration?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult {
        checkAmount(amount)
        val body =
            buildJsonObject {
                put("feature", feature.key)
                put("amount", amount)
                ttl?.let { put("ttlSeconds", seconds(it)) }
            }
        return write("POST", "/usage/holds", UsageResult.serializer(), idempotencyKey, timeout, body)
    }

    private fun holdPath(holdId: String) = "/usage/holds/" + pathSegment(checkId(holdId, "Provide the id of the hold."))

    override suspend fun settleUsage(
        holdId: String,
        amount: Long,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult {
        require(amount in 0..MAX_AMOUNT) { "Pass amount as a whole number from 0 to the held amount." }
        return write(
            "POST",
            holdPath(holdId) + "/settle",
            UsageResult.serializer(),
            idempotencyKey,
            timeout,
            buildJsonObject {
                put("amount", amount)
            },
        )
    }

    override suspend fun releaseUsage(
        holdId: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult = write("DELETE", holdPath(holdId), UsageResult.serializer(), idempotencyKey, timeout)

    override suspend fun hold(
        holdId: String,
        timeout: Duration?,
    ): UsageHold = read(holdPath(holdId), UsageHold.serializer(), timeout)

    override suspend fun <T> withHold(
        feature: Feature.Metered,
        amount: Long,
        ttl: Duration?,
        idempotencyKey: String?,
        timeout: Duration?,
        work: suspend (hold: ActiveHold) -> T,
    ): T {
        val key = checkIdempotencyKey(idempotencyKey, MAX_HOLD_KEY_LENGTH) ?: newIdempotencyKey()
        checkAmount(amount)
        val held = holdUsage(feature, amount, ttl, key, timeout)
        val holdId = held.holdId
        if (holdId == null || !isOpen(held, holdId, timeout)) throw EntitlerUsageRefusedException(held)
        val active = ActiveHold(holdId, held.amount, held.expiresAt)
        var finished = false
        val result =
            try {
                work(active).also { finished = true }
            } finally {
                if (!finished) withContext(NonCancellable) { releaseQuietly(holdId) }
            }
        settle(feature, active, key, timeout, result)
        return result
    }

    private suspend fun isOpen(
        held: UsageResult,
        holdId: String,
        timeout: Duration?,
    ): Boolean =
        when (held.outcome) {
            UsageOutcome.Held -> true
            UsageOutcome.Duplicate -> hold(holdId, timeout).state == HoldState.Open
            else -> false
        }

    private suspend fun settle(
        feature: Feature.Metered,
        hold: ActiveHold,
        key: String,
        timeout: Duration?,
        result: Any?,
    ) {
        val used = hold.used ?: hold.amount
        val settled = minOf(used, hold.amount)
        var excess = (used - hold.amount).takeIf { it > 0 }
        try {
            try {
                settleUsage(hold.holdId, settled, timeout = timeout)
            } catch (error: EntitlerApiException) {
                if (error.code != ErrorCode.HoldExpired) throw error
                excess = used.takeIf { it > 0 }
            }
            excess?.let { recordUsage(feature, it, UsageMode.Observe, idempotencyKey = key + EXCESS, timeout = timeout) }
        } catch (error: EntitlerException) {
            throw EntitlerUsageSettlementException(hold.holdId, settled, excess, result, error)
        }
    }

    private suspend fun releaseQuietly(holdId: String) {
        try {
            releaseUsage(holdId, timeout = engine.timeout)
        } catch (error: EntitlerException) {
            engine.report(error)
        }
    }

    override suspend fun snapshot(
        ttl: Duration?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): IssuedSnapshot {
        val body = buildJsonObject { ttl?.let { put("ttlSeconds", seconds(it)) } }
        return write("POST", "/snapshots", IssuedSnapshot.serializer(), idempotencyKey, timeout, body, changesAnswers = false)
    }

    override fun toString(): String = "Customer(id=$id)"

    private companion object {
        const val EXCESS = ":excess"
    }
}
