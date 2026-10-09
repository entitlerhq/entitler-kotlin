# Changing plans

`plans()` answers the plans a customer holds (`held`) and the plans they can move to (`options`):
subscribing, moving up, down or across, adding an add-on, with what each move changes, its mode
(self-serve or sales-led) and the SKUs that buy it. On the in-app client it needs the organisation's
customer portal capability (`409 limit_reached` otherwise).

```kotlin
val plans = customer.plans()
for (option in plans.options) {
    val label = if (option.selfServe) "Switch to ${option.plan.name}" else "Talk to us about ${option.plan.name}"
    println("$label (${option.direction}, ${option.timing}): ${option.impact.joinToString { it.text }}")
}
```

`timing` is the API's `when` (`now` or `end`), renamed because `when` is a Kotlin keyword. A check
that is not entitled also lists `upgrades`, the plans that would give the feature. Make a self-serve
move with the [billing](billing.md) methods.
