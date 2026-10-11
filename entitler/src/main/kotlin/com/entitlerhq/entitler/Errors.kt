package com.entitlerhq.entitler

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Every failure the SDK raises. Cancellation surfaces as the coroutine's own `CancellationException`.
 *
 * ```kotlin
 * try {
 *     customer.grant(Features.sso, days = 30, idempotencyKey = ticket.id)
 * } catch (e: EntitlerApiException) {
 *     if (e.code == ErrorCode.ScopeRequired) log("The key needs customers:write.")
 * }
 * ```
 */
public sealed class EntitlerException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * True when Entitler could not be reached: a connection failure, a timeout, a failing token provider,
     * or an answer `429`, `5xx` or [ErrorCode.InvalidResponse]. An offline-capable app falls back to its
     * snapshot when it is true.
     */
    public val isUnreachable: Boolean
        get() =
            when (this) {
                is EntitlerConnectionException, is EntitlerTimeoutException, is EntitlerTokenException -> true
                is EntitlerApiException -> status == TOO_MANY_REQUESTS || status >= SERVER_ERRORS || code == ErrorCode.InvalidResponse
                else -> false
            }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERRORS = 500
    }
}

/**
 * The API answered with a status that is not 2xx, or with a 2xx answer this SDK cannot read
 * ([ErrorCode.InvalidResponse], whose cause names the decoding failure and the answer it expected, never the body).
 *
 * @property status The HTTP status.
 * @property code The error code, or [ErrorCode.HttpError] when the answer had none.
 * @property requestId The `x-request-id` header, for Entitler support, or null.
 * @property retryAfter How long the API asked to wait before trying again, or null.
 * @property idempotencyKey The key the request sent, to repeat the call with later, or null.
 * @property payment For `402 payment_required`, the payment a plan change waits on, or null.
 */
public class EntitlerApiException(
    public val status: Int,
    public val code: ErrorCode,
    message: String,
    public val requestId: String? = null,
    public val retryAfter: Duration? = null,
    public val idempotencyKey: String? = null,
    public val payment: Payment? = null,
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
 * `startHold` or `withHold` could not hold the amount: no allowance remains, or the customer is not
 * entitled. The work never ran.
 *
 * @property result The hold's answer, with its outcome, refusal and meter.
 */
public class EntitlerUsageRefusedException(
    public val result: UsageResult,
) : EntitlerException("Entitler did not hold ${result.amount} of ${result.feature}: ${result.refusal ?: result.outcome}.")

/**
 * A hold's key replays a hold already settled, released or expired, raised by `startHold` and
 * `withHold`. The work the key stands for already happened: show that work's result, never an
 * upgrade prompt.
 *
 * @property result The replay's answer, with its outcome.
 */
public class EntitlerUsageReplayedException(
    public val result: UsageResult,
) : EntitlerException("The hold of ${result.feature} under this key was already ${result.outcome}.")

/**
 * A hold's `finish()` (and so `withHold`, after its work succeeded) could not settle the hold or record
 * the excess after its retries. Keep the work's output, call `settleUsage(holdId, amount)` before the hold
 * expires, and record the excess under the hold's key plus `:excess`.
 *
 * @property holdId The hold to settle.
 * @property amount The amount to settle.
 * @property excess The amount still to record in observe mode, or null when there is none.
 * @property result `withHold`'s work result, or null from a hold's `finish()`.
 */
public class EntitlerUsageSettlementException(
    public val holdId: String,
    public val amount: Long,
    public val excess: Long?,
    public val result: Any?,
    cause: Throwable,
) : EntitlerException("Entitler could not settle hold $holdId after the work: ${cause.message}", cause) {
    internal fun withResult(result: Any?): EntitlerUsageSettlementException =
        EntitlerUsageSettlementException(holdId, amount, excess, result, checkNotNull(cause))
}

/**
 * The payment a plan change waits on.
 *
 * @property status Why the payment has not succeeded.
 * @property url The provider's page where the customer pays or confirms it, or null.
 */
@Serializable
public data class Payment(
    val status: PaymentStatus,
    val url: String?,
)
