package com.entitlerhq.entitler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration

/**
 * A customer on the server: a [Customer] plus registration, details, tokens, tracks, reads at another
 * instant, and the company's decisions.
 *
 * The company's decisions ([setPlan], [setAddOn], [grant], [revokeGrant], [adjustMeter], [cancelUsage])
 * are changes you make for a deal, a support ticket or a verified store purchase, under no self-serve
 * rules. Each takes `actor` (1 to 200 characters naming the person or system that decided, such as a
 * support agent's id or `hubspot`) and a `reason` of at most 200 characters, which the customer's
 * activity shows beside the key's name. Take each write's `idempotencyKey` from the event that caused it
 * (the webhook delivery, the ticket action), never from the object it changes.
 */
public interface ServerCustomer : Customer {
    /** The id your app uses for the customer. */
    override val id: String

    /**
     * Checks a metered feature as it reads at [asOf], through the cache under its own key. Needs the
     * organisation's `as_of` capability (`409 capability_required` otherwise).
     */
    public suspend fun check(
        feature: Feature.Metered,
        asOf: Instant,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): MeteredCheck

    /** Checks a feature as it reads at [asOf]; see the metered overload. */
    public suspend fun check(
        feature: Feature,
        asOf: Instant,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Check

    /** Checks the feature with [key] as it reads at [asOf]; see the metered overload. */
    public suspend fun check(
        key: String,
        asOf: Instant,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Check

    /** Whether the customer is entitled to [feature] at [asOf], answering [default] when the check fails. */
    public suspend fun isEntitled(
        feature: Feature,
        asOf: Instant,
        default: Boolean = false,
        revalidate: Boolean = false,
    ): Boolean

    /** Whether the customer is entitled to the feature with [key] at [asOf], answering [default] when the check fails. */
    public suspend fun isEntitled(
        key: String,
        asOf: Instant,
        default: Boolean = false,
        revalidate: Boolean = false,
    ): Boolean

    /**
     * The customer's entitlements as they read at [asOf]. As-of shows the effects of time on the plans,
     * grants and meters in place (renewals, booked moves, grant expiries, meter resets), never an unreleased
     * change: to preview one, put a test customer on a track for testers.
     */
    public suspend fun entitlements(
        asOf: Instant,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Entitlements

    /** The customer's plans as they read at [asOf]. */
    public suspend fun plans(
        asOf: Instant,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): CustomerPlans

    /** The customer's meters and usage log as they read at [asOf]. */
    public suspend fun usage(
        asOf: Instant,
        cursor: String? = null,
        timeout: Duration? = null,
    ): CustomerUsage

    /**
     * Registers the customer, or stores the details given for one who exists, `PUT /customers/{id}`.
     * Call it at sign-up and at sign-in with the latest details. Needs `customers:register`; changing an
     * existing customer's details needs `customers:write` or `customers:profile`.
     *
     * @param metadata Metadata to merge; a null value removes that key.
     * @param visitor The visitor id from before sign-up, so the customer keeps their experiment arm.
     */
    public suspend fun register(
        name: String? = null,
        email: String? = null,
        metadata: Map<String, String?>? = null,
        visitor: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): RegisteredCustomer

    /**
     * The customer's details, with a page of their usage log from [cursor], `GET /customers/{id}`.
     *
     * @param asOf Read them as they stand at that instant.
     */
    public suspend fun details(
        cursor: String? = null,
        asOf: Instant? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Changes the customer's details, `PATCH /customers/{id}`. A null metadata value removes that key. */
    public suspend fun update(
        name: String? = null,
        email: String? = null,
        metadata: Map<String, String?>? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerSummary

    /**
     * Erases the customer, `DELETE /customers/{id}?erase=true`: the account deletion app stores require. It
     * ends a Stripe subscription now, then erases their details, usage and events. Store subscriptions are
     * invisible to Entitler, so cancel them through Apple or Google first. Erasing twice is safe.
     */
    public suspend fun erase(
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    )

    /**
     * Mints a customer token for an in-app client, `POST /customers/{id}/tokens`. Needs `tokens:mint`.
     *
     * Without [scopes] it holds `entitlements:read` only. Ask for [Scope.UsageWrite] only when the app
     * records usage itself, and for [Scope.BillingSelf] only for people who may buy for the customer (a
     * workspace's owners, not every member).
     *
     * @param ttl How long it lasts, in whole seconds (rounded up) from 60 to 3,600; an hour by default.
     */
    public suspend fun token(
        scopes: List<Scope>? = null,
        ttl: Duration? = null,
        timeout: Duration? = null,
    ): IssuedCustomerToken

    /**
     * Moves the customer to the track named [track], the same in every environment, or back to All
     * customers with null, `PUT /customers/{id}/track`. Needs `tracks:assign`, which the server key preset
     * lacks.
     */
    public suspend fun setTrack(
        track: String?,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerTrack

    /**
     * The payment provider's view of the customer (status, the SKU it bills, drift), `GET
     * /customers/{id}/billing`, for support tools. Billing pages read [plans].
     */
    public suspend fun billing(timeout: Duration? = null): CustomerBilling

    /**
     * Moves the customer to [plan], by key or id, as the company decides, `PUT /customers/{id}/plan`, under no
     * self-serve rules. The plan they already hold, with the same period, billing and [until], changes
     * nothing and answers `changed` false.
     *
     * ```kotlin
     * customer.setPlan("enterprise", period = "yearly", billing = PlanBilling.End, actor = "hubspot", idempotencyKey = deal.eventId)
     * ```
     *
     * @param period The period, by its key, such as `yearly`.
     * @param timing [Timing.Now] or [Timing.End] (at renewal); the project's policy when null.
     * @param billing [PlanBilling.Provider] (the API's default), [PlanBilling.Keep] or [PlanBilling.End].
     * @param until When the customer returns to the product's default plan, unless a later change moves them.
     * @param register Register the customer first if they are not registered yet.
     */
    public suspend fun setPlan(
        plan: String,
        period: String? = null,
        timing: Timing? = null,
        billing: PlanBilling? = null,
        until: Instant? = null,
        register: Boolean = false,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): PlanChange

    /**
     * Moves the customer to a plan or to a SKU they bought, which names its own period. Record a store
     * purchase only from your server, after verifying it with Apple or Google, with [until] the
     * transaction's expiry, and again at each renewal.
     */
    public suspend fun setPlan(
        choice: PlanChoice,
        period: String? = null,
        timing: Timing? = null,
        billing: PlanBilling? = null,
        until: Instant? = null,
        register: Boolean = false,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): PlanChange

    /**
     * Sets the quantity of the add-on [addOn], `PUT /customers/{id}/add-ons/{addOn}`: adds it, sets its
     * quantity, or removes it at 0.
     *
     * @param quantity From 0 to 10,000.
     */
    public suspend fun setAddOn(
        addOn: String,
        quantity: Long,
        timing: Timing? = null,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): PlanChange

    /**
     * Grants the customer [feature], `POST /customers/{id}/grants`, answering the grant made so a support
     * tool keeps its id.
     *
     * @param value An amount from 0 to 999,999,999 or [FeatureValue.Unlimited]; null for an on/off feature.
     * @param days How long it lasts, from 0 to 3,650; 0 or null for no end.
     */
    public suspend fun grant(
        feature: Feature,
        value: FeatureValue? = null,
        days: Int? = null,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): GrantChange

    /** Revokes a grant, `DELETE /customers/{id}/grants/{grantId}`, answering the revoked grant. */
    public suspend fun revokeGrant(
        grantId: String,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): GrantChange

    /**
     * Moves the meter of [feature], `POST /customers/{id}/meters/{feature}/adjustments`: by [by] (`-500` gives
     * back 500 credits without racing new usage) or to [to]. Pass exactly one. The meter never goes below 0.
     *
     * @param idempotencyKey The adjustment's key, such as the support ticket action's id.
     */
    public suspend fun adjustMeter(
        feature: Feature.Metered,
        idempotencyKey: String,
        by: Long? = null,
        to: Long? = null,
        actor: String? = null,
        reason: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Takes a recorded usage report off the meter, as a correction, `DELETE /customers/{id}/usage/{usageId}`. */
    public suspend fun cancelUsage(
        usageId: String,
        actor: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult
}

internal class ServerCustomerImpl(
    engine: Engine,
    override val id: String,
) : CustomerImpl(engine, id, id),
    ServerCustomer {
    override suspend fun check(
        feature: Feature.Metered,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): MeteredCheck = meteredCheck(feature, revalidate, timeout, asOfOf(asOf))

    override suspend fun check(
        feature: Feature,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = check(feature.key, revalidate, timeout, asOfOf(asOf))

    override suspend fun check(
        key: String,
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Check = check(key, revalidate, timeout, asOfOf(asOf))

    override suspend fun isEntitled(
        feature: Feature,
        asOf: Instant,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(feature.key, default, revalidate, asOfOf(asOf))

    override suspend fun isEntitled(
        key: String,
        asOf: Instant,
        default: Boolean,
        revalidate: Boolean,
    ): Boolean = isEntitled(key, default, revalidate, asOfOf(asOf))

    override suspend fun entitlements(
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): Entitlements = cachedRead("/entitlements", Entitlements.serializer(), revalidate, timeout, asOf = asOfOf(asOf))

    override suspend fun plans(
        asOf: Instant,
        revalidate: Boolean,
        timeout: Duration?,
    ): CustomerPlans = cachedRead("/plans", CustomerPlans.serializer(), revalidate, timeout, asOf = asOfOf(asOf))

    override suspend fun usage(
        asOf: Instant,
        cursor: String?,
        timeout: Duration?,
    ): CustomerUsage {
        val instant = asOfOf(asOf)
        return read("/usage", CustomerUsage.serializer(), timeout, listOfNotNull(cursor?.let { "cursor" to it }), instant)
    }

    override suspend fun register(
        name: String?,
        email: String?,
        metadata: Map<String, String?>?,
        visitor: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): RegisteredCustomer {
        val details = detailsJson(name, email, metadata)
        return write(
            "PUT",
            "",
            RegisteredCustomer.serializer(),
            idempotencyKey,
            timeout,
            details.takeIf { it.isNotEmpty() },
            visitor = checkVisitor(visitor),
        )
    }

    override suspend fun details(
        cursor: String?,
        asOf: Instant?,
        timeout: Duration?,
    ): CustomerDetail {
        val instant = asOf?.let(::asOfOf)
        return read("", CustomerDetail.serializer(), timeout, listOfNotNull(cursor?.let { "cursor" to it }), instant)
    }

    override suspend fun update(
        name: String?,
        email: String?,
        metadata: Map<String, String?>?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerSummary = write("PATCH", "", CustomerSummary.serializer(), idempotencyKey, timeout, detailsJson(name, email, metadata))

    override suspend fun erase(
        idempotencyKey: String?,
        timeout: Duration?,
    ) {
        val request =
            ApiRequest("DELETE", base, listOf("erase" to "true"), idempotencyKey = checkIdempotencyKey(idempotencyKey), timeout = timeout)
        engine.ensureOpen()
        engine.bump(pathId)
        engine.send(request)
    }

    override suspend fun token(
        scopes: List<Scope>?,
        ttl: Duration?,
        timeout: Duration?,
    ): IssuedCustomerToken {
        val body =
            buildJsonObject {
                scopes?.let { put("scopes", scopesJson(it)) }
                ttl?.let { put("ttlSeconds", seconds(it)) }
            }
        return write("POST", "/tokens", IssuedCustomerToken.serializer(), null, timeout, body, changesAnswers = false)
    }

    override suspend fun setTrack(
        track: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerTrack {
        track?.let { require(it.isNotBlank()) { "Name the track by its name." } }
        val body = JsonObject(mapOf("track" to (track?.let(::JsonPrimitive) ?: JsonNull)))
        return write("PUT", "/track", CustomerTrack.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun billing(timeout: Duration?): CustomerBilling = read("/billing", CustomerBilling.serializer(), timeout)

    override suspend fun setPlan(
        plan: String,
        period: String?,
        timing: Timing?,
        billing: PlanBilling?,
        until: Instant?,
        register: Boolean,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange = setPlan(Plan(plan), period, timing, billing, until, register, actor, reason, idempotencyKey, timeout)

    override suspend fun setPlan(
        choice: PlanChoice,
        period: String?,
        timing: Timing?,
        billing: PlanBilling?,
        until: Instant?,
        register: Boolean,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange {
        val body =
            buildJsonObject {
                choiceJson(choice, period)
                timing?.let { put("when", it.value) }
                billing?.let { put("billing", it.value) }
                until?.let { put("until", formatInstant(it)) }
                if (register) put("register", true)
                decidedBy(actor, reason)
            }
        return write("PUT", "/plan", PlanChange.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun setAddOn(
        addOn: String,
        quantity: Long,
        timing: Timing?,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): PlanChange {
        val path = "/add-ons/" + pathSegment(checkPlan(addOn))
        val body =
            buildJsonObject {
                put("quantity", quantity)
                timing?.let { put("when", it.value) }
                decidedBy(actor, reason)
            }
        return write("PUT", path, PlanChange.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun grant(
        feature: Feature,
        value: FeatureValue?,
        days: Int?,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): GrantChange {
        val body =
            buildJsonObject {
                put("feature", checkId(feature.key, FEATURE_REQUIRED))
                grantValue(value)?.let { put("value", it) }
                days?.let { put("days", it) }
                decidedBy(actor, reason)
            }
        return write("POST", "/grants", GrantChange.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun revokeGrant(
        grantId: String,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): GrantChange {
        val path = "/grants/" + pathSegment(checkId(grantId, "Provide the id of the grant."))
        return write("DELETE", path, GrantChange.serializer(), idempotencyKey, timeout, decidedByJson(actor, reason))
    }

    override suspend fun adjustMeter(
        feature: Feature.Metered,
        idempotencyKey: String,
        by: Long?,
        to: Long?,
        actor: String?,
        reason: String?,
        timeout: Duration?,
    ): UsageResult {
        val byValid = by != null && to == null && by != 0L && by in -MAX_AMOUNT..MAX_AMOUNT
        val toValid = to != null && by == null && to in 0..MAX_AMOUNT
        require(byValid || toValid) { "Pass either by, a whole number other than 0, or to, a whole number of 0 or more." }
        checkIdempotencyKey(idempotencyKey)
        val path = "/meters/" + pathSegment(checkId(feature.key, FEATURE_REQUIRED)) + "/adjustments"
        val body =
            buildJsonObject {
                by?.let { put("by", it) }
                to?.let { put("to", it) }
                decidedBy(actor, reason)
            }
        return write("POST", path, UsageResult.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun cancelUsage(
        usageId: String,
        actor: String?,
        reason: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageResult {
        val path = "/usage/" + pathSegment(checkId(usageId, "Provide the id of the usage report."))
        return write("DELETE", path, UsageResult.serializer(), idempotencyKey, timeout, decidedByJson(actor, reason))
    }

    override fun toString(): String = "ServerCustomer(id=$id)"
}

private fun detailsJson(
    name: String?,
    email: String?,
    metadata: Map<String, String?>?,
): JsonObject =
    buildJsonObject {
        name?.let { put("name", it) }
        email?.let { put("email", it) }
        metadata?.let { put("metadata", JsonObject(it.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull })) }
    }

private fun grantValue(value: FeatureValue?): String? =
    when (value) {
        null, FeatureValue.On -> null
        is FeatureValue.Amount -> value.value.toString()
        FeatureValue.Unlimited -> "unlimited"
    }

private fun JsonObjectBuilder.decidedBy(
    actor: String?,
    reason: String?,
) {
    actor?.let { put("actor", it) }
    reason?.let { put("reason", it) }
}

private fun decidedByJson(
    actor: String?,
    reason: String?,
): JsonObject? = buildJsonObject { decidedBy(actor, reason) }.takeIf { it.isNotEmpty() }

private fun JsonObjectBuilder.choiceJson(
    choice: PlanChoice,
    period: String?,
) {
    when (choice) {
        is Plan -> {
            put("plan", choice.plan)
            period?.let { put("period", it) }
        }

        is Sku -> {
            require(period == null) { "A SKU names its own period; leave period out." }
            put(
                "sku",
                buildJsonObject {
                    put("connector", choice.connector)
                    put("ids", JsonObject(choice.ids.mapValues { (_, v) -> JsonPrimitive(v) as JsonElement }))
                },
            )
        }
    }
}
