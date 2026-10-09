package com.entitlerhq.entitler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * A customer on the server: a [Customer] plus registration, details, tokens, tracks, self-serve
 * billing, and the vendor's actions under [vendor].
 *
 * Billing methods act as the customer choosing for themselves, so they send `selfServe: true`: the
 * change must be on a self-serve path among the plans the customer can move to, or the API answers
 * `403 not_self_serve`. Changes you make on the customer's behalf live under [vendor]. A period is named
 * by its label exactly as pricing shows it, such as `Monthly`.
 */
public interface ServerCustomer : Customer {
    /** The id your app uses for the customer. */
    override val id: String

    /** Changes the vendor makes on the customer's behalf, apart so they are never made by accident. */
    public val vendor: VendorActions

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

    /** The customer's details, with a page of their usage log from [cursor], `GET /customers/{id}`. */
    public suspend fun details(
        cursor: String? = null,
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
     * Deletes the customer, `DELETE /customers/{id}`. With [erase], their personal data is erased too, and
     * the answer's `externalId` reads `erased_<uuid>` rather than the id your app used.
     */
    public suspend fun delete(
        erase: Boolean = false,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerSummary

    /**
     * Mints a customer token for an in-app client, `POST /customers/{id}/tokens`. Needs `tokens:mint`.
     *
     * @param ttl How long it lasts, in whole seconds (rounded up) from 60 to 3,600; an hour by default.
     */
    public suspend fun token(
        scopes: List<Scope>? = null,
        ttl: Duration? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): IssuedCustomerToken

    /** Moves the customer to the track with [trackId], or back to All customers with null, `PUT /customers/{id}/track`. */
    public suspend fun setTrack(
        trackId: String?,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerTrack

    /** Subscribes the customer to [plan], by key or id, self-serve, `POST /customers/{id}/subscription`. */
    public suspend fun subscribe(
        plan: String,
        period: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Subscribes the customer to a plan or a SKU they bought (which names its own period), self-serve. */
    public suspend fun subscribe(
        choice: PlanChoice,
        period: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Starts a self-serve checkout for [plan], `POST /customers/{id}/checkout`: send the customer to its URL. */
    public suspend fun checkout(
        plan: String,
        successUrl: String,
        cancelUrl: String,
        period: String? = null,
        connection: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): ProviderPage

    /** Starts a self-serve checkout for a plan or a SKU. */
    public suspend fun checkout(
        choice: PlanChoice,
        successUrl: String,
        cancelUrl: String,
        period: String? = null,
        connection: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): ProviderPage

    /** Cancels the subscription, `DELETE /customers/{id}/subscription`. */
    public suspend fun cancel(
        timing: ChangeTiming? = null,
        product: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Undoes a change booked for the period's end, `DELETE /customers/{id}/subscription/pending`. */
    public suspend fun undoPendingChange(
        product: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Adds the add-on [plan], self-serve, `POST /customers/{id}/subscription/add-ons`. */
    public suspend fun addAddOn(
        plan: String,
        quantity: Int? = null,
        replaces: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Adds an add-on, or the add-on a SKU they bought sells, self-serve. */
    public suspend fun addAddOn(
        choice: PlanChoice,
        quantity: Int? = null,
        replaces: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Changes how many of the add-on [plan] the customer holds, self-serve. */
    public suspend fun setAddOnQuantity(
        plan: String,
        quantity: Int,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Removes the add-on [plan], `DELETE /customers/{id}/subscription/add-ons/{plan}`. */
    public suspend fun removeAddOn(
        plan: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Undoes an add-on change booked for the period's end. */
    public suspend fun undoAddOnChange(
        plan: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** The customer's billing at their payment provider, `GET /customers/{id}/billing`. */
    public suspend fun billing(timeout: Duration? = null): CustomerBilling

    /** Opens the payment provider's billing portal, `POST /customers/{id}/billing-portal`: send the customer to its URL. */
    public suspend fun billingPortal(
        returnUrl: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): ProviderPage

    /** The customer's state at every payment provider connection, `GET /customers/{id}/providers`. */
    public suspend fun providers(timeout: Duration? = null): CustomerProviders
}

/**
 * Changes the vendor makes on a customer's behalf. The subscription and add-on methods send
 * `selfServe: false`, so the vendor may move the customer anywhere, sales-led plans included.
 */
public interface VendorActions {
    /** Subscribes the customer to [plan], anywhere in the catalogue. */
    public suspend fun subscribe(
        plan: String,
        period: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Subscribes the customer to a plan or a SKU, anywhere in the catalogue. */
    public suspend fun subscribe(
        choice: PlanChoice,
        period: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Moves the customer to [plan] in Entitler only, while the payment provider keeps billing the plan they held. */
    public suspend fun override(
        plan: String,
        period: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Ends an override, `DELETE /customers/{id}/subscription/override`. */
    public suspend fun undoOverride(
        product: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Starts a checkout for [plan], anywhere in the catalogue. */
    public suspend fun checkout(
        plan: String,
        successUrl: String,
        cancelUrl: String,
        period: String? = null,
        connection: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): ProviderPage

    /** Starts a checkout for a plan or a SKU, anywhere in the catalogue. */
    public suspend fun checkout(
        choice: PlanChoice,
        successUrl: String,
        cancelUrl: String,
        period: String? = null,
        connection: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): ProviderPage

    /** Adds the add-on [plan], anywhere in the catalogue. */
    public suspend fun addAddOn(
        plan: String,
        quantity: Int? = null,
        replaces: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Adds an add-on, or the add-on a SKU sells, anywhere in the catalogue. */
    public suspend fun addAddOn(
        choice: PlanChoice,
        quantity: Int? = null,
        replaces: String? = null,
        timing: ChangeTiming? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Changes how many of the add-on [plan] the customer holds. */
    public suspend fun setAddOnQuantity(
        plan: String,
        quantity: Int,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /**
     * Grants [feature] to the customer, `POST /customers/{id}/grants`.
     *
     * ```kotlin
     * customer.vendor.grant(Features.sso, days = 30, reason = "Trial for the pilot")
     * ```
     *
     * @param value The value to grant: an amount from 0 to 999,999,999, or unlimited; leave it out for an on/off feature.
     * @param days How long it lasts, 0 to 3,650; leave it out, or pass 0, for no end.
     */
    public suspend fun grant(
        feature: Feature,
        value: FeatureValue? = null,
        days: Int? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Grants the feature with [key], as `grant(Feature)`. */
    public suspend fun grant(
        key: String,
        value: FeatureValue? = null,
        days: Int? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Revokes a grant, `DELETE /customers/{id}/grants/{grantId}`. */
    public suspend fun revokeGrant(
        grantId: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail

    /** Sets how much of [feature] the customer has used this period, `PUT /customers/{id}/meters/{feature}`. */
    public suspend fun setMeter(
        feature: Feature.Metered,
        used: Long,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult

    /** Takes a recorded usage report off the meter, as a correction, `DELETE /customers/{id}/usage/{usageId}`. */
    public suspend fun cancelUsage(
        usageId: String,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageResult
}

internal class ServerCustomerImpl(
    engine: Engine,
    override val id: String,
) : CustomerImpl(engine, id, id),
    ServerCustomer {
    override val vendor: VendorActions = Vendor()

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
            details.takeIf {
                it.isNotEmpty()
            },
            visitor = checkVisitor(visitor),
        )
    }

    override suspend fun details(
        cursor: String?,
        timeout: Duration?,
    ): CustomerDetail = read("", CustomerDetail.serializer(), timeout, listOfNotNull(cursor?.let { "cursor" to it }))

    override suspend fun update(
        name: String?,
        email: String?,
        metadata: Map<String, String?>?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerSummary = write("PATCH", "", CustomerSummary.serializer(), idempotencyKey, timeout, detailsJson(name, email, metadata))

    override suspend fun delete(
        erase: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerSummary =
        write(
            "DELETE",
            "",
            CustomerSummary.serializer(),
            idempotencyKey,
            timeout,
            query = if (erase) listOf("erase" to "true") else emptyList(),
        )

    override suspend fun token(
        scopes: List<Scope>?,
        ttl: Duration?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): IssuedCustomerToken {
        val body =
            buildJsonObject {
                scopes?.let { put("scopes", scopesJson(it)) }
                ttl?.let { put("ttlSeconds", seconds(it)) }
            }
        return write("POST", "/tokens", IssuedCustomerToken.serializer(), idempotencyKey, timeout, body, changesAnswers = false)
    }

    override suspend fun setTrack(
        trackId: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerTrack {
        val body = JsonObject(mapOf("trackId" to (trackId?.let(::JsonPrimitive) ?: JsonNull)))
        return write("PUT", "/track", CustomerTrack.serializer(), idempotencyKey, timeout, body)
    }

    override suspend fun subscribe(
        plan: String,
        period: String?,
        timing: ChangeTiming?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = subscribe(Plan(plan), period, timing, idempotencyKey, timeout)

    override suspend fun subscribe(
        choice: PlanChoice,
        period: String?,
        timing: ChangeTiming?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = subscription(choiceJson(choice, period, timing, selfServe = true), idempotencyKey, timeout)

    override suspend fun checkout(
        plan: String,
        successUrl: String,
        cancelUrl: String,
        period: String?,
        connection: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): ProviderPage = checkout(Plan(plan), successUrl, cancelUrl, period, connection, idempotencyKey, timeout)

    override suspend fun checkout(
        choice: PlanChoice,
        successUrl: String,
        cancelUrl: String,
        period: String?,
        connection: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): ProviderPage =
        checkout(
            choiceJson(choice, period, null, selfServe = true),
            successUrl,
            cancelUrl,
            connection,
            idempotencyKey,
            timeout,
        )

    override suspend fun cancel(
        timing: ChangeTiming?,
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail =
        write(
            "DELETE",
            "/subscription",
            CustomerDetail.serializer(),
            idempotencyKey,
            timeout,
            query = listOfNotNull(product?.let { "product" to it }, timing?.let { "when" to it.value }),
        )

    override suspend fun undoPendingChange(
        product: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail =
        write(
            "DELETE",
            "/subscription/pending",
            CustomerDetail.serializer(),
            idempotencyKey,
            timeout,
            query = productQuery(product),
        )

    override suspend fun addAddOn(
        plan: String,
        quantity: Int?,
        replaces: String?,
        timing: ChangeTiming?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = addAddOn(Plan(plan), quantity, replaces, timing, idempotencyKey, timeout)

    override suspend fun addAddOn(
        choice: PlanChoice,
        quantity: Int?,
        replaces: String?,
        timing: ChangeTiming?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = addOn(choiceJson(choice, null, timing, selfServe = true), quantity, replaces, idempotencyKey, timeout)

    override suspend fun setAddOnQuantity(
        plan: String,
        quantity: Int,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = addOnQuantity(plan, quantity, selfServe = true, idempotencyKey, timeout)

    override suspend fun removeAddOn(
        plan: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = write("DELETE", addOnPath(plan), CustomerDetail.serializer(), idempotencyKey, timeout)

    override suspend fun undoAddOnChange(
        plan: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = write("DELETE", addOnPath(plan) + "/pending", CustomerDetail.serializer(), idempotencyKey, timeout)

    override suspend fun billing(timeout: Duration?): CustomerBilling = read("/billing", CustomerBilling.serializer(), timeout)

    override suspend fun billingPortal(
        returnUrl: String,
        idempotencyKey: String?,
        timeout: Duration?,
    ): ProviderPage =
        write(
            "POST",
            "/billing-portal",
            ProviderPage.serializer(),
            idempotencyKey,
            timeout,
            buildJsonObject { put("returnUrl", returnUrl) },
            changesAnswers = false,
        )

    override suspend fun providers(timeout: Duration?): CustomerProviders = read("/providers", CustomerProviders.serializer(), timeout)

    override fun toString(): String = "ServerCustomer(id=$id)"

    private suspend fun subscription(
        body: JsonObject,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail = write("POST", "/subscription", CustomerDetail.serializer(), idempotencyKey, timeout, body)

    private suspend fun checkout(
        choice: JsonObject,
        successUrl: String,
        cancelUrl: String,
        connection: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): ProviderPage {
        val body =
            JsonObject(
                choice +
                    buildJsonObject {
                        put("successUrl", successUrl)
                        put("cancelUrl", cancelUrl)
                        connection?.let { put("connection", it) }
                    },
            )
        return write("POST", "/checkout", ProviderPage.serializer(), idempotencyKey, timeout, body, changesAnswers = false)
    }

    private suspend fun addOn(
        choice: JsonObject,
        quantity: Int?,
        replaces: String?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail {
        val extra =
            buildJsonObject {
                quantity?.let { put("quantity", it) }
                replaces?.let { put("replaces", it) }
            }
        return write("POST", "/subscription/add-ons", CustomerDetail.serializer(), idempotencyKey, timeout, JsonObject(choice + extra))
    }

    private suspend fun addOnQuantity(
        plan: String,
        quantity: Int,
        selfServe: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail {
        val body =
            buildJsonObject {
                put("quantity", quantity)
                put("selfServe", selfServe)
            }
        return write("PATCH", addOnPath(plan), CustomerDetail.serializer(), idempotencyKey, timeout, body)
    }

    private fun addOnPath(plan: String) = "/subscription/add-ons/" + pathSegment(checkPlan(plan))

    private fun productQuery(product: String?) = listOfNotNull(product?.let { "product" to it })

    private inner class Vendor : VendorActions {
        override suspend fun subscribe(
            plan: String,
            period: String?,
            timing: ChangeTiming?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = subscribe(Plan(plan), period, timing, idempotencyKey, timeout)

        override suspend fun subscribe(
            choice: PlanChoice,
            period: String?,
            timing: ChangeTiming?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = subscription(choiceJson(choice, period, timing, selfServe = false), idempotencyKey, timeout)

        override suspend fun override(
            plan: String,
            period: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail {
            val body = JsonObject(choiceJson(Plan(plan), period, null, selfServe = false) + ("override" to JsonPrimitive(true)))
            return subscription(body, idempotencyKey, timeout)
        }

        override suspend fun undoOverride(
            product: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail =
            write("DELETE", "/subscription/override", CustomerDetail.serializer(), idempotencyKey, timeout, query = productQuery(product))

        override suspend fun checkout(
            plan: String,
            successUrl: String,
            cancelUrl: String,
            period: String?,
            connection: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): ProviderPage = checkout(Plan(plan), successUrl, cancelUrl, period, connection, idempotencyKey, timeout)

        override suspend fun checkout(
            choice: PlanChoice,
            successUrl: String,
            cancelUrl: String,
            period: String?,
            connection: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): ProviderPage =
            checkout(
                choiceJson(choice, period, null, selfServe = false),
                successUrl,
                cancelUrl,
                connection,
                idempotencyKey,
                timeout,
            )

        override suspend fun addAddOn(
            plan: String,
            quantity: Int?,
            replaces: String?,
            timing: ChangeTiming?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = addAddOn(Plan(plan), quantity, replaces, timing, idempotencyKey, timeout)

        override suspend fun addAddOn(
            choice: PlanChoice,
            quantity: Int?,
            replaces: String?,
            timing: ChangeTiming?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = addOn(choiceJson(choice, null, timing, selfServe = false), quantity, replaces, idempotencyKey, timeout)

        override suspend fun setAddOnQuantity(
            plan: String,
            quantity: Int,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = addOnQuantity(plan, quantity, selfServe = false, idempotencyKey, timeout)

        override suspend fun grant(
            feature: Feature,
            value: FeatureValue?,
            days: Int?,
            reason: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail = grant(feature.key, value, days, reason, idempotencyKey, timeout)

        override suspend fun grant(
            key: String,
            value: FeatureValue?,
            days: Int?,
            reason: String?,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail {
            val body =
                buildJsonObject {
                    put("feature", checkId(key, FEATURE_REQUIRED))
                    grantValue(value)?.let { put("value", it) }
                    days?.let { put("days", it) }
                    reason?.let { put("reason", it) }
                }
            return write("POST", "/grants", CustomerDetail.serializer(), idempotencyKey, timeout, body)
        }

        override suspend fun revokeGrant(
            grantId: String,
            idempotencyKey: String?,
            timeout: Duration?,
        ): CustomerDetail =
            write(
                "DELETE",
                "/grants/" + pathSegment(checkId(grantId, "Provide the id of the grant.")),
                CustomerDetail.serializer(),
                idempotencyKey,
                timeout,
            )

        override suspend fun setMeter(
            feature: Feature.Metered,
            used: Long,
            idempotencyKey: String?,
            timeout: Duration?,
        ): UsageResult =
            write(
                "PUT",
                "/meters/" + pathSegment(feature.key),
                UsageResult.serializer(),
                idempotencyKey,
                timeout,
                buildJsonObject {
                    put("used", used)
                },
            )

        override suspend fun cancelUsage(
            usageId: String,
            idempotencyKey: String?,
            timeout: Duration?,
        ): UsageResult =
            write(
                "DELETE",
                "/usage/" + pathSegment(checkId(usageId, "Provide the id of the usage report.")),
                UsageResult.serializer(),
                idempotencyKey,
                timeout,
            )
    }
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

private fun JsonObjectBuilder.timingAndSelfServe(
    timing: ChangeTiming?,
    selfServe: Boolean,
) {
    timing?.let { put("when", it.value) }
    put("selfServe", selfServe)
}

private fun choiceJson(
    choice: PlanChoice,
    period: String?,
    timing: ChangeTiming?,
    selfServe: Boolean,
): JsonObject =
    buildJsonObject {
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
        timingAndSelfServe(timing, selfServe)
    }
