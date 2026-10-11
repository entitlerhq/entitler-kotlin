# Offline snapshots

A snapshot is the customer's entitlements, signed by Entitler (ES256), for an app to keep and check
without a connection until it expires. Snapshots are the only answers that survive a relaunch: the
in-app client keeps everything else in memory. Mint one while online:

```kotlin
suspend fun mintSnapshot(customer: Customer): String = customer.snapshot(ttl = 7.days).token
```

Verify it offline, with no request:

```kotlin
try {
    val verified = verifySnapshot(token, SnapshotExpectation(bundledKeys, customer = userId, environment = environmentId))
    verified.entitlements.has(Features.exportPdf)
} catch (error: EntitlerSnapshotException) {
    if (error.code == ErrorCode.SnapshotExpired) println("Connect to refresh your plan.")
    false
}
```

Verification checks, in order: the token's form, the signing key, the signature, the claims' shape,
the issuer, that it was not signed in the future (beyond `clockSkewSeconds`, 60 seconds by default, at
most 300), that it has not expired, the customer and the environment. Each failure is an
`EntitlerSnapshotException` with code `snapshot_invalid` or `snapshot_expired`. Offline gating never
offers a purchase, so a verified snapshot's entitlements list no `upgrades`.

## Falling back when Entitler is unreachable

Ask Entitler when you can, and verify the stored snapshot only when `isUnreachable` says Entitler could
not answer (no connection, a timeout, a rate limit, a server error or an unreadable answer). Any other
error, such as a revoked token, is a real answer and must not fall back:

```kotlin
suspend fun canExport(
    me: Customer,
    storedSnapshot: String,
    expected: SnapshotExpectation,
): Boolean =
    try {
        me.check(Features.exportPdf).entitled
    } catch (error: EntitlerException) {
        if (!error.isUnreachable) throw error
        verifySnapshot(storedSnapshot, expected).entitlements.has(Features.exportPdf)
    }
```

## Where the expectation comes from

A mistake in `SnapshotExpectation` shows only offline, so take each part from a source the snapshot
cannot change:

- `customer` is the signed-in person's id from your app's own session, never from the stored snapshot.
  For a customer registered through an identity token it is `<provider id>:<subject>`, with the sign-in
  provider's Entitler id from your app's configuration.
- `environment` is the environment id your build ships, beside its snapshot keys.
- `keys` are the keys your app trusts (below).

## Key pinning

The keys decide which snapshots your app trusts. Ship them with the app, written at build time by the
command-line tool, which needs no key:

```sh
java -jar entitler-generator.jar snapshot-keys --out src/main/resources/snapshot-keys.json
```

Run it from a Gradle task, as [feature constants](feature-constants.md) shows for `generate`, so pinning
needs no hand-written download. Replace the keys only with keys fetched from Entitler over HTTPS. Never
store them beside the snapshot or load them from the same record: anyone who can edit that record could
replace both.

## Key rotation

- Entitler publishes a new signing key before signing with it.
- It keeps retired keys published for 30 days after their last use, longer than any snapshot lives.
- An emergency replacement withdraws the old keys at once, and snapshots they signed stop verifying
  once the app fetches the keys again.

So refresh the keys whenever the app is online, keep them in your own trusted storage, and verify
offline against the keys you hold. A snapshot signed by a key the app has not fetched yet fails until
the app is next online. Verification itself never makes a request.

```kotlin
suspend fun refreshKeys(
    entitler: EntitlerClient,
    trusted: TrustedKeyStore,
) {
    try {
        trusted.write(entitler.snapshotKeys())
    } catch (error: EntitlerException) {
        if (!error.isUnreachable) throw error
    }
}
```

## Frozen meters

Meters in a snapshot are frozen at the instant it was signed: `used` and `remaining` do not move while
the app is offline. A snapshot expires no later than the first time-limited grant it holds ends, or the
customer's booked change takes effect.
