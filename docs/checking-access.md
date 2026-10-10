# Checking access

A check asks Entitler whether a customer is entitled to one feature. The answer is the server's
decision, `entitled`, with the customer's value of the feature and where it comes from.

```kotlin
val check = customer.check(Features.exportPdf)
if (check.entitled) {
    exportPdf()
} else {
    println("Upgrade to ${check.upgrades.firstOrNull()?.name}")
}
```

Checking a `Feature.Metered` answers a `MeteredCheck`, with its meter:

```kotlin
val credits = customer.check(Features.aiCredits)
println("${credits.used} used, ${credits.held} on hold, ${credits.remaining} left, starting again at ${credits.resetsAt}")
```

A feature's value is a sealed interface, so `when` is exhaustive:

```kotlin
val seats =
    when (val value = customer.check(Features.teamSeats).value) {
        FeatureValue.On -> "Included"
        is FeatureValue.Amount -> "${value.value} seats"
        FeatureValue.Unlimited -> "Unlimited seats"
    }
```

Never decide entitlement from the value yourself. For a metered feature, `entitled` means allowance
remains: a feature with an allowance of 100 and nothing remaining has a value of 100 and is not
entitled.

## Asking again with `revalidate`

Reads keep an answer for as long as Entitler's `max-age` allows (see [reliability](reliability.md)).
Right after a change the customer made, such as an upgrade or the [return page](billing-pages.md#the-return-page),
pass `revalidate = true` so the read asks Entitler again instead of answering the kept one:

```kotlin
val check = customer.check(Features.exportPdf, revalidate = true)
if (check.entitled) showExportButton()
```

Every read takes `revalidate`: `check`, `isEntitled`, `entitlements`, `plans`, `pricing` and `usage`.

## `isEntitled`: a check that never fails

`isEntitled(feature, default)` answers `check(feature).entitled`. When the check fails for any reason
(Entitler unreachable, a timeout, a revoked key), it answers `default` and passes the error to
`onError`, so a feature check never takes your app down. Cancellation still propagates.

```kotlin
val canExport = customer.isEntitled(Features.exportPdf)
val canSeeTips = customer.isEntitled(Feature.OnOff("tips"), default = true)
```

Choose between failing closed and failing open:

- Fail closed (`default = false`, the default) for paid features: an outage never gives away what
  customers pay for.
- Fail open (`default = true`) only where losing a sale or annoying a customer is worse than giving the
  feature away, such as tips or onboarding.

When Entitler is unreachable and the cache holds an earlier answer, `isEntitled` answers that one,
marked `stale` (see [reliability](reliability.md)).

## Every entitlement at once

`entitlements()` answers the customer's whole list, groups included, each with the server's decision:

```kotlin
val entitlements = customer.entitlements()
if (entitlements.has(Features.collaboration)) {
    println("Team tools are on.")
}
val seats = entitlements[Features.teamSeats]?.value
```

`has` answers false for a feature the list does not hold, and never expands a group itself.

## Features without the generator

Generated constants are the primary path (see [feature constants](feature-constants.md)). Declare one
by hand when you do not use the generator:

```kotlin
object MyFeatures {
    /** Download any document as a PDF. */
    val exportPdf = Feature.OnOff("export_pdf")

    /** Spent on AI actions each month. */
    val aiCredits = Feature.Metered("ai_credits")
}
```

`check` and `isEntitled` also take a plain key, such as `customer.check("export_pdf")`.
