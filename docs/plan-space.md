# Plan space and upgrades

`planSpace()` answers the plans a customer holds and the moves open to them: subscribing, moving up,
down or across, adding an add-on, with what each move changes and whether the customer may make it
themselves.

```kotlin
val space = customer.planSpace()
for (option in space.options) {
    val label = if (option.selfServe) "Switch to ${option.plan.name}" else "Talk to us about ${option.plan.name}"
    println("$label (${option.direction}, ${option.timing}): ${option.impact.joinToString { it.text }}")
}
```

`timing` is the API's `when` (`now` or `end`), renamed because `when` is a Kotlin keyword. A check
that is not entitled also lists `upgrades`, the plans that would give the feature. Make a self-serve
move with the [billing](billing.md) methods.
