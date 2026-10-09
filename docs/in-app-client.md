# The in-app client

Apps never hold a secret key: anyone can unpack an app and read it. An in-app client takes one of two
credentials, each acting on one customer, the one it names, through `me`.

## Customer tokens from your server

Your server mints a customer token for the signed-in customer, with at most `entitlements:read`,
`usage:read` and `usage:write`, for at most an hour:

```kotlin
val issued = customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageRead, Scope.UsageWrite), ttl = 15.minutes)
return issued.token
```

The app builds its client from a token provider that fetches one from your server:

```kotlin
fun tokenProvider(api: YourApi): EntitlerClient = EntitlerClient({ api.fetchEntitlerToken() })
```

The client asks the provider for the first request, keeps the token, and asks again when it expires
within 60 seconds and after a `401`, which it retries once with the new token. Concurrent requests
share one refresh. A provider that fails or answers a blank or unreadable token fails the call with
`EntitlerTokenException`. A fixed token (`EntitlerClient(token)`) cannot be refreshed, so a `401` fails
the call. A `403 credential_not_allowed` means the route refuses that kind of credential, and is never
retried or refreshed.

## Identity tokens

With a sign-in provider registered on the project, the app pairs a publishable key holding only
product scopes with the person's identity token, and can register the person itself:

```kotlin
val entitler = EntitlerIdentityClient("ent_live_publishable_key", { api.identityToken() })
if (entitler.scopes().registration == true) {
    entitler.register()
}
```

`register()` exists only on `EntitlerIdentityClient`. Customers registered this way have ids of the
form `<provider id>:<subject>`; a server acting on one uses that id.

## Visitors

The in-app client sends a visitor id on every request, so a person keeps their experiment arm before
and after registering. It generates one for its lifetime; keep it across launches yourself and pass it
back (the library is plain JVM and has no Android `Context`):

```kotlin
val entitler = EntitlerClient(token) { visitor = stored }
store(entitler.visitor)
return entitler
```

On Android, keep it in DataStore or SharedPreferences (see [Android](android.md)).
