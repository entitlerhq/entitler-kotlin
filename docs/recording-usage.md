# Recording usage

Usage is recorded on metered features, in whole numbers of the feature's unit. Usage methods take only
`Feature.Metered`, so a typo or an on/off feature fails to compile.

## Idempotency keys name the event

Every report and hold requires an idempotency key, kept by Entitler per customer and never freed, so a
retried write never counts twice, whether the retry comes from the SDK, another process or a restart.
Name the key after the event that caused the usage (this message, this export request, this webhook
delivery), never after an object whose state changes, such as a document or a deal: a key reused after
the object changes replays the first answer and records nothing. Reusing a key for a different request
answers `422 idempotency_mismatch`. A replay answers with `replayed` true.

## Gate mode

`gate`, the API's default, records only if the amount fits the allowance. Use it before work that must
not start without allowance. A refusal is an answer, not an error:

```kotlin
val result = customer.recordUsage(Features.aiCredits, 5, idempotencyKey = "job-$jobId")
when (result.outcome) {
    UsageOutcome.Recorded, UsageOutcome.Duplicate -> println("${result.remaining} credits left")
    UsageOutcome.Refused -> showUpgradePrompt()
}
```

`refusal` says why: `not_entitled` or `over_allowance`. A refused report stores no key, so the same key
succeeds once there is allowance.

## Observe mode

`observe` always records what happened, and `overBy` says how far the meter is past the allowance. Use
it for work that already happened, such as streamed tokens, bandwidth or minutes. `occurredAt` places
the usage in the period it happened in:

```kotlin
val result =
    customer.recordUsage(
        Features.aiCredits,
        tokens,
        mode = UsageMode.Observe,
        occurredAt = startedAt,
        idempotencyKey = "stream-$streamId",
    )
if (result.overBy > 0) println("Over the allowance by ${result.overBy}")
```

## Holds around work: `startHold`

A hold reserves an amount before work whose cost is known only as it goes, such as a streamed model
reply. `startHold` holds the amount and answers a `Hold`, a `Closeable` handle. Set `hold.used` to the
total used so far as the work goes (no request is sent), then `finish()`:

```kotlin
try {
    customer.startHold(Features.aiCredits, 500, idempotencyKey = "reply-$messageId").use { hold ->
        hold.used = 0
        var tokens = 0L
        chunks.collect { chunk ->
            tokens += chunk.length
            hold.used = tokens
            print(chunk)
        }
        hold.finish()
    }
} catch (refused: EntitlerUsageRefusedException) {
    println("Refused: ${refused.result.refusal}")
} catch (replayed: EntitlerUsageReplayedException) {
    println("Already streamed: ${replayed.result.outcome}")
}
```

- `finish()` charges for the work that happened, whether it succeeded, failed or was cut off: it
  settles `used`, or the held amount when nothing was reported. Anything past the held amount is
  recorded in observe mode under the hold's key plus `:excess`, and so is the whole amount when the hold
  expired first. Streamed work sets `used = 0` before it starts, so finishing after a failure that used
  nothing frees the whole hold.
- `release()` frees the hold, recording nothing, for work that never ran.
- `close()`, through `use`, acts only when neither ran: it settles `used` when it was set, else
  releases. So a disconnect after 3,000 tokens charges for 3,000 tokens.
- Only the first of `finish`, `release` and `close` acts; later calls answer its result. Each runs to
  the end even when the caller is cancelled.
- A refused hold throws `EntitlerUsageRefusedException`: there is no allowance. A key that replays a
  hold already settled, released or expired throws `EntitlerUsageReplayedException`: the work this key
  stands for already happened, so never show a paying customer an upgrade prompt for it.
- `isDuplicate` is true when another caller holds the same key and may be doing the same work, so you
  can wait for that caller's result instead of paying for the work twice.
- When `finish()` cannot settle, it throws `EntitlerUsageSettlementException` with the hold id, the
  amount to settle and the excess still to record: keep your output and call `settleUsage` before the
  hold expires. Failures inside `close()` go to `onError`; a hold never settled expires on its own.
- The key is at most 193 characters, leaving room for `:excess`. `ttl` runs from 1 second to an hour,
  5 minutes by default.

While work runs, `remaining` drops by the whole hold and rises again when it settles, so a live meter
shows `held` apart from `used`:

```kotlin
val credits = customer.check(Features.aiCredits)
println("${credits.used} used, ${credits.held} on hold, ${credits.remaining} left, starting again at ${credits.resetsAt}")
```

## `withHold`: work inside one call

`withHold` wraps `startHold` for work that runs inside one call: it holds the amount, runs your block,
finishes with the amount the block set in `hold.used`, and answers the block's own result. When the
block throws or is cancelled, it settles `used` when set, else releases, and the error propagates.

```kotlin
try {
    val summary =
        customer.withHold(Features.aiCredits, 100, idempotencyKey = "summary-$jobId") { hold ->
            summarise("…").also { hold.used = it.creditsUsed }
        }
    println(summary.text)
} catch (refused: EntitlerUsageRefusedException) {
    println("Refused: ${refused.result.refusal}")
} catch (replayed: EntitlerUsageReplayedException) {
    println("Already summarised: ${replayed.result.outcome}")
}
```

A settlement failure carries the block's result in `EntitlerUsageSettlementException.result`. The
accounting happens exactly once, but the block does not: two callers sharing a key may both run it.
Read `hold.isDuplicate`, or coordinate the work yourself when it must run once.

## Holds by hand

`holdUsage`, `settleUsage` and `releaseUsage` are the requests underneath, for a hold settled from
another process. Settle with the real amount, from 0 to the amount held:

```kotlin
val hold = customer.holdUsage(Features.aiCredits, 100, ttl = 10.minutes, idempotencyKey = "hold-$jobId")
val holdId = hold.holdId ?: return showUpgradePrompt()
var settled = false
try {
    val summary = summarise("…")
    customer.settleUsage(holdId, summary.creditsUsed)
    settled = true
} finally {
    if (!settled) customer.releaseUsage(holdId)
}
```

Settling the same amount again answers `duplicate`; a hold that was settled, released or expired
answers `409 hold_settled`, `hold_released` or `hold_expired`. Releasing twice is safe.

## Batches

On the server, `recordUsageBatch` records events of many customers in observe mode, in requests of at
most 500 events sent in order, and answers one result per event in input order. Every event carries its
own idempotency key, and each request's key is derived from its events, so resending the same events
replays the first answer. The call never throws: an event the SDK can refuse (a blank customer, a bad
amount or key) is answered `error` and not sent, and a request that fails after its retries answers its
events `error` (with `connection_failed` or `timed_out` when no answer arrived) while the next request
still goes. Resend the events answered `error`; their keys make it safe.

```kotlin
val events =
    rows.map { row ->
        UsageBatchEvent(row.customerId, Features.aiCredits, row.amount, idempotencyKey = "meter-row-${row.rowId}", occurredAt = row.at)
    }
val result = entitler.recordUsageBatch(events)
println("${result.recorded} recorded, ${result.duplicates} already recorded, ${result.errors} not recorded")
```

## Corrections

`cancelUsage(usageId)` takes a report off the meter, and `adjustMeter` moves it `by` an amount or sets
it `to` one (see [company decisions](company-decisions.md)).

## Limits on devices

Reports and holds sent with customer and identity tokens count towards 100 every 10 seconds per
customer, shared by all of that customer's devices; settlements and releases do not count. The SDK
retries `429 rate_limited` answers. Counting on a device is advisory: a modified app can skip it, so
only usage recorded by your server can be enforced.
