# Pricing pages and visitors

## Signed-out pricing

A pricing page or paywall shown before sign-in reads `pricing()`, which needs `plans:read`. On the web,
your server renders it with the server client. An app with no backend route for it uses the publishable
key alone, which can hold `plans:read` and acts on nobody:

```kotlin
EntitlerPublishableClient(publishableKey).use { entitler ->
    val pricing = entitler.pricing()
    pricing.plans.forEach { plan -> println(plan.name) }
}
```

Each plan lists its periods and, per period, where it is listed for sale with the provider's price when
there is one. The [pricing-page example](../examples/src/main/kotlin/com/entitlerhq/examples/pricingpage/Main.kt)
prints "Coming soon" for a period with no price yet. Pricing is always computed now. Once the customer
signs in, read their own pricing with `me.pricing()`.

## Visitors on the server

A visitor id keeps a person in the same experiment arm on every page and after signing up. The server
client never generates or keeps one, because one server serves many visitors: mint one with
`newVisitorId()`, keep it in a first-party cookie, and pass it back.

```kotlin
val visitor = cookieVisitor ?: entitler.newVisitorId()
val pricing = entitler.pricing(visitor = visitor)
pricing.plans.forEach { plan -> println("${plan.name}: ${plan.periods.joinToString { it.label }}") }
```

## Through sign-up

The signed-out screen's visitor goes into your app's own sign-up request, and your server passes it to
`register`, so the new customer keeps the arm:

```kotlin
customer.register(name = "Ada Lovelace", email = "ada@example.com", visitor = visitor)
val pricing = customer.pricing(visitor = visitor)
println(pricing.experiment?.arm)
```

## Visitors in an app

The in-app clients send a visitor id on every request. They generate one for their lifetime unless
given one: the library is plain JVM with no Android `Context`, so keep the id yourself across launches
(on Android, in DataStore) and pass it as `visitor`, or as `visitorSource`, a `suspend` function the
client calls once before its first request:

```kotlin
val entitler = EntitlerPublishableClient(publishableKey) { visitor = stored }
store(entitler.visitor())
return entitler
```

Reset it at sign-out and at account deletion, so the next person on a shared device is never linked to
the last:

```kotlin
fun resetVisitor(store: (String?) -> Unit) {
    store(newVisitorId())
}
```

A visitor id matches `VISITOR_ID_PATTERN` (16 to 64 letters, numbers, hyphens or underscores); a new
one is 24 random bytes in base64url.
