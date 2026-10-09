# Offline snapshots

A snapshot is the customer's entitlements, signed by Entitler (ES256), for an app to keep and check
without a connection until it expires. Mint one while online:

```kotlin
suspend fun mintSnapshot(customer: Customer): String = customer.snapshot(ttl = 7.days).token
```

Verify it offline, with no request:

```kotlin
try {
    val verified = verifySnapshot(token, bundledKeys, customer = userId, environment = environmentId)
    verified.entitlements.has(Features.exportPdf)
} catch (error: EntitlerSnapshotException) {
    if (error.code == ErrorCode.SnapshotExpired) println("Connect to refresh your plan.")
    false
}
```

Verification checks, in order: the token's form, the signing key, the signature, the claims' shape,
the issuer, that it was not signed in the future (beyond `clockSkew`, 60 seconds by default, at most
300), that it has not expired, the customer and the environment. Each failure is an
`EntitlerSnapshotException` with code `snapshot_invalid` or `snapshot_expired`.

## Key pinning

The keys decide which snapshots your app trusts. Ship them with the app, fetched with
`snapshotKeys()` at build time, and replace them only with keys fetched from Entitler over HTTPS.
Never store them beside the snapshot or load them from the same record: anyone who can edit that
record could replace both.

## Frozen meters

Meters in a snapshot are frozen at the instant it was signed: `used` and `remaining` do not move while
the app is offline. A snapshot expires no later than the first time-limited grant it holds ends, or
the customer's booked change takes effect.
