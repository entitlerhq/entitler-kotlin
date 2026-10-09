# Pricing pages and visitors

Signed-out pricing comes from your server, with `plans:read`. Neither in-app credential can hold that
scope, so an app's signed-out pricing page calls your server, or your server renders it.

A visitor id keeps a person in the same experiment arm on every page and after signing up. The server
client never generates or keeps one, because one server serves many visitors: mint one with
`newVisitorId()`, keep it in a first-party cookie, and pass it back.

```kotlin
val visitor = cookieVisitor ?: entitler.newVisitorId()
val pricing = entitler.pricing(visitor = visitor)
pricing.plans.forEach { plan -> println("${plan.name}: ${plan.periods.joinToString { it.label }}") }
```

At sign-up, pass the same visitor to `register`, so the new customer keeps the arm:

```kotlin
customer.register(name = "Ada Lovelace", email = "ada@example.com", visitor = visitor)
val pricing = customer.pricing(visitor = visitor)
println(pricing.experiment?.arm)
```

A visitor id matches `VISITOR_ID_PATTERN` (16 to 64 letters, numbers, hyphens or underscores); a new
one is 24 random bytes in base64url. Pricing is always computed now, even with `asOf` set.

Each plan lists its periods and, per period, where it is listed for sale with the provider's price
when there is one. The [pricing-page example](../examples/src/main/kotlin/com/entitlerhq/examples/pricingpage/Main.kt)
prints "Coming soon" for a period with no price yet.
