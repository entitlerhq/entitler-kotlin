# The in-app client

Apps never hold a secret key: anyone can unpack an app and read it. An in-app client takes one of two
credentials, each acting on one customer, the one it names, through `me`. Before sign-in, a
[publishable client](pricing-and-visitors.md#signed-out-pricing) reads the pricing and acts on nobody.

## Customer tokens from your server

Your server mints a customer token for the signed-in customer, whose id it takes from the session its
sign-in verified, never from what the app sends. The token lasts at most an hour, with the fewest
scopes the app needs, from `entitlements:read`, `plans:read`, `usage:read`, `usage:write` and
`billing:self`:

```kotlin
val issued = customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageWrite), ttl = 15.minutes)
return issued.token
```

Add `billing:self` only for people who may buy for the customer, such as a workspace's owners, not
every member: it lets the app call [the billing methods](billing-pages.md).

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
product scopes with the person's identity token, and registers the person after each sign-in; an
existing customer is answered without being created again:

```kotlin
val entitler = EntitlerIdentityClient("ent_pk_live_your_key", { api.identityToken() })
entitler.register()
```

`register()` exists only on `EntitlerIdentityClient`. It answers `403 registration_closed` when the
sign-in provider does not let people register themselves (`scopes().registration` says so ahead), and
`403 scope_required` when the key lacks `customers:register`. Customers registered this way have ids
of the form `<provider id>:<subject>`; a server acting on one uses that id.

Pass a token provider rather than a fixed token, so the client fetches a fresh identity token when one
expires. Choose a sign-in provider that refreshes its own tokens, such as Firebase Auth: Sign in with
Apple's identity tokens last ten minutes and the device cannot refresh them, so a session built on
them alone ends after ten minutes.

## One client per signed-in customer

Create the client when the customer signs in, keep it where the app keeps state for the session, not
in one screen, and close it at sign-out. Closing cancels the calls in flight and drops the kept
answers; a closed client throws `IllegalStateException` on every call. The next sign-in creates a new
client, so nothing kept for one person is ever answered to another:

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

## Visitors and kept answers

The in-app client sends a visitor id on every request, so a person keeps their experiment arm before
and after registering; see [pricing pages and visitors](pricing-and-visitors.md#visitors-in-an-app)
for keeping it across launches and resetting it at sign-out. It keeps answers in memory only (a size,
or off): nothing it reads survives a relaunch except an [offline snapshot](offline-snapshots.md).
On Android, see [Android](android.md).
