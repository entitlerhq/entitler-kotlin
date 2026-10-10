# Store purchases

When Google Play or the App Store bills a plan, the app's paywall buys a SKU from `plans()` (each
option's `skus`) through Play Billing or StoreKit. The app never records the purchase: your server
verifies it with the store, then records it with `setPlan`. Most storefronts forbid card charges and
web checkout for digital goods, so these apps never call `subscribe` or pass a return URL.

A store SKU never touches Stripe. When Stripe already bills that product, the API refuses with
`409 billed_elsewhere` and changes nothing.

## Google Play

1. The app buys the subscription with Play Billing and sends the purchase token to your server.
2. Your server verifies it with the Google Play Developer API (`purchases.subscriptionsv2.get`) and
   acknowledges it.
3. It records the purchase, with `until` the expiry the verification answered:

```kotlin
suspend fun recordPlayPurchase(
    customer: ServerCustomer,
    purchase: VerifiedPurchase,
) {
    customer.setPlan(
        Sku("google", mapOf("productId" to purchase.productId)),
        until = purchase.expiresAt,
        actor = "google-play",
        idempotencyKey = "google-${purchase.transactionId}",
    )
}
```

4. Real-time developer notifications for a renewal repeat steps 2 and 3 with the new expiry.

## App Store

1. The app buys the subscription with StoreKit and sends the transaction to your server.
2. Your server verifies it with the App Store Server API.
3. It records the purchase, with `until` the transaction's `expiresDate`:

```kotlin
suspend fun recordApplePurchase(
    customer: ServerCustomer,
    purchase: VerifiedPurchase,
) {
    customer.setPlan(
        Sku("apple", mapOf("productId" to purchase.productId)),
        until = purchase.expiresAt,
        actor = "app-store",
        idempotencyKey = "apple-${purchase.transactionId}",
    )
}
```

4. App Store Server Notifications for a renewal (`DID_RENEW`) repeat steps 2 and 3.

## Expiry and refunds

Because each purchase carries `until`, a missed store notification ends the plan at the expiry
instead of keeping it for ever. A refund or a revoked purchase moves the customer back at once:

```kotlin
suspend fun storeRefund(
    customer: ServerCustomer,
    transactionId: String,
) {
    customer.setPlan("free", actor = "app-store", reason = "Refunded", idempotencyKey = "refund-$transactionId")
}
```

## Deleting an account

`erase()` deletes the customer in Entitler, but only the person can cancel a store subscription. Ask
them to cancel it in the store first, or the store keeps charging them:

```kotlin
suspend fun deleteAccount(customer: ServerCustomer) {
    val billedBy = customer.plans(revalidate = true).held.mapNotNull { it.billedBy }
    if (Provider.Apple in billedBy || Provider.Google in billedBy) {
        println("Cancel your subscription in the App Store or Google Play first.")
        return
    }
    customer.erase()
}
```
