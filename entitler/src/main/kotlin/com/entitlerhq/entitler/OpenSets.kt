package com.entitlerhq.entitler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An error code from the API, or one of the SDK's own (`http_error`, `snapshot_invalid`, `snapshot_expired`).
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = ErrorCodeSerializer::class)
public data class ErrorCode(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
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
    override fun toString(): String = value

    /** The values this SDK knows. */
    public companion object {
        /** Reads pricing and the feature list. */
        public val PlansRead: Scope = Scope("plans:read")

        /** Reads checks, entitlement lists, plan space, customer pricing and snapshots. */
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
 * Which kind of credential reported usage.
 *
 * An open set: a value this SDK does not know keeps its raw [value].
 */
@Serializable(with = UsageSourceSerializer::class)
public data class UsageSource(
    /** The raw value, as the API sends it. */
    public val value: String,
) {
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
