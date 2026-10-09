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
| `check`, `isEntitled`, `entitlements`, `planSpace`, `Customer.pricing`, `snapshot` | `entitlements:read` |
| `usage`, `hold` | `usage:read` |
| `recordUsage`, `holdUsage`, `settleUsage`, `releaseUsage`, `withHold`, `recordUsageBatch` | `usage:write` |
| `register` | `customers:register`; changing an existing customer's details also needs `customers:write` or `customers:profile` |
| `details`, `customers.list`, `billing`, `providers` | `customers:read` |
| `update` | `customers:write` or `customers:profile` |
| `customers.create`, `delete`, billing changes, `vendor` actions | `customers:write` |
| `token` | `tokens:mint` |
| `setTrack` | `tracks:assign` |
| `EntitlerServer.pricing`, `features` | `plans:read` |
| `scopes`, `snapshotKeys`, `verifySnapshot`, `newVisitorId` | none |

Customer tokens hold at most `entitlements:read`, `usage:read` and `usage:write`; an identity token
beside a publishable key holds at most those and `customers:register`.
