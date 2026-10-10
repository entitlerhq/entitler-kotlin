# Reliability

## Timeouts

Each attempt has a deadline, `timeout` (10 seconds by default), covering the whole attempt from
connecting to the last byte of the answer; it is OkHttp's call timeout. Override it for one call:

```kotlin
suspend fun quickCheck(customer: Customer): Boolean = customer.check(Features.exportPdf, timeout = 1.seconds).entitled
```

An injected `OkHttpClient` keeps its own timeouts beside this one. Every method honours coroutine
cancellation: cancelling the caller cancels the request and ends a retry wait at once.

## Retries

Connection failures, timeouts and answers `408`, `429`, `500`, `502`, `503` and `504` are retried, at
most `maxRetries` times (2 by default), for every request: reads are safe, and every write carries an
idempotency key the API honours, the same on every attempt. The SDK waits as `Retry-After` asks, or a
random time up to `min(8, 0.5 × 2^n)` seconds before retry `n`. A `Retry-After` longer than
`maxRetryDelay` (10 seconds) fails at once, and the error's `retryAfter` says when to try again.

## The answer cache

Checks, entitlement lists, customer plans and customer pricing, the server's `pricing()` and
`features()`, and the publishable client's `pricing()` go through an answer cache: an in-memory store of
1,000 answers by default. An answer younger than its `max-age` answers without a request, unless this
client wrote to that customer since or the read passes `revalidate = true`; otherwise the SDK
revalidates it with its `ETag`, and a `304` answers the kept copy. Answers that hold a meter are
revalidated every time. Answers marked
`no-store` are never kept. Cache keys are SHA-256 digests and never hold a credential; a refreshed
token keeps its customer's answers.

## Stale answers

When a read through the cache fails because Entitler is unreachable (a connection failure, a timeout,
`429` or a `5xx`, after retries) and the cache holds an answer received less than `staleFor` ago (24
hours by default), the read answers it with `stale` true and passes the error to `onError`. An answer
that does not decode counts as unreachable too, since its usual cause is a proxy or captive portal.
For the next 30 seconds (or the answer's `Retry-After`, if longer) reads with a kept answer are
answered from it without a request, so an outage costs one slow read, not one per call:

```kotlin
val check = customer.check(Features.exportPdf)
if (check.stale) println("Entitler is unreachable; this answer was kept from earlier.")
```

## Options

```kotlin
EntitlerServer(System.getenv("ENTITLER_KEY")) {
    timeout = 3.seconds
    maxRetries = 3
    maxRetryDelay = 5.seconds
    staleFor = 6.hours
    cache = MemoryCacheStore(5_000)
    onError = { error -> println("Entitler fallback: ${error.message}") }
}
```

On the server, a custom store shares answers through Redis or another shared store. It must be safe for
concurrent use; a failing `get` counts as a miss and a failing `set` is skipped, each passed to
`onError`:

```kotlin
class MapCacheStore : CacheStore {
    private val entries = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun get(key: String): CacheEntry? = entries[key]

    override suspend fun set(
        key: String,
        entry: CacheEntry,
        ttl: Duration,
    ) {
        entries[key] = entry
    }
}
```

Turn the cache off with `cache = null`. Stores other than the in-memory one should expire entries after
`staleFor` plus the entry's `max-age`.

Custom stores are for the server client only. An in-app client's credential changes with every token,
so a store kept across launches would never be read again: it takes a `MemoryCacheStore` or `null`.
Stale answers never cross credentials, so when a token provider fails (offline, typically) the read
fails with `EntitlerTokenException`. Apps that must work offline or after a relaunch verify an
[offline snapshot](offline-snapshots.md), falling back when `error.isUnreachable`.
