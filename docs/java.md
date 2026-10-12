# Calling from Java

The SDK's requests are Kotlin `suspend` functions. From Java, call them through a small Kotlin adapter
in your project that answers futures:

```kotlin
object EntitlerFutures {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether [customer] is entitled to [feature], as a future. */
    @JvmStatic
    fun isEntitled(
        customer: Customer,
        feature: Feature,
    ): CompletableFuture<Boolean> = scope.future { customer.isEntitled(feature) }
}
```

The factories and top-level functions are on the `Entitler` class, with overloads for the defaults:

```java
EntitlerServer entitler = Entitler.EntitlerServer(key);
Customer customer = entitler.customer("user_123");
boolean canExport = EntitlerFutures.isEntitled(customer, new Feature.OnOff("export_pdf")).join();
```

`EntitlerException` is unchecked, so Java code catches it where it chooses.
