# Errors

Every failure is an `EntitlerException`, a sealed class (unchecked for Java callers). Cancellation is
the coroutine's own `CancellationException`, never an `EntitlerException`. Bad arguments, such as a
blank customer id, are `IllegalArgumentException`s, raised before any request.

| Exception | When | Carries |
| --- | --- | --- |
| `EntitlerApiException` | the API answered with a status that is not 2xx, or a 2xx answer the SDK cannot read (`invalid_response`) | `status`, `code`, `message`, `requestId`, `retryAfter`, `idempotencyKey`, `payment` |
| `EntitlerConnectionException` | no answer arrived: DNS, TLS, or a connection refused or reset | `cause`, `idempotencyKey` |
| `EntitlerTimeoutException` | an attempt took longer than its timeout | `idempotencyKey` |
| `EntitlerTokenException` | a token provider failed or answered an unusable token | `cause` |
| `EntitlerSnapshotException` | a snapshot failed verification | `code`: `snapshot_invalid` or `snapshot_expired` |
| `EntitlerUsageRefusedException` | `startHold` or `withHold` could not hold the amount | `result`, the refused answer |
| `EntitlerUsageReplayedException` | `startHold` or `withHold` was given the key of a hold already settled, released or expired | `result`, the replayed answer |
| `EntitlerUsageSettlementException` | a hold's `finish()` could not settle | `holdId`, `amount`, `excess`, `result`, `cause` |

A closed client throws `IllegalStateException`. `isUnreachable` answers true when Entitler could not be
reached: a connection failure, a timeout, a failed token provider, and API answers `429`, `5xx` and
`invalid_response`. An offline-capable app falls back to its [snapshot](offline-snapshots.md) then.

```kotlin
try {
    customer.grant(Features.sso, days = 30, actor = "support")
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

        is EntitlerSnapshotException,
        is EntitlerUsageRefusedException,
        is EntitlerUsageReplayedException,
        is EntitlerUsageSettlementException,
        -> {
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
its raw value. `ErrorCode.HttpError` (`http_error`) is the SDK's own, for an answer with no error code,
and `ErrorCode.InvalidResponse` (`invalid_response`) for an answer it cannot read. API messages are
written for you, the developer, not for the people using your app: show your own copy. The codes you are
most likely to handle:

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
| `capability_required` | the organisation's plan lacks a capability, such as as-of reads or the customer portal |
| `rate_limited` | too many requests; the SDK retries |
| `registration_closed` | the sign-in provider does not let people register themselves |

## Codes of the billing calls

| Call | Codes |
| --- | --- |
| `subscribe` | `not_self_serve`, `return_url_required`, `payment_required` (a declined payment), `scope_required` (in-app without `billing:self`), `capability_required` (in-app without the customer portal) |
| `cancel`, `undoPendingChange` | `not_self_serve`, `scope_required`, `capability_required` |
| `billingPortal` | `not_found` (no provider has billed the customer), `stale` (no payment provider connection), `scope_required`, `capability_required` |
| `syncBilling` | `scope_required`, `capability_required` |
| `setPlan` | `billed_elsewhere` (a store SKU where Stripe bills the product), `payment_required` (with `payment.status`), `not_found` (no such plan or SKU) |
| `setAddOn`, `grant`, `revokeGrant`, `adjustMeter` | `billed_elsewhere` (`setAddOn` with a store SKU where Stripe bills the product), `customer_not_found`, `feature_not_found`, `not_found`, `scope_required` |
