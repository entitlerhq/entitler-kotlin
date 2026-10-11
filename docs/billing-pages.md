# Billing pages

A billing page is built from one read, `plans()`, and changed through the customer's own choices:
`subscribe`, `cancel`, `undoPendingChange`, `billingPortal` and `syncBilling`. They are on `Customer`,
so the same code runs on your server and in an app.

These methods act as the customer choosing for themselves, under self-serve rules: a plan change must
be among the moves `plans()` lists along a self-serve path, and a cancel must be one the customer may
make, or the API refuses it with `403 not_self_serve`. The project's policies decide when a change
takes effect (a downgrade or a cancel at renewal, an upgrade now). On the server they need
`customers:write`; on the in-app client the token needs `billing:self` and the organisation needs the
customer portal capability (`409 capability_required` otherwise). Mint `billing:self` only for people
who may buy for the customer, such as a workspace's owners, not every member.

## The plans page

`plans()` answers the plans the customer holds (`held`) and the moves they can make (`options`). Each
held plan names its period, when it renews, a change booked for renewal (`pending`) and `billedBy`, the
provider that bills it (none when nobody does, as with an invoiced contract). Each option names its
`move` (`subscribe`, `upgrade`, `downgrade`, `switch`, `add` or `replace`), its `action` and its
periods:

- `buy`: show a button that calls `subscribe`;
- `contact`: a sales-led plan, so show a way to reach your team;
- `unavailable`: show the plan with its `reason`.

```kotlin
suspend fun planButtons(customer: Customer) {
    val plans = customer.plans()
    for (held in plans.held) {
        println("Your plan: ${held.plan.name}, billed by ${held.billedBy ?: "your account manager"}")
    }
    for (option in plans.options) {
        when (option.action) {
            MoveAction.Buy -> println("Choose ${option.plan.name} (${option.periods.joinToString { it.label }})")
            MoveAction.Contact -> println("Talk to us about ${option.plan.name}")
            else -> println("${option.plan.name} is unavailable: ${option.reason}")
        }
    }
}
```

A held plan's `pending` is the change booked for its renewal, a sealed `BookedChange`: `Move` to
another plan, `Cancel` (moving to the product's default plan, or to none), `Billing` (who bills the plan
from renewal: the provider, nobody, as with an invoiced contract, or a store), or `Unknown`, a change
this version of the SDK does not know, kept with its raw JSON:

```kotlin
fun renewalNote(held: HeldPlan): String? =
    when (val pending = held.pending) {
        is BookedChange.Move -> "Moves to ${pending.plan.name} at renewal"
        is BookedChange.Cancel -> "Ends at renewal" + pending.movingTo?.let { ", then ${it.name}" }.orEmpty()
        is BookedChange.Billing -> "Billed by ${pending.billing} from renewal"
        is BookedChange.Unknown, null -> null
    }
```

A plan or add-on is named by its key or public id. A period is named by its key (`monthly`, `yearly`),
which stays the same when its `label` is renamed or translated. A check the customer is not entitled to
lists `upgrades` too, the plans that would give them the feature.

## Subscribing and the next step

`subscribe` answers a `SubscribeStep`, the next thing to do:

- `Done`: the change is made; `change.effective` is `now`, or `renewal` with `change.at`.
- `Pay`: send the customer to `url`, the provider's page (Stripe Checkout, or a confirmation such as
  3-D Secure). The plan changes once they pay.
- `Confirming`: the provider is still confirming a payment, such as a bank debit. Show the plan as
  pending.
- `Manage`: a store bills the product (`billedBy`, `apple` or `google`), so the customer changes it
  there.
- `Unknown`: a step this version of the SDK does not know; show the plan as pending.

```kotlin
suspend fun choosePlan(
    customer: Customer,
    plan: String,
    period: String,
): String? =
    when (val step = customer.subscribe(plan, period = period, returnUrl = "https://example.com/billing/return")) {
        is SubscribeStep.Done -> null
        is SubscribeStep.Pay -> step.url
        SubscribeStep.Confirming -> null.also { println("Your payment is being confirmed.") }
        is SubscribeStep.Manage -> null.also { println("Change your plan in your ${step.billedBy} subscriptions.") }
        is SubscribeStep.Unknown -> null
    }
```

An add-on goes through `subscribe` too: it is added, has its quantity set when held, or replaces
another add-on, as `plans()` lists the move. A customer credential never charges a saved payment
method, so a paid change from the in-app client always answers `Pay`. A declined payment is an error:
`402 payment_required` with `payment.status` `declined`.

## Return URLs

`returnUrl` matters only when the next step is a web page: the provider sends the customer back to it
whether they paid or left. A change that needs a page and has no `returnUrl` is refused with
`400 return_url_required` before anything changes, so pass one whenever Stripe may bill the customer.

- Web apps pass a page of their own.
- Android apps billed through Stripe pass an [app link](android.md#paying-through-stripe), and open the
  `Pay` step's `url`, and the billing portal's, in a Custom Tab, which hands control back to the app
  at that link.
- Apps whose plans a store bills never call `subscribe`: their paywall buys the `skus` from `plans()`
  through Play Billing or StoreKit, and your server records the verified purchase. See
  [store purchases](store-purchases.md).

## The return page

Back from the provider, call `syncBilling()` and then read with `revalidate`, so the customer sees the
plan they paid for without waiting for the provider's notification or a kept answer's `max-age`:

```kotlin
suspend fun billingReturn(customer: Customer) {
    customer.syncBilling()
    val plans = customer.plans(revalidate = true)
    println("You're on ${plans.held.joinToString { it.plan.name }}")
}
```

## Cancelling and undoing

`cancel` and `undoPendingChange` act on the customer's plan, or on the add-on `addOn` names; pass
`product` when the customer holds plans in several products, never both. Each answers a `PlanChange`
whose `changed` is false when there was nothing to do. `undoPendingChange` cannot undo a `Billing`
change, which only the company books: it answers `403 not_self_serve`, and your server undoes it with
`setPlan` (see [company decisions](company-decisions.md)).

```kotlin
suspend fun cancelAndUndo(customer: Customer) {
    val cancel = customer.cancel()
    println("Your plan ends ${cancel.at}")
    customer.undoPendingChange()
    customer.cancel(addOn = "extra_seats")
}
```

## The billing portal

`billingPortal` opens the provider's page for payment details and invoices. It changes no plan and
always needs `returnUrl`. A customer the provider has never billed answers `404 not_found`, and an
environment with no payment provider connection answers `409 stale`:

```kotlin
suspend fun portal(customer: Customer): String? =
    try {
        customer.billingPortal(returnUrl = "https://example.com/account").url
    } catch (error: EntitlerApiException) {
        if (error.code != ErrorCode.NotFound && error.code != ErrorCode.Stale) throw error
        null
    }
```

Changes your company decides, such as a contract or a support grant, are in
[company decisions](company-decisions.md).
