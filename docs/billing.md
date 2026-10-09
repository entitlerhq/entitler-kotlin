# Billing

## Self-serve changes

`ServerCustomer`'s billing methods act as the customer choosing for themselves in your interface, so
they send `selfServe: true`: the change must be on a self-serve path among the plans the customer can move to, or
the API answers `403 not_self_serve`.

```kotlin
customer.subscribe("pro", period = "Yearly")
customer.addAddOn("sso_addon")
customer.cancel(timing = ChangeTiming.End)
customer.undoPendingChange()
```

A plan is named by its key or public id. A SKU the customer bought, such as an app store product,
names its own period:

```kotlin
customer.subscribe(Sku("apple", mapOf("productId" to "pro.monthly")))
```

`setAddOnQuantity`, `removeAddOn` and `undoAddOnChange` change add-ons; `billing()` and `providers()`
read what the payment provider holds.

## Checkout and the billing portal

`checkout` answers the payment provider's page to send the customer to. A plan change that waits on a
payment answers `402 payment_required` with `payment`, whose `url` is where the customer confirms it:

```kotlin
suspend fun checkout(customer: ServerCustomer): String? =
    try {
        customer.checkout("pro", successUrl = "https://example.com/thanks", cancelUrl = "https://example.com/pricing").url
    } catch (error: EntitlerApiException) {
        when (error.code) {
            ErrorCode.NotSelfServe -> null
            ErrorCode.PaymentRequired -> error.payment?.url
            else -> throw error
        }
    }
```

```kotlin
suspend fun portal(customer: ServerCustomer): String = customer.billingPortal(returnUrl = "https://example.com/account").url
```

An environment with no payment provider connection answers both with `409 stale`.

## Vendor actions

Changes you make on the customer's behalf live under `vendor`, so they are never made by accident and
are easy to find in review. The subscription and add-on methods send `selfServe: false`: you may move
the customer anywhere, sales-led plans included.

```kotlin
customer.vendor.subscribe("team")
customer.vendor.override("team")
customer.vendor.undoOverride()
val detail = customer.vendor.grant(Features.teamSeats, FeatureValue.Amount(10), days = 30, reason = "Pilot")
customer.vendor.revokeGrant(detail.grants.first().id)
customer.vendor.setMeter(Features.aiCredits, 0)
```

`override` moves the customer in Entitler only, while the payment provider keeps billing the plan they
held, until `undoOverride` or a later plan change.
