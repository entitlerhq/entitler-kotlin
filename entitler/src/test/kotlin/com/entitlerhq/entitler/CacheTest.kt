package com.entitlerhq.entitler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class CacheTest {
    private val api = FakeApi()

    @TempDir
    lateinit var directory: File

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
    fun `a fresh entry answers without a request`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "private, max-age=60")
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
    fun `an answer's Age counts toward its max-age, and no-cache always revalidates`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=60", "Age" to "50")
                api.reply(304, "")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=60, no-cache")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 10_000
                customer.check("export_pdf")
                assertEquals(2, api.server.requestCount)
                customer.check("sso")
                customer.check("sso")
                assertEquals(4, api.server.requestCount)
            }
        }

    @Test
    fun `a 304 renews the entry, merging the headers it sends`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=60")
                api.reply(304, "", "ETag" to "\"v2\"")
                api.reply(304, "")
                val customer = server.customer("u")
                val first = customer.check("export_pdf")
                api.now += 60_000
                assertEquals(first, customer.check("export_pdf"))
                api.take()
                assertEquals("\"v1\"", api.take().headers["If-None-Match"])
                api.now += 59_000
                customer.check("export_pdf")
                assertEquals(2, api.server.requestCount)
                api.now += 1_000
                customer.check("export_pdf")
                assertEquals("\"v2\"", api.take().headers["If-None-Match"])
            }
        }

    @Test
    fun `max-age and Age saturate however many digits they have`() =
        runTest {
            api.server().use { server ->
                val huge = "9".repeat(40)
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=$huge")
                api.replyFixture("check-full", 200, etag, "Cache-Control" to "max-age=600", "Age" to huge)
                api.replyFixture("check-full", 200, etag, "Cache-Control" to "max-age=600")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 365L * 24 * 3_600_000
                customer.check("export_pdf")
                assertEquals(1, api.server.requestCount)
                customer.check("sso")
                customer.check("sso")
                assertEquals(3, api.server.requestCount)
            }
        }

    @Test
    fun `an entry with only an ETag is revalidated every time`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "private, no-cache")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                customer.check("export_pdf")
                api.take()
                assertEquals("\"v1\"", api.take().headers["If-None-Match"])
            }
        }

    @Test
    fun `a write to the customer revalidates a fresh entry, and others keep theirs`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("usage-result")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 1
                customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
                api.now += 1
                customer.check("export_pdf")
                assertEquals(3, api.server.requestCount)
                api.replyFixture("check", 200, "Cache-Control" to "max-age=600")
                server.customer("other").check("export_pdf")
                server.customer("other").check("export_pdf")
                assertEquals(4, api.server.requestCount)
            }
        }

    @Test
    fun `a failed write still bumps the generation, and tokens and snapshots do not`() =
        runTest {
            api.server { maxRetries = 0 }.use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("token")
                api.replyFixture("snapshot")
                api.reply(400, """{"error":{"code":"invalid_body","message":"No."}}""")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                api.now += 1
                customer.token()
                customer.snapshot()
                customer.check("export_pdf")
                assertEquals(3, api.server.requestCount)
                assertFailsWith<EntitlerApiException> { customer.update(name = "x") }
                api.now += 1
                customer.check("export_pdf")
                assertEquals(5, api.server.requestCount)
            }
        }

    @Test
    fun `writes track nothing with the cache off, and tracking is pruned once entries can no longer be fresh`() =
        runTest {
            api.server { cache = null }.use { server ->
                repeat(3) { api.replyFixture("summary") }
                repeat(3) { server.customer("u$it").update(name = "x") }
                assertEquals(0, (server.customer("u") as CustomerImpl).engine.trackedWrites)
            }
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                server.customer("seen").check("export_pdf")
                repeat(1_100) { api.replyFixture("summary") }
                repeat(1_000) { server.customer("u$it").update(name = "x") }
                val engine = (server.customer("u") as CustomerImpl).engine
                assertEquals(1_000, engine.trackedWrites)
                api.now += 601_000
                repeat(100) { server.customer("v$it").update(name = "x") }
                assertTrue(engine.trackedWrites <= 100, "${engine.trackedWrites}")
            }
        }

    @Test
    fun `ten thousand writes within the horizon prune a bounded number of times and never leave a stale entry fresh`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                val seen = server.customer("seen")
                seen.check("export_pdf")
                val engine = (seen as CustomerImpl).engine
                repeat(10_000) {
                    api.now += 1
                    engine.bump(if (it == 5_000) "seen" else "u$it")
                }
                assertTrue(engine.trackedWrites <= 10_000, "${engine.trackedWrites}")
                assertTrue(engine.prunes <= 4, "${engine.prunes}")
                seen.check("export_pdf")
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `a customer forgotten past the bound still revalidates an entry from before their write`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                val seen = server.customer("seen")
                val other = server.customer("other")
                seen.check("export_pdf")
                api.now += 1
                val engine = (seen as CustomerImpl).engine
                engine.bump("seen")
                val started = System.nanoTime()
                repeat(100_000) {
                    api.now += 1
                    engine.bump("u$it")
                }
                assertTrue(System.nanoTime() - started < 5_000_000_000L, "100,000 writes took too long")
                assertTrue(engine.trackedWrites <= 10_000, "${engine.trackedWrites}")
                assertTrue(engine.prunes <= 30, "${engine.prunes}")
                seen.check("export_pdf")
                assertEquals(2, api.server.requestCount)
                other.check("export_pdf")
                other.check("export_pdf")
                assertEquals(3, api.server.requestCount)
            }
        }

    @Test
    fun `an answer whose customer was forgotten while its read was in flight is revalidated`() =
        runTest {
            api.server().use { server ->
                api.server.enqueue(
                    MockResponse
                        .Builder()
                        .body(fixture("check"))
                        .addHeader(etag.first, etag.second)
                        .addHeader("Cache-Control", "max-age=600")
                        .headersDelay(500, TimeUnit.MILLISECONDS)
                        .build(),
                )
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                val customer = server.customer("seen")
                val engine = (customer as CustomerImpl).engine
                withContext(Dispatchers.IO) {
                    val read = async { customer.check("export_pdf") }
                    kotlinx.coroutines.delay(100)
                    engine.bump("seen")
                    repeat(20_000) { engine.bump("u$it") }
                    read.await()
                }
                customer.check("export_pdf")
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `with a clock that never moves, a read answered after a write is kept fresh`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.replyFixture("summary")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                val customer = server.customer("u")
                customer.check("export_pdf")
                customer.update(name = "x")
                customer.check("export_pdf")
                customer.check("export_pdf")
                assertEquals(3, api.server.requestCount)
            }
        }

    @Test
    fun `with a clock that never moves, an answer exactly max-age old is not fresh`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600")
                api.reply(304, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                customer.check("export_pdf")
                assertEquals(1, api.server.requestCount)
                api.now += 600_000
                customer.check("export_pdf")
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `a read that started before a write and answered after it is never kept as fresh`() =
        runTest {
            val readArrived = CountDownLatch(1)
            val writeDone = CountDownLatch(1)
            api.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.method == "GET") {
                            readArrived.countDown()
                            if (api.server.requestCount <= 1) writeDone.await(5, TimeUnit.SECONDS)
                            MockResponse
                                .Builder()
                                .body(
                                    fixture("check"),
                                ).addHeader("ETag", "\"v1\"")
                                .addHeader("Cache-Control", "max-age=600")
                                .build()
                        } else {
                            MockResponse.Builder().body(fixture("usage-result")).build()
                        }
                }
            api.server { clock = System::currentTimeMillis }.use { server ->
                val customer = server.customer("u")
                withContext(Dispatchers.IO) {
                    val read = async { customer.check("export_pdf") }
                    readArrived.await(5, TimeUnit.SECONDS)
                    customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
                    writeDone.countDown()
                    read.await()
                    Thread.sleep(5)
                    customer.check("export_pdf")
                }
                assertEquals(3, api.server.requestCount)
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
    fun `no-store answers are never kept, and kept entries carry the store's lifetime`() =
        runTest {
            val store = RecordingStore()
            api.server { cache = store }.use { server ->
                api.replyFixture("check", 200, etag, "Cache-Control" to "no-store, max-age=60")
                api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=60", "Age" to "3")
                server.customer("u").check("export_pdf")
                assertTrue(store.entries.isEmpty())
                server.customer("u").check("export_pdf")
                val entry = store.entries.values.single()
                assertEquals(1, entry.v)
                assertEquals("\"v1\"", entry.etag)
                assertEquals("max-age=60", entry.cacheControl)
                assertEquals(3, entry.age)
                assertEquals(24.hours + 60.seconds, store.ttls.single())
            }
        }

    @Test
    fun `the cache key is SHA-256 of the documented JSON array`() =
        runTest {
            val store = RecordingStore()
            api.server { cache = store }.use { server ->
                api.replyFixture("check", 200, etag)
                server.customer("a/b").check("export_pdf", asOf = Instant.parse("2026-07-01T19:30:00+10:00"))
            }
            val url = api.baseUrl.trimEnd('/') + "/customers/a%2Fb/entitlements/export_pdf"
            val expected =
                sha256("""["entitler-cache-v1","GET","$url","key","${sha256(SERVER_KEY)}","2026-07-01T09:30:00.000Z",null]""")
            assertEquals(setOf(expected), store.entries.keys)
        }

    @Test
    fun `cache keys depend on the principal, as-of and visitor but never hold a credential`() =
        runTest {
            val store = RecordingStore()
            val headers = arrayOf(etag, "Cache-Control" to "max-age=60")
            api.server { cache = store }.use {
                api.replyFixture("check", 200, *headers)
                it.customer("u").check("export_pdf")
            }
            EntitlerServer("ent_test_other") {
                api.setUp(this)
                cache = store
            }.use {
                api.replyFixture("check", 200, *headers)
                it.customer("u").check("export_pdf")
            }
            api.server { cache = store }.use {
                api.replyFixture("check", 200, *headers)
                it.customer("u").check("export_pdf", asOf = Instant.parse("2026-07-01T09:30:00Z"))
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
    fun `a refreshed token starts new entries`() =
        runTest {
            var exp = api.now / 1000 + 30
            EntitlerClient({ customerToken(exp = exp++) }) { api.setUp(this) }.use { client ->
                repeat(2) { api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=600") }
                client.me.check("export_pdf")
                api.now += 20_000
                client.me.check("export_pdf")
                assertEquals(2, api.server.requestCount)
                assertNull(api.take().headers["If-None-Match"])
                assertNull(api.take().headers["If-None-Match"])
            }
        }

    @Test
    fun `answers are copies`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("entitlements", 200, etag, "Cache-Control" to "max-age=60")
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
            val entry = CacheEntry(body = "{}", receivedAt = Instant.EPOCH)
            store.set("a", entry, 1.seconds)
            store.set("b", entry, 1.seconds)
            store.get("a")
            store.set("c", entry, 1.seconds)
            assertSame(entry, store.get("a"))
            assertNull(store.get("b"))
            assertSame(entry, store.get("c"))
            assertFailsWith<IllegalArgumentException> { MemoryCacheStore(0) }
            assertFalse(entry.toString().contains("{}"))
        }

    @Test
    fun `entries serialise with kotlinx serialization`() {
        val entry =
            CacheEntry(
                body = """{"a":1}""",
                etag = "\"e\"",
                cacheControl = "max-age=5",
                age = 2,
                receivedAt = Instant.parse("2026-10-09T01:00:00Z"),
            )
        val text =
            kotlinx.serialization.json.Json
                .encodeToString(CacheEntry.serializer(), entry)
        assertTrue(text.contains("\"v\":1"))
        assertEquals(
            entry,
            kotlinx.serialization.json.Json
                .decodeFromString(CacheEntry.serializer(), text),
        )
    }

    @Test
    fun `the memory store is safe for concurrent use`() =
        runTest {
            val store = MemoryCacheStore(50)
            withContext(Dispatchers.Default) {
                (1..2000)
                    .map { i ->
                        async {
                            store.set("k${i % 100}", CacheEntry(body = "$i", receivedAt = Instant.EPOCH), 1.seconds)
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
                repeat(20) { api.replyFixture("check", 200, etag, "Cache-Control" to "max-age=60") }
                val answers =
                    withContext(Dispatchers.Default) {
                        (1..20).map { async { server.customer("u").check("export_pdf") } }.awaitAll()
                    }
                assertTrue(answers.all { it.entitled })
            }
        }

    @Test
    fun `a failing custom store counts as a miss and goes to onError`() =
        runTest {
            val broken =
                object : CacheStore {
                    override suspend fun get(key: String): CacheEntry? = error("store down")

                    override suspend fun set(
                        key: String,
                        entry: CacheEntry,
                        ttl: Duration,
                    ) = error("store down")
                }
            api.server { cache = broken }.use { server ->
                api.replyFixture("check", 200, etag)
                assertTrue(server.customer("u").check("export_pdf").entitled)
                assertEquals(listOf("store down", "store down"), api.errors.map { it.message })
            }
        }

    @Test
    fun `requests bypass an injected client's HTTP cache`() =
        runTest {
            Cache(directory, 1_000_000).use { httpCache ->
                val http = OkHttpClient.Builder().cache(httpCache).build()
                api
                    .server {
                        httpClient = http
                        cache = null
                    }.use { server ->
                        repeat(2) { api.replyFixture("check", 200, "Cache-Control" to "public, max-age=600") }
                        server.customer("u").check("export_pdf")
                        server.customer("u").check("export_pdf")
                        assertEquals(2, api.server.requestCount)
                        assertEquals("no-cache", api.take().headers["Cache-Control"])
                    }
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
                api.replyFixture("check", 200, etag)
                api.reply(502, "")
                val customer = server.customer("u")
                customer.check("export_pdf")
                assertTrue(customer.isEntitled("export_pdf", default = false))
                assertEquals(1, api.errors.size)
            }
        }

    @Test
    fun `an onError that throws changes nothing`() =
        runTest {
            api
                .server {
                    maxRetries = 0
                    onError = { error("broken handler") }
                }.use { server ->
                    api.replyFixture("check", 200, etag)
                    api.reply(503, "")
                    server.customer("v").check("export_pdf")
                    assertTrue(server.customer("v").check("export_pdf").stale)
                    api.reply(500, "")
                    assertFalse(server.customer("u").isEntitled("export_pdf"))
                    api.replyFixture("usage-held")
                    api.reply(500, "")
                    assertFailsWith<IllegalStateException> {
                        server
                            .customer(
                                "v",
                            ).withHold(Feature.Metered("ai_credits"), 1, idempotencyKey = "k") { error("work") }
                    }
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
                api.replyFixture("pricing", 200, etag, "Cache-Control" to "max-age=60")
                api.replyFixture("features", 200, etag, "Cache-Control" to "max-age=60")
                server.pricing()
                server.pricing()
                server.features()
                server.features()
                assertEquals(2, api.server.requestCount)
            }
        }
}
