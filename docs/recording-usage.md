# Recording usage

Usage is recorded on metered features, in whole numbers of the feature's unit. Usage methods take only
`Feature.Metered`, so a typo or an on/off feature fails to compile.

## Idempotency keys from your own work

Every usage write carries an idempotency key, kept by Entitler per customer and never freed, so a
retried write never counts twice. Pass a key derived from your own unit of work (a job id, a message
id): a retry from another process, or after a restart, is then recognised. Without one, the SDK
generates a key that protects only its own retries. Reusing a key for a different request answers
`422 idempotency_mismatch`.

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

## Holds

A hold reserves an amount against `remaining` until it is settled, released, or expires (`ttl` from 1
second to an hour; 5 minutes by default). Settle with the real amount, from 0 to the amount held:

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
answers `409 hold_settled`, `hold_released` or `hold_expired`. Releasing twice is safe. `hold(holdId)`
reads a hold back.

## `withHold`

`withHold` does the above for you: it holds the amount, runs your block, and settles the amount the
block answers.

```kotlin
try {
    customer.withHold(Features.aiCredits, 100, idempotencyKey = "summary-$jobId") {
        val summary = summarise("…")
        summary.creditsUsed
    }
} catch (refused: EntitlerUsageRefusedException) {
    println("Refused: ${refused.result.refusal}")
}
```

- A refused hold never runs the block; the call throws `EntitlerUsageRefusedException` with the answer.
- When the block answers more than was held, the excess is recorded in observe mode with the hold's
  key plus `:excess`, and `withHold` answers that report.
- When the block throws or is cancelled, the hold is released (a failed release goes to `onError`) and
  the error propagates.
- A failed settlement propagates with `holdId` set, so you can settle again.

## Batches

On the server, `recordUsageBatch` records events of many customers in observe mode, in requests of at
most 500 events sent in order, and answers one result per event in input order:

```kotlin
val events = rows.map { UsageBatchEvent(it.customerId, Features.aiCredits, it.amount, it.at, idempotencyKey = it.rowId) }
val result = entitler.recordUsageBatch(events)
println("${result.recorded} recorded, ${result.duplicates} already recorded, ${result.errors} not recorded")
```

## Corrections

`vendor.cancelUsage(usageId)` takes a report off the meter, and `vendor.setMeter(feature, used)` sets
the meter. Both answer a `UsageResult` (`cancelled` and `adjusted`). Client reports from customer and
identity tokens are limited to 100 every 10 seconds per customer; the SDK retries `429` answers.
