@file:UseSerializers(InstantSerializer::class)

package com.entitlerhq.entitler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * The environment an answer comes from.
 *
 * @property id The environment's id.
 * @property name The environment's name, such as `development`.
 * @property kind Whether it is a test or live environment.
 */
@Serializable
public data class Environment(
    val id: String,
    val name: String,
    val kind: EnvironmentKind,
)

/**
 * A track: the catalogue release a group of customers is served.
 *
 * @property id The track's id.
 * @property name The track's name, such as `All customers`.
 */
@Serializable
public data class Track(
    val id: String,
    val name: String,
)

/**
 * The experiment arm a customer or visitor is in.
 *
 * @property id The experiment's id.
 * @property arm The arm, [ExperimentArm.Control] or [ExperimentArm.Variant].
 */
@Serializable
public data class Experiment(
    val id: String,
    val arm: ExperimentArm,
)

/**
 * A plan that would entitle the customer to a feature: the same rule as the paywall's [MoveOption], so
 * an "Upgrade" button and the paywall agree.
 *
 * @property plan The plan's key.
 * @property name The plan's name.
 * @property move The kind of move.
 * @property action [MoveAction.Buy] when the customer can make the move alone, [MoveAction.Contact] for a
 *   sales-led move, or [MoveAction.Unavailable] with a [reason].
 * @property reason Why the move is unavailable, a sentence for the developer, or null.
 */
@Serializable
public data class Upgrade(
    val plan: String,
    val name: String,
    val move: Move,
    val action: MoveAction,
    val reason: String?,
)

/**
 * A billing period, named by its stable [key] (sent back in writes) and its display [label].
 *
 * @property key The key to send in writes, such as `monthly`.
 * @property label The label to show, such as Monthly.
 */
@Serializable
public data class PlanPeriod(
    val key: String,
    val label: String,
)

/**
 * Where a feature's value comes from, with its share of the value.
 *
 * The [type] decides which other properties are set.
 *
 * @property type `plan`, `addon`, `grant`, `banked` or `group`.
 * @property plan The plan's key, for a plan or add-on.
 * @property name The plan's name, for a plan or add-on.
 * @property version The plan's version, for a plan or add-on.
 * @property byDefault For a plan: true when the customer holds no plan yet and reads as the default plan.
 * @property quantity How many of the add-on the customer holds.
 * @property grant The grant's id.
 * @property until When the grant ends, or null when it does not.
 * @property value This source's share of the value.
 * @property features The members of a group, every one held.
 */
@Serializable
public data class EntitlementSource(
    val type: String,
    val plan: String? = null,
    val name: String? = null,
    val version: Long? = null,
    val byDefault: Boolean? = null,
    val quantity: Long? = null,
    val grant: String? = null,
    val until: Instant? = null,
    val value: FeatureValue? = null,
    val features: List<String>? = null,
)

/**
 * The answer to a check of one feature for one customer.
 *
 * @property customer The customer's external id.
 * @property feature The feature's key.
 * @property type The feature's type.
 * @property entitled Whether the customer is entitled: the server's decision.
 * @property value The customer's value of the feature; `Amount(0)` when they hold none.
 * @property sources Where the value comes from.
 * @property used For a metered feature, how much is used this period or window.
 * @property held For a metered feature, how much open holds reserve.
 * @property remaining For a metered feature, how much remains.
 * @property resetsAt For a metered feature, when the meter starts again, or null.
 * @property upgrades Plans the customer could move to when not entitled.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class Check(
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
    val stale: Boolean = false,
)

/**
 * The answer to a check of a metered feature, with its meter.
 *
 * @property customer The customer's external id.
 * @property feature The feature's key.
 * @property type The feature's type, `metered`.
 * @property entitled Whether the customer is entitled: the server's decision.
 * @property value The customer's allowance.
 * @property sources Where the allowance comes from.
 * @property used How much is used this period or window.
 * @property held How much open holds reserve; counted in [remaining].
 * @property remaining How much remains.
 * @property resetsAt When the meter starts again, or null.
 * @property upgrades Plans the customer could move to when not entitled.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class MeteredCheck(
    val customer: String,
    val feature: String,
    val type: FeatureType,
    val entitled: Boolean,
    val value: FeatureValue,
    val sources: List<EntitlementSource>,
    val used: Long,
    val held: Long,
    val remaining: Limit,
    val resetsAt: Instant? = null,
    val upgrades: List<Upgrade>,
    val asOf: Instant,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
    val stale: Boolean = false,
)

/**
 * One feature in a customer's entitlement list.
 *
 * @property key The feature's key.
 * @property type The feature's type.
 * @property entitled Whether the customer is entitled: the server's decision.
 * @property value The customer's value of the feature.
 * @property sources Where the value comes from.
 * @property used For a metered feature, how much is used this period or window.
 * @property held For a metered feature, how much open holds reserve.
 * @property remaining For a metered feature, how much remains.
 * @property resetsAt For a metered feature, when the meter starts again, or null.
 * @property upgrades The plans that would entitle the customer, empty when entitled (and in a snapshot,
 *   since offline gating never offers a purchase).
 */
@Serializable
public data class Entitlement(
    val key: String,
    val type: FeatureType,
    val entitled: Boolean,
    val value: FeatureValue,
    val sources: List<EntitlementSource>,
    val used: Long? = null,
    val held: Long? = null,
    val remaining: Limit? = null,
    val resetsAt: Instant? = null,
    val upgrades: List<Upgrade> = emptyList(),
)

/**
 * A customer's entitlements, groups included, each with the server's decision.
 *
 * ```kotlin
 * val entitlements = customer.entitlements()
 * if (entitlements.has(Features.collaboration)) showTeamTools()
 * ```
 *
 * @property customer The customer's external id.
 * @property entitlements Every feature the list holds.
 * @property asOf The instant the answer is for.
 * @property environment The environment; null in a verified snapshot, whose environment id is on the snapshot.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class Entitlements(
    val customer: String,
    val entitlements: List<Entitlement>,
    val asOf: Instant,
    val environment: Environment?,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
    val stale: Boolean = false,
) {
    /** The entitlement for [feature], or null when the list does not hold it. */
    public operator fun get(feature: Feature): Entitlement? = get(feature.key)

    /** The entitlement for the feature with [key], or null when the list does not hold it. */
    public operator fun get(key: String): Entitlement? = entitlements.firstOrNull { it.key == key }

    /** Whether the customer is entitled to [feature]; false when the list does not hold it. */
    public fun has(feature: Feature): Boolean = has(feature.key)

    /** Whether the customer is entitled to the feature with [key]; false when the list does not hold it. */
    public fun has(key: String): Boolean = get(key)?.entitled ?: false
}

/**
 * A plan, named by its id, key and name.
 *
 * @property id The plan's public id.
 * @property key The plan's key.
 * @property name The plan's name.
 * @property kind Whether it is a base plan or an add-on, where the answer says.
 * @property version The plan's version, where the answer says.
 * @property product The plan's product key, where the answer says.
 */
@Serializable
public data class PlanSummary(
    val id: String,
    val key: String,
    val name: String,
    val kind: PlanKind? = null,
    val version: Long? = null,
    val product: String? = null,
)

/**
 * A product, named by its key and name.
 *
 * @property key The product's key.
 * @property name The product's name.
 */
@Serializable
public data class ProductSummary(
    val key: String,
    val name: String,
)

/**
 * A plan the customer holds.
 *
 * @property plan The plan.
 * @property product The plan's product, or null.
 * @property version The version held, or null.
 * @property byDefault True when the customer holds no plan yet and reads as the default plan. A customer
 * registered with `register()` holds the default plan itself, so this is false.
 * @property period How often it is billed. Null only for a plan held by default.
 * @property renewsAt When it renews, or null.
 * @property pending The change booked for [renewsAt], or null.
 * @property billedBy Who bills it: [Provider.Stripe], or a store whose own page manages it
 *   ([Provider.Apple], [Provider.Google]); null when nothing bills it.
 */
@Serializable
public data class HeldPlan(
    val plan: PlanSummary,
    val product: ProductSummary?,
    val version: Long?,
    val byDefault: Boolean,
    val period: PlanPeriod?,
    val renewsAt: Instant?,
    val pending: BookedChange?,
    val billedBy: Provider?,
)

/**
 * A change booked for a held plan's renewal. Handle every case with an exhaustive `when`:
 *
 * ```kotlin
 * val note = when (val pending = held.pending) {
 *     is BookedChange.Move -> "Moves to ${pending.plan.name} at renewal"
 *     is BookedChange.Cancel -> "Ends at renewal"
 *     is BookedChange.Billing -> "Billed ${pending.billing} from renewal"
 *     is BookedChange.Unknown, null -> null
 * }
 * ```
 */
@Serializable(with = BookedChangeSerializer::class)
public sealed interface BookedChange {
    /** The kind of change, as the API names it. */
    public val type: BookedChangeType

    /**
     * A move to another plan at renewal.
     *
     * @property plan The plan the customer moves to.
     */
    public data class Move(
        val plan: PlanSummary,
    ) : BookedChange {
        override val type: BookedChangeType get() = BookedChangeType.Move
    }

    /**
     * A cancel at renewal.
     *
     * @property movingTo The default plan the customer moves to, or null when the product has none.
     */
    public data class Cancel(
        val movingTo: PlanSummary? = null,
    ) : BookedChange {
        override val type: BookedChangeType get() = BookedChangeType.Cancel
    }

    /**
     * A change of who bills the plan from renewal, such as a `setPlan` with [PlanBilling.End] booked for
     * renewal.
     *
     * @property billing Who bills it from renewal.
     */
    public data class Billing(
        val billing: BookedBilling,
    ) : BookedChange {
        override val type: BookedChangeType get() = BookedChangeType.Billing
    }

    /**
     * A change this version of the SDK does not know.
     *
     * @property raw The change as the API sent it.
     */
    public data class Unknown(
        override val type: BookedChangeType,
        val raw: kotlinx.serialization.json.JsonObject,
    ) : BookedChange
}

internal object BookedChangeSerializer : kotlinx.serialization.KSerializer<BookedChange> {
    override val descriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("com.entitlerhq.entitler.BookedChange")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BookedChange {
        val input = decoder as kotlinx.serialization.json.JsonDecoder
        val body =
            input.decodeJsonElement() as? kotlinx.serialization.json.JsonObject
                ?: throw kotlinx.serialization.SerializationException("Expected a booked change.")
        val type = body.string("type") ?: throw kotlinx.serialization.SerializationException("Expected a booked change with type.")
        return when (type) {
            "move" -> BookedChange.Move(field(input, body, "plan", PlanSummary.serializer()))
            "cancel" -> BookedChange.Cancel(optional(input, body, "movingTo", PlanSummary.serializer()))
            "billing" -> BookedChange.Billing(field(input, body, "billing", BookedBillingSerializer))
            else -> BookedChange.Unknown(BookedChangeType(type), body)
        }
    }

    private fun <T : Any> optional(
        input: kotlinx.serialization.json.JsonDecoder,
        body: kotlinx.serialization.json.JsonObject,
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        val element = body[name]
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        checkShape(serializer.descriptor, element)
        return input.json.decodeFromJsonElement(serializer, element)
    }

    private fun <T : Any> field(
        input: kotlinx.serialization.json.JsonDecoder,
        body: kotlinx.serialization.json.JsonObject,
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = optional(input, body, name, serializer) ?: throw kotlinx.serialization.SerializationException("Expected $name.")

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: BookedChange,
    ) {
        val output = encoder as kotlinx.serialization.json.JsonEncoder
        val fields =
            when (value) {
                is BookedChange.Move -> {
                    mapOf("plan" to output.json.encodeToJsonElement(PlanSummary.serializer(), value.plan))
                }

                is BookedChange.Cancel -> {
                    mapOf(
                        "movingTo" to
                            (
                                value.movingTo?.let { output.json.encodeToJsonElement(PlanSummary.serializer(), it) }
                                    ?: kotlinx.serialization.json.JsonNull
                            ),
                    )
                }

                is BookedChange.Billing -> {
                    mapOf("billing" to kotlinx.serialization.json.JsonPrimitive(value.billing.value))
                }

                is BookedChange.Unknown -> {
                    value.raw - "type"
                }
            }
        output.encodeJsonElement(
            kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(value.type.value)) + fields),
        )
    }
}

/**
 * What a move changes for the customer.
 *
 * @property kind How the move changes the feature.
 * @property text The change in words.
 */
@Serializable
public data class Impact(
    val kind: ImpactKind,
    val text: String,
)

/**
 * A price at a payment provider.
 *
 * @property amount The amount in the currency's smallest unit.
 * @property currency The currency code, such as `aud`.
 * @property interval How often it recurs, or null for a one-time price.
 * @property intervalCount How many intervals each charge covers.
 * @property tax Whether the amount includes tax.
 */
@Serializable
public data class ProviderPrice(
    val amount: Long,
    val currency: String,
    val interval: PriceInterval?,
    val intervalCount: Long,
    val tax: TaxBehaviour,
)

/**
 * A SKU a payment provider sells a plan's period as.
 *
 * @property period The period it sells, or null for none.
 * @property connector Where it is bought: `stripe`, `apple`, `google`, or another connector.
 * @property ids The provider's ids for the SKU.
 * @property price The provider's price, or null when unknown.
 */
@Serializable
public data class OfferedSku(
    val period: PlanPeriod?,
    val connector: String,
    val ids: Map<String, String>,
    val price: ProviderPrice? = null,
)

/**
 * A plan the customer can move to, as a paywall or billing page shows it: [action] decides the button,
 * and [skus] are the store products.
 *
 * @property plan The plan to move to.
 * @property product The plan's product, or null.
 * @property from The plan it moves from, or null.
 * @property move The kind of move.
 * @property action [MoveAction.Buy] when the customer can make the move alone, [MoveAction.Contact] for a
 *   sales-led move only the company can make, or [MoveAction.Unavailable] with a [reason].
 * @property reason Why the move is unavailable, a sentence for the developer, or null.
 * @property timing When it takes effect (the API's `when`).
 * @property periods The plan's billing periods, from the catalogue.
 * @property impact What it changes, line by line.
 * @property skus The products that sell it on Stripe, the App Store and Google Play.
 */
@Serializable
public data class MoveOption(
    val plan: OfferedPlan,
    val product: ProductSummary?,
    val from: PlanSummary?,
    val move: Move,
    val action: MoveAction,
    val reason: String?,
    @SerialName("when") val timing: Timing,
    val periods: List<PlanPeriod>,
    val impact: List<Impact>,
    val skus: List<OfferedSku>,
)

/**
 * The plan a [MoveOption] moves to.
 *
 * @property id The plan's public id.
 * @property key The plan's key.
 * @property name The plan's name.
 * @property kind Whether it is a base plan or an add-on.
 * @property description The plan's description.
 * @property default True for a product's default plan.
 */
@Serializable
public data class OfferedPlan(
    val id: String,
    val key: String,
    val name: String,
    val kind: PlanKind,
    val description: String,
    val default: Boolean,
)

/**
 * The customer's plans: the plans they hold and the plans they can move to.
 *
 * @property customer The customer's external id.
 * @property held The plans they hold.
 * @property options The plans they can move to, each with its action, periods, impact and SKUs.
 * @property asOf The instant the answer is for.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The customer's experiment arm, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class CustomerPlans(
    val customer: String,
    val held: List<HeldPlan>,
    val options: List<MoveOption>,
    val asOf: Instant,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
    val stale: Boolean = false,
)

/**
 * A product on the pricing page.
 *
 * @property key The product's key.
 * @property name The product's name.
 * @property defaultPlan The key of the plan a new customer reads as, or null.
 */
@Serializable
public data class PublishedProduct(
    val key: String,
    val name: String,
    val defaultPlan: String?,
)

/**
 * A billing period a plan is sold for.
 *
 * @property key The period's key, such as `monthly`, which stays the same when its label is renamed or translated.
 * @property label The period's label, such as `Monthly`.
 * @property count How many units it lasts.
 * @property unit The unit.
 */
@Serializable
public data class BillingPeriod(
    val key: String,
    val label: String,
    val count: Int,
    val unit: PeriodUnit,
)

/**
 * A payment provider connection.
 *
 * @property provider The connection's provider.
 * @property connectionId The connection's id.
 */
@Serializable
public data class Channel(
    val provider: Provider,
    val connectionId: String,
)

/**
 * Where a plan's period is listed for sale.
 *
 * @property channel The connection.
 * @property name The connection's name.
 * @property mode Whether the connection takes real or test money, or null.
 * @property purchasable Whether it can be bought there now.
 * @property ids The provider's ids, or null.
 * @property price The provider's price, or null.
 */
@Serializable
public data class ListingChannel(
    val channel: Channel,
    val name: String,
    val mode: ConnectionMode? = null,
    val purchasable: Boolean,
    val ids: Map<String, String>? = null,
    val price: ProviderPrice? = null,
)

/**
 * A plan period's listings.
 *
 * @property period The period, or null for a one-time plan.
 * @property channels Where it is listed.
 */
@Serializable
public data class PeriodListing(
    val period: PlanPeriod?,
    val channels: List<ListingChannel>,
)

/**
 * A plan on the pricing page.
 *
 * @property id The plan's public id.
 * @property key The plan's key.
 * @property name The plan's name.
 * @property description The plan's description.
 * @property kind Whether it is a base plan or an add-on.
 * @property product The plan's product key, or null.
 * @property salesLed True when only the vendor sells it.
 * @property status Whether it is still sold to new customers.
 * @property version The plan's version, or null.
 * @property default True for the plan a new customer reads as.
 * @property periods The periods it is sold for.
 * @property attachesTo For an add-on, the plans it attaches to.
 * @property features Each feature's value in the plan, by key.
 * @property listings Where each period is listed for sale.
 */
@Serializable
public data class PricingPlan(
    val id: String,
    val key: String,
    val name: String,
    val description: String,
    val kind: PlanKind,
    val product: String?,
    val salesLed: Boolean,
    val status: PlanStatus,
    val version: Long?,
    val default: Boolean,
    val periods: List<BillingPeriod>,
    val attachesTo: List<String>,
    val features: Map<String, FeatureValue>,
    val listings: List<PeriodListing>,
)

/**
 * Pricing: the plans on sale, always computed now.
 *
 * @property customer The customer's external id for customer pricing, else null.
 * @property defaultPlan The key of the plan a new customer reads as, or null.
 * @property products The products on sale.
 * @property plans The plans on sale.
 * @property environment The environment.
 * @property track The track whose catalogue is on sale.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property experiment The experiment arm, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class Pricing(
    val customer: String?,
    val defaultPlan: String?,
    val products: List<PublishedProduct>,
    val plans: List<PricingPlan>,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val experiment: Experiment?,
    val stale: Boolean = false,
)

/**
 * How often a metered feature's meter starts again.
 *
 * @property count How many units.
 * @property unit The unit.
 */
@Serializable
public data class MeterWindow(
    val count: Int,
    val unit: MeterUnit,
)

/**
 * A feature in the catalogue.
 *
 * @property id The feature's public id.
 * @property key The feature's key.
 * @property name The feature's name.
 * @property type The feature's type.
 * @property description The feature's description.
 * @property unit The feature's unit, such as `credits`, or empty.
 * @property resetEvery How often the meter starts again, or null.
 * @property archived True when the feature is archived.
 * @property includes For a group, its direct members' keys.
 */
@Serializable
public data class FeatureListing(
    val id: String,
    val key: String,
    val name: String,
    val type: FeatureType,
    val description: String,
    val unit: String,
    val resetEvery: MeterWindow?,
    val archived: Boolean,
    val includes: List<String>,
)

/**
 * The catalogue's features, as the release the key's track serves.
 *
 * @property features The features.
 * @property environment The environment.
 * @property track The track.
 * @property release The catalogue release the track serves, or null when it follows a change.
 * @property change The change the track follows, or null.
 * @property stale True only when the SDK answered from a kept copy because Entitler was unreachable.
 */
@Serializable
public data class FeatureList(
    val features: List<FeatureListing>,
    val environment: Environment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val stale: Boolean = false,
)

/**
 * The scopes a client's credential holds.
 *
 * ```kotlin
 * if (Scope.UsageWrite in client.scopes()) showUsageButton()
 * ```
 *
 * @property granted The scopes this SDK knows, in a fixed order.
 * @property registration For an identity client, whether the sign-in provider lets a new person register; else null.
 */
public data class Scopes(
    val granted: List<Scope>,
    val registration: Boolean?,
) {
    /** Whether the credential holds [scope]. */
    public operator fun contains(scope: Scope): Boolean = scope in granted
}
