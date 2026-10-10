# Testing your app

The `entitler-testing` artefact tests your own gating and billing code without the network. Add it for
tests only:

```kts
dependencies {
    testImplementation("com.entitlerhq:entitler-testing:0.1.0")
}
```

Write the code under test against `Customer` (or `ServerCustomer`), so a test can pass a fake one:

```kotlin
class ExportService(
    private val customer: Customer,
) {
    /** Exports a report when the customer has the feature and credits remain, charging one credit for it. */
    suspend fun export(reportId: String): String {
        if (!customer.isEntitled(Feature.OnOff("export_pdf"))) return "Upgrade to export PDFs."
        val charge = customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "export-$reportId")
        return if (charge.outcome == UsageOutcome.Refused) "Out of credits." else "Exported $reportId."
    }
```

## A fake customer

`fakeCustomer` answers a `ServerCustomer` that makes no request. Give it each feature's value: `true` or
`false`, a whole number, a `FeatureValue`, or a meter with `metered(value, used)`.

- Reads answer complete checks and entitlements from those values. A key it does not hold answers
  `404 feature_not_found`, so `isEntitled` answers its default. `plans()` and `pricing()` answer the
  `plans` and `pricing` you pass, built with `Answers`.
- Usage writes move the meters as Entitler would: a gated report past the allowance is refused, holds
  settle and release, and a reused key replays.
- `writes` holds every write it received, in order, with its method and arguments.
- Other writes answer a plain success (`subscribe` a `Done` step, the billing writes a `PlanChange`
  made now). `answer(method) { … }` replaces one method's answer, or throws from it.

```kotlin
@Test
fun `exports and charges a credit, then runs out`() =
    runBlocking<Unit> {
        val customer = fakeCustomer("export_pdf" to true, "ai_credits" to metered(value = 100, used = 99))
        val service = ExportService(customer)
        assertEquals("Exported r1.", service.export("r1"))
        assertEquals("Out of credits.", service.export("r2"))
        assertEquals(listOf("recordUsage", "recordUsage"), customer.writes.map { it.method })
        assertEquals("export-r1", customer.writes.first().arguments["idempotencyKey"])
    }
```

```kotlin
@Test
fun `an upgrade opens the payment page, or needs nothing when done`() =
    runBlocking<Unit> {
        val customer = fakeCustomer()
        assertNull(ExportService(customer).upgrade("https://example.com/back"))
        customer.answer("subscribe") { SubscribeStep.Pay("https://checkout.example.com/pay") }
        assertEquals("https://checkout.example.com/pay", ExportService(customer).upgrade("https://example.com/back"))
    }
```

## A fake server

To test the HTTP path too, such as your `OkHttpClient` settings, serve answers from OkHttp's
`MockWebServer`. `Answers` builds every answer the SDK decodes, as complete JSON from `Answers.body`, so a
fixture missing a field never fails as `invalid_response` and passes a test for the wrong reason:

```kotlin
web.enqueue(MockResponse.Builder().body(Answers.body(Answers.check(entitled = false))).build())
val entitler =
    EntitlerServer("ent_test_key") {
        baseUrl = web.url("/").toString()
        maxRetries = 0
        cache = null
    }
```

Set `maxRetries = 0`, so a failing answer fails at once instead of being retried, and `cache = null`, so
each read reaches the server. The SDK reads the system clock for token expiry, kept answers and stale
answers, so `runTest`'s virtual time does not move them; retry waits use `delay` and do follow it.
