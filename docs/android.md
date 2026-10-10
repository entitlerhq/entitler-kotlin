# Android

The SDK is plain Kotlin/JVM, with JVM 11 bytecode and no API that Android API 21 lacks; CI checks this
with animal-sniffer signatures for API 21. Every request is a `suspend` function, so it never runs on
the main thread unless you block on it. The library takes no Android dependency, so this guide shows
the Android parts around it.

## Core library desugaring

Answers use `java.time.Instant`, which Android has from API 26. For a lower `minSdk`, turn on core
library desugaring in your app:

```kts
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

## One client per signed-in person

Create the client when the person signs in, hold it where the app keeps state for the session (your
`Application` or an app-scoped dependency injection graph, not a screen's view model, so screens share
one cache, one token and one visitor), and close it at sign-out. The next sign-in creates a new client,
so nothing kept for one person is ever answered to another.

```kotlin
class EntitlerSession(
    private val api: YourApi,
) {
    private var current: EntitlerClient? = null

    /** The signed-in customer's client, created at sign-in. */
    val entitler: EntitlerClient
        get() = checkNotNull(current) { "Nobody is signed in." }

    /** Creates the client for the person who just signed in. */
    fun signIn() {
        current?.close()
        current = EntitlerClient({ api.fetchEntitlerToken() })
    }

    /** Closes the client, so nothing of this customer outlives their session. */
    fun signOut() {
        current?.close()
        current = null
    }
}
```

## Keeping the visitor in DataStore

DataStore answers asynchronously, so pass `visitorSource`, which the client calls once before its first
request; the client is built at once and the visitor arrives later:

```kotlin
fun signedInClient(
    api: YourApi,
    visitors: VisitorStore,
): EntitlerClient =
    EntitlerClient({ api.fetchEntitlerToken() }) {
        visitorSource = { visitors.read() ?: newVisitorId().also { visitors.write(it) } }
    }
```

At sign-out and at account deletion, keep a new visitor, so the next person on a shared device is never
linked to the last:

```kotlin
suspend fun rotateVisitor(visitors: VisitorStore) {
    visitors.write(newVisitorId())
}
```

## View models

```kotlin
class ExportViewModel(
    private val me: Customer,
    private val scope: CoroutineScope,
) {
    private val canExport = MutableStateFlow(false)

    /** Whether the export button shows. */
    val showExport: StateFlow<Boolean> = canExport

    /** Asks Entitler again, for example when the screen opens. */
    fun refresh() {
        scope.launch { canExport.value = me.isEntitled(Features.exportPdf) }
    }
}
```

In an Android view model, pass `viewModelScope`, and `session.entitler.me` from the app-scoped session.

## Paying through Stripe

An app whose plans Stripe bills passes a verified Android App Link as `returnUrl`, and opens the `Pay`
step's URL, and `billingPortal`'s, in a Custom Tab (`androidx.browser`:
`CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))`). When the person pays or
leaves, the provider sends them to the link, Android opens your activity, and the app calls
`syncBilling()` and then `plans(revalidate = true)`:

```kotlin
class UpgradeViewModel(
    private val me: Customer,
    private val tabs: CustomTabs,
) {
    /** Starts the upgrade, opening the payment page when there is one. */
    suspend fun upgrade(plan: String) {
        when (val step = me.subscribe(plan, period = "monthly", returnUrl = "https://example.com/app/billing-return")) {
            is SubscribeStep.Pay -> tabs.open(step.url)
            is SubscribeStep.Manage -> tabs.open("https://play.google.com/store/account/subscriptions")
            else -> refresh()
        }
    }

    /** Called when the app link brings the person back from the payment page. */
    suspend fun onBillingReturn() {
        me.syncBilling()
        refresh()
    }

    private suspend fun refresh() {
        val plans = me.plans(revalidate = true)
        println("On ${plans.held.joinToString { it.plan.name }}")
    }
}
```

Declare the link with an intent filter marked `android:autoVerify="true"` for
`https://example.com/app/billing-return`, and publish `/.well-known/assetlinks.json` on that domain, so
Android opens the app instead of a browser. The token behind `me` needs `billing:self`; mint it only for
people who may buy.

## Paying through Google Play

An app whose plans Google Play bills never calls `subscribe`. Its paywall reads `plans()`, buys the
option's `skus` with Play Billing, and sends the purchase token to your server, which verifies it and
records it with `setPlan(Sku("google", …), until = …)` (see [store purchases](store-purchases.md)).
`subscribe` on a plan Google bills answers `Manage`, so the app opens the Play subscriptions page, as
above.

## Size

R8 drops the parts of the SDK an app never calls. The SDK ships no reflection and needs no ProGuard
rules of its own; kotlinx.serialization ships the rules its generated serializers need.
