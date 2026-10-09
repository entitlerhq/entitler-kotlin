# As-of

`asOf` reads the API at another instant: what a customer was entitled to last month, or what they
will be once a booked change takes effect. It is sent as `Entitler-As-Of` on every request, formatted
in UTC with milliseconds.

```kotlin
EntitlerServer(System.getenv("ENTITLER_KEY")) { asOf = Instant.parse("2026-09-01T00:00:00Z") }.use { past ->
    val check = past.customer(customerId).check(Features.aiCredits)
    println("On 1 September: ${check.used} used")
}
```

- Reading at another instant needs the organisation's `as_of` capability; without it the API answers
  `409 limit_reached`.
- Pricing is always computed now.
- Writes take `asOf` only in a test environment.
