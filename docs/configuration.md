# Configuration

Both clients take their options in the factory's configuration lambda.

| Option | Default | Meaning |
| --- | --- | --- |
| `baseUrl` | `https://api.entitler.dev` | the API's base URL; trailing slashes are removed |
| `timeout` | 10 seconds | per attempt |
| `maxRetries` | 2 | retries after the first attempt |
| `maxRetryDelay` | 10 seconds | the longest `Retry-After` the SDK waits for |
| `cache` | `MemoryCacheStore(1000)` | the answer cache; a custom `CacheStore`, or `null` for off |
| `staleFor` | 24 hours | how long a kept answer may stand in when Entitler is unreachable |
| `onError` | none | called with each error a fallback absorbed |
| `asOf` | none | read the API at another instant (see [as-of](as-of.md)) |
| `httpClient` | one the SDK builds | your `OkHttpClient`, to share its connection pool or add interceptors |
| `visitor` | generated | in-app clients only: the visitor id to send |

```kotlin
fun sharedHttpClient(http: OkHttpClient): EntitlerServer =
    EntitlerServer(System.getenv("ENTITLER_KEY")) {
        baseUrl = "https://api.entitler.dev"
        httpClient = http.newBuilder().callTimeout(Duration.ofSeconds(30)).build()
    }
```

Clients are thread-safe and meant to be shared. `close()` releases only an `OkHttpClient` the SDK
built; one you pass stays yours. A client's string form shows its base URL and kind, never a
credential. The SDK sends nothing to anyone but the base URL: no telemetry.
