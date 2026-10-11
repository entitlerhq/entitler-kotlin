@file:UseSerializers(InstantSerializer::class)

package com.entitlerhq.entitler

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * A customer registered by `register()`.
 *
 * @property id Entitler's id for the customer.
 * @property externalId The id your app uses for the customer.
 * @property environmentId The environment's id.
 * @property createdAt When the customer was registered.
 * @property created True when this call registered them; false when they already were.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class RegisteredCustomer(
    val id: String,
    val externalId: String,
    val environmentId: String,
    val createdAt: Instant,
    val created: Boolean,
    val replayed: Boolean = false,
)

/**
 * A customer, as the customer list shows them.
 *
 * @property id Entitler's id for the customer.
 * @property externalId The id your app uses for the customer.
 * @property name The customer's name.
 * @property email The customer's email address, or empty.
 * @property environmentId The environment's id.
 * @property sample True for a sample customer.
 * @property createdAt When the customer was registered.
 * @property plan Their plan, or null.
 * @property plans Their plans in every product.
 * @property defaultPlan The plan they read as before holding one, or null.
 * @property status Their subscription's status.
 * @property kind How they hold their plan.
 * @property metadata Your app's metadata.
 * @property track Their track.
 * @property testCustomer True for a test customer.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class CustomerSummary(
    val id: String,
    val externalId: String,
    val name: String,
    val email: String,
    val environmentId: String,
    val sample: Boolean,
    val createdAt: Instant,
    val plan: PlanSummary?,
    val plans: List<PlanSummary>,
    val defaultPlan: PlanSummary?,
    val status: String,
    val kind: CustomerKind,
    val metadata: Map<String, String>,
    val track: Track,
    val testCustomer: Boolean,
    val replayed: Boolean = false,
)

/**
 * A page of the customer list.
 *
 * @property items The customers.
 * @property next The cursor of the next page, or null on the last.
 * @property used How many customers count against the organisation's limit.
 * @property limit The organisation's customer limit.
 */
@Serializable
public data class CustomerPage(
    val items: List<CustomerSummary>,
    val next: String?,
    val used: Long,
    val limit: Limit,
)

/**
 * How a purchase was made.
 *
 * @property money Whether it was paid with real or test money.
 * @property channel The connection it was bought through, or null.
 * @property release The catalogue release, or null.
 * @property change The change, or null.
 * @property arm The experiment arm, or null.
 */
@Serializable
public data class Purchase(
    val money: MoneyKind,
    val channel: Channel?,
    val release: Long?,
    val change: String?,
    val arm: ExperimentArm?,
)

/**
 * The payment provider's billing period.
 *
 * @property provider The provider.
 * @property startsAt When the period started.
 * @property endsAt When it ends.
 * @property trialEndsAt When the trial ends, or null.
 * @property cancelsAt When the subscription cancels, or null.
 */
@Serializable
public data class ProviderPeriod(
    val provider: Provider,
    val startsAt: Instant,
    val endsAt: Instant,
    val trialEndsAt: Instant?,
    val cancelsAt: Instant?,
)

/**
 * An add-on the customer holds.
 *
 * @property plan The add-on.
 * @property version The version held.
 * @property quantity How many they hold.
 * @property countable Whether they can hold more than one.
 * @property addedAt When it was added.
 * @property movingTo The add-on it moves to at the period's end, or null.
 * @property purchase How it was bought.
 */
@Serializable
public data class HeldAddOn(
    val plan: PlanSummary,
    val version: Long,
    val quantity: Long,
    val countable: Boolean,
    val addedAt: Instant,
    val movingTo: PlanSummary?,
    val purchase: Purchase,
)

/**
 * A customer's subscription in one product.
 *
 * @property product The product.
 * @property plan The plan held.
 * @property version The version held.
 * @property cohort The cohort, or null.
 * @property period How often it is billed, or null for none.
 * @property startedAt When it started.
 * @property renewsAt When it renews.
 * @property billing The provider's billing period, or null.
 * @property until When the customer returns to the default plan, as [ServerCustomer.setPlan] set it, or null.
 * @property addOns The add-ons held.
 * @property pending A change booked for the period's end, or null.
 * @property purchase How it was bought.
 * @property override The override that put them on this plan, or null.
 */
@Serializable
public data class Subscription(
    val product: ProductSummary,
    val plan: PlanSummary,
    val version: Long,
    val cohort: Long?,
    val period: PlanPeriod?,
    val startedAt: Instant,
    val renewsAt: Instant,
    val billing: ProviderPeriod?,
    val until: Instant?,
    val addOns: List<HeldAddOn>,
    val pending: BookedChange?,
    val purchase: Purchase,
    val override: PlanOverride?,
)

/**
 * A move made in Entitler only, with billing kept, as a deliberate exception: the payment provider
 * keeps billing the plan the customer held before.
 *
 * @property from The plan they held before.
 * @property period The billing period of the plan they held before, or null for none.
 * @property billedBy The connection that kept billing them for the plan they held before, or null.
 * @property since When the override began.
 * @property by Who made the override.
 */
@Serializable
public data class PlanOverride(
    val from: PlanSummary,
    val period: PlanPeriod?,
    val billedBy: Channel?,
    val since: Instant,
    val by: String,
)

/**
 * A customer's subscription and default plan in one product.
 *
 * @property product The product.
 * @property defaultPlan The plan they read as before holding one, or null.
 * @property subscription Their subscription, or null.
 */
@Serializable
public data class ProductSubscription(
    val product: ProductSummary,
    val defaultPlan: PlanSummary?,
    val subscription: Subscription?,
)

/**
 * A grant: a feature value the vendor gave a customer.
 *
 * @property id The grant's id.
 * @property feature The feature's key.
 * @property value The value granted: [FeatureValue.On] for an on/off feature, an amount, or unlimited.
 * @property from When it started.
 * @property until When it ends, or null.
 * @property revokedAt When it was revoked, or null.
 * @property reason Why it was given.
 * @property by Who gave it: the key's or person's name.
 * @property actor The person or system that decided, as the write named it, or null.
 */
@Serializable
public data class Grant(
    val id: String,
    val feature: String,
    @Serializable(with = GrantValueSerializer::class)
    val value: FeatureValue,
    val from: Instant,
    val until: Instant?,
    val revokedAt: Instant?,
    val reason: String,
    val by: String,
    val actor: String?,
)

/**
 * An entry in a customer's activity.
 *
 * @property text What happened.
 * @property at When.
 * @property by The name of the key or person that made the change, or null for one that came with time.
 * @property actor The person or system that decided, as the write named it, or null.
 * @property reason Why, as the write gave it, or null.
 */
@Serializable
public data class Activity(
    val text: String,
    val at: Instant,
    val by: String?,
    val actor: String?,
    val reason: String?,
)

/**
 * A customer's details: their subscription, entitlements, grants and usage.
 *
 *
 * @property customer The customer.
 * @property subscription Their subscription in the first product, or null.
 * @property defaultPlan The plan they read as before holding one, or null.
 * @property products Their subscription in every product.
 * @property addOns The add-ons they hold in every product.
 * @property entitlements Their entitlements.
 * @property banked Banked amounts, by feature key.
 * @property moveOptions The moves open to them.
 * @property grants Their grants.
 * @property usage A page of their usage log.
 * @property activity What happened to them.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class CustomerDetail(
    val customer: CustomerSummary,
    val subscription: Subscription?,
    val defaultPlan: PlanSummary?,
    val products: List<ProductSubscription>,
    val addOns: List<HeldAddOn>,
    val entitlements: List<Entitlement>,
    val banked: Map<String, Long>,
    val moveOptions: List<MoveOption>,
    val grants: List<Grant>,
    val usage: UsageLog,
    val activity: List<Activity>,
    val asOf: Instant,
    val environment: Environment,
    val replayed: Boolean = false,
)

/**
 * A customer token minted by [ServerCustomer.token], to hand to an in-app client.
 *
 * Its string form never shows the token.
 *
 * @property token The token.
 * @property customer The customer's external id.
 * @property scopes The scopes it holds.
 * @property expiresAt When it expires.
 */
@Serializable
public data class IssuedCustomerToken(
    val token: String,
    val customer: String,
    val scopes: List<Scope>,
    val expiresAt: Instant,
) {
    /** A string form that never shows the token. */
    override fun toString(): String = "IssuedCustomerToken(customer=$customer, scopes=$scopes, expiresAt=$expiresAt)"
}

/**
 * A signed entitlements snapshot, to keep and verify offline with [verifySnapshot].
 *
 * Its string form never shows the token.
 *
 * @property token The snapshot.
 * @property expiresAt When it stops verifying.
 * @property keyId The id of the key that signed it.
 */
@Serializable
public data class IssuedSnapshot(
    val token: String,
    val expiresAt: Instant,
    val keyId: String,
) {
    /** A string form that never shows the token. */
    override fun toString(): String = "IssuedSnapshot(expiresAt=$expiresAt, keyId=$keyId)"
}

/**
 * A customer's track after [ServerCustomer.setTrack].
 *
 * @property customer The customer's external id.
 * @property environmentId The id of the environment the customer is in.
 * @property track Their track now.
 * @property source Who put them on the track, or null for All customers.
 * @property previousTrackId The track they were on, or null.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class CustomerTrack(
    val customer: String,
    val environmentId: String,
    val track: Track,
    val source: TrackSource?,
    val previousTrackId: String?,
    val replayed: Boolean = false,
)

/**
 * A plan the provider bills that differs from the one Entitler holds.
 *
 * @property billedPlan The plan the provider bills.
 * @property heldPlan The plan Entitler holds, or null.
 * @property observedAt When a provider event last showed the difference.
 */
@Serializable
public data class BillingDrift(
    val billedPlan: String,
    val heldPlan: String?,
    val observedAt: Instant,
)

/**
 * A customer's billing in one product.
 *
 * @property product The product the change is in, or null.
 * @property status The provider subscription's status, or null.
 * @property sku The SKU billed, or null.
 * @property items How many items the subscription holds.
 * @property drift A difference between the provider and Entitler, or null.
 */
@Serializable
public data class ProductBilling(
    val product: String?,
    val status: SubscriptionStatus?,
    val sku: OfferedSku?,
    val items: Long,
    val drift: BillingDrift?,
)

/**
 * A customer's billing at their payment provider.
 *
 * @property provider The provider that bills them, or null when none does.
 * @property status The provider subscription's status, or null.
 * @property sku The SKU billed, or null.
 * @property items How many items the subscription holds.
 * @property drift A difference between the provider and Entitler, or null.
 * @property products Billing in each product.
 */
@Serializable
public data class CustomerBilling(
    val provider: Provider?,
    val status: SubscriptionStatus?,
    val sku: OfferedSku?,
    val items: Long,
    val drift: BillingDrift?,
    val products: List<ProductBilling>,
)

/**
 * A page at the payment provider, such as the billing portal.
 *
 * @property provider The provider whose page it is.
 * @property url The page to send the customer to.
 */
@Serializable
public data class ProviderPage(
    val provider: Provider,
    val url: String,
)

/**
 * The answer to [Customer.syncBilling].
 *
 * @property changed True when the provider's state moved the customer to another plan or quantity.
 */
@Serializable
public data class BillingSync(
    val changed: Boolean,
)

/**
 * A plan or add-on change, made or booked: the answer to `cancel`, `undoPendingChange`, `setPlan` and
 * `setAddOn`, and the fields of a [SubscribeStep.Done].
 *
 * @property product The product the change is in, or null.
 * @property plan The plan or add-on the change leaves the customer on, or null when none in that product.
 * @property quantity An add-on's quantity after the change (0 when removed), or null for a plan.
 * @property effective [Effective.Now], or [Effective.Renewal] at [at].
 * @property at When it takes or took effect.
 * @property until When the customer returns to the product's default plan, or null.
 * @property changed False when nothing changed: nothing to cancel or undo, or a plan already held.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class PlanChange(
    val product: ProductSummary?,
    val plan: PlanSummary?,
    val quantity: Long?,
    val effective: Effective,
    val at: Instant,
    val until: Instant?,
    val changed: Boolean,
    val replayed: Boolean = false,
)

/**
 * A grant made or revoked: the answer to [ServerCustomer.grant] and [ServerCustomer.revokeGrant].
 *
 * @property grant The grant, so a support tool keeps its id.
 * @property replayed True when the call repeated an earlier one with the same key, so nothing changed.
 */
@Serializable
public data class GrantChange(
    val grant: Grant,
    val replayed: Boolean = false,
)

/**
 * The next step after [Customer.subscribe]. Handle every case with an exhaustive `when`:
 *
 * ```kotlin
 * when (val step = me.subscribe("pro", period = "monthly", returnUrl = appLink)) {
 *     is SubscribeStep.Done -> showPlan(step.change)
 *     is SubscribeStep.Pay -> openCustomTab(step.url)
 *     SubscribeStep.Confirming -> showPending()
 *     is SubscribeStep.Manage -> openStoreSubscriptions(step.billedBy)
 *     is SubscribeStep.Unknown -> showPending()
 * }
 * ```
 */
@Serializable(with = SubscribeStepSerializer::class)
public sealed interface SubscribeStep {
    /**
     * The change is made, or booked for renewal.
     *
     * @property change The change, with `effective` and `at`.
     */
    public data class Done(
        val change: PlanChange,
    ) : SubscribeStep

    /**
     * Send the customer to [url], the provider's page (Stripe Checkout, or a confirmation such as 3-D
     * Secure). The plan changes once they pay.
     *
     * @property url The page to open.
     */
    public data class Pay(
        val url: String,
    ) : SubscribeStep

    /** The provider is still confirming a payment, such as a bank debit; the plan changes when it succeeds. */
    public data object Confirming : SubscribeStep

    /**
     * A store bills the product, so the customer changes it there.
     *
     * @property billedBy [Provider.Apple] or [Provider.Google].
     */
    public data class Manage(
        val billedBy: Provider,
    ) : SubscribeStep

    /**
     * A next step this version does not know.
     *
     * @property value The raw `next` the API sent.
     */
    public data class Unknown(
        val value: String,
    ) : SubscribeStep
}

/**
 * What [ServerCustomer.setPlan] moves the customer to: a plan by its key or id, a SKU the customer
 * bought, which names its own period, or [NoPlan] in a product. `setPlan` also takes a plan key as a
 * plain `String`.
 */
public sealed interface PlanChoice

/**
 * A plan, named by its key or public id.
 *
 * @property plan The plan's key or public id.
 */
public data class Plan(
    val plan: String,
) : PlanChoice {
    init {
        checkPlan(plan)
        pathSegment(plan)
    }
}

/**
 * No plan in [product]: ends the customer's plan there, so they move to the product's default plan, or hold
 * no plan when it has none, now or at renewal. [PlanBilling.Keep] is refused by the API, and a period,
 * `until` or `version` never go with it.
 *
 * ```kotlin
 * customer.setPlan(NoPlan("app"), timing = Timing.End, actor = "support", reason = ticket.summary)
 * ```
 *
 * @property product The product's key.
 */
public data class NoPlan(
    val product: String,
) : PlanChoice {
    init {
        checkId(product, PRODUCT_REQUIRED)
    }
}

/**
 * A SKU the customer bought at a payment provider, which names its own period.
 *
 * ```kotlin
 * customer.setPlan(Sku("apple", mapOf("productId" to "pro.monthly")), until = expiry, idempotencyKey = transaction.id)
 * ```
 *
 * @property connector The connector, such as `stripe`, `apple` or `google`.
 * @property ids The provider's ids for the SKU.
 */
public data class Sku(
    val connector: String,
    val ids: Map<String, String>,
) : PlanChoice

internal object SubscribeStepSerializer : kotlinx.serialization.KSerializer<SubscribeStep> {
    override val descriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("com.entitlerhq.entitler.SubscribeStep")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SubscribeStep {
        val input = decoder as kotlinx.serialization.json.JsonDecoder
        val body = input.decodeJsonElement() as? kotlinx.serialization.json.JsonObject
        val next = body?.string("next") ?: throw kotlinx.serialization.SerializationException("Expected a step with next.")
        return when (next) {
            "done" -> {
                SubscribeStep.Done(input.json.decodeFromJsonElement(PlanChange.serializer(), checked(PlanChange.serializer(), body)))
            }

            "pay" -> {
                SubscribeStep.Pay(body.string("url") ?: throw kotlinx.serialization.SerializationException("Expected url."))
            }

            "confirming" -> {
                SubscribeStep.Confirming
            }

            "manage" -> {
                val billedBy = body["billedBy"] ?: throw kotlinx.serialization.SerializationException("Expected billedBy.")
                SubscribeStep.Manage(input.json.decodeFromJsonElement(ProviderSerializer, billedBy))
            }

            else -> {
                SubscribeStep.Unknown(next)
            }
        }
    }

    private fun checked(
        serializer: kotlinx.serialization.KSerializer<*>,
        body: kotlinx.serialization.json.JsonObject,
    ) = body.also { checkShape(serializer.descriptor, it) }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: SubscribeStep,
    ) {
        val output = encoder as kotlinx.serialization.json.JsonEncoder
        val next = kotlinx.serialization.json.JsonPrimitive(nextOf(value))
        val fields =
            when (value) {
                is SubscribeStep.Done -> {
                    output.json.encodeToJsonElement(PlanChange.serializer(), value.change) as kotlinx.serialization.json.JsonObject
                }

                is SubscribeStep.Pay -> {
                    kotlinx.serialization.json.JsonObject(
                        mapOf("url" to kotlinx.serialization.json.JsonPrimitive(value.url)),
                    )
                }

                is SubscribeStep.Manage -> {
                    kotlinx.serialization.json.JsonObject(
                        mapOf("billedBy" to kotlinx.serialization.json.JsonPrimitive(value.billedBy.value)),
                    )
                }

                else -> {
                    kotlinx.serialization.json.JsonObject(emptyMap())
                }
            }
        output.encodeJsonElement(kotlinx.serialization.json.JsonObject(mapOf("next" to next) + fields))
    }

    private fun nextOf(step: SubscribeStep): String =
        when (step) {
            is SubscribeStep.Done -> "done"
            is SubscribeStep.Pay -> "pay"
            SubscribeStep.Confirming -> "confirming"
            is SubscribeStep.Manage -> "manage"
            is SubscribeStep.Unknown -> step.value
        }
}
