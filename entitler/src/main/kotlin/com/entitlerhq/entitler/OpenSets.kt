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

        /** Another provider already bills that product. */
        public val BilledElsewhere: ErrorCode = ErrorCode("billed_elsewhere")

        /** The request body is too large. */
        public val BodyTooLarge: ErrorCode = ErrorCode("body_too_large")

        /** The route refuses requests from a browser. */
        public val BrowserNotAllowed: ErrorCode = ErrorCode("browser_not_allowed")

        /** A cap in the organisation's Entitler plan is reached. */
        public val CapReached: ErrorCode = ErrorCode("cap_reached")

        /** The organisation's plan lacks the capability the request needs; the message names it. */
        public val CapabilityRequired: ErrorCode = ErrorCode("capability_required")

        /** A carried-forward change conflicts with another. */
        public val CarryForwardConflict: ErrorCode = ErrorCode("carry_forward_conflict")

        /** The catalogue must be empty for this change. */
        public val CatalogueNotEmpty: ErrorCode = ErrorCode("catalogue_not_empty")

        /** The change is locked. */
        public val ChangeLocked: ErrorCode = ErrorCode("change_locked")

        /** The track needs a change for this. */
        public val ChangeRequired: ErrorCode = ErrorCode("change_required")

        /** No answer arrived for a batch request; the SDK's own code, only on batch event results. */
        public val ConnectionFailed: ErrorCode = ErrorCode("connection_failed")

        /** The connection is in use. */
        public val ConnectionInUse: ErrorCode = ErrorCode("connection_in_use")

        /** The connection is in the wrong mode. */
        public val ConnectionMode: ErrorCode = ErrorCode("connection_mode")

        /** The call needs a payment provider connection. */
        public val ConnectionRequired: ErrorCode = ErrorCode("connection_required")

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

        /** The request body is not valid. */
        public val InvalidBody: ErrorCode = ErrorCode("invalid_body")

        /** The idempotency key is not valid. */
        public val InvalidIdempotencyKey: ErrorCode = ErrorCode("invalid_idempotency_key")

        /** The usage instant is outside the window the API accepts. */
        public val InvalidOccurredAt: ErrorCode = ErrorCode("invalid_occurred_at")

        /** The path is not valid. */
        public val InvalidPath: ErrorCode = ErrorCode("invalid_path")

        /** The API sent a 2xx answer this SDK cannot read; the SDK's own code. */
        public val InvalidResponse: ErrorCode = ErrorCode("invalid_response")

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

        /** The sign-in provider does not let people register themselves. */
        public val RegistrationClosed: ErrorCode = ErrorCode("registration_closed")

        /** The change needs a payment page, so pass a return URL. */
        public val ReturnUrlRequired: ErrorCode = ErrorCode("return_url_required")

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

        /** No snapshot signing key is available. */
        public val SnapshotKeyUnavailable: ErrorCode = ErrorCode("snapshot_key_unavailable")

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

        /** Lets a customer token or identity token make the customer's own billing choices. */
        public val BillingSelf: Scope = Scope("billing:self")

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
 * Where a meter's reset windows start.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MeterAnchorSerializer::class)
public data class MeterAnchor(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** From the start of the customer's billing period, the period's end closing the last window early. */
        public val Period: MeterAnchor = MeterAnchor("period")

        /** On calendar boundaries in the project's time zone. */
        public val Calendar: MeterAnchor = MeterAnchor("calendar")
    }
}

internal object MeterAnchorSerializer : OpenStringSerializer<MeterAnchor>(
    "MeterAnchor",
    ::MeterAnchor,
    MeterAnchor::value,
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
        public val Gains: ImpactKind = ImpactKind("gains")

        /** The customer loses the feature. */
        public val Loses: ImpactKind = ImpactKind("loses")

        /** The feature's value changes. */
        public val Changes: ImpactKind = ImpactKind("changes")

        /** The feature stays the same. */
        public val Same: ImpactKind = ImpactKind("same")
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

/**
 * The kind of move to a plan.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MoveSerializer::class)
public data class Move(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Subscribe from no plan in a product. */
        public val Subscribe: Move = Move("subscribe")

        /** Move up to another plan. */
        public val Upgrade: Move = Move("upgrade")

        /** Move down to another plan. */
        public val Downgrade: Move = Move("downgrade")

        /** Switch to a plan neither up nor down. */
        public val Switch: Move = Move("switch")

        /** Add an add-on. */
        public val Add: Move = Move("add")

        /** Replace an add-on with another. */
        public val Replace: Move = Move("replace")
    }
}

internal object MoveSerializer : OpenStringSerializer<Move>(
    "Move",
    ::Move,
    Move::value,
)

/**
 * Who can make a move.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MoveActionSerializer::class)
public data class MoveAction(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The customer can make the move alone. */
        public val Buy: MoveAction = MoveAction("buy")

        /** A sales-led move, which only the company can make. */
        public val Contact: MoveAction = MoveAction("contact")

        /** Neither can make it now; the answer says why. */
        public val Unavailable: MoveAction = MoveAction("unavailable")
    }
}

internal object MoveActionSerializer : OpenStringSerializer<MoveAction>(
    "MoveAction",
    ::MoveAction,
    MoveAction::value,
)

/**
 * The kind of change booked for a renewal.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = BookedChangeTypeSerializer::class)
public data class BookedChangeType(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** A move to another plan. */
        public val Move: BookedChangeType = BookedChangeType("move")

        /** A cancel, moving to the default plan. */
        public val Cancel: BookedChangeType = BookedChangeType("cancel")

        /** A change of who bills the plan from renewal. */
        public val Billing: BookedChangeType = BookedChangeType("billing")
    }
}

internal object BookedChangeTypeSerializer : OpenStringSerializer<BookedChangeType>(
    "BookedChangeType",
    ::BookedChangeType,
    BookedChangeType::value,
)

/**
 * Who bills a plan from renewal, in a [BookedChange.Billing].
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = BookedBillingSerializer::class)
public data class BookedBilling(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The payment provider that bills the product. */
        public val Provider: BookedBilling = BookedBilling("provider")

        /** Nobody: the customer holds the plan outside any provider, as with an invoiced contract. */
        public val Outside: BookedBilling = BookedBilling("outside")

        /** An app store. */
        public val Store: BookedBilling = BookedBilling("store")
    }
}

internal object BookedBillingSerializer : OpenStringSerializer<BookedBilling>(
    "BookedBilling",
    ::BookedBilling,
    BookedBilling::value,
)

/**
 * When a plan change takes effect.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = TimingSerializer::class)
public data class Timing(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Now. */
        public val Now: Timing = Timing("now")

        /** At the end of the current period, at renewal. */
        public val End: Timing = Timing("end")
    }
}

internal object TimingSerializer : OpenStringSerializer<Timing>(
    "Timing",
    ::Timing,
    Timing::value,
)

/**
 * How a company plan change is billed.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PlanBillingSerializer::class)
public data class PlanBilling(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Charge the change through the provider that bills the product. */
        public val Provider: PlanBilling = PlanBilling("provider")

        /** Move the customer in Entitler while the provider keeps billing the plan it bills, until a later change. */
        public val Keep: PlanBilling = PlanBilling("keep")

        /** End the provider's subscription when the change takes effect, so the customer holds the plan outside any provider. */
        public val End: PlanBilling = PlanBilling("end")
    }
}

internal object PlanBillingSerializer : OpenStringSerializer<PlanBilling>(
    "PlanBilling",
    ::PlanBilling,
    PlanBilling::value,
)

/**
 * The version of the plan held that [ServerCustomer.setPlan] moves the customer to.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = PlanVersionSerializer::class)
public data class PlanVersion(
    /** The raw value, as the API takes it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The newest version of the plan the customer holds, ending a grandfathered one, now. */
        public val Latest: PlanVersion = PlanVersion("latest")
    }
}

internal object PlanVersionSerializer : OpenStringSerializer<PlanVersion>(
    "PlanVersion",
    ::PlanVersion,
    PlanVersion::value,
)

/**
 * When a plan change took or takes effect.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = EffectiveSerializer::class)
public data class Effective(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Now. */
        public val Now: Effective = Effective("now")

        /** At renewal. */
        public val Renewal: Effective = Effective("renewal")
    }
}

internal object EffectiveSerializer : OpenStringSerializer<Effective>(
    "Effective",
    ::Effective,
    Effective::value,
)

/**
 * Whether a purchase was paid with real money or test money.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = MoneyKindSerializer::class)
public data class MoneyKind(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Money that is charged. */
        public val Real: MoneyKind = MoneyKind("real")

        /** Test money, never charged. */
        public val Test: MoneyKind = MoneyKind("test")
    }
}

internal object MoneyKindSerializer : OpenStringSerializer<MoneyKind>(
    "MoneyKind",
    ::MoneyKind,
    MoneyKind::value,
)

/**
 * A provider subscription's status.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = SubscriptionStatusSerializer::class)
public data class SubscriptionStatus(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
    /** The raw value. */
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** The first payment has not succeeded yet. */
        public val Incomplete: SubscriptionStatus = SubscriptionStatus("incomplete")

        /** The first payment never succeeded. */
        public val IncompleteExpired: SubscriptionStatus = SubscriptionStatus("incomplete_expired")

        /** In a trial. */
        public val Trialing: SubscriptionStatus = SubscriptionStatus("trialing")

        /** Active and paid. */
        public val Active: SubscriptionStatus = SubscriptionStatus("active")

        /** A renewal payment failed and is being retried. */
        public val PastDue: SubscriptionStatus = SubscriptionStatus("past_due")

        /** Cancelled. */
        public val Canceled: SubscriptionStatus = SubscriptionStatus("canceled")

        /** Renewal payments failed. */
        public val Unpaid: SubscriptionStatus = SubscriptionStatus("unpaid")

        /** Paused. */
        public val Paused: SubscriptionStatus = SubscriptionStatus("paused")
    }
}

internal object SubscriptionStatusSerializer : OpenStringSerializer<SubscriptionStatus>(
    "SubscriptionStatus",
    ::SubscriptionStatus,
    SubscriptionStatus::value,
)
