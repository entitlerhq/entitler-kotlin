package com.entitlerhq.entitler.testing

import com.entitlerhq.entitler.BillingSync
import com.entitlerhq.entitler.Check
import com.entitlerhq.entitler.CustomerBilling
import com.entitlerhq.entitler.CustomerDetail
import com.entitlerhq.entitler.CustomerKind
import com.entitlerhq.entitler.CustomerPage
import com.entitlerhq.entitler.CustomerPlans
import com.entitlerhq.entitler.CustomerSummary
import com.entitlerhq.entitler.CustomerTrack
import com.entitlerhq.entitler.CustomerUsage
import com.entitlerhq.entitler.Effective
import com.entitlerhq.entitler.Entitlement
import com.entitlerhq.entitler.EntitlementSource
import com.entitlerhq.entitler.Entitlements
import com.entitlerhq.entitler.Environment
import com.entitlerhq.entitler.EnvironmentKind
import com.entitlerhq.entitler.FeatureList
import com.entitlerhq.entitler.FeatureListing
import com.entitlerhq.entitler.FeatureType
import com.entitlerhq.entitler.FeatureUsage
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.Grant
import com.entitlerhq.entitler.GrantChange
import com.entitlerhq.entitler.HeldPlan
import com.entitlerhq.entitler.IssuedCustomerToken
import com.entitlerhq.entitler.IssuedSnapshot
import com.entitlerhq.entitler.Limit
import com.entitlerhq.entitler.MeteredCheck
import com.entitlerhq.entitler.MoveOption
import com.entitlerhq.entitler.PlanChange
import com.entitlerhq.entitler.PlanSummary
import com.entitlerhq.entitler.Pricing
import com.entitlerhq.entitler.PricingPlan
import com.entitlerhq.entitler.ProviderPage
import com.entitlerhq.entitler.PublishedProduct
import com.entitlerhq.entitler.RegisteredCustomer
import com.entitlerhq.entitler.Scope
import com.entitlerhq.entitler.SnapshotKey
import com.entitlerhq.entitler.SnapshotKeys
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.Track
import com.entitlerhq.entitler.Upgrade
import com.entitlerhq.entitler.UsageEvent
import com.entitlerhq.entitler.UsageEventResult
import com.entitlerhq.entitler.UsageEventsResult
import com.entitlerhq.entitler.UsageLog
import com.entitlerhq.entitler.UsageMode
import com.entitlerhq.entitler.UsageOutcome
import com.entitlerhq.entitler.UsageRefusal
import com.entitlerhq.entitler.UsageResult
import com.entitlerhq.entitler.UsageSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Builders for complete Entitler answers, for tests that fake the HTTP transport: every builder fills
 * each field the API documents, so an answer never fails to decode because a fixture missed one.
 *
 * Each builder answers the typed value; [body] writes any of them as the JSON the API sends.
 *
 * ```kotlin
 * server.enqueue(MockResponse(body = Answers.body(Answers.check(feature = "export_pdf", entitled = true))))
 * ```
 */
public object Answers {
    /** The instant every builder answers for unless given another. */
    public val AS_OF: Instant = Instant.parse("2026-07-01T00:00:00Z")

    /** The test environment every builder answers in. */
    public val ENVIRONMENT: Environment = Environment("e_test", "Development", EnvironmentKind.Test)

    /** The All customers track every builder answers on. */
    public val TRACK: Track = Track("t_all", "All customers")

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    /** The JSON body the API sends for a [Check]. */
    public fun body(value: Check): String = encode(Check.serializer(), value)

    /** The JSON body the API sends for a [MeteredCheck]. */
    public fun body(value: MeteredCheck): String = encode(MeteredCheck.serializer(), value)

    /** The JSON body the API sends for [Entitlements]. */
    public fun body(value: Entitlements): String = encode(Entitlements.serializer(), value)

    /** The JSON body the API sends for [CustomerPlans]. */
    public fun body(value: CustomerPlans): String = encode(CustomerPlans.serializer(), value)

    /** The JSON body the API sends for [Pricing]. */
    public fun body(value: Pricing): String = encode(Pricing.serializer(), value)

    /** The JSON body the API sends for a [FeatureList]. */
    public fun body(value: FeatureList): String = encode(FeatureList.serializer(), value)

    /** The JSON body the API sends for [CustomerUsage]. */
    public fun body(value: CustomerUsage): String = encode(CustomerUsage.serializer(), value)

    /** The JSON body the API sends for a [UsageResult]. */
    public fun body(value: UsageResult): String = encode(UsageResult.serializer(), value)

    /** The JSON body the API sends for a batch's [UsageEventsResult]. */
    public fun body(value: UsageEventsResult): String = encode(UsageEventsResult.serializer(), value)

    /** The JSON body the API sends for a [CustomerDetail]. */
    public fun body(value: CustomerDetail): String = encode(CustomerDetail.serializer(), value)

    /** The JSON body the API sends for a [CustomerSummary]. */
    public fun body(value: CustomerSummary): String = encode(CustomerSummary.serializer(), value)

    /** The JSON body the API sends for a [CustomerPage]. */
    public fun body(value: CustomerPage): String = encode(CustomerPage.serializer(), value)

    /** The JSON body the API sends for a [RegisteredCustomer]. */
    public fun body(value: RegisteredCustomer): String = encode(RegisteredCustomer.serializer(), value)

    /** The JSON body the API sends for an [IssuedCustomerToken]. */
    public fun body(value: IssuedCustomerToken): String = encode(IssuedCustomerToken.serializer(), value)

    /** The JSON body the API sends for an [IssuedSnapshot]. */
    public fun body(value: IssuedSnapshot): String = encode(IssuedSnapshot.serializer(), value)

    /** The JSON body the API sends for [SnapshotKeys]. */
    public fun body(value: SnapshotKeys): String = encode(SnapshotKeys.serializer(), value)

    /** The JSON body the API sends for a [CustomerTrack]. */
    public fun body(value: CustomerTrack): String = encode(CustomerTrack.serializer(), value)

    /** The JSON body the API sends for [CustomerBilling]. */
    public fun body(value: CustomerBilling): String = encode(CustomerBilling.serializer(), value)

    /** The JSON body the API sends for a [ProviderPage]. */
    public fun body(value: ProviderPage): String = encode(ProviderPage.serializer(), value)

    /** The JSON body the API sends for a [BillingSync]. */
    public fun body(value: BillingSync): String = encode(BillingSync.serializer(), value)

    /** The JSON body the API sends for a [PlanChange]. */
    public fun body(value: PlanChange): String = encode(PlanChange.serializer(), value)

    /** The JSON body the API sends for a [SubscribeStep]. */
    public fun body(value: SubscribeStep): String = encode(SubscribeStep.serializer(), value)

    /** The JSON body the API sends for a [GrantChange]. */
    public fun body(value: GrantChange): String = encode(GrantChange.serializer(), value)

    /** The JSON body `GET /keys/self` sends for a credential holding [scopes]. */
    public fun scopesBody(
        scopes: List<Scope>,
        registration: Boolean? = null,
    ): String =
        JsonObject(
            mapOf(
                "scopes" to JsonArray(scopes.map { JsonPrimitive(it.value) }),
                "registration" to (registration?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ).toString()

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        return withoutMarks(element).toString()
    }

    private fun withoutMarks(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonObject(element - setOf("stale", "replayed")) else element

    /** A check of [feature]; a metered one also carries [used], [held], [remaining] and [resetsAt]. */
    public fun check(
        feature: String = "export_pdf",
        entitled: Boolean = true,
        value: FeatureValue = if (entitled) FeatureValue.On else FeatureValue.Amount(0),
        type: FeatureType = typeOf(value),
        customer: String = "user_1",
        used: Long? = null,
        held: Long? = null,
        remaining: Limit? = null,
        resetsAt: Instant? = null,
        upgrades: List<Upgrade> = emptyList(),
        asOf: Instant = AS_OF,
    ): Check =
        Check(
            customer = customer,
            feature = feature,
            type = type,
            entitled = entitled,
            value = value,
            sources = listOf(planSource()),
            used = used,
            held = held,
            remaining = remaining,
            resetsAt = resetsAt,
            upgrades = upgrades,
            asOf = asOf,
            environment = ENVIRONMENT,
            track = TRACK,
            release = 1,
            testers = true,
        )

    /** A check of a metered [feature] with its meter. */
    public fun meteredCheck(
        feature: String = "ai_credits",
        value: FeatureValue = FeatureValue.Amount(DEFAULT_ALLOWANCE),
        used: Long = 0,
        held: Long = 0,
        remaining: Limit = remainingOf(value, used, held),
        entitled: Boolean = remaining != FeatureValue.Amount(0),
        customer: String = "user_1",
        resetsAt: Instant? = null,
        upgrades: List<Upgrade> = emptyList(),
        asOf: Instant = AS_OF,
    ): MeteredCheck =
        MeteredCheck(
            customer = customer,
            feature = feature,
            type = FeatureType.Metered,
            entitled = entitled,
            value = value,
            sources = listOf(planSource()),
            used = used,
            held = held,
            remaining = remaining,
            resetsAt = resetsAt,
            upgrades = upgrades,
            asOf = asOf,
            environment = ENVIRONMENT,
            track = TRACK,
            release = 1,
            testers = true,
        )

    /** One item of an entitlement list. */
    public fun entitlement(
        key: String = "export_pdf",
        entitled: Boolean = true,
        value: FeatureValue = if (entitled) FeatureValue.On else FeatureValue.Amount(0),
        type: FeatureType = typeOf(value),
        used: Long? = null,
        held: Long? = null,
        remaining: Limit? = null,
        upgrades: List<Upgrade> = emptyList(),
    ): Entitlement = Entitlement(key, type, entitled, value, listOf(planSource()), used, held, remaining, null, upgrades)

    /** A customer's entitlement list. */
    public fun entitlements(
        items: List<Entitlement> = listOf(entitlement()),
        customer: String = "user_1",
        asOf: Instant = AS_OF,
    ): Entitlements = Entitlements(customer, items, asOf, ENVIRONMENT, TRACK, 1, null, true)

    /** A customer's plans: the plans they hold and the plans they can move to. */
    public fun customerPlans(
        held: List<HeldPlan> = listOf(HeldPlan(plan("free"), byDefault = true)),
        options: List<MoveOption> = emptyList(),
        customer: String = "user_1",
        asOf: Instant = AS_OF,
    ): CustomerPlans = CustomerPlans(customer, held, options, asOf, ENVIRONMENT, TRACK, 1, null, true)

    /** Pricing, signed out or for [customer]. */
    public fun pricing(
        plans: List<PricingPlan> = emptyList(),
        products: List<PublishedProduct> = emptyList(),
        customer: String? = null,
    ): Pricing = Pricing(customer, plans.firstOrNull { it.default }?.key, products, plans, ENVIRONMENT, TRACK, 1, null, true)

    /** The catalogue's feature list. */
    public fun featureList(features: List<FeatureListing> = emptyList()): FeatureList = FeatureList(features, ENVIRONMENT, TRACK, 1)

    /** A customer's meters and a page of their usage log. */
    public fun customerUsage(
        features: List<FeatureUsage> = emptyList(),
        log: List<UsageEvent> = emptyList(),
        next: String? = null,
        customer: String = "user_1",
        asOf: Instant = AS_OF,
    ): CustomerUsage = CustomerUsage(customer, null, features, UsageLog(log, next), asOf, ENVIRONMENT, TRACK, 1, null, true)

    /** The answer to a usage write: the metered check after it, and what happened. */
    public fun usageResult(
        outcome: UsageOutcome = UsageOutcome.Recorded,
        feature: String = "ai_credits",
        amount: Long = 1,
        value: FeatureValue = FeatureValue.Amount(DEFAULT_ALLOWANCE),
        used: Long = amount,
        held: Long = 0,
        mode: UsageMode = UsageMode.Gate,
        refusal: UsageRefusal? = null,
        id: String? = "u_1",
        holdId: String? = null,
        expiresAt: Instant? = null,
        meterChange: Long = if (outcome == UsageOutcome.Recorded) amount else 0,
        overBy: Long = 0,
        customer: String = "user_1",
        asOf: Instant = AS_OF,
    ): UsageResult {
        val remaining = remainingOf(value, used, held)
        return UsageResult(
            outcome = outcome,
            refusal = refusal,
            id = id,
            holdId = holdId,
            mode = mode,
            amount = amount,
            meterChange = meterChange,
            overBy = overBy,
            late = false,
            occurredAt = asOf,
            expiresAt = expiresAt,
            reportedAs = UsageSource.Api,
            customer = customer,
            feature = feature,
            type = FeatureType.Metered,
            entitled = remaining != FeatureValue.Amount(0),
            value = value,
            sources = listOf(planSource()),
            used = used,
            held = held,
            remaining = remaining,
            upgrades = emptyList(),
            asOf = asOf,
            environment = ENVIRONMENT,
            track = TRACK,
            release = 1,
            testers = true,
        )
    }

    /** A batch's results, with the totals summed from them. */
    public fun usageEventsResult(results: List<UsageEventResult>): UsageEventsResult =
        UsageEventsResult(
            results,
            results.count { it.outcome == UsageOutcome.Recorded },
            results.count { it.outcome == UsageOutcome.Duplicate },
            results.count { it.outcome == UsageOutcome.Error },
        )

    /** A customer, as lists and updates answer them. */
    public fun customerSummary(
        externalId: String = "user_1",
        name: String = "Ada Lovelace",
        email: String = "ada@example.com",
        plan: PlanSummary? = plan("free"),
        metadata: Map<String, String> = emptyMap(),
    ): CustomerSummary =
        CustomerSummary(
            id = "c_" + externalId,
            externalId = externalId,
            name = name,
            email = email,
            environmentId = ENVIRONMENT.id,
            sample = false,
            createdAt = AS_OF,
            plan = plan,
            plans = listOfNotNull(plan),
            defaultPlan = plan("free"),
            status = "active",
            kind = CustomerKind.Default,
            metadata = metadata,
            track = TRACK,
            testCustomer = true,
        )

    /** A customer's details. */
    public fun customerDetail(
        customer: CustomerSummary = customerSummary(),
        entitlements: List<Entitlement> = listOf(entitlement()),
        grants: List<Grant> = emptyList(),
    ): CustomerDetail =
        CustomerDetail(
            customer = customer,
            defaultPlan = plan("free"),
            products = emptyList(),
            addOns = emptyList(),
            entitlements = entitlements,
            banked = emptyMap(),
            moveOptions = emptyList(),
            grants = grants,
            usage = UsageLog(emptyList()),
            activity = emptyList(),
            asOf = AS_OF,
            environment = ENVIRONMENT,
        )

    /** A page of the customer list. */
    public fun customerPage(
        items: List<CustomerSummary> = listOf(customerSummary()),
        next: String? = null,
        used: Long = items.size.toLong(),
        limit: Limit = FeatureValue.Unlimited,
    ): CustomerPage = CustomerPage(items, next, used, limit)

    /** The answer to `register`. */
    public fun registeredCustomer(
        externalId: String = "user_1",
        created: Boolean = true,
    ): RegisteredCustomer = RegisteredCustomer("c_" + externalId, externalId, ENVIRONMENT.id, AS_OF, created)

    /** A minted customer token. */
    public fun issuedCustomerToken(
        token: String = "header.payload.signature",
        customer: String = "user_1",
        scopes: List<Scope> = listOf(Scope.EntitlementsRead),
    ): IssuedCustomerToken = IssuedCustomerToken(token, customer, scopes, AS_OF.plusSeconds(HOUR))

    /** A minted snapshot. */
    public fun issuedSnapshot(
        token: String = "header.payload.signature",
    ): IssuedSnapshot = IssuedSnapshot(token, AS_OF.plusSeconds(HOUR), "k_1")

    /** The keys that verify snapshots. */
    public fun snapshotKeys(keys: List<SnapshotKey> = emptyList()): SnapshotKeys = SnapshotKeys(keys)

    /** The answer to `setTrack`. */
    public fun customerTrack(
        track: Track = TRACK,
        customer: String = "user_1",
    ): CustomerTrack = CustomerTrack(customer, track)

    /** A customer's billing at their payment provider. */
    public fun customerBilling(): CustomerBilling = CustomerBilling(items = 0)

    /** A page at the payment provider, such as the billing portal. */
    public fun providerPage(url: String = "https://billing.example.com/session"): ProviderPage = ProviderPage(url)

    /** The answer to `syncBilling`. */
    public fun billingSync(changed: Boolean = false): BillingSync = BillingSync(changed)

    /** A plan change made now. */
    public fun planChange(
        plan: String? = "pro",
        quantity: Long? = null,
        effective: Effective = Effective.Now,
        changed: Boolean = true,
        until: Instant? = null,
    ): PlanChange = PlanChange("main", plan?.let(::plan), quantity, effective, AS_OF, until, changed)

    /** A grant made or revoked. */
    public fun grantChange(
        feature: String = "sso",
        value: String = "",
        revokedAt: Instant? = null,
        actor: String? = null,
    ): GrantChange = GrantChange(Grant("g_1", feature, value, AS_OF, null, revokedAt, "", "Test key", actor))

    /** A plan named by [key]. */
    public fun plan(key: String): PlanSummary = PlanSummary("p_$key", key, key.replaceFirstChar { it.uppercase() })

    private fun planSource() = EntitlementSource(type = "plan", plan = "free", name = "Free")

    private fun typeOf(value: FeatureValue): FeatureType = if (value == FeatureValue.On) FeatureType.Boolean else FeatureType.Config

    internal fun remainingOf(
        value: FeatureValue,
        used: Long,
        held: Long,
    ): Limit =
        when (value) {
            is FeatureValue.Amount -> FeatureValue.Amount((value.value - used - held).coerceAtLeast(0))
            else -> FeatureValue.Unlimited
        }

    private const val HOUR = 3600L
    private const val DEFAULT_ALLOWANCE = 100L
}
