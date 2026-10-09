package com.entitlerhq.entitler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class CacheTest {
    private val api = FakeApi()

    @AfterEach
    fun close() = api.close()

    private class RecordingStore : CacheStore {
        val entries = mutableMapOf<String, CacheEntry>()

        override suspend fun get(key: String): CacheEntry? = entries[key]

        override suspend fun set(
            key: String,
            entry: CacheEntry,
        ) {
            entries[key] = entry
        }
    }

    @Test
    fun `a fresh entry answers without a request`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v1\"", "Cache-Control" to "private, max-age=60")
                val customer = server.customer("u")
                val first = customer.check("export_pdf")
                api.now += 59_000
                val second = customer.check("export_pdf")
                assertEquals(first, second)
                assertFalse(second.stale)
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `an entry past its max-age is revalidated and a 304 answers the kept body`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v1\"", "Cache-Control" to "max-age=60")
                api.reply(304, "", "ETag" to "\"v2\"", "Cache-Control" to "max-age=30")
                api.reply(304, "")
                val customer = server.customer("u")
                val first = customer.check("export_pdf")
                api.now += 60_000
                assertEquals(first, customer.check("export_pdf"))
                api.take()
                assertEquals("\"v1\"", api.take().headers["If-None-Match"])
                api.now += 29_000
                customer.check("export_pdf")
                assertEquals(2, api.server.requestCount)
                api.now += 1_000
                customer.check("export_pdf")
                assertEquals("\"v2\"", api.take().headers["If-None-Match"])
            }
        }

    @Test
    fun `an entry with only an ETag is revalidated every time`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v1\"", "Cache-Control" to "private, no-cache")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                customer.check("export_pdf")
                api.take()
                assertEquals("\"v1\"", api.take().headers["If-None-Match"])
            }
        }

    @Test
    fun `a write to the customer revalidates a fresh entry`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v1\"", "Cache-Control" to "max-age=600")
                api.replyFixture("usage-result")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 1
                customer.recordUsage(Feature.Metered("ai_credits"), 1)
                api.now += 1
                customer.check("export_pdf")
                assertEquals(3, api.server.requestCount)
                server.customer("other").let { api.replyFixture("check", 200, "Cache-Control" to "max-age=600") }
                server.customer("other").check("export_pdf")
                server.customer("other").check("export_pdf")
                assertEquals(4, api.server.requestCount)
            }
        }

    @Test
    fun `a 304 with nothing kept fails with http_error`() =
        runTest {
            api.server().use { server ->
                api.reply(304, "")
                val failure = assertFailsWith<EntitlerApiException> { server.customer("u").check("export_pdf") }
                assertEquals(304, failure.status)
                assertEquals(ErrorCode.HttpError, failure.code)
            }
        }

    @Test
    fun `no-store answers are never kept`() =
        runTest {
            val store = RecordingStore()
            api.server { cache = store }.use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v1\"", "Cache-Control" to "no-store, max-age=60")
                api.replyFixture("check", 200)
                server.customer("u").check("export_pdf")
                server.customer("u").check("export_pdf")
                assertTrue(store.entries.isEmpty())
            }
        }

    @Test
    fun `cache keys depend on the principal, as-of and visitor but never hold a credential`() =
        runTest {
            val store = RecordingStore()
            val headers = arrayOf("ETag" to "\"v\"", "Cache-Control" to "max-age=60")
            api.server { cache = store }.use {
                it.customer("u").let { c ->
                    api.replyFixture("check", 200, *headers)
                    c.check("export_pdf")
                }
            }
            EntitlerServer("ent_test_other") {
                api.setUp(this)
                cache = store
            }.use {
                api.replyFixture("check", 200, *headers)
                it.customer("u").check("export_pdf")
            }
            api
                .server {
                    cache = store
                    asOf = Instant.parse("2026-07-01T09:30:00Z")
                }.use {
                    api.replyFixture("check", 200, *headers)
                    it.customer("u").check("export_pdf")
                }
            api.server { cache = store }.use {
                api.replyFixture("customer-pricing", 200, *headers)
                it.customer("u").pricing(visitor = "v".repeat(20))
            }
            api.server { cache = store }.use {
                api.replyFixture("customer-pricing", 200, *headers)
                it.customer("u").pricing()
            }
            assertEquals(5, store.entries.size)
            store.entries.keys.forEach {
                assertTrue(Regex("[0-9a-f]{64}").matches(it))
                assertFalse(it.contains(SERVER_KEY))
            }
            assertFalse(
                store.entries.values
                    .toString()
                    .contains("export_pdf"),
            )
        }

    @Test
    fun `a refreshed token keeps its customer's entries`() =
        runTest {
            val store = RecordingStore()
            var exp = api.now / 1000 + 30
            val client =
                EntitlerClient({ customerToken(exp = exp++) }) {
                    api.setUp(this)
                    cache = store
                }
            api.replyFixture("check", 200, "ETag" to "\"v\"", "Cache-Control" to "max-age=600")
            client.me.check("export_pdf")
            client.me.check("export_pdf")
            assertEquals(1, api.server.requestCount)
            assertEquals(1, store.entries.size)
            client.close()
        }

    @Test
    fun `answers are copies`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("entitlements", 200, "Cache-Control" to "max-age=60")
                val first = server.customer("u").entitlements()
                val second = server.customer("u").entitlements()
                assertEquals(first, second)
                assertNotEquals(System.identityHashCode(first.entitlements), System.identityHashCode(second.entitlements))
            }
        }

    @Test
    fun `the memory store evicts the least recently used entry`() =
        runTest {
            val store = MemoryCacheStore(2)
            val entry = CacheEntry("{}", null, null, Instant.EPOCH)
            store.set("a", entry)
            store.set("b", entry)
            store.get("a")
            store.set("c", entry)
            assertSame(entry, store.get("a"))
            assertNull(store.get("b"))
            assertSame(entry, store.get("c"))
            assertFailsWith<IllegalArgumentException> { MemoryCacheStore(0) }
            assertFalse(entry.toString().contains("{}"))
        }

    @Test
    fun `the memory store is safe for concurrent use`() =
        runTest {
            val store = MemoryCacheStore(50)
            withContext(Dispatchers.Default) {
                (1..2000)
                    .map { i ->
                        async {
                            store.set("k${i % 100}", CacheEntry("$i", null, null, Instant.EPOCH))
                            store.get("k${(i + 1) % 100}")
                        }
                    }.awaitAll()
            }
            assertTrue((0 until 100).count { store.get("k$it") != null } <= 50)
        }

    @Test
    fun `concurrent checks through the cache all answer`() =
        runTest {
            api.server().use { server ->
                repeat(20) { api.replyFixture("check", 200, "ETag" to "\"v\"", "Cache-Control" to "max-age=60") }
                val answers =
                    withContext(Dispatchers.Default) {
                        (1..20).map { async { server.customer("u").check("export_pdf") } }.awaitAll()
                    }
                assertTrue(answers.all { it.entitled })
            }
        }

    @Test
    fun `an unreachable Entitler answers a kept entry marked stale and calls onError`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v\"")
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
    fun `a kept entry older than staleFor does not stand in`() =
        runTest {
            api
                .server {
                    maxRetries = 0
                    staleFor = 10.seconds
                }.use { server ->
                    api.replyFixture("check", 200, "ETag" to "\"v\"")
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
                api.replyFixture("check", 200, "ETag" to "\"v\"")
                api.reply(403, """{"error":{"code":"scope_required","message":"No."}}""")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertFailsWith<EntitlerApiException> { customer.check("export_pdf") }
            }
        }

    @Test
    fun `isEntitled answers the default and calls onError when the check fails`() =
        runTest {
            api
                .server {
                    maxRetries = 0
                    cache = null
                }.use { server ->
                    api.reply(500, "")
                    api.reply(404, """{"error":{"code":"feature_not_found","message":"No such feature."}}""")
                    api.replyFixture("check")
                    val customer = server.customer("u")
                    assertTrue(customer.isEntitled(Feature.OnOff("export_pdf"), default = true))
                    assertFalse(customer.isEntitled("nope"))
                    assertTrue(customer.isEntitled("export_pdf"))
                    assertEquals(
                        listOf(ErrorCode.HttpError, ErrorCode.FeatureNotFound),
                        api.errors.map { (it as EntitlerApiException).code },
                    )
                    assertFailsWith<IllegalArgumentException> { customer.isEntitled(" ") }
                }
        }

    @Test
    fun `isEntitled answers a stale check's decision`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, "ETag" to "\"v\"")
                api.reply(502, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.isEntitled("export_pdf", default = false))
                assertEquals(1, api.errors.size)
            }
        }

    @Test
    fun `an onError that throws never breaks isEntitled`() =
        runTest {
            api
                .server {
                    maxRetries = 0
                    onError = { error("broken handler") }
                }.use { server ->
                    api.reply(500, "")
                    assertFalse(server.customer("u").isEntitled("export_pdf"))
                }
        }

    @Test
    fun `the cache can be turned off`() =
        runTest {
            api.server { cache = null }.use { server ->
                repeat(2) { api.replyFixture("check", 200, "Cache-Control" to "max-age=60") }
                server.customer("u").check("export_pdf")
                server.customer("u").check("export_pdf")
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `server pricing and features go through the cache`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("pricing", 200, "Cache-Control" to "max-age=60")
                api.replyFixture("features", 200, "Cache-Control" to "max-age=60")
                server.pricing()
                server.pricing()
                server.features()
                server.features()
                assertEquals(2, api.server.requestCount)
            }
        }
}
