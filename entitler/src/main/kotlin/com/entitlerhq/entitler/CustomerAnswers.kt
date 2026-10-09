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
 */
@Serializable
public data class RegisteredCustomer(
    val id: String,
    val externalId: String,
    val environmentId: String,
    val createdAt: Instant,
    val created: Boolean,
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
 * @property kind `recurring`, `one_time`, `changing`, `default` or `none`.
 * @property metadata Your app's metadata.
 * @property track Their track.
 * @property testCustomer True for a test customer.
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
    val plan: PlanSummary? = null,
    val plans: List<PlanSummary>,
    val defaultPlan: PlanSummary? = null,
    val status: String,
    val kind: String,
    val metadata: Map<String, String>,
    val track: Track,
    val testCustomer: Boolean,
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
    val next: String? = null,
    val used: Long,
    val limit: Limit,
)

/**
 * How a purchase was made.
 *
 * @property money `real` or `test`.
 * @property channel The connection it was bought through, or null.
 * @property release The catalogue release, or null.
 * @property change The change, or null.
 * @property arm The experiment arm, or null.
 */
@Serializable
public data class Purchase(
    val money: String,
    val channel: Channel? = null,
    val release: Long? = null,
    val change: String? = null,
    val arm: String? = null,
)

/**
 * The payment provider's billing period.
 *
 * @property provider The provider, `stripe`.
 * @property startsAt When the period started.
 * @property endsAt When it ends.
 * @property trialEndsAt When the trial ends, or null.
 * @property cancelsAt When the subscription cancels, or null.
 */
@Serializable
public data class ProviderPeriod(
    val provider: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val trialEndsAt: Instant? = null,
    val cancelsAt: Instant? = null,
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
    val movingTo: PlanSummary? = null,
    val purchase: Purchase,
)

/**
 * A change booked for the end of the period.
 *
 * @property type `move` or `cancel`.
 * @property plan For a move, the plan it moves to.
 * @property movingTo For a cancel, the plan it moves to, or null.
 */
@Serializable
public data class PendingChange(
    val type: String,
    val plan: PlanSummary? = null,
    val movingTo: PlanSummary? = null,
)

/**
 * A plan the vendor set in Entitler only, while the provider bills another.
 *
 * @property from The plan the provider bills.
 * @property period The period.
 * @property billedBy The connection that bills it, or null.
 * @property since When the override started.
 * @property by Who set it.
 */
@Serializable
public data class PlanOverride(
    val from: PlanSummary,
    val period: String,
    val billedBy: Channel? = null,
    val since: Instant,
    val by: String,
)

/**
 * A customer's subscription in one product.
 *
 * @property product The product.
 * @property plan The plan held.
 * @property version The version held.
 * @property cohort The cohort, or null.
 * @property period The billing period.
 * @property startedAt When it started.
 * @property renewsAt When it renews.
 * @property billing The provider's billing period, or null.
 * @property addOns The add-ons held.
 * @property pending A change booked for the period's end, or null.
 * @property purchase How it was bought.
 * @property override A vendor override, or null.
 */
@Serializable
public data class Subscription(
    val product: ProductSummary,
    val plan: PlanSummary,
    val version: Long,
    val cohort: Long? = null,
    val period: String,
    val startedAt: Instant,
    val renewsAt: Instant,
    val billing: ProviderPeriod? = null,
    val addOns: List<HeldAddOn>,
    val pending: PendingChange? = null,
    val purchase: Purchase,
    val override: PlanOverride? = null,
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
    val defaultPlan: PlanSummary? = null,
    val subscription: Subscription? = null,
)

/**
 * A grant: a feature value the vendor gave a customer.
 *
 * @property id The grant's id.
 * @property feature The feature's key.
 * @property value The value granted: an amount, `unlimited`, or empty for an on/off feature.
 * @property from When it started.
 * @property until When it ends, or null.
 * @property revokedAt When it was revoked, or null.
 * @property reason Why it was given.
 * @property by Who gave it.
 */
@Serializable
public data class Grant(
    val id: String,
    val feature: String,
    val value: String,
    val from: Instant,
    val until: Instant? = null,
    val revokedAt: Instant? = null,
    val reason: String,
    val by: String,
)

/**
 * An entry in a customer's activity.
 *
 * @property text What happened.
 * @property at When.
 */
@Serializable
public data class Activity(
    val text: String,
    val at: Instant,
)

/**
 * A customer's details: their subscription, entitlements, grants and usage.
 *
 * Subscription changes answer this too, with [selfServe] set.
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
 * @property selfServe For a subscription change, whether the customer could have made it themselves; else null.
 */
@Serializable
public data class CustomerDetail(
    val customer: CustomerSummary,
    val subscription: Subscription? = null,
    val defaultPlan: PlanSummary? = null,
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
    val selfServe: Boolean? = null,
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
 * @property track Their track now.
 * @property source `server`, `dashboard` or `store_sandbox`, or null for All customers.
 * @property previousTrackId The track they were on, or null.
 */
@Serializable
public data class CustomerTrack(
    val customer: String,
    val track: Track,
    val source: String? = null,
    val previousTrackId: String? = null,
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
    val heldPlan: String? = null,
    val observedAt: Instant,
)

/**
 * A customer's billing in one product.
 *
 * @property product The product's key, or null.
 * @property status The provider subscription's status, or null.
 * @property sku The SKU billed, or null.
 * @property items How many items the subscription holds.
 * @property drift A difference between the provider and Entitler, or null.
 */
@Serializable
public data class ProductBilling(
    val product: String? = null,
    val status: String? = null,
    val sku: OfferedSku? = null,
    val items: Long,
    val drift: BillingDrift? = null,
)

/**
 * A customer's billing at their payment provider.
 *
 * @property provider `stripe`, or null when no provider bills them.
 * @property status The provider subscription's status, such as `active`, or null.
 * @property sku The SKU billed, or null.
 * @property items How many items the subscription holds.
 * @property drift A difference between the provider and Entitler, or null.
 * @property products Billing in each product, when the answer has it.
 */
@Serializable
public data class CustomerBilling(
    val provider: String? = null,
    val status: String? = null,
    val sku: OfferedSku? = null,
    val items: Long,
    val drift: BillingDrift? = null,
    val products: List<ProductBilling>? = null,
)

/**
 * A page at the payment provider: a checkout or the billing portal.
 *
 * @property provider The provider, `stripe`.
 * @property url The page to send the customer to.
 */
@Serializable
public data class ProviderPage(
    val provider: String,
    val url: String,
)

/**
 * A payment provider connection, as alerts name it.
 *
 * @property id The connection's id.
 * @property name The connection's name.
 * @property provider `stripe`, `apple` or `google`.
 */
@Serializable
public data class ProviderConnection(
    val id: String,
    val name: String,
    val provider: String,
)

/**
 * What a provider sold.
 *
 * @property plan The plan's key.
 * @property version The plan's version, or null.
 * @property period The period.
 * @property kind `plan` or `addon`.
 * @property product The product's key, or null.
 */
@Serializable
public data class ProviderSale(
    val plan: String,
    val version: Long? = null,
    val period: String,
    val kind: String,
    val product: String? = null,
)

/**
 * An item of a provider subscription.
 *
 * @property id The item's id.
 * @property ids The provider's ids.
 * @property quantity The quantity.
 * @property sale What it sells, or null when unknown.
 */
@Serializable
public data class ProviderItem(
    val id: String,
    val ids: Map<String, String>,
    val quantity: Long,
    val sale: ProviderSale? = null,
)

/**
 * The start and end of a provider subscription's period.
 *
 * @property startsAt When it started.
 * @property endsAt When it ends.
 */
@Serializable
public data class ProviderSubscriptionPeriod(
    val startsAt: Instant,
    val endsAt: Instant,
)

/**
 * A subscription at the payment provider.
 *
 * @property id The subscription's id.
 * @property status The provider's status.
 * @property billing Whether it bills.
 * @property period Its period, or null.
 * @property trialEndsAt When its trial ends, or null.
 * @property cancelsAt When it cancels, or null.
 * @property items Its items.
 */
@Serializable
public data class ProviderSubscription(
    val id: String,
    val status: String,
    val billing: Boolean,
    val period: ProviderSubscriptionPeriod? = null,
    val trialEndsAt: Instant? = null,
    val cancelsAt: Instant? = null,
    val items: List<ProviderItem>,
)

/**
 * An amount of money.
 *
 * @property value The amount in the currency's smallest unit.
 * @property currency The currency code.
 */
@Serializable
public data class Money(
    val value: Long,
    val currency: String,
)

/**
 * A one-time payment at the payment provider.
 *
 * @property id The payment's id.
 * @property ids The provider's ids.
 * @property quantity The quantity.
 * @property amount The amount, or null.
 * @property status `paid`, `refunded` or `partially_refunded`.
 * @property paidAt When it was paid.
 * @property sale What it bought, or null when unknown.
 */
@Serializable
public data class ProviderPayment(
    val id: String,
    val ids: Map<String, String>,
    val quantity: Long,
    val amount: Money? = null,
    val status: String,
    val paidAt: Instant,
    val sale: ProviderSale? = null,
)

/**
 * What a payment provider holds for a customer.
 *
 * @property subscriptions Their subscriptions.
 * @property payments Their one-time payments.
 */
@Serializable
public data class ProviderState(
    val subscriptions: List<ProviderSubscription>,
    val payments: List<ProviderPayment>,
)

/**
 * A customer's state at one provider connection.
 *
 * @property connection The connection.
 * @property readAt When Entitler read it.
 * @property state What the provider holds.
 */
@Serializable
public data class ProviderAccount(
    val connection: ProviderConnection,
    val readAt: Instant,
    val state: ProviderState,
)

/**
 * The facts behind an alert. Which are set depends on the alert's rule.
 *
 * @property customer The customer's external id, or null.
 * @property connection The connection's id.
 * @property provider `stripe`, `apple` or `google`.
 * @property product The product's key.
 * @property plans The plans involved.
 * @property plan The plan involved.
 * @property held The plan Entitler holds, or null.
 * @property planId The plan's id.
 * @property heldId The held plan's id, or null.
 * @property ids The provider's ids.
 * @property count A count.
 * @property boughtThrough Where it was bought.
 * @property refusal Why a payment was refused.
 * @property reason A reason.
 * @property providerCustomer The provider's customer id.
 */
@Serializable
public data class AlertFacts(
    val customer: String? = null,
    val connection: String,
    val provider: String,
    val product: String? = null,
    val plans: List<String>? = null,
    val plan: String? = null,
    val held: String? = null,
    val planId: String? = null,
    val heldId: String? = null,
    val ids: String? = null,
    val count: Long? = null,
    val boughtThrough: String? = null,
    val refusal: String? = null,
    val reason: String? = null,
    val providerCustomer: String? = null,
)

/**
 * A customer named by an alert.
 *
 * @property externalId The customer's external id.
 * @property name The customer's name.
 */
@Serializable
public data class AlertCustomer(
    val externalId: String,
    val name: String,
)

/**
 * Something about a customer's billing that needs attention.
 *
 * @property id The alert's id.
 * @property rule The rule that raised it.
 * @property title Its title.
 * @property message What to do.
 * @property facts The facts behind it.
 * @property customer The customer, or null.
 * @property connection The connection.
 * @property openedAt When it opened.
 * @property seenAt When it was last seen.
 * @property resolvedAt When it was resolved, or null.
 * @property resolvedBy `provider` or `person`, or null.
 */
@Serializable
public data class Alert(
    val id: String,
    val rule: String,
    val title: String,
    val message: String,
    val facts: AlertFacts,
    val customer: AlertCustomer? = null,
    val connection: ProviderConnection,
    val openedAt: Instant,
    val seenAt: Instant,
    val resolvedAt: Instant? = null,
    val resolvedBy: String? = null,
)

/**
 * A customer's state at every payment provider connection, and its alerts.
 *
 * @property connections Their state at each connection.
 * @property alerts Alerts about them.
 * @property linked The connections linked to them, when the answer has them.
 */
@Serializable
public data class CustomerProviders(
    val connections: List<ProviderAccount>,
    val alerts: List<Alert>,
    val linked: List<String>? = null,
)

/**
 * What a billing call subscribes to or adds: a plan by its key or id, or a SKU the customer bought,
 * which names its own period. Billing methods also take a plan key as a plain `String`.
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
 * A SKU the customer bought at a payment provider, which names its own period.
 *
 * ```kotlin
 * customer.subscribe(Sku("apple", mapOf("productId" to "pro.monthly")))
 * ```
 *
 * @property connector The connector, such as `stripe` or `apple`.
 * @property ids The provider's ids for the SKU.
 */
public data class Sku(
    val connector: String,
    val ids: Map<String, String>,
) : PlanChoice
