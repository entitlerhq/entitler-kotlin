# Errors

Every failure is an `EntitlerException`, a sealed class (unchecked for Java callers). Cancellation is
the coroutine's own `CancellationException`, never an `EntitlerException`. Bad arguments, such as a
blank customer id, are `IllegalArgumentException`s, raised before any request.

| Exception | When | Carries |
| --- | --- | --- |
| `EntitlerApiException` | the API answered with a status that is not 2xx | `status`, `code`, `message`, `requestId`, `retryAfter`, `idempotencyKey`, `payment`, `listingGaps`, `listingProblems` |
| `EntitlerConnectionException` | no answer arrived, or one the SDK cannot read | `cause`, `idempotencyKey` |
| `EntitlerTimeoutException` | an attempt took longer than its timeout | `idempotencyKey` |
| `EntitlerTokenException` | a token provider failed or answered an unusable token | `cause` |
| `EntitlerSnapshotException` | a snapshot failed verification | `code`: `snapshot_invalid` or `snapshot_expired` |
| `EntitlerUsageRefusedException` | `withHold` could not hold the amount | `result`, the refused answer |

```kotlin
try {
    customer.vendor.grant(Features.sso, days = 30)
} catch (error: EntitlerException) {
    when (error) {
        is EntitlerApiException -> {
            when (error.code) {
                ErrorCode.ScopeRequired -> println("The key needs customers:write.")
                ErrorCode.CustomerNotFound -> customer.register()
                else -> println("${error.status} ${error.code}: ${error.message} (request ${error.requestId})")
            }
        }

        is EntitlerConnectionException, is EntitlerTimeoutException -> {
            println("Try again with the same key later.")
        }

        is EntitlerTokenException -> {
            println("Sign in again.")
        }

        is EntitlerSnapshotException, is EntitlerUsageRefusedException, is EntitlerUsageSettlementException -> {
            throw error
        }
    }
}
```

A failed write's error carries the idempotency key it sent, so you can repeat the call later with the
same key and never count twice.

## Error codes

`ErrorCode` is an open set: every code the API documents is a constant (`ErrorCode.ScopeRequired`,
`ErrorCode.CustomerNotFound`, `ErrorCode.NotSelfServe`…), and a code this SDK does not know yet keeps
its raw value. `ErrorCode.HttpError` (`http_error`) is the SDK's own, for an answer with no error code.
The codes you are most likely to handle:

| Code | Meaning |
| --- | --- |
| `scope_required` | the credential lacks a scope the call needs |
| `credential_not_allowed` | the route refuses this kind of credential |
| `unauthorised` | the credential is not valid now |
| `customer_not_found` | a write to a customer who is not registered |
| `feature_not_found` | no feature with that key |
| `not_metered` | usage on a feature that is not metered |
| `idempotency_mismatch` | a key reused for a different request |
| `hold_settled`, `hold_released`, `hold_expired` | the hold is closed |
| `not_self_serve` | a self-serve change off the customer's self-serve paths |
| `payment_required` | a plan change waits on a payment, see `payment` |
| `stale` | no payment provider connection, or the answer is out of date |
| `limit_reached` | a limit, such as reading as of another instant without the capability |
| `rate_limited` | too many requests; the SDK retries |
