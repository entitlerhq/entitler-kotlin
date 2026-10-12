# As-of

A server customer's reads take `asOf` to read the API at another instant: what a customer was entitled
to last month, or what they will be once a booked change takes effect. It is sent as `Entitler-As-Of` on
that request only, formatted in UTC with milliseconds, and the answer is kept in the cache under its own
key.

```kotlin
val check = entitler.customer(customerId).check(Features.aiCredits, asOf = Instant.parse("2026-09-01T00:00:00Z"))
println("On 1 September: ${check.used} used")
```

`check`, `isEntitled`, `entitlements`, `plans`, `usage` and `details` take it. Nothing else sends it: not
the in-app clients, not pricing, and never a write, so a preview can never change a live environment.

- Reading at another instant needs the organisation's `as_of` capability; without it the API answers
  `409 capability_required`.
- Pricing is always computed now.
- As-of shows the effects of time on the plans, grants and meters already in place (renewals, booked
  moves, grant expiries, meter resets), never a release nobody has rolled out yet, since nothing is
  derived on a schedule. To preview an unreleased change, put a test customer on a
  [track](tracks.md) for testers.
