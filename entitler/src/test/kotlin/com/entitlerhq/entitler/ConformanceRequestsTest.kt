package com.entitlerhq.entitler

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
        assertEquals(8, Conformance.files.size)
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
                val outcome = runCatching { readRoute(client, built, case.str("visitor")) }
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
        val asOf = case.str("asOfInput")?.let { OffsetDateTime.parse(it).toInstant() } ?: case.str("asOf")?.let(Instant::parse)
        val common: EntitlerConfig.() -> Unit = {
            baseUrl = case["built"]!!.jsonObject.str("baseUrl")!!
            httpClient = transport.client
            cache = store
            maxRetries = 0
            this.asOf = asOf
        }
        val inApp: EntitlerClientConfig.() -> Unit = {
            common()
            visitor = case.str("visitor")
        }
        return when (case.str("principalKind")) {
            "key" -> EntitlerServer(case.str("credential")!!, common)
            "customer-token" -> EntitlerClient(case.str("credential")!!, inApp)
            else -> EntitlerIdentityClient(case.str("key")!!, case.str("identityToken")!!, inApp)
        }
    }

    private suspend fun readRoute(
        client: java.io.Closeable,
        built: JsonObject,
        visitor: String?,
    ) {
        val params = built["params"]!!.jsonObject
        val server = client as? EntitlerServer
        val customer = server?.customer(params.str("id") ?: "-") ?: (client as EntitlerClient).me
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
                val error =
                    runCatching {
                        if (kind == "query") {
                            server.customers.page(q = case.str("value") ?: utf16(case["valueUtf16"]!!))
                        } else {
                            segmentCall(server, case)
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

    private suspend fun segmentCall(
        server: EntitlerServer,
        case: JsonObject,
    ) {
        val request = case["request"]!!.jsonObject
        val params = request["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        case.str("parameter")?.let { parameter ->
            params[if (parameter == "customer") "id" else parameter] = case.str("id") ?: utf16(case["idUtf16"]!!)
        }
        val customer = server.customer(params.getValue("id"))
        when (request.str("route")) {
            "/customers/{id}/entitlements/{feature}" -> customer.check(params.getValue("feature"))
            "/customers/{id}/subscription/add-ons/{plan}" -> customer.removeAddOn(params.getValue("plan"))
            "/customers/{id}/usage/holds/{holdId}" -> customer.hold(params.getValue("holdId"))
            "/customers/{id}/grants/{grantId}" -> customer.vendor.revokeGrant(params.getValue("grantId"))
            "/customers/{id}/usage/{usageId}" -> customer.vendor.cancelUsage(params.getValue("usageId"))
            else -> fail("Unknown route.")
        }
    }

    private fun idempotencyKey(case: JsonObject) =
        runTest {
            val transport = FakeTransport { 0 }
            EntitlerServer(CONFORMANCE_KEY) {
                httpClient = transport.client
                cache = null
                maxRetries = 0
            }.use { server ->
                val error = runCatching { sendWithKey(server, case, transport) }.exceptionOrNull()
                val outcome = case["outcome"]!!.jsonObject
                if (outcome.str("kind") == "ArgumentError") {
                    assertArgumentError(outcome, error)
                    assertEquals(0, transport.received.size)
                } else {
                    if (error is IllegalArgumentException || (case.str("method") == "withHold" && error != null)) throw error
                    assertKeysSent(case, outcome, transport.received)
                }
            }
        }

    private suspend fun sendWithKey(
        server: EntitlerServer,
        case: JsonObject,
        transport: FakeTransport,
    ) {
        val key = case.str("key")
        val credits = Feature.Metered("ai_credits")
        val customer = server.customer("user_42")
        when (case.str("method")) {
            "write" -> {
                customer.recordUsage(credits, 1, idempotencyKey = key)
            }

            "withHold" -> {
                val answers = listOf("usage-held", "usage-settled", "usage-result").map { Canned.Status(200, body = fixture(it)) }
                transport.queue.addAll(answers)
                customer.withHold(credits, 100, idempotencyKey = key) { it.used = 130 }
            }

            "recordUsageBatch" -> {
                val events = case["events"]!!.jsonPrimitive.int
                server.recordUsageBatch(List(events) { UsageBatchEvent("user_42", credits, 1) }, idempotencyKey = key)
            }

            "batchEvent" -> {
                server.recordUsageBatch(listOf(UsageBatchEvent("user_42", credits, 1, idempotencyKey = key)))
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
        when (case.str("method")) {
            "write" -> {
                assertEquals(key, received.single().headers["Idempotency-Key"])
            }

            "withHold" -> {
                assertEquals(key, received.first().headers["Idempotency-Key"])
                assertEquals(outcome.str("excessKey"), received.last().headers["Idempotency-Key"])
            }

            "batchEvent" -> {
                val events = Json.parseToJsonElement(received.single().body).jsonObject["events"]!!.jsonArray
                assertEquals(key, events.single().jsonObject.str("idempotencyKey"))
            }

            else -> {
                assertBatchRequests(outcome["requests"]!!.jsonArray.map { it.jsonObject }, received)
            }
        }
    }

    private fun assertBatchRequests(
        requests: List<JsonObject>,
        received: List<Received>,
    ) {
        assertEquals(requests.size, received.size)
        val generated = mutableSetOf<String>()
        requests.zip(received).forEach { (expected, request) ->
            val events = Json.parseToJsonElement(request.body).jsonObject["events"]!!.jsonArray
            assertEquals(expected["events"]!!.jsonPrimitive.int, events.size)
            val sent = assertNotNull(request.headers["Idempotency-Key"])
            val wanted = expected.str("idempotencyKey")
            if (wanted != null) {
                assertEquals(wanted, sent)
            } else {
                assertTrue(UUID_V4.matches(sent), "$sent is a UUID v4")
                assertTrue(generated.add(sent), "each request has its own key")
            }
        }
    }
}
