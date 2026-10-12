# Configuration

Every client takes its options in the factory's configuration lambda.

| Option | Default | Meaning |
| --- | --- | --- |
| `baseUrl` | `https://api.entitler.dev` | the API's base URL; trailing slashes are removed |
| `timeout` | 10 seconds | per attempt |
| `maxRetries` | 2 | retries after the first attempt |
| `maxRetryDelay` | 10 seconds | the longest `Retry-After` the SDK waits for |
| `cache` | `MemoryCacheStore(1000)` | the answer cache, or `null` for off; the server client also takes a custom `CacheStore` |
| `staleFor` | 24 hours | how long a kept answer may stand in when Entitler is unreachable |
| `onError` | none | called with each error a fallback absorbed |
| `httpClient` | one the SDK builds | your `OkHttpClient`, to share its connection pool or add interceptors |
| `visitor` | generated | in-app clients only: the visitor id to send |
| `visitorSource` | none | in-app clients only: a `suspend` function answering the visitor id, called once before the first request |

```kotlin
fun sharedHttpClient(http: OkHttpClient): EntitlerServer =
    EntitlerServer(System.getenv("ENTITLER_KEY")) {
        baseUrl = "https://api.entitler.dev"
        httpClient = http.newBuilder().callTimeout(Duration.ofSeconds(30)).build()
    }
```

Clients are thread-safe and meant to be shared. Create the server client on first use where a build or a
framework loads your code without the key, such as a static site build or a test that never calls
Entitler:

```kotlin
object Entitler {
    /** The process's one server client, created when first used, so a build that loads this class without the key still works. */
    val server: EntitlerServer by lazy { EntitlerServer(System.getenv("ENTITLER_KEY")) }
}
```

Reads at another instant are per call, on the server only (see [as-of](as-of.md)). `close()` releases only an `OkHttpClient` the SDK
built; one you pass stays yours. A client's string form shows its base URL and kind, never a
credential. The SDK sends nothing to anyone but the base URL: no telemetry.
