# Ktor and Spring

Build one `EntitlerServer` per application, share it, and close it when the application stops. Server
code acts on any customer through `entitler.customer(id)`, so it never holds a client per person; the
one-client-per-signed-in-customer rule is for [apps](in-app-client.md#one-client-per-signed-in-customer).

## Ktor

A route-scoped plugin gates a route on a feature:

```kotlin
val RequireFeature =
    createRouteScopedPlugin("RequireFeature", ::RequireFeatureConfig) {
        val entitler = pluginConfig.entitler
        val feature = pluginConfig.feature
        onCall { call ->
            val userId = call.request.headers["X-User-Id"]
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
            } else if (!entitler.customer(userId).isEntitled(feature)) {
                call.respond(HttpStatusCode.PaymentRequired, "Upgrade to use ${feature.key}.")
            }
        }
    }
```

```kotlin
fun Application.module(entitler: EntitlerServer) {
    monitor.subscribe(ApplicationStopped) { entitler.close() }
    routing {
        route("/reports/pdf") {
            install(RequireFeature) {
                this.entitler = entitler
                feature = Feature.OnOff("export_pdf")
            }
            get { call.respondText("Your PDF is ready.") }
        }
    }
}
```

The full program is the [Ktor example](../examples/src/main/kotlin/com/entitlerhq/examples/ktor/Main.kt).

## Spring

Declare the client as a bean that Spring closes with the context, and call it from `suspend` handlers
(Spring MVC and WebFlux both support them with kotlinx-coroutines-reactor):

```kotlin
@Configuration
class EntitlerConfiguration {
    /** The server client. */
    @Bean(destroyMethod = "close")
    fun entitler(): EntitlerServer = EntitlerServer(System.getenv("ENTITLER_KEY"))
}
```

```kotlin
@RestController
class ReportController(
    private val entitler: EntitlerServer,
) {
    /** Exports a report when the customer is entitled to it. */
    @GetMapping("/reports/pdf")
    suspend fun exportPdf(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<String> {
        if (!entitler.customer(userId).isEntitled(Features.exportPdf)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body("Upgrade to export PDFs.")
        }
        return ResponseEntity.ok("Your PDF is ready.")
    }
}
```
