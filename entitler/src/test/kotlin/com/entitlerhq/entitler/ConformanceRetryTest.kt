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

/** The conformance cases for retry timing and token refresh (sections 7.2, 7.5, 3.5 and 4.3). */
class ConformanceRetryTest {
    @TestFactory
    fun `retry timing`() = conformanceTests("retry-timing.json", ::retryTiming)

    @TestFactory
    fun `token refresh`() = conformanceTests("token-refresh.json", ::tokenRefresh)

    private class Run(
        val received: List<Received>,
        val providerCalls: Int,
        val error: Throwable?,
    ) {
        val waits: List<Long> get() = received.map { it.at }.zipWithNext { a, b -> b - a }
    }

    @Suppress("LongParameterList")
    private fun attempts(
        credential: JsonObject,
        now: String,
        maxRetries: Int,
        maxRetryDelaySeconds: Double,
        request: JsonObject,
        answers: List<Canned>,
        random: Random = FixedRandom(ALMOST_ONE),
    ): Run {
        lateinit var run: Run
        runTest {
            val clock = FakeClock(millisOf(now)).also { it.scheduler = testScheduler }
            val transport =
                FakeTransport(clock::now).apply {
                    queue.addAll(answers)
                    answer = { Canned.Status(418) }
                }
            val calls = AtomicInteger()

            fun EntitlerConfig.common() {
                httpClient = transport.client
                this.clock = clock::now
                this.random = random
                this.maxRetries = maxRetries
                maxRetryDelay = (maxRetryDelaySeconds * MILLIS_PER_SECOND).toLong().milliseconds
                (this as? EntitlerServerConfig)?.cache = null
                (this as? EntitlerClientConfig)?.cache = null
            }
            val path = request.str("path")!!.split('/')
            val call: suspend (Customer) -> Unit = { customer ->
                if (request.str("method") == "GET") {
                    customer.check(path[4])
                } else {
                    val body = request["body"]!!.jsonObject
                    customer.recordUsage(
                        Feature.Metered(body.str("feature")!!),
                        body["amount"]!!.jsonPrimitive.long,
                        idempotencyKey = "write-1",
                    )
                }
            }
            val error =
                when (credential.str("kind")) {
                    "key" -> {
                        EntitlerServer(credential.str("key")!!) { common() }.use { runCatching { call(it.customer(path[2])) } }
                    }

                    "customerToken" -> {
                        EntitlerClient(credential.str("token")!!) { common() }.use { runCatching { call(it.me) } }
                    }

                    else -> {
                        val tokens = credential["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
                        EntitlerClient({ tokens[calls.getAndIncrement()] }) { common() }.use { runCatching { call(it.me) } }
                    }
                }.exceptionOrNull()
            run = Run(transport.received.toList(), calls.get(), error)
        }
        return run
    }

    private fun assertWait(
        bounds: JsonObject,
        wait: Long,
    ) {
        val min = (bounds["minSeconds"]!!.jsonPrimitive.double * MILLIS_PER_SECOND).toLong()
        val max = (bounds["maxSeconds"]!!.jsonPrimitive.double * MILLIS_PER_SECOND).toLong()
        if (min == max) {
            assertEquals(min, wait, "wait")
        } else {
            assertTrue(wait in maxOf(min, (max * ALMOST_ONE).toLong() - 1)..max, "wait of $wait ms within $min to $max ms")
        }
    }

    private fun assertResult(
        expected: JsonObject,
        error: Throwable?,
    ) {
        when (expected.str("kind")) {
            null -> {
                if (error != null) throw error
            }

            "ApiError" -> {
                val failure = assertIs<EntitlerApiException>(error)
                assertEquals(expected["status"]!!.jsonPrimitive.int, failure.status)
                assertOpen(expected["code"]!!, failure.code, "code")
            }

            "TimeoutError" -> {
                assertIs<EntitlerTimeoutException>(error)
            }

            else -> {
                fail("Unknown result kind.")
            }
        }
    }

    private fun retryTiming(case: JsonObject) {
        val outcome = case["outcome"]!!.jsonObject
        when (case.str("kind")) {
            "retryAfter" -> retryAfter(case, outcome)
            "backoff" -> backoff(case, outcome)
            "limits" -> limits(case, outcome)
            else -> sequence(case, outcome)
        }
    }

    private fun retryAfter(
        case: JsonObject,
        outcome: JsonObject,
    ) {
        val first = Canned.Status(503, listOfNotNull(case.str("retryAfter")?.let { "Retry-After" to it }).toMap())
        val maxRetryDelay = case["maxRetryDelay"]!!.jsonPrimitive.double
        val run = attempts(serverKey, case.str("now")!!, 2, maxRetryDelay, check, listOf(first, ok))
        when (outcome.str("action")) {
            "wait" -> {
                if (run.error != null) throw run.error
                assertEquals(listOf(outcome["seconds"]!!.jsonPrimitive.long * MILLIS_PER_SECOND), run.waits)
            }

            "backoff" -> {
                if (run.error != null) throw run.error
                assertWait(Json.parseToJsonElement("""{"minSeconds":0,"maxSeconds":0.5}""").jsonObject, run.waits.single())
            }

            else -> {
                assertEquals(1, run.received.size)
                assertEquals(503, assertIs<EntitlerApiException>(run.error).status)
            }
        }
    }

    private fun backoff(
        case: JsonObject,
        outcome: JsonObject,
    ) {
        val retry = case["retry"]!!.jsonPrimitive.int
        val run = attempts(serverKey, CONFORMANCE_NOW, retry + 1, 10.0, check, List(retry + 1) { unavailable } + ok)
        if (run.error != null) throw run.error
        assertWait(outcome, run.waits[retry])
    }

    private fun limits(
        case: JsonObject,
        outcome: JsonObject,
    ) {
        val maxRetries = case["maxRetries"]!!.jsonPrimitive.int
        val plenty = List(maxRetries + 3) { unavailable }
        assertEquals(
            outcome["maxAttemptsWithoutUnauthorised"]!!.jsonPrimitive.int,
            attempts(serverKey, CONFORMANCE_NOW, maxRetries, 10.0, check, plenty).received.size,
        )
        val provider = Json.parseToJsonElement("""{"kind":"customerTokenProvider","tokens":["$PROVIDED_TOKEN","$PROVIDED_TOKEN"]}""")
        val me = Json.parseToJsonElement("""{"method":"GET","path":"/customers/me/entitlements/export_pdf"}""").jsonObject
        assertEquals(
            outcome["maxAttempts"]!!.jsonPrimitive.int,
            attempts(provider.jsonObject, CONFORMANCE_NOW, maxRetries, 10.0, me, listOf(Canned.Status(401)) + plenty).received.size,
        )
    }

    private fun sequence(
        case: JsonObject,
        outcome: JsonObject,
    ) {
        val run =
            attempts(
                case["credential"]!!.jsonObject,
                case.str("now")!!,
                case["maxRetries"]!!.jsonPrimitive.int,
                case["maxRetryDelay"]!!.jsonPrimitive.double,
                case["request"]!!.jsonObject,
                case["answers"]!!.jsonArray.map { Canned.of(it.jsonObject) },
            )
        assertEquals(outcome["attempts"]!!.jsonPrimitive.int, run.received.size, "attempts")
        outcome["providerCalls"]?.let { assertEquals(it.jsonPrimitive.int, run.providerCalls, "provider calls") }
        val waits = outcome["waits"]!!.jsonArray
        assertEquals(waits.size, run.waits.size, "waits")
        waits.zip(run.waits).forEach { (bounds, wait) -> assertWait(bounds.jsonObject, wait) }
        assertResult(outcome["result"]!!.jsonObject, run.error)
        val keys = run.received.map { it.headers["Idempotency-Key"] }
        if (outcome.bool("sameIdempotencyKeyOnEveryAttempt") == true) {
            assertNotNull(keys.first())
            assertEquals(1, keys.toSet().size)
        }
        if (outcome.bool("errorCarriesIdempotencyKey") == true) {
            assertEquals(keys.first(), assertIs<EntitlerApiException>(run.error).idempotencyKey)
        }
    }

    private val serverKey = Json.parseToJsonElement("""{"kind":"key","key":"$CONFORMANCE_KEY"}""").jsonObject
    private val check = Json.parseToJsonElement("""{"method":"GET","path":"/customers/user_42/entitlements/export_pdf"}""").jsonObject
    private val ok = Canned.Status(200, body = CHECK_BODY)
    private val unavailable = Canned.Status(503)

    private fun tokenRefresh(case: JsonObject) =
        runTest {
            val clock = FakeClock(millisOf(case.str("receivedAt")!!))
            val transport = FakeTransport(clock::now).apply { answer = { Canned.Status(200, body = CHECK_BODY) } }
            val calls = AtomicInteger()
            val token = case.str("token")!!
            EntitlerClient({
                calls.incrementAndGet()
                token
            }) {
                httpClient = transport.client
                this.clock = clock::now
                cache = null
                maxRetries = 0
            }.use { client ->
                val outcome = case["outcome"]!!.jsonObject
                if (outcome.str("kind") == "TokenError") {
                    val error = runCatching { client.me.check("export_pdf") }.exceptionOrNull()
                    assertIs<EntitlerTokenException>(error)
                    assertEquals(0, transport.received.size)
                    return@use
                }
                client.me.check("export_pdf")
                val expected = outcome["requestHeaders"]!!.jsonObject.str("Authorization")
                assertEquals(expected, transport.received.single().headers["Authorization"])
                assertEquals(1, calls.get())
                clock.base = millisOf(case.str("now")!!)
                client.me.check("export_pdf")
                assertEquals(outcome["asksProvider"]!!.jsonPrimitive.boolean, calls.get() == 2, "asks the provider again")
            }
        }
}
