package com.entitlerhq.entitler

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Every failure the SDK raises. Cancellation surfaces as the coroutine's own `CancellationException`.
 *
 * ```kotlin
 * try {
 *     customer.vendor.grant(Features.sso, days = 30)
 * } catch (e: EntitlerApiException) {
 *     if (e.code == ErrorCode.ScopeRequired) log("The key needs customers:write.")
 * }
 * ```
 */
public sealed class EntitlerException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The API answered with a status that is not 2xx, or with a 2xx answer this SDK cannot read
 * ([ErrorCode.InvalidResponse], with the decoding failure as its cause).
 *
 * @property status The HTTP status.
 * @property code The error code, or [ErrorCode.HttpError] when the answer had none.
 * @property requestId The `x-request-id` header, for Entitler support, or null.
 * @property retryAfter How long the API asked to wait before trying again, or null.
 * @property idempotencyKey The key the request sent, to repeat the call with later, or null.
 * @property payment For `402 payment_required`, the payment a plan change waits on, or null.
 * @property listingGaps For `409 listing_gaps`, the gaps, else empty.
 * @property listingProblems For `409 listing_invalid`, the problems, else empty.
 */
public class EntitlerApiException(
    public val status: Int,
    public val code: ErrorCode,
    message: String,
    public val requestId: String? = null,
    public val retryAfter: Duration? = null,
    public val idempotencyKey: String? = null,
    public val payment: Payment? = null,
    public val listingGaps: List<ListingGap> = emptyList(),
    public val listingProblems: List<ListingProblem> = emptyList(),
    cause: Throwable? = null,
) : EntitlerException(message, cause)

/**
 * No answer arrived: DNS, TLS, or a connection refused or reset.
 *
 * @property idempotencyKey The key the request sent, to repeat the call with later, or null.
 */
public class EntitlerConnectionException(
    message: String,
    cause: Throwable? = null,
    public val idempotencyKey: String? = null,
) : EntitlerException(message, cause)

/**
 * An attempt took longer than its timeout.
 *
 * @property idempotencyKey The key the request sent, to repeat the call with later, or null.
 */
public class EntitlerTimeoutException(
    message: String,
    cause: Throwable? = null,
    public val idempotencyKey: String? = null,
) : EntitlerException(message, cause)

/**
 * A token provider failed, or answered a blank or unreadable token. The message never holds the token;
 * a provider's own error is kept as the cause, and its contents are outside the SDK's control.
 */
public class EntitlerTokenException(
    message: String,
    cause: Throwable? = null,
) : EntitlerException(message, cause)

/**
 * A snapshot failed verification. No request was made.
 *
 * @property code [ErrorCode.SnapshotInvalid] or [ErrorCode.SnapshotExpired].
 */
public class EntitlerSnapshotException(
    public val code: ErrorCode,
    message: String,
) : EntitlerException(message)

/**
 * `withHold` could not hold the amount, or its key replays a hold already settled, released or
 * expired, so the work never ran.
 *
 * @property result The hold's answer, with its outcome, refusal and meter.
 */
public class EntitlerUsageRefusedException(
    public val result: UsageResult,
) : EntitlerException("Entitler did not hold ${result.amount} of ${result.feature}: ${result.refusal ?: result.outcome}.")

/**
 * `withHold`'s work succeeded, but settling the hold or recording the excess failed after its
 * retries. Keep the work's output and call `settleUsage(holdId, amount)` before the hold expires.
 *
 * @property holdId The hold to settle.
 * @property amount The amount to settle.
 * @property excess The amount still to record in observe mode, or null when there is none.
 * @property result The work's result.
 */
public class EntitlerUsageSettlementException(
    public val holdId: String,
    public val amount: Long,
    public val excess: Long?,
    public val result: Any?,
    cause: Throwable,
) : EntitlerException("Entitler could not settle hold $holdId after the work succeeded: ${cause.message}", cause)

/**
 * The payment a plan change waits on.
 *
 * @property status Why the payment has not succeeded.
 * @property url The provider's page where the customer pays or confirms it, or null.
 */
@Serializable
public data class Payment(
    val status: PaymentStatus,
    val url: String? = null,
)

/**
 * A gap a rollout or experiment would leave in the listings.
 *
 * @property kind [ListingGapKind.StopsSelling] or [ListingGapKind.Unlisted].
 * @property plan The plan's id.
 * @property key The plan's key.
 * @property period The period.
 * @property channel The connection, or null.
 */
@Serializable
public data class ListingGap(
    val kind: ListingGapKind,
    val plan: String,
    val key: String,
    val period: String,
    val channel: Channel? = null,
)

/**
 * A listing whose provider price fails the checks.
 *
 * @property channel The connection.
 * @property plan The plan's id.
 * @property period The period.
 * @property ids The provider's ids.
 * @property problem What is wrong with the price.
 */
@Serializable
public data class ListingProblem(
    val channel: Channel,
    val plan: String,
    val period: String,
    val ids: Map<String, String>,
    val problem: ListingProblemKind,
)
