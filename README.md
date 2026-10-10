# Entitler SDK for Kotlin

The official Entitler SDK for Kotlin. Entitler helps SaaS teams manage plans, feature access, usage
limits and customer grants; this SDK lets your servers on the JVM and your Android apps check whether
a customer is entitled to a feature, record metered usage, show pricing and manage subscriptions. It
retries, caches answers, keeps last known answers when Entitler is unreachable, and verifies offline
snapshots, so a feature check never takes your app down.

## Requirements and install

- Kotlin/JVM, with JVM 11 or later. On a server, any JVM 11 or later.
- Android API 21 or later, with [core library desugaring](docs/android.md) below API 26 (the SDK uses `java.time`).
- Runtime dependencies: OkHttp, kotlinx.serialization JSON and kotlinx.coroutines.

```kts
dependencies {
    implementation("com.entitlerhq:entitler:0.1.0")
    testImplementation("com.entitlerhq:entitler-testing:0.1.0")
}
```

## Quick start

On your server, with a secret key from the dashboard in `ENTITLER_KEY`:

```kotlin
val entitler = EntitlerServer(System.getenv("ENTITLER_KEY"))

val customer = entitler.customer("user_123")
customer.register(name = "Ada Lovelace", email = "ada@example.com")

if (customer.isEntitled(Features.exportPdf)) {
    exportPdf()
}

customer.recordUsage(Features.aiCredits, 5, idempotencyKey = "summary-job_42")
```

Every request is a `suspend` function. Build one server client per process, share it, and close it on
shutdown. Every usage write takes an idempotency key named after the event, so a retry never counts twice.

## The clients

| Client | Built from | Runs on | Acts on |
| --- | --- | --- | --- |
| `EntitlerServer(key)` | a secret key | your servers | any customer, by the id your app uses |
| `EntitlerClient(token)` | a customer token your server minted, or a provider that fetches one | apps (Android, desktop) | the signed-in customer, `me` |
| `EntitlerIdentityClient(key, identityToken)` | a publishable key and an identity token from your sign-in provider | apps | the signed-in person, who it can also `register()` |
| `EntitlerPublishableClient(key)` | a publishable key | apps and pages before sign-in | nobody: it reads the pricing |

A secret key never goes into an app: anyone can unpack an app and read it, and with it act on every
customer. Apps use a customer token, which acts on one customer for at most an hour, an identity token
beside a publishable key, or the publishable key alone for signed-out pricing. An app holds one client
per signed-in customer and closes it at sign-out.

```kotlin
val entitler = EntitlerClient({ api.fetchEntitlerToken() })

if (entitler.me.isEntitled(Features.exportPdf)) {
    showExportButton()
}
```

The server's customers and `me` answer the same `Customer` interface, so code that gates features,
records usage and runs the billing page is written once.

## API at a glance

- `Customer`: `check(feature)` (a `MeteredCheck` for `Feature.Metered`), `isEntitled(feature, default)`,
  `entitlements()` with `has` and `get`, `plans()`, `pricing(visitor)`, `usage()` and `usageLog()`,
  `recordUsage`, `startHold` (a `Closeable` `Hold`), `withHold`, `holdUsage`, `settleUsage`,
  `releaseUsage`, `snapshot()`, and the customer's billing choices: `subscribe` (a `SubscribeStep`),
  `cancel`, `undoPendingChange`, `billingPortal` and `syncBilling`.
- `ServerCustomer` adds `register`, `details`, `update`, `erase`, `token`, `setTrack`, `billing`, reads
  `asOf` an instant, and the company's decisions: `setPlan`, `setAddOn`, `grant`, `revokeGrant`,
  `adjustMeter` and `cancelUsage`.
- `EntitlerServer` adds `customers.list` (a `Flow`) and `customers.page`, `customers.create`,
  `recordUsageBatch`, `pricing`, `features`, `scopes`, `snapshotKeys`, `verifySnapshot`, `newVisitorId`.
- Answers are immutable data classes; a feature's value is `FeatureValue.On`, `Amount(value)` or
  `Unlimited`. Every failure is an `EntitlerException`.
- `entitler-testing` holds `fakeCustomer(...)` and answer builders for your own tests.

## Feature constants and the generator

`entitler generate` writes an `object Features` with one typed constant per feature in your catalogue,
so `check(Features.aiCredits)` answers a metered check and usage methods accept only metered features:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --package com.example
```

It writes `src/main/kotlin/EntitlerFeatures.kt` unless `--out` names another file. `entitler snapshot-keys`
writes the keys an app pins to verify offline snapshots. See [feature constants](docs/feature-constants.md)
for the Gradle task, `--check` in CI, and declaring features by hand.

## Guides

- [Checking access](docs/checking-access.md)
- [Recording usage](docs/recording-usage.md)
- [Pricing pages and visitors](docs/pricing-and-visitors.md)
- [Billing pages](docs/billing-pages.md)
- [Company decisions](docs/company-decisions.md)
- [Store purchases](docs/store-purchases.md)
- [The in-app client](docs/in-app-client.md)
- [Android](docs/android.md)
- [Offline snapshots](docs/offline-snapshots.md)
- [Reliability](docs/reliability.md)
- [Errors](docs/errors.md)
- [As-of](docs/as-of.md)
- [Tracks](docs/tracks.md)
- [Scopes](docs/scopes.md)
- [Configuration](docs/configuration.md)
- [Testing your app](docs/testing.md)
- [Feature constants](docs/feature-constants.md)
- [Ktor and Spring](docs/frameworks.md)
- [Calling from Java](docs/java.md)
- [Versioning and support](docs/versioning.md)

## Examples

Each runs with `ENTITLER_KEY` set (and `ENTITLER_PUBLISHABLE_KEY` for the pricing page), for example `ENTITLER_KEY=... ./gradlew :examples:quickstart`:

- [quickstart](examples/src/main/kotlin/com/entitlerhq/examples/quickstart/Main.kt): register a customer, check a feature, record usage.
- [pricing-page](examples/src/main/kotlin/com/entitlerhq/examples/pricingpage/Main.kt): print the pricing through the publishable client and through the server with a visitor id (`:examples:pricingPage`).
- [in-app](examples/src/main/kotlin/com/entitlerhq/examples/inapp/Main.kt): mint a customer token, use it from an in-app client and close it at sign-out (`:examples:inApp`).
- [metered-work](examples/src/main/kotlin/com/entitlerhq/examples/meteredwork/Main.kt): a `startHold` handle around streamed work, `withHold`, and an observe-mode report (`:examples:meteredWork`).
- [offline](examples/src/main/kotlin/com/entitlerhq/examples/offline/Main.kt): verify a snapshot offline and fall back to it when Entitler is unreachable (`:examples:offline`).
- [billing](examples/src/main/kotlin/com/entitlerhq/examples/billing/Main.kt): a billing page, every subscribe step, the return page, the portal, an invoiced contract and a grant (`:examples:billing`).
- [generated-features](examples/src/main/kotlin/com/entitlerhq/examples/generatedfeatures/Main.kt): generated constants in use (`:examples:generatedFeatures`).
- [ktor](examples/src/main/kotlin/com/entitlerhq/examples/ktor/Main.kt): a Ktor route plugin that gates a feature (`:examples:ktor`).
- [testing](examples/src/test/kotlin/com/entitlerhq/examples/testing/ExportServiceTest.kt): a unit test of gating and billing code with `fakeCustomer` (`:examples:test`).

## Licence

[MIT](LICENSE).
