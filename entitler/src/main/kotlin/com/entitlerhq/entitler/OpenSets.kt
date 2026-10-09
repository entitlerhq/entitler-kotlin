package com.entitlerhq.entitler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An error code from the API, or one of the SDK's own (`http_error`, `invalid_response`, `connection_failed`,
 * `timed_out`, `snapshot_invalid`, `snapshot_expired`).
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ErrorCodeSerializer::class)
public data class ErrorCode(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** An allowance in the organisation's Entitler plan is used up. */
        public val AllowanceReached: ErrorCode = ErrorCode("allowance_reached")

        /** The provider account is already connected. */
        public val AlreadyConnected: ErrorCode = ErrorCode("already_connected")

        /** Reading or writing at another instant is not allowed here. */
        public val AsOfNotAllowed: ErrorCode = ErrorCode("as_of_not_allowed")

        /** The request body is too large. */
        public val BodyTooLarge: ErrorCode = ErrorCode("body_too_large")

        /** The route refuses requests from a browser. */
        public val BrowserNotAllowed: ErrorCode = ErrorCode("browser_not_allowed")

        /** A cap in the organisation's Entitler plan is reached. */
        public val CapReached: ErrorCode = ErrorCode("cap_reached")

        /** A carried-forward change conflicts with another. */
        public val CarryForwardConflict: ErrorCode = ErrorCode("carry_forward_conflict")

        /** The catalogue must be empty for this change. */
        public val CatalogueNotEmpty: ErrorCode = ErrorCode("catalogue_not_empty")

        /** The change is locked. */
        public val ChangeLocked: ErrorCode = ErrorCode("change_locked")

        /** The track needs a change for this. */
        public val ChangeRequired: ErrorCode = ErrorCode("change_required")

        /** The connection is in use. */
        public val ConnectionInUse: ErrorCode = ErrorCode("connection_in_use")

        /** The connection is in the wrong mode. */
        public val ConnectionMode: ErrorCode = ErrorCode("connection_mode")

        /** The call needs a payment provider connection. */
        public val ConnectionRequired: ErrorCode = ErrorCode("connection_required")

        /** No answer arrived for a batch request; the SDK's own code, only on batch event results. */
        public val ConnectionFailed: ErrorCode = ErrorCode("connection_failed")

        /** The payment provider connection's key cannot be read. */
        public val ConnectionUnreadable: ErrorCode = ErrorCode("connection_unreadable")

        /** The route refuses this kind of credential. */
        public val CredentialNotAllowed: ErrorCode = ErrorCode("credential_not_allowed")

        /** The customer is not registered. */
        public val CustomerNotFound: ErrorCode = ErrorCode("customer_not_found")

        /** The email address is taken. */
        public val EmailTaken: ErrorCode = ErrorCode("email_taken")

        /** The email address is not confirmed. */
        public val EmailUnconfirmed: ErrorCode = ErrorCode("email_unconfirmed")

        /** An experiment is running. */
        public val ExperimentRunning: ErrorCode = ErrorCode("experiment_running")

        /** There is no feature with that key. */
        public val FeatureNotFound: ErrorCode = ErrorCode("feature_not_found")

        /** The usage hold expired. */
        public val HoldExpired: ErrorCode = ErrorCode("hold_expired")

        /** The usage hold was released. */
        public val HoldReleased: ErrorCode = ErrorCode("hold_released")

        /** The usage hold was settled. */
        public val HoldSettled: ErrorCode = ErrorCode("hold_settled")

        /** The answer carried no error code; the SDK's own code. */
        public val HttpError: ErrorCode = ErrorCode("http_error")

        /** The write needs an idempotency key. */
        public val IdempotencyKeyRequired: ErrorCode = ErrorCode("idempotency_key_required")

        /** The idempotency key was used for a different request. */
        public val IdempotencyMismatch: ErrorCode = ErrorCode("idempotency_mismatch")

        /** Entitler failed unexpectedly. */
        public val Internal: ErrorCode = ErrorCode("internal")

        /** The amount is not valid. */
        public val InvalidAmount: ErrorCode = ErrorCode("invalid_amount")

        /** The API sent a 2xx answer this SDK cannot read; the SDK's own code. */
        public val InvalidResponse: ErrorCode = ErrorCode("invalid_response")

        /** The request body is not valid. */
        public val InvalidBody: ErrorCode = ErrorCode("invalid_body")

        /** The idempotency key is not valid. */
        public val InvalidIdempotencyKey: ErrorCode = ErrorCode("invalid_idempotency_key")

        /** The usage instant is outside the window the API accepts. */
        public val InvalidOccurredAt: ErrorCode = ErrorCode("invalid_occurred_at")

        /** The path is not valid. */
        public val InvalidPath: ErrorCode = ErrorCode("invalid_path")

        /** The last environment cannot be removed. */
        public val LastEnvironment: ErrorCode = ErrorCode("last_environment")

        /** A limit is reached, such as reading at another instant without the `as_of` capability. */
        public val LimitReached: ErrorCode = ErrorCode("limit_reached")

        /** A rollout or experiment would leave gaps in the listings. */
        public val ListingGaps: ErrorCode = ErrorCode("listing_gaps")

        /** A listing's provider price fails the checks. */
        public val ListingInvalid: ErrorCode = ErrorCode("listing_invalid")

        /** The method is not allowed on the route. */
        public val MethodNotAllowed: ErrorCode = ErrorCode("method_not_allowed")

        /** Nothing was found. */
        public val NotFound: ErrorCode = ErrorCode("not_found")

        /** The plan is not listed. */
        public val NotListed: ErrorCode = ErrorCode("not_listed")

        /** The feature is not metered. */
        public val NotMetered: ErrorCode = ErrorCode("not_metered")

        /** The change is not on a self-serve path from the customer's plans. */
        public val NotSelfServe: ErrorCode = ErrorCode("not_self_serve")

        /** A payment must succeed first. */
        public val PaymentRequired: ErrorCode = ErrorCode("payment_required")

        /** The call needs a person, not a key. */
        public val PersonRequired: ErrorCode = ErrorCode("person_required")

        /** The plan is still billed. */
        public val PlanStillBilled: ErrorCode = ErrorCode("plan_still_billed")

        /** The provider account changed. */
        public val ProviderAccountChanged: ErrorCode = ErrorCode("provider_account_changed")

        /** The payment provider charged only part of a change. */
        public val ProviderPartial: ErrorCode = ErrorCode("provider_partial")

        /** Publishing failed. */
        public val PublicationFailed: ErrorCode = ErrorCode("publication_failed")

        /** Too many requests; retry after a while. */
        public val RateLimited: ErrorCode = ErrorCode("rate_limited")

        /** The change needs a review. */
        public val ReviewRequired: ErrorCode = ErrorCode("review_required")

        /** The credential lacks a scope the call needs. */
        public val ScopeRequired: ErrorCode = ErrorCode("scope_required")

        /** Sign-ups are closed. */
        public val SignUpsClosed: ErrorCode = ErrorCode("sign_ups_closed")

        /** The snapshot expired; the SDK's own code. */
        public val SnapshotExpired: ErrorCode = ErrorCode("snapshot_expired")

        /** The snapshot is not valid; the SDK's own code. */
        public val SnapshotInvalid: ErrorCode = ErrorCode("snapshot_invalid")

        /** The answer is out of date, such as a checkout without a payment provider connection. */
        public val Stale: ErrorCode = ErrorCode("stale")

        /** The switch is in use. */
        public val SwitchInUse: ErrorCode = ErrorCode("switch_in_use")

        /** A switch must be turned on first. */
        public val SwitchNeeded: ErrorCode = ErrorCode("switch_needed")

        /** The switch is off. */
        public val SwitchOff: ErrorCode = ErrorCode("switch_off")

        /** A batch request took longer than its timeout; the SDK's own code, only on batch event results. */
        public val TimedOut: ErrorCode = ErrorCode("timed_out")

        /** There are too many customers. */
        public val TooManyCustomers: ErrorCode = ErrorCode("too_many_customers")

        /** The track is closed. */
        public val TrackClosed: ErrorCode = ErrorCode("track_closed")

        /** The credential is not valid now. */
        public val Unauthorised: ErrorCode = ErrorCode("unauthorised")

        /** Entitler or the payment provider is unavailable for now. */
        public val Unavailable: ErrorCode = ErrorCode("unavailable")
    }
}

internal object ErrorCodeSerializer : OpenStringSerializer<ErrorCode>("ErrorCode", ::ErrorCode, ErrorCode::value)

/**
 * A scope a credential may hold, such as `usage:write`.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ScopeSerializer::class)
public data class Scope(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Reads pricing and the feature list. */
        public val PlansRead: Scope = Scope("plans:read")

        /** Reads checks, entitlement lists, customer plans, customer pricing and snapshots. */
        public val EntitlementsRead: Scope = Scope("entitlements:read")

        /** Reads usage and holds. */
        public val UsageRead: Scope = Scope("usage:read")

        /** Records usage and places holds. */
        public val UsageWrite: Scope = Scope("usage:write")

        /** Registers customers. */
        public val CustomersRegister: Scope = Scope("customers:register")

        /** Reads customers and billing. */
        public val CustomersRead: Scope = Scope("customers:read")

        /** Changes customers, subscriptions, grants and meters. */
        public val CustomersWrite: Scope = Scope("customers:write")

        /** Changes customers' names, emails and metadata. */
        public val CustomersProfile: Scope = Scope("customers:profile")

        /** Creates sample customers. */
        public val CustomersSample: Scope = Scope("customers:sample")

        /** Mints customer tokens. */
        public val TokensMint: Scope = Scope("tokens:mint")

        /** Changes the catalogue. */
        public val PlansWrite: Scope = Scope("plans:write")

        /** Releases the catalogue. */
        public val PlansRelease: Scope = Scope("plans:release")

        /** Manages tracks. */
        public val TracksManage: Scope = Scope("tracks:manage")

        /** Promotes changes between tracks. */
        public val TracksPromote: Scope = Scope("tracks:promote")

        /** Moves customers between tracks. */
        public val TracksAssign: Scope = Scope("tracks:assign")

        /** Manages keys. */
        public val KeysManage: Scope = Scope("keys:manage")

        /** Manages members. */
        public val MembersManage: Scope = Scope("members:manage")

        /** Manages projects. */
        public val ProjectsManage: Scope = Scope("projects:manage")

        /** Manages the organisation. */
        public val OrgManage: Scope = Scope("org:manage")
    }
}

internal object ScopeSerializer : OpenStringSerializer<Scope>("Scope", ::Scope, Scope::value)

/**
 * A feature's type.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = FeatureTypeSerializer::class)
public data class FeatureType(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** On or off. */
        public val Boolean: FeatureType = FeatureType("boolean")

        /** A setting with an amount. */
        public val Config: FeatureType = FeatureType("config")

        /** Metered against an allowance. */
        public val Metered: FeatureType = FeatureType("metered")

        /** A group of features. */
        public val Group: FeatureType = FeatureType("group")
    }
}

internal object FeatureTypeSerializer : OpenStringSerializer<FeatureType>("FeatureType", ::FeatureType, FeatureType::value)

/**
 * How a usage report counts against the allowance.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageModeSerializer::class)
public data class UsageMode(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Records only if the amount fits the allowance; otherwise records nothing and answers `refused`. */
        public val Gate: UsageMode = UsageMode("gate")

        /** Always records what happened; the meter may pass the allowance. */
        public val Observe: UsageMode = UsageMode("observe")
    }
}

internal object UsageModeSerializer : OpenStringSerializer<UsageMode>("UsageMode", ::UsageMode, UsageMode::value)

/**
 * What a usage write did.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageOutcomeSerializer::class)
public data class UsageOutcome(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The usage was recorded. */
        public val Recorded: UsageOutcome = UsageOutcome("recorded")

        /** A replay of an earlier request, which stands; nothing new was recorded. */
        public val Duplicate: UsageOutcome = UsageOutcome("duplicate")

        /** Nothing was recorded or held: see the refusal. */
        public val Refused: UsageOutcome = UsageOutcome("refused")

        /** The amount is held. */
        public val Held: UsageOutcome = UsageOutcome("held")

        /** The hold was settled. */
        public val Settled: UsageOutcome = UsageOutcome("settled")

        /** The hold was released. */
        public val Released: UsageOutcome = UsageOutcome("released")

        /** The report was cancelled. */
        public val Cancelled: UsageOutcome = UsageOutcome("cancelled")

        /** The meter was set. */
        public val Adjusted: UsageOutcome = UsageOutcome("adjusted")

        /** A batch event was not recorded: see its error. */
        public val Error: UsageOutcome = UsageOutcome("error")
    }
}

internal object UsageOutcomeSerializer : OpenStringSerializer<UsageOutcome>("UsageOutcome", ::UsageOutcome, UsageOutcome::value)

/**
 * Why a gated report or a hold was refused.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageRefusalSerializer::class)
public data class UsageRefusal(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The customer is not entitled to the feature. */
        public val NotEntitled: UsageRefusal = UsageRefusal("not_entitled")

        /** The amount does not fit what remains. */
        public val OverAllowance: UsageRefusal = UsageRefusal("over_allowance")
    }
}

internal object UsageRefusalSerializer : OpenStringSerializer<UsageRefusal>("UsageRefusal", ::UsageRefusal, UsageRefusal::value)

/**
 * The state of a usage hold.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = HoldStateSerializer::class)
public data class HoldState(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Open until settled, released or expired. */
        public val Open: HoldState = HoldState("open")

        /** Settled. */
        public val Settled: HoldState = HoldState("settled")

        /** Released. */
        public val Released: HoldState = HoldState("released")

        /** Expired on its own. */
        public val Expired: HoldState = HoldState("expired")
    }
}

internal object HoldStateSerializer : OpenStringSerializer<HoldState>("HoldState", ::HoldState, HoldState::value)

/**
 * The kind of environment.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = EnvironmentKindSerializer::class)
public data class EnvironmentKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** A test environment. */
        public val Test: EnvironmentKind = EnvironmentKind("test")

        /** A live environment. */
        public val Live: EnvironmentKind = EnvironmentKind("live")
    }
}

internal object EnvironmentKindSerializer : OpenStringSerializer<EnvironmentKind>(
    "EnvironmentKind",
    ::EnvironmentKind,
    EnvironmentKind::value,
)

/**
 * The arm of an experiment a customer or visitor is in.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ExperimentArmSerializer::class)
public data class ExperimentArm(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Sees the track's release. */
        public val Control: ExperimentArm = ExperimentArm("control")

        /** Sees the track's release with the experiment's change. */
        public val Variant: ExperimentArm = ExperimentArm("variant")
    }
}

internal object ExperimentArmSerializer : OpenStringSerializer<ExperimentArm>(
    "ExperimentArm",
    ::ExperimentArm,
    ExperimentArm::value,
)

/**
 * Which kind of credential reported usage.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageSourceSerializer::class)
public data class UsageSource(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** A key. */
        public val Api: UsageSource = UsageSource("api")

        /** A customer token or identity token. */
        public val Client: UsageSource = UsageSource("client")

        /** The dashboard. */
        public val Dashboard: UsageSource = UsageSource("dashboard")
    }
}

internal object UsageSourceSerializer : OpenStringSerializer<UsageSource>("UsageSource", ::UsageSource, UsageSource::value)

/**
 * Why a payment has not succeeded.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PaymentStatusSerializer::class)
public data class PaymentStatus(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The card was declined. */
        public val Declined: PaymentStatus = PaymentStatus("declined")

        /** The customer must confirm it, such as with 3D Secure. */
        public val RequiresAction: PaymentStatus = PaymentStatus("requires_action")

        /** The provider is still processing it. */
        public val Processing: PaymentStatus = PaymentStatus("processing")

        /** The provider gave no reason. */
        public val Pending: PaymentStatus = PaymentStatus("pending")
    }
}

internal object PaymentStatusSerializer : OpenStringSerializer<PaymentStatus>("PaymentStatus", ::PaymentStatus, PaymentStatus::value)

internal abstract class OpenStringSerializer<T : Any>(
    name: String,
    private val create: (String) -> T,
    private val raw: (T) -> String,
) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor("com.entitlerhq.entitler.$name", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): T = create(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(raw(value))
}

/**
 * Why a plan and period would be left without a listing.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ListingGapKindSerializer::class)
public data class ListingGapKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The track sells it on the channel today and the new catalogue leaves it blank there. */
        public val StopsSelling: ListingGapKind = ListingGapKind("stops_selling")

        /** A self-serve plan and period with no ids on any of the connections. */
        public val Unlisted: ListingGapKind = ListingGapKind("unlisted")
    }
}

internal object ListingGapKindSerializer : OpenStringSerializer<ListingGapKind>(
    "ListingGapKind",
    ::ListingGapKind,
    ListingGapKind::value,
)

/**
 * What is wrong with a listing's provider price.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ListingProblemKindSerializer::class)
public data class ListingProblemKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The connection has no such price. */
        public val PriceNotFound: ListingProblemKind = ListingProblemKind("price_not_found")

        /** The price is archived. */
        public val PriceInactive: ListingProblemKind = ListingProblemKind("price_inactive")

        /** The price bills on another interval than the billing period, or recurs for a one-time plan. */
        public val IntervalMismatch: ListingProblemKind = ListingProblemKind("interval_mismatch")
    }
}

internal object ListingProblemKindSerializer : OpenStringSerializer<ListingProblemKind>(
    "ListingProblemKind",
    ::ListingProblemKind,
    ListingProblemKind::value,
)

/**
 * A provider that sells through a connection.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ProviderSerializer::class)
public data class Provider(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Stripe. */
        public val Stripe: Provider = Provider("stripe")

        /** The App Store. */
        public val Apple: Provider = Provider("apple")

        /** Google Play. */
        public val Google: Provider = Provider("google")
    }
}

internal object ProviderSerializer : OpenStringSerializer<Provider>(
    "Provider",
    ::Provider,
    Provider::value,
)

/**
 * Whether a connection takes real money or test money.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ConnectionModeSerializer::class)
public data class ConnectionMode(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Real money. */
        public val Live: ConnectionMode = ConnectionMode("live")

        /** Test money. */
        public val Test: ConnectionMode = ConnectionMode("test")
    }
}

internal object ConnectionModeSerializer : OpenStringSerializer<ConnectionMode>(
    "ConnectionMode",
    ::ConnectionMode,
    ConnectionMode::value,
)

/**
 * Whether a plan is a base plan or an add-on.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PlanKindSerializer::class)
public data class PlanKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** A base plan. */
        public val Plan: PlanKind = PlanKind("plan")

        /** An add-on. */
        public val AddOn: PlanKind = PlanKind("addon")
    }
}

internal object PlanKindSerializer : OpenStringSerializer<PlanKind>(
    "PlanKind",
    ::PlanKind,
    PlanKind::value,
)

/**
 * Whether a plan is still sold to new customers.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PlanStatusSerializer::class)
public data class PlanStatus(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Sold to new customers. */
        public val Active: PlanStatus = PlanStatus("active")

        /** Kept for the customers who hold it, sold to no one new. */
        public val Legacy: PlanStatus = PlanStatus("legacy")
    }
}

internal object PlanStatusSerializer : OpenStringSerializer<PlanStatus>(
    "PlanStatus",
    ::PlanStatus,
    PlanStatus::value,
)

/**
 * The unit of a billing period.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PeriodUnitSerializer::class)
public data class PeriodUnit(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Days. */
        public val Days: PeriodUnit = PeriodUnit("days")

        /** Weeks. */
        public val Weeks: PeriodUnit = PeriodUnit("weeks")

        /** Months. */
        public val Months: PeriodUnit = PeriodUnit("months")
    }
}

internal object PeriodUnitSerializer : OpenStringSerializer<PeriodUnit>(
    "PeriodUnit",
    ::PeriodUnit,
    PeriodUnit::value,
)

/**
 * The unit of a meter's reset window.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MeterUnitSerializer::class)
public data class MeterUnit(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Hours. */
        public val Hours: MeterUnit = MeterUnit("hours")

        /** Days. */
        public val Days: MeterUnit = MeterUnit("days")

        /** Weeks. */
        public val Weeks: MeterUnit = MeterUnit("weeks")

        /** Months. */
        public val Months: MeterUnit = MeterUnit("months")
    }
}

internal object MeterUnitSerializer : OpenStringSerializer<MeterUnit>(
    "MeterUnit",
    ::MeterUnit,
    MeterUnit::value,
)

/**
 * The move that would give a customer more of a feature.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UpgradeMoveSerializer::class)
public data class UpgradeMove(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Subscribe to the plan. */
        public val Subscribe: UpgradeMove = UpgradeMove("subscribe")

        /** Move up to the plan. */
        public val Upgrade: UpgradeMove = UpgradeMove("upgrade")

        /** Switch to the plan. */
        public val Switch: UpgradeMove = UpgradeMove("switch")

        /** Add the plan as an add-on. */
        public val Add: UpgradeMove = UpgradeMove("add")
    }
}

internal object UpgradeMoveSerializer : OpenStringSerializer<UpgradeMove>(
    "UpgradeMove",
    ::UpgradeMove,
    UpgradeMove::value,
)

/**
 * How a move changes a feature for the customer.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ImpactKindSerializer::class)
public data class ImpactKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The customer gains the feature. */
        public val Gains: ImpactKind = ImpactKind("Gains")

        /** The customer loses the feature. */
        public val Loses: ImpactKind = ImpactKind("Loses")

        /** The feature's value changes. */
        public val Changes: ImpactKind = ImpactKind("Changes")

        /** The feature stays the same. */
        public val Same: ImpactKind = ImpactKind("Same")
    }
}

internal object ImpactKindSerializer : OpenStringSerializer<ImpactKind>(
    "ImpactKind",
    ::ImpactKind,
    ImpactKind::value,
)

/**
 * How often a provider price recurs.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PriceIntervalSerializer::class)
public data class PriceInterval(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Daily. */
        public val Day: PriceInterval = PriceInterval("day")

        /** Weekly. */
        public val Week: PriceInterval = PriceInterval("week")

        /** Monthly. */
        public val Month: PriceInterval = PriceInterval("month")

        /** Yearly. */
        public val Year: PriceInterval = PriceInterval("year")
    }
}

internal object PriceIntervalSerializer : OpenStringSerializer<PriceInterval>(
    "PriceInterval",
    ::PriceInterval,
    PriceInterval::value,
)

/**
 * Whether a provider price includes tax.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = TaxBehaviourSerializer::class)
public data class TaxBehaviour(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The price includes tax. */
        public val Inclusive: TaxBehaviour = TaxBehaviour("inclusive")

        /** Tax is added to the price. */
        public val Exclusive: TaxBehaviour = TaxBehaviour("exclusive")

        /** The provider does not say. */
        public val Unspecified: TaxBehaviour = TaxBehaviour("unspecified")
    }
}

internal object TaxBehaviourSerializer : OpenStringSerializer<TaxBehaviour>(
    "TaxBehaviour",
    ::TaxBehaviour,
    TaxBehaviour::value,
)

/**
 * The kind of move to a plan.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PlanMoveSerializer::class)
public data class PlanMove(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Subscribe to a first plan. */
        public val Subscribe: PlanMove = PlanMove("subscribe")

        /** Move from the held plan. */
        public val Move: PlanMove = PlanMove("move")

        /** Add an add-on. */
        public val Add: PlanMove = PlanMove("add")

        /** Replace the held plan in another product. */
        public val Replace: PlanMove = PlanMove("replace")
    }
}

internal object PlanMoveSerializer : OpenStringSerializer<PlanMove>(
    "PlanMove",
    ::PlanMove,
    PlanMove::value,
)

/**
 * Which way a move goes.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MoveDirectionSerializer::class)
public data class MoveDirection(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** An upgrade. */
        public val Up: MoveDirection = MoveDirection("up")

        /** A downgrade. */
        public val Down: MoveDirection = MoveDirection("down")

        /** A move across, neither up nor down. */
        public val Cross: MoveDirection = MoveDirection("cross")
    }
}

internal object MoveDirectionSerializer : OpenStringSerializer<MoveDirection>(
    "MoveDirection",
    ::MoveDirection,
    MoveDirection::value,
)

/**
 * How a plan is sold.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = SellingSerializer::class)
public data class Selling(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The customer moves themselves. */
        public val SelfServe: Selling = Selling("self-serve")

        /** Only the vendor can make the move. */
        public val SalesLed: Selling = Selling("sales-led")
    }
}

internal object SellingSerializer : OpenStringSerializer<Selling>(
    "Selling",
    ::Selling,
    Selling::value,
)

/**
 * When a move takes effect.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MoveTimingSerializer::class)
public data class MoveTiming(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Now. */
        public val Now: MoveTiming = MoveTiming("now")

        /** At the end of the current period. */
        public val End: MoveTiming = MoveTiming("end")
    }
}

internal object MoveTimingSerializer : OpenStringSerializer<MoveTiming>(
    "MoveTiming",
    ::MoveTiming,
    MoveTiming::value,
)

/**
 * How a customer holds their plan.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = CustomerKindSerializer::class)
public data class CustomerKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** A recurring subscription. */
        public val Recurring: CustomerKind = CustomerKind("recurring")

        /** A one-time purchase. */
        public val OneTime: CustomerKind = CustomerKind("one_time")

        /** A plan change is under way. */
        public val Changing: CustomerKind = CustomerKind("changing")

        /** The default plan, with no purchase. */
        public val Default: CustomerKind = CustomerKind("default")

        /** No plan. */
        public val None: CustomerKind = CustomerKind("none")
    }
}

internal object CustomerKindSerializer : OpenStringSerializer<CustomerKind>(
    "CustomerKind",
    ::CustomerKind,
    CustomerKind::value,
)

/**
 * Who put a customer on their track.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = TrackSourceSerializer::class)
public data class TrackSource(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Your server. */
        public val Server: TrackSource = TrackSource("server")

        /** Someone in the dashboard. */
        public val Dashboard: TrackSource = TrackSource("dashboard")

        /** A store sandbox purchase. */
        public val StoreSandbox: TrackSource = TrackSource("store_sandbox")
    }
}

internal object TrackSourceSerializer : OpenStringSerializer<TrackSource>(
    "TrackSource",
    ::TrackSource,
    TrackSource::value,
)

/**
 * The kind of entry in the usage log.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageEventKindSerializer::class)
public data class UsageEventKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Usage reported. */
        public val Use: UsageEventKind = UsageEventKind("use")

        /** The meter set to a value. */
        public val Adjust: UsageEventKind = UsageEventKind("adjust")
    }
}

internal object UsageEventKindSerializer : OpenStringSerializer<UsageEventKind>(
    "UsageEventKind",
    ::UsageEventKind,
    UsageEventKind::value,
)
