package com.entitlerhq.entitler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Kept answers standing in while Entitler is unreachable, and the window that follows (section 7.4). */
class StandInTest {
    private val api = FakeApi()

    @AfterEach
    fun close() = api.close()

    private class RecordingStore : CacheStore {
        val entries = mutableMapOf<String, CacheEntry>()
        val ttls = mutableListOf<Duration>()

        override suspend fun get(key: String): CacheEntry? = entries[key]

        override suspend fun set(
            key: String,
            entry: CacheEntry,
            ttl: Duration,
        ) {
            entries[key] = entry
            ttls += ttl
        }
    }

    private val etag = "ETag" to "\"v1\""

    @Test
    fun `an unreachable Entitler answers a kept entry marked stale and calls onError`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(503, """{"error":{"code":"unavailable","message":"Down."}}""")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 23.hours.inWholeMilliseconds
                val stale = customer.check("export_pdf")
                assertTrue(stale.stale)
                assertTrue(stale.entitled)
                assertEquals(503, (api.errors.single() as EntitlerApiException).status)
            }
        }

    @Test
    fun `an undecodable 2xx is invalid_response and counts as unreachable`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(200, "<html>captive portal</html>")
                api.reply(200, "<html>captive portal</html>")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.check("export_pdf").stale)
                assertEquals(ErrorCode.InvalidResponse, (api.errors.single() as EntitlerApiException).code)
                val failure = assertFailsWith<EntitlerApiException> { customer.check("sso") }
                assertEquals(ErrorCode.InvalidResponse, failure.code)
                assertEquals("Entitler sent an answer this SDK cannot read.", failure.message)
            }
        }

    @Test
    fun `after an unreachable read, kept entries answer for 30 seconds, then one request goes through`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(503, "")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.check("export_pdf").stale)
                api.now += 29_000
                assertTrue(customer.check("export_pdf").stale)
                assertEquals(2, api.server.requestCount)
                api.now += 1_000
                assertFalse(customer.check("export_pdf").stale)
                assertEquals(3, api.server.requestCount)
            }
        }

    @Test
    fun `an overlong Retry-After keeps the outage window open instead of overflowing it`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(429, "", "Retry-After" to "99999999999999999999999")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.check("export_pdf").stale)
                api.now += 3_600_000
                assertTrue(customer.check("export_pdf").stale)
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `when the window ends, one read probes while the others answer stale`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(503, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.check("export_pdf").stale)
                api.now += 30_000
                api.server.enqueue(
                    MockResponse
                        .Builder()
                        .code(304)
                        .headersDelay(500, TimeUnit.MILLISECONDS)
                        .build(),
                )
                val answers =
                    withContext(Dispatchers.IO) {
                        (1..20).map { async { customer.check("export_pdf") } }.awaitAll()
                    }
                assertEquals(3, api.server.requestCount)
                assertEquals(1, answers.count { !it.stale })
                api.replyFixture("check", 200, etag)
                assertFalse(customer.check("export_pdf").stale)
                assertEquals(4, api.server.requestCount)
            }
        }

    @Test
    fun `a successful answer ends the window, even after an absurd Retry-After`() =
        runTest {
            for (failure in listOf("503" to "", "429" to "99999999999999999999999")) {
                api.server { maxRetries = 0 }.use { server ->
                    val start = api.server.requestCount
                    api.replyFixture("check", 200, etag)
                    api.reply(
                        failure.first.toInt(),
                        "",
                        *listOfNotNull(failure.second.takeIf { it.isNotEmpty() }?.let { "Retry-After" to it }).toTypedArray(),
                    )
                    val customer = server.customer("u")
                    customer.check("export_pdf")
                    assertTrue(customer.check("export_pdf").stale)
                    api.replyFixture("check-metered")
                    assertFalse(customer.check("ai_credits").stale)
                    api.reply(304, "")
                    assertFalse(customer.check("export_pdf").stale)
                    assertEquals(start + 4, api.server.requestCount)
                }
            }
        }

    @Test
    fun `the window never outlasts staleFor`() =
        runTest {
            val store = RecordingStore()
            val settings: EntitlerServerConfig.() -> Unit = {
                maxRetries = 0
                staleFor = 1.hours
                cache = store
            }
            api.server(settings).use { first ->
                api.server(settings).use { second ->
                    api.replyFixture("check", 200, etag)
                    api.reply(429, "", "Retry-After" to "86400")
                    first.customer("u").check("export_pdf")
                    assertTrue(first.customer("u").check("export_pdf").stale)
                    api.now += 30.minutes.inWholeMilliseconds
                    api.reply(304, "")
                    assertFalse(second.customer("u").check("export_pdf").stale)
                    api.now += 31.minutes.inWholeMilliseconds
                    api.reply(304, "")
                    assertFalse(first.customer("u").check("export_pdf").stale)
                    assertEquals(4, api.server.requestCount)
                }
            }
        }

    @Test
    fun `a kept entry older than staleFor does not stand in`() =
        runTest {
            api
                .server {
                    maxRetries = 0
                    staleFor = 10.seconds
                }.use { server ->
                    api.replyFixture("check", 200, etag)
                    api.reply(500, "")
                    val customer = server.customer("u")
                    customer.check("export_pdf")
                    api.now += 10_000
                    assertFailsWith<EntitlerApiException> { customer.check("export_pdf") }
                    assertTrue(api.errors.isEmpty())
                }
        }

    @Test
    fun `failures that are not unreachability never stand in`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag)
                api.reply(403, """{"error":{"code":"scope_required","message":"No."}}""")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertFailsWith<EntitlerApiException> { customer.check("export_pdf") }
            }
        }

    @Test
    fun `after a 401 refresh, an entry kept under the previous token never stands in`() =
        runTest {
            var calls = 0
            EntitlerClient({ customerToken(sub = "s${++calls}") }) {
                api.setUp(this)
                maxRetries = 0
            }.use { client ->
                api.replyFixture("check", 200, etag)
                api.reply(401, """{"error":{"code":"unauthorised","message":"Expired."}}""")
                api.reply(503, "")
                client.me.check("export_pdf")
                val failure = assertFailsWith<EntitlerApiException> { client.me.check("export_pdf") }
                assertEquals(503, failure.status)
                assertEquals(2, calls)
            }
        }

    @Test
    fun `when the token provider fails, entries kept under the previous token are never answered`() =
        runTest {
            var calls = 0
            EntitlerClient({
                calls++
                if (calls > 2) error("offline") else customerToken(sub = "s$calls", exp = api.now / 1000 + 120)
            }) {
                api.setUp(this)
                maxRetries = 0
            }.use { client ->
                api.replyFixture("check", 200, etag)
                client.me.check("export_pdf")
                api.now += 61_000
                api.replyFixture("check", 200, etag)
                client.me.check("export_pdf")
                api.now += 61_000
                assertFailsWith<EntitlerTokenException> { client.me.check("export_pdf") }
                assertFalse(client.me.isEntitled("export_pdf"))
                assertTrue(api.errors.single() is EntitlerTokenException)
            }
        }
}
