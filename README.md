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

customer.recordUsage(Features.aiCredits, 5)
```

Every request is a `suspend` function. Build one client per process, share it, and close it on shutdown.

## Two clients

| Client | Built from | Runs on | Acts on |
| --- | --- | --- | --- |
| `EntitlerServer(key)` | a secret project key | your servers | any customer, by the id your app uses |
| `EntitlerClient(token)` | a customer token your server minted, or a provider that fetches one | apps (Android, desktop) | the signed-in customer, `me` |
| `EntitlerIdentityClient(key, identityToken)` | a publishable key and an identity token from your sign-in provider | apps | the signed-in person, who it can also `register()` |

A secret key never goes into an app: anyone can unpack an app and read it, and with it act on every
customer. Apps use a customer token, which acts on one customer for at most an hour, or an identity
token beside a publishable key holding only product scopes.

```kotlin
val entitler = EntitlerClient({ api.fetchEntitlerToken() })

if (entitler.me.isEntitled(Features.exportPdf)) {
    showExportButton()
}
```

Both clients answer the same `Customer` interface, so code that gates features and records usage is
written once.

## API at a glance

- `Customer`: `check(feature)` (a `MeteredCheck` for `Feature.Metered`), `isEntitled(feature, default)`,
  `entitlements()` with `has` and `get`, `planSpace()`, `pricing(visitor)`, `usage()` and `usageLog()`,
  `recordUsage`, `holdUsage`, `settleUsage`, `releaseUsage`, `hold`, `withHold`, `snapshot()`.
- `ServerCustomer` adds `register`, `details`, `update`, `delete`, `token`, `setTrack`, self-serve
  billing (`subscribe`, `checkout`, `cancel`, `undoPendingChange`, `addAddOn`, `setAddOnQuantity`,
  `removeAddOn`, `undoAddOnChange`, `billing`, `billingPortal`, `providers`) and `vendor` actions
  (`subscribe`, `override`, `undoOverride`, `checkout`, `addAddOn`, `setAddOnQuantity`, `grant`,
  `revokeGrant`, `setMeter`, `cancelUsage`).
- `EntitlerServer` adds `customers.list` (a `Flow`) and `customers.page`, `customers.create`,
  `recordUsageBatch`, `pricing`, `features`, `scopes`, `snapshotKeys`, `verifySnapshot`, `newVisitorId`.
- Answers are immutable data classes; a feature's value is `FeatureValue.On`, `Amount(value)` or
  `Unlimited`. Every failure is an `EntitlerException`.

## Feature constants and the generator

`entitler generate` writes an `object Features` with one typed constant per feature in your catalogue,
so `check(Features.aiCredits)` answers a metered check and usage methods accept only metered features:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --out src/main/kotlin/Features.kt --package com.example
```

See [feature constants](docs/feature-constants.md) for the Gradle task, `--check` in CI, and declaring
features by hand.

## Guides

- [Checking access](docs/checking-access.md)
- [Recording usage](docs/recording-usage.md)
- [Pricing pages and visitors](docs/pricing-and-visitors.md)
- [Plan space and upgrades](docs/plan-space.md)
- [Billing](docs/billing.md)
- [The in-app client](docs/in-app-client.md)
- [Android](docs/android.md)
- [Offline snapshots](docs/offline-snapshots.md)
- [Reliability](docs/reliability.md)
- [Errors](docs/errors.md)
- [As-of](docs/as-of.md)
- [Tracks](docs/tracks.md)
- [Scopes](docs/scopes.md)
- [Configuration](docs/configuration.md)
- [Feature constants](docs/feature-constants.md)
- [Ktor and Spring](docs/frameworks.md)
- [Calling from Java](docs/java.md)
- [Versioning and support](docs/versioning.md)

## Examples

Each runs with `ENTITLER_KEY` set, for example `ENTITLER_KEY=... ./gradlew :examples:quickstart`:

- [quickstart](examples/src/main/kotlin/com/entitlerhq/examples/quickstart/Main.kt): register a customer, check a feature, record usage.
- [pricing-page](examples/src/main/kotlin/com/entitlerhq/examples/pricingpage/Main.kt): print the pricing with a visitor id (`:examples:pricingPage`).
- [in-app](examples/src/main/kotlin/com/entitlerhq/examples/inapp/Main.kt): mint a customer token and use it from an in-app client (`:examples:inApp`).
- [metered-work](examples/src/main/kotlin/com/entitlerhq/examples/meteredwork/Main.kt): `withHold` and an observe-mode report (`:examples:meteredWork`).
- [offline](examples/src/main/kotlin/com/entitlerhq/examples/offline/Main.kt): verify a snapshot offline (`:examples:offline`).
- [billing](examples/src/main/kotlin/com/entitlerhq/examples/billing/Main.kt): subscribe, plan space, checkout and a grant (`:examples:billing`).
- [generated-features](examples/src/main/kotlin/com/entitlerhq/examples/generatedfeatures/Main.kt): generated constants in use (`:examples:generatedFeatures`).
- [ktor](examples/src/main/kotlin/com/entitlerhq/examples/ktor/Main.kt): a Ktor route plugin that gates a feature (`:examples:ktor`).

## Licence

[MIT](LICENSE).
