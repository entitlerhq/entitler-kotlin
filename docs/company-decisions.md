# Company decisions

`ServerCustomer` adds the decisions your company makes: a deal, a support ticket or a verified store
purchase. They follow no self-serve rules, so sales-led plans are included, and they need
`customers:write`. Each takes `actor`, the person or system that decided (a support agent's id, or
`hubspot`), and `reason`, which the customer's activity shows beside the key's name.

## Plans and add-ons

`setPlan` moves the customer to a plan by its key or public id, or to a [store SKU](store-purchases.md).
On `setPlan`, `timing` is `Now` or `End` (at renewal), the project's policy when left out. `billing` decides what
happens at the provider:

- `Provider` (the default) charges the change through the provider that bills the product.
- `Keep` moves the customer in Entitler while the provider keeps billing the plan it bills, as a
  deliberate exception such as a goodwill upgrade, until a later `setPlan`.
- `End` ends the provider's subscription when the change takes effect, and the customer holds the
  plan outside any provider, as with an invoiced contract.

`until` returns the customer to the product's default plan at that instant, unless a later `setPlan`
moved them or set a later `until`. `setAddOn` adds an add-on, sets its quantity, or removes it at 0, always taking effect now.

```kotlin
suspend fun companyDecisions(
    customer: ServerCustomer,
    dealId: String,
) {
    customer.setPlan("enterprise", period = "yearly", billing = PlanBilling.End, actor = "hubspot", idempotencyKey = "deal-$dealId")
    customer.setAddOn("extra_seats", quantity = 150, actor = "hubspot", idempotencyKey = "deal-$dealId-seats")
    customer.setPlan("team", billing = PlanBilling.Keep, until = Instant.parse("2026-12-31T00:00:00Z"), reason = "Outage credit")
}
```

Setting the plan the customer already holds, with the same period, billing and `until`, changes
nothing and answers `changed` false, so a redelivered webhook is harmless, with two exceptions: it undoes
a change booked for renewal, and with `billing` `Provider` or `End` it ends a `Keep` exception. While
Stripe bills the product, `until` needs `billing` `End`.

`NoPlan(product)` ends the customer's plan in a product: they move to its default plan, or hold no plan
when it has none, now or at renewal (`Keep` is refused). `version = PlanVersion.Latest` moves them now
to the newest version of the plan they hold. `setAddOn` with `replaces` swaps one add-on for another in
one write, and naming an add-on's held quantity undoes an add-on move booked for renewal.

```kotlin
suspend fun planHousekeeping(
    customer: ServerCustomer,
    ticketId: String,
) {
    customer.setPlan(NoPlan("app"), timing = Timing.End, actor = "support", idempotencyKey = "ticket-$ticketId-end")
    customer.setPlan("pro", version = PlanVersion.Latest, actor = "migration", idempotencyKey = "ticket-$ticketId-latest")
    customer.setAddOn("support_premium", 1, replaces = "support_standard", actor = "support", idempotencyKey = "ticket-$ticketId-support")
}
```

## Grants and meters

`grant` gives a feature for `days` (0 for no end) and answers the grant made, so a support tool keeps
its id for `revokeGrant`. `adjustMeter` moves a meter `by` an amount (`-500` gives back 500 credits
without racing new usage) or sets it `to` a value, and never below 0. `cancelUsage` takes a usage
report off the meter.

```kotlin
suspend fun supportTool(
    customer: ServerCustomer,
    agentId: String,
    ticketId: String,
) {
    val change = customer.grant(Features.teamSeats, FeatureValue.Amount(10), days = 30, actor = agentId, reason = "Pilot")
    println("Grant ${change.grant.id}")
    customer.revokeGrant(change.grant.id, actor = agentId)
    customer.adjustMeter(Features.aiCredits, idempotencyKey = "ticket-$ticketId", by = -500, actor = agentId, reason = "Failed run")
}
```

## Keys from the event

`adjustMeter` requires an idempotency key, as usage writes do: a support tool's double submit would
otherwise apply twice. Name every key after the event that caused the write (the deal, the ticket, the
store transaction), never after the customer or the plan, so a retry of the same event is answered
once and a new event is a new change. The other writes take an optional key the SDK generates and
reuses across its own retries.
