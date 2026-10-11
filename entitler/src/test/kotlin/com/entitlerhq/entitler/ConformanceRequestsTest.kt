package com.entitlerhq.entitler

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The conformance cases for cache keys, path encoding and idempotency keys (sections 7.3, 6.1, 4.3, 5.5, 7.2 and 5.2). */
class ConformanceRequestsTest {
    @Test
    fun `every conformance file matches its manifest entry`() {
        Conformance.verified
        assertEquals(10, Conformance.files.size)
    }

    @TestFactory
    fun `cache keys`() = conformanceTests("cache-keys.json", ::cacheKey)

    @TestFactory
    fun `path encoding`() = conformanceTests("path-encoding.json", ::pathEncoding)

    @TestFactory
    fun `idempotency keys`() = conformanceTests("idempotency-keys.json", ::idempotencyKey)

    private fun cacheKey(case: JsonObject) =
        runTest {
            val transport = FakeTransport { 0 }
            val store = RecordingStore()
            val built = case["built"]!!.jsonObject
            cacheClient(case, transport, store).use { client ->
                val callVisitor = if (client is EntitlerServer || case.str("clientVisitor") != null) case.str("visitor") else null
                val outcome = runCatching { readRoute(client, built, callVisitor, asOfOf(case)) }
                outcome.exceptionOrNull()?.let { error ->
                    if (error !is EntitlerApiException || error.code != ErrorCode.InvalidResponse) throw error
                }
            }
            val outcome = case["outcome"]!!.jsonObject
            assertEquals(outcome.str("cacheKey"), sha256(outcome.str("serialised")!!))
            assertEquals(outcome.str("cacheKey"), store.asked.first())
            val request = transport.received.single()
            assertEquals(case.str("url"), request.url)
            assertEquals(case.str("method"), request.method)
            for ((name, value) in outcome["requestHeaders"]!!.jsonObject) {
                assertEquals(stringOf(value), request.headers[name], name)
            }
        }

    /** A cache store that records the keys it is asked for and keeps nothing. */
    private class RecordingStore : CacheStore {
        val asked = mutableListOf<String>()

        override suspend fun get(key: String): CacheEntry? {
            asked += key
            return null
        }

        override suspend fun set(
            key: String,
            entry: CacheEntry,
            ttl: Duration,
        ) = Unit
    }

    private fun cacheClient(
        case: JsonObject,
        transport: FakeTransport,
        store: CacheStore,
    ): java.io.Closeable {
        val common: EntitlerConfig.() -> Unit = {
            baseUrl = case["built"]!!.jsonObject.str("baseUrl")!!
            httpClient = transport.client
            maxRetries = 0
        }
        val inApp: EntitlerClientConfig.() -> Unit = {
            common()
            testStore = store
            visitor = case.str("clientVisitor") ?: case.str("visitor")
        }
        val credential = case.str("credential")
        return when (case.str("principalKind")) {
            "key" if credential!!.trim().startsWith("ent_pk_") -> {
                EntitlerPublishableClient(credential, inApp)
            }

            "key" -> {
                EntitlerServer(case.str("credential")!!) {
                    common()
                    cache = store
                }
            }

            "customer-token" -> {
                EntitlerClient(case.str("credential")!!, inApp)
            }

            else -> {
                EntitlerIdentityClient(case.str("key")!!, case.str("identityToken")!!, inApp)
            }
        }
    }

    private fun asOfOf(case: JsonObject): Instant? =
        case.str("asOfInput")?.let { OffsetDateTime.parse(it).toInstant() } ?: case.str("asOf")?.let(Instant::parse)

    private suspend fun readRoute(
        client: java.io.Closeable,
        built: JsonObject,
        visitor: String?,
        asOf: Instant?,
    ) {
        val params = built["params"]!!.jsonObject
        val server = client as? EntitlerServer
        val serverCustomer = server?.customer(params.str("id") ?: "-")
        if (client is EntitlerPublishableClient) {
            assertEquals("/pricing", built.str("route"))
            client.pricing(visitor)
            return
        }
        val customer = serverCustomer ?: (client as EntitlerClient).me
        if (asOf != null) {
            checkNotNull(serverCustomer).check(params.str("feature")!!, asOf = asOf)
            return
        }
        when (built.str("route")) {
            "/customers/{id}/entitlements/{feature}" -> customer.check(params.str("feature")!!)
            "/customers/{id}/entitlements" -> customer.entitlements()
            "/customers/{id}/plans" -> customer.plans()
            "/customers/{id}/pricing" -> customer.pricing(visitor)
            "/pricing" -> server!!.pricing(visitor)
            "/pricing/features" -> server!!.features()
            else -> fail("Unknown route.")
        }
    }

    private fun pathEncoding(case: JsonObject) =
        runTest {
            val transport = FakeTransport { 0 }
            val kind = case.str("kind")
            EntitlerServer(CONFORMANCE_KEY) {
                if (kind == "baseUrl") case.str("baseUrl")?.let { baseUrl = it }
                httpClient = transport.client
                cache = null
                maxRetries = 0
            }.use { server ->
                transport.answer = { Canned.Status(200, body = pathAnswer(it)) }
                val error =
                    runCatching {
                        when (kind) {
                            "query" -> queryCall(server, case)
                            "arguments" -> argumentsCall(server, case["call"]!!.jsonObject)
                            else -> segmentCall(server, case)
                        }
                    }.exceptionOrNull()
                val outcome = case["outcome"]!!.jsonObject
                if (outcome.str("kind") == "ArgumentError") {
                    assertArgumentError(outcome, error)
                    assertEquals(0, transport.received.size)
                } else {
                    if (error is IllegalArgumentException) throw error
                    val url = transport.received.single().url
                    assertEquals(outcome.str("url"), url)
                    (outcome.str("segment") ?: outcome.str("encoded"))?.let { assertTrue(url.contains(it), "$url holds $it") }
                }
            }
        }

    private fun pathAnswer(request: Received): String =
        when {
            request.url.contains("/entitlements/") -> CHECK_BODY
            request.url.contains("?erase=true") -> ""
            request.url.contains("/grants/") -> fixture("grant-change")
            request.url.contains("/usage") || request.url.contains("/meters/") -> fixture("usage-result")
            request.url.endsWith("/customers") || request.url.contains("/customers?") -> fixture("customers")
            else -> fixture("plan-change")
        }

    private suspend fun queryCall(
        server: EntitlerServer,
        case: JsonObject,
    ) {
        val value = case.str("value") ?: utf16(case["valueUtf16"]!!)
        val request = case["request"]!!.jsonObject
        when (case.str("parameter")) {
            "q" -> {
                server.customers.page(q = value)
            }

            "cursor" -> {
                server.customers.page(cursor = value)
            }

            else -> {
                val customer = server.customer(request["params"]!!.jsonObject.str("id")!!)
                if (request
                        .str(
                            "route",
                        )!!
                        .endsWith("/pending")
                ) {
                    customer.undoPendingChange(product = value)
                } else {
                    customer.cancel(product = value)
                }
            }
        }
    }

    private suspend fun argumentsCall(
        server: EntitlerServer,
        call: JsonObject,
    ) {
        val options = call["options"]!!.jsonObject
        val customer = server.customer(call.str("customer")!!)
        when (call.str("method")) {
            "cancel" -> customer.cancel(addOn = options.str("addOn"), product = options.str("product"))
            "undoPendingChange" -> customer.undoPendingChange(addOn = options.str("addOn"), product = options.str("product"))
            else -> fail("Unknown method.")
        }
    }

    private suspend fun segmentCall(
        server: EntitlerServer,
        case: JsonObject,
    ) {
        val request = case["request"]!!.jsonObject
        val params = request["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        case.str("parameter")?.let { parameter ->
            params[if (parameter == "customer") "id" else parameter] = case.str("id") ?: utf16(case["idUtf16"]!!)
        }
        val options = request["options"] as? JsonObject ?: JsonObject(emptyMap())
        val customer = server.customer(params.getValue("id"))
        val route = request.str("method") + " " + request.str("route")
        if (route.contains("/usage") || route.contains("/grants/")) return usageSegmentCall(customer, route, params, options)
        when (route) {
            "GET /customers/{id}/entitlements/{feature}" -> {
                customer.check(params.getValue("feature"))
            }

            "DELETE /customers/{id}?erase=true" -> {
                customer.erase()
            }

            "DELETE /customers/{id}/subscription/add-ons/{addOn}" -> {
                customer.cancel(addOn = params.getValue("addOn"))
            }

            "DELETE /customers/{id}/subscription/add-ons/{addOn}/pending" -> {
                customer.undoPendingChange(addOn = params.getValue("addOn"))
            }

            "PUT /customers/{id}/add-ons/{addOn}" -> {
                customer.setAddOn(params.getValue("addOn"), options["quantity"]!!.jsonPrimitive.long)
            }

            "POST /customers/{id}/meters/{feature}/adjustments" -> {
                customer.adjustMeter(
                    Feature.Metered(params.getValue("feature")),
                    idempotencyKey = options.str("idempotencyKey")!!,
                    by = options["by"]!!.jsonPrimitive.long,
                )
            }

            else -> {
                fail("Unknown route.")
            }
        }
    }

    private suspend fun usageSegmentCall(
        customer: ServerCustomer,
        route: String,
        params: Map<String, String>,
        options: JsonObject,
    ) {
        when (route) {
            "POST /customers/{id}/usage/holds/{holdId}/settle" -> {
                customer.settleUsage(params.getValue("holdId"), options["amount"]!!.jsonPrimitive.long)
            }

            "DELETE /customers/{id}/usage/holds/{holdId}" -> {
                customer.releaseUsage(params.getValue("holdId"))
            }

            "DELETE /customers/{id}/grants/{grantId}" -> {
                customer.revokeGrant(params.getValue("grantId"))
            }

            "DELETE /customers/{id}/usage/{usageId}" -> {
                customer.cancelUsage(params.getValue("usageId"))
            }

            else -> {
                fail("Unknown route.")
            }
        }
    }

    private fun idempotencyKey(case: JsonObject) =
        runTest {
            val transport = FakeTransport { 0 }
            transport.answer = { Canned.Status(200, body = keyAnswer(it)) }
            EntitlerServer(CONFORMANCE_KEY) {
                httpClient = transport.client
                cache = null
                maxRetries = 0
            }.use { server ->
                val outcome = case["outcome"]!!.jsonObject
                if (case.str("method") == "recordUsageBatch") {
                    assertBatch(server, case, outcome, transport)
                    return@use
                }
                val error = runCatching { sendWithKey(server, case) }.exceptionOrNull()
                if (outcome.str("kind") == "ArgumentError") {
                    assertArgumentError(outcome, error)
                    assertEquals(0, transport.received.size)
                } else {
                    error?.let { throw it }
                    assertKeysSent(case, outcome, transport.received)
                    if (case.str("key") == null) {
                        val first = transport.received.first().headers["Idempotency-Key"]
                        transport.received.clear()
                        sendWithKey(server, case)
                        val second = transport.received.first().headers["Idempotency-Key"]
                        assertTrue(first != second, "each call sends its own key")
                    }
                }
            }
        }

    private fun keyAnswer(request: Received): String {
        val url = request.url
        return when {
            url.endsWith("/usage/holds") -> fixture("usage-held").replace("\"amount\": 100", "\"amount\": 1")
            url.endsWith("/settle") -> fixture("usage-settled")
            url.endsWith("/usage") || url.contains("/adjustments") -> fixture("usage-result")
            url.endsWith("/tokens") -> fixture("token")
            url.endsWith("/snapshots") -> fixture("snapshot")
            url.endsWith("/billing-portal") -> fixture("provider-page")
            url.endsWith("/billing/sync") -> fixture("billing-sync")
            url.endsWith("/usage/events") -> "{}"
            else -> fixture("plan-change")
        }
    }

    private suspend fun sendWithKey(
        server: EntitlerServer,
        case: JsonObject,
    ) {
        val key = case.str("key")
        val credits = Feature.Metered("ai_credits")
        val customer = server.customer("user_42")
        when (case.str("method")) {
            "recordUsage" -> {
                customer.recordUsage(credits, 1, idempotencyKey = checkNotNull(key))
            }

            "holdUsage" -> {
                customer.holdUsage(credits, 1, idempotencyKey = checkNotNull(key))
            }

            "startHold" -> {
                customer.startHold(credits, 1, idempotencyKey = checkNotNull(key)).use {
                    it.used = 2
                    it.finish()
                }
            }

            "withHold" -> {
                customer.withHold(credits, 1, idempotencyKey = checkNotNull(key)) { it.used = 2 }
            }

            "adjustMeter" -> {
                customer.adjustMeter(credits, idempotencyKey = checkNotNull(key), by = 1)
            }

            "cancel" -> {
                customer.cancel(idempotencyKey = key)
            }

            "token" -> {
                customer.token()
            }

            "snapshot" -> {
                customer.snapshot()
            }

            "billingPortal" -> {
                customer.billingPortal(returnUrl = "https://app.example.com/billing")
            }

            "syncBilling" -> {
                customer.syncBilling()
            }

            else -> {
                fail("Unknown method.")
            }
        }
    }

    private fun assertKeysSent(
        case: JsonObject,
        outcome: JsonObject,
        received: List<Received>,
    ) {
        val key = case.str("key")
        val sent = assertNotNull(received.first().headers["Idempotency-Key"])
        if (key != null) assertEquals(key, sent) else assertTrue(UUID_V4.matches(sent), "$sent is a UUID v4")
        outcome.str("excessKey")?.let { excessKey ->
            assertEquals(excessKey, received.last { it.url.endsWith("/usage") }.headers["Idempotency-Key"])
        }
    }

    private suspend fun assertBatch(
        server: EntitlerServer,
        case: JsonObject,
        outcome: JsonObject,
        transport: FakeTransport,
    ) {
        val events = batchEvents(case)
        val register = case.bool("register")
        repeat(case["calls"]?.jsonPrimitive?.int ?: 1) {
            transport.received.clear()
            val result = if (register == null) server.recordUsageBatch(events) else server.recordUsageBatch(events, register)
            val refused = outcome["refused"]!!.jsonArray.map { it.jsonObject }
            val errors = result.results.withIndex().filter { (index, _) -> refused.any { it["index"]!!.jsonPrimitive.int == index } }
            assertEquals(refused.size, errors.size)
            refused.zip(errors).forEach { (expected, actual) ->
                assertEquals(UsageOutcome.Error, actual.value.outcome)
                assertEquals(
                    expected.str("code"),
                    actual.value.error
                        ?.code
                        .toString(),
                )
                assertEquals(expected.str("message"), actual.value.error?.message)
            }
            val requests = outcome["requests"]!!.jsonArray.map { it.jsonObject }
            assertEquals(requests.size, transport.received.size)
            requests.zip(transport.received).forEach { (expected, request) ->
                val sent = Json.parseToJsonElement(request.body).jsonObject["events"]!!.jsonArray
                assertEquals(expected["events"]!!.jsonPrimitive.int, sent.size)
                assertEquals(expected.str("idempotencyKey"), "batch:" + sha256(expected.str("serialised")!!))
                assertEquals(expected.str("idempotencyKey"), request.headers["Idempotency-Key"])
            }
        }
    }

    private fun batchEvents(case: JsonObject): List<UsageBatchEvent> {
        val listed = case["events"]?.jsonArray?.map { it.jsonObject }
        val pattern = case["eventPattern"]?.jsonObject
        val events =
            (
                listed ?: List(pattern!!["count"]!!.jsonPrimitive.int) { i ->
                    JsonObject(
                        mapOf(
                            "customer" to JsonPrimitive(pattern.str("customerPrefix") + (i % pattern["customers"]!!.jsonPrimitive.int)),
                            "feature" to JsonPrimitive(pattern.str("feature")),
                            "amount" to JsonPrimitive(i + 1),
                            "idempotencyKey" to JsonPrimitive(pattern.str("keyPrefix") + i),
                        ),
                    )
                }
            ).toMutableList()
        case["overrides"]?.jsonArray?.forEach { override ->
            events[override.jsonObject["index"]!!.jsonPrimitive.int] = override.jsonObject["event"]!!.jsonObject
        }
        return events.map { event ->
            UsageBatchEvent(
                event.str("customer")!!,
                Feature.Metered(event.str("feature")!!),
                event["amount"]!!.jsonPrimitive.long,
                idempotencyKey = event.str("idempotencyKey")!!,
                occurredAt = event.str("occurredAt")?.let { OffsetDateTime.parse(it).toInstant() },
            )
        }
    }
}
