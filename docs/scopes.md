# Scopes

The server decides what a credential may do. The SDK never refuses a call itself: a missing scope
answers `403 scope_required`. `scopes()` tells an app which scopes its client holds, for deciding what
to show; each call asks again, so a newly granted scope shows without a restart.

```kotlin
suspend fun showUsageControls(entitler: EntitlerClient): Boolean = Scope.UsageWrite in entitler.scopes()
```

For an identity client, `registration` says whether the sign-in provider lets a new person register.

| Call | Scope |
| --- | --- |
| `check`, `isEntitled`, `entitlements`, `snapshot` | `entitlements:read` |
| `plans`, `Customer.pricing`, `EntitlerServer.pricing`, the publishable client's `pricing`, `features` | `plans:read` |
| `usage`, `usageLog` | `usage:read` |
| `recordUsage`, `startHold`, `withHold`, `holdUsage`, `settleUsage`, `releaseUsage`, `recordUsageBatch` | `usage:write` |
| `subscribe`, `cancel`, `undoPendingChange`, `billingPortal`, `syncBilling` | `customers:write` on the server; `billing:self` in an app |
| `register` | `customers:register`; changing an existing customer's details also needs `customers:write` or `customers:profile` |
| `details`, `detailsLog`, `customers.list`, `billing` | `customers:read` |
| `update` | `customers:write` or `customers:profile` |
| `customers.create`, `erase`, `setPlan`, `setAddOn`, `grant`, `revokeGrant`, `adjustMeter`, `cancelUsage` | `customers:write` |
| `token` | `tokens:mint` |
| `setTrack` | `tracks:assign` |
| `scopes`, `snapshotKeys`, `verifySnapshot`, `newVisitorId` | none |

`token()` without `scopes` mints `entitlements:read` only. Ask for `usage:write` only when the app
records usage itself, and for `billing:self` only for people who may buy for the customer.
