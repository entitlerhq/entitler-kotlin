package com.entitlerhq.entitler

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    private val api = FakeApi()

    @AfterEach
    fun close() = api.close()

    private val error = """{"error":{"code":"unavailable","message":"Try again."}}"""

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `retried statuses are retried`(status: Int) =
        runTest {
            api.server { cache = null }.use { server ->
                api.reply(status, error)
                api.replyFixture("check")
                assertTrue(server.customer("u").check("export_pdf").entitled)
                assertEquals(2, api.server.requestCount)
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 402, 403, 404, 409, 422, 501])
    fun `other statuses are not retried`(status: Int) =
        runTest {
            api.server { cache = null }.use { server ->
                api.reply(status, error)
                val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("export_pdf") }
                assertEquals(status, failure.status)
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `retries stop at maxRetries and fail with the last error`() =
        runTest {
            api.server { maxRetries = 3 }.use { server ->
                repeat(3) { api.reply(503, error) }
                api.reply(500, """{"error":{"code":"internal","message":"Last."}}""")
                val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("export_pdf") }
                assertEquals(500, failure.status)
                assertEquals("Last.", failure.message)
                assertEquals(4, api.server.requestCount)
            }
        }

    @Test
    fun `every attempt of a write sends the same idempotency key`() =
        runTest {
            api.server().use { server ->
                api.reply(503, error)
                api.reply(502, error)
                api.replyFixture("summary")
                server.customer("u").update(name = "Ada")
                val keys = List(3) { api.take().headers["Idempotency-Key"] }
                assertEquals(1, keys.toSet().size)
                assertTrue(Regex("[0-9a-f-]{36}").matches(keys.first()!!))
            }
        }

    @Test
    fun `a caller's idempotency key is sent and carried by the error`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.reply(503, error, "x-request-id" to "req_1")
                val failure =
                    assertFailsWith<EntitlerApiException> {
                        server.customer("u").recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "job-42")
                    }
                assertEquals("job-42", failure.idempotencyKey)
                assertEquals("req_1", failure.requestId)
                assertEquals("job-42", api.take().headers["Idempotency-Key"])
            }
        }

    @Test
    fun `an invalid idempotency key fails before any request`() =
        runTest {
            api.server().use { server ->
                for (key in listOf("", "x".repeat(201), "tab\tkey", "é", " leading", "trailing ")) {
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            server.customer("u").recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = key)
                        }
                    assertEquals("Pass idempotencyKey as 1 to 200 printable ASCII characters.", failure.message)
                }
                server.customer("u").let { api.replyFixture("usage-result") }
                server.customer("u").recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "x".repeat(200))
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `Retry-After in seconds is waited for`() =
        runTest {
            api.server { cache = null }.use { server ->
                api.reply(429, error, "Retry-After" to "3")
                api.replyFixture("check")
                val started = currentTime
                server.customer("u").check("export_pdf")
                assertEquals(3000, currentTime - started)
            }
        }

    @Test
    fun `Retry-After as a date is waited for`() =
        runTest {
            api.server { cache = null }.use { server ->
                val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(api.now + 5000).atOffset(ZoneOffset.UTC))
                api.reply(503, error, "Retry-After" to date)
                api.replyFixture("check")
                val started = currentTime
                server.customer("u").check("export_pdf")
                assertEquals(5000, currentTime - started)
            }
        }

    @Test
    fun `a Retry-After longer than maxRetryDelay fails at once with retryAfter`() =
        runTest {
            api.server { maxRetryDelay = 5.seconds }.use { server ->
                api.reply(503, error, "Retry-After" to "60")
                val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("export_pdf") }
                assertEquals(60.seconds, failure.retryAfter)
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `backoff waits a random time within the full-jitter bounds`() =
        runTest {
            api.server { maxRetries = 6 }.use { server ->
                repeat(6) { api.reply(500, error) }
                api.replyFixture("check")
                val started = currentTime
                server.customer("u").check("export_pdf")
                val ceilings = listOf(500L, 1000, 2000, 4000, 8000, 8000)
                assertTrue(currentTime - started <= ceilings.sum())
                assertTrue(currentTime - started > 0)
            }
        }

    @Test
    fun `the backoff bound never overflows, even with 64 retries`() =
        runTest {
            api
                .server {
                    maxRetries = 64
                    random = FixedRandom(ALMOST_ONE)
                }.use { server ->
                    repeat(64) { api.reply(500, error) }
                    api.replyFixture("check")
                    val started = currentTime
                    assertTrue(server.customer("u").check("export_pdf").entitled)
                    val waited = currentTime - started
                    val ceilings = listOf(500L, 1000, 2000, 4000) + List(60) { 8000L }
                    assertTrue(waited <= ceilings.sum(), "$waited")
                    assertTrue(waited >= ceilings.sum() * 99 / 100, "$waited")
                    assertEquals(65, api.server.requestCount)
                }
        }

    @Test
    fun `connection failures are retried and then raised as connection errors`() =
        runTest {
            api.server { maxRetries = 1 }.use { server ->
                repeat(2) { api.server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
                val failure =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertFailsWith<EntitlerConnectionException> {
                            server.customer("u").recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k1")
                        }
                    }
                assertEquals("k1", failure.idempotencyKey)
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `an attempt past its timeout is a timeout error`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.server.enqueue(
                    MockResponse
                        .Builder()
                        .body("{}")
                        .headersDelay(2, TimeUnit.SECONDS)
                        .build(),
                )
                val failure =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertFailsWith<EntitlerTimeoutException> {
                            server
                                .customer(
                                    "u",
                                ).recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k", timeout = 200.milliseconds)
                        }
                    }
                assertTrue(failure.idempotencyKey!!.isNotEmpty())
            }
        }

    @Test
    fun `a sub-millisecond timeout still times out, and a per-call timeout must be positive`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.server.enqueue(
                    MockResponse
                        .Builder()
                        .body(fixture("check"))
                        .headersDelay(2, TimeUnit.SECONDS)
                        .build(),
                )
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertFailsWith<EntitlerTimeoutException> {
                        server.customer("u").check("export_pdf", timeout = kotlin.time.Duration.parse("500us"))
                    }
                }
                for (timeout in listOf(kotlin.time.Duration.ZERO, (-1).milliseconds)) {
                    val failure = assertFailsWith<IllegalArgumentException> { server.customer("u").check("export_pdf", timeout = timeout) }
                    assertEquals("Pass timeout as a positive duration.", failure.message)
                }
            }
            api
                .server {
                    maxRetries = 0
                    timeout = kotlin.time.Duration.parse("500us")
                }.use { server ->
                    api.server.enqueue(
                        MockResponse
                            .Builder()
                            .body(fixture("check"))
                            .headersDelay(2, TimeUnit.SECONDS)
                            .build(),
                    )
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertFailsWith<EntitlerTimeoutException> { server.customer("u").check("export_pdf") }
                    }
                }
        }

    @Test
    fun `an infinite timeout or maxRetryDelay and an overlong Retry-After neither overflow nor hang`() =
        runTest {
            api
                .server {
                    maxRetries = 1
                    timeout = kotlin.time.Duration.INFINITE
                    maxRetryDelay = kotlin.time.Duration.INFINITE
                }.use { server ->
                    api.server.enqueue(
                        MockResponse
                            .Builder()
                            .body(fixture("check"))
                            .headersDelay(300, TimeUnit.MILLISECONDS)
                            .build(),
                    )
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertTrue(server.customer("u").check("export_pdf").entitled)
                        api.reply(429, error, "Retry-After" to "99999999999999999999999")
                        val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("sso") }
                        assertEquals(429, failure.status)
                        assertTrue(failure.retryAfter!!.isInfinite())
                    }
                }
        }

    @Test
    fun `cancellation ends a retry wait at once`() =
        runTest {
            api.server().use { server ->
                api.reply(503, error, "Retry-After" to "8")
                val call = async { server.customer("u").check("export_pdf") }
                runCurrent()
                val deadline = System.currentTimeMillis() + 5000
                while (api.server.requestCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
                Thread.sleep(100)
                runCurrent()
                advanceTimeBy(1000)
                call.cancel()
                assertFailsWith<CancellationException> { call.await() }
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `an error body without an error object reports http_error`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.reply(418, "teapot")
                val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("export_pdf") }
                assertEquals(ErrorCode.HttpError, failure.code)
                assertEquals("Entitler answered with HTTP 418.", failure.message)
                assertNull(failure.requestId)
                assertNull(failure.retryAfter)
                assertNull(failure.idempotencyKey)
                assertNull(failure.payment)
            }
        }

    @Test
    fun `an answer the SDK cannot read is invalid_response and is not retried`() =
        runTest {
            api.server().use { server ->
                api.reply(200, """{"customer":"u"}""", "x-request-id" to "req_2")
                val failure =
                    assertFailsWith<EntitlerApiException> {
                        server
                            .customer(
                                "u",
                            ).recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k9")
                    }
                assertEquals(ErrorCode.InvalidResponse, failure.code)
                assertEquals(200, failure.status)
                assertEquals("req_2", failure.requestId)
                assertEquals("k9", failure.idempotencyKey)
                assertIs<kotlinx.serialization.SerializationException>(failure.cause)
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `Retry-After takes digits or any of the three HTTP date forms, and ignores anything else`() {
        val now = Instant.parse("1994-11-06T08:49:30Z").toEpochMilli()
        assertEquals(7.seconds, retryAfterOf("7", now))
        assertEquals(7.seconds, retryAfterOf(" 7 ", now))
        assertEquals(7.seconds, retryAfterOf("Sun, 06 Nov 1994 08:49:37 GMT", now))
        assertEquals(7.seconds, retryAfterOf("Sunday, 06-Nov-94 08:49:37 GMT", now))
        assertEquals(7.seconds, retryAfterOf("Sun Nov  6 08:49:37 1994", now))
        assertEquals(0.seconds, retryAfterOf("Sun, 06 Nov 1994 08:49:00 GMT", now))
        for (bad in listOf("", "-1", "1.5", "soon", "+3")) assertNull(retryAfterOf(bad, now), bad)
    }

    @Test
    fun `redirects are never followed and fail with http_error`() =
        runTest {
            val other = mockwebserver3.MockWebServer().apply { start() }
            try {
                api.server { maxRetries = 0 }.use { server ->
                    for (status in listOf(301, 302, 307, 308)) {
                        api.reply(
                            status,
                            """{"error":{"code":"moved","message":"Elsewhere."}}""",
                            "Location" to other.url("/steal").toString(),
                        )
                        val failure =
                            assertFailsWith<EntitlerApiException> {
                                server
                                    .customer(
                                        "u",
                                    ).recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
                            }
                        assertEquals(status, failure.status)
                        assertEquals(ErrorCode.HttpError, failure.code)
                        assertEquals("Entitler answered with HTTP $status.", failure.message)
                    }
                }
                EntitlerIdentityClient(PUBLISHABLE_KEY, "identity") {
                    api.setUp(this)
                    httpClient = okhttp3.OkHttpClient()
                }.use { client ->
                    api.reply(307, "", "Location" to other.url("/steal").toString())
                    assertFailsWith<EntitlerApiException> { client.me.check("export_pdf") }
                }
                assertEquals(0, other.requestCount)
            } finally {
                other.close()
            }
        }
}
