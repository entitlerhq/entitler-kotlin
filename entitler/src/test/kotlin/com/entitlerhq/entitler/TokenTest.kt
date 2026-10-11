package com.entitlerhq.entitler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenTest {
    private val api = FakeApi()

    @AfterEach
    fun close() = api.close()

    private fun client(provider: suspend () -> String) =
        EntitlerClient(provider) {
            api.setUp(this)
            cache = null
            maxRetries = 0
        }

    @Test
    fun `a provider's own timeout is a token error that isEntitled absorbs, not a cancellation`() =
        runTest {
            client {
                kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(1_000) }
                "never"
            }.use { client ->
                assertFalse(client.me.isEntitled("export_pdf"))
                kotlin.test.assertIs<EntitlerTokenException>(api.errors.single())
            }
        }

    private class Fatal : Error("fatal")

    @Test
    fun `an Error from the provider is never turned into a token error or sent to onError, even from a hold's disposal`() =
        runTest {
            var calls = 0
            client {
                if (++calls > 1) throw Fatal()
                customerToken(exp = api.now / 1000 + 120)
            }.use { client ->
                api.replyFixture("usage-held")
                val hold = client.me.startHold(Feature.Metered("ai_credits"), 100, "k") as HoldHandle
                api.now += 61_000
                assertFailsWith<Fatal> { hold.dispose() }
                assertTrue(api.errors.isEmpty(), "${api.errors}")
            }
        }

    @Test
    fun `the provider is asked on first use and its token kept`() =
        runTest {
            val calls = AtomicInteger()
            val token = customerToken()
            client {
                calls.incrementAndGet()
                token
            }.use { client ->
                assertEquals(0, calls.get())
                repeat(2) { api.replyFixture("check") }
                client.me.check("export_pdf")
                client.me.check("export_pdf")
                assertEquals(1, calls.get())
                assertEquals("Bearer $token", api.take().headers["Authorization"])
                assertEquals("/customers/me/entitlements/export_pdf", api.take().path)
            }
        }

    @Test
    fun `a token expiring within 60 seconds is refreshed before use`() =
        runTest {
            val tokens = ArrayDeque(listOf(customerToken(exp = api.now / 1000 + 120), customerToken(sub = "second")))
            client { tokens.removeFirst() }.use { client ->
                repeat(2) { api.replyFixture("check") }
                client.me.check("export_pdf")
                api.now += 61_000
                client.me.check("export_pdf")
                api.take()
                assertTrue(api.take().headers["Authorization"]!!.endsWith(customerToken(sub = "second")))
            }
        }

    @Test
    fun `a 401 refreshes the token once and retries`() =
        runTest {
            val calls = AtomicInteger()
            client { customerToken(sub = "u${calls.incrementAndGet()}") }.use { client ->
                api.reply(401, """{"error":{"code":"unauthorised","message":"Expired."}}""")
                api.replyFixture("check")
                assertTrue(client.me.check("export_pdf").entitled)
                assertEquals(2, calls.get())
                assertTrue(api.take().headers["Authorization"]!!.endsWith(customerToken(sub = "u1")))
                assertTrue(api.take().headers["Authorization"]!!.endsWith(customerToken(sub = "u2")))
            }
        }

    @Test
    fun `a second 401 fails the call`() =
        runTest {
            client { customerToken() }.use { client ->
                repeat(2) { api.reply(401, """{"error":{"code":"unauthorised","message":"Expired."}}""") }
                val failure = assertFailsWith<EntitlerApiException> { client.me.check("export_pdf") }
                assertEquals(ErrorCode.Unauthorised, failure.code)
                assertEquals(2, api.server.requestCount)
            }
        }

    @Test
    fun `a fixed token is never refreshed`() =
        runTest {
            api.client { maxRetries = 0 }.use { client ->
                api.reply(401, """{"error":{"code":"unauthorised","message":"Expired."}}""")
                assertFailsWith<EntitlerApiException> { client.me.check("export_pdf") }
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `a 403 credential_not_allowed is neither refreshed nor retried`() =
        runTest {
            val calls = AtomicInteger()
            client {
                calls.incrementAndGet()
                customerToken()
            }.use { client ->
                api.reply(403, """{"error":{"code":"credential_not_allowed","message":"Not with a customer token."}}""")
                val failure = assertFailsWith<EntitlerApiException> { client.me.check("export_pdf") }
                assertEquals(ErrorCode.CredentialNotAllowed, failure.code)
                assertEquals(1, calls.get())
                assertEquals(1, api.server.requestCount)
            }
        }

    @Test
    fun `concurrent requests share one refresh`() =
        runTest {
            val calls = AtomicInteger()
            client {
                calls.incrementAndGet()
                delay(50)
                customerToken()
            }.use { client ->
                repeat(10) { api.replyFixture("check") }
                withContext(Dispatchers.Default) {
                    (1..10).map { async { client.me.check("export_pdf") } }.awaitAll()
                }
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `a failing provider fails the call with a token error carrying the cause`() =
        runTest {
            client { error("offline") }.use { client ->
                val failure = assertFailsWith<EntitlerTokenException> { client.me.check("export_pdf") }
                assertEquals("offline", failure.cause!!.message)
                assertEquals(0, api.server.requestCount)
            }
        }

    @Test
    fun `blank and unreadable tokens fail with a token error that never holds the token`() =
        runTest {
            client { "  " }.use { assertFailsWith<EntitlerTokenException> { it.me.check("export_pdf") } }
            client { "not-a-jwt-secret" }.use {
                val failure = assertFailsWith<EntitlerTokenException> { it.me.check("export_pdf") }
                assertFalse(failure.message!!.contains("not-a-jwt-secret"))
            }
            for (claims in listOf("""{"sub":"u"}""", """{"exp":"soon"}""", """{"exp":100,"iat":"x"}""", """{"exp":100,"iat":100}""")) {
                client { jwt(claims) }.use { assertFailsWith<EntitlerTokenException> { it.me.check("export_pdf") } }
            }
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `a caller cancelled while the provider runs ends in cancellation, not a token error`() =
        runTest {
            val never = kotlinx.coroutines.CompletableDeferred<String>()
            client { never.await() }.use { client ->
                val call = launch(kotlinx.coroutines.Dispatchers.Default) { client.me.check("export_pdf") }
                kotlinx.coroutines.delay(50)
                call.cancel()
                call.join()
                assertTrue(call.isCancelled)
                assertTrue(api.errors.isEmpty())
            }
        }

    @Test
    fun `a cancellation the provider raises while nobody cancelled the call is a token error`() =
        runTest {
            client { throw CancellationException("stop") }.use {
                assertFailsWith<EntitlerTokenException> { it.me.check("export_pdf") }
            }
        }

    @Test
    fun `an identity client sends the key, the identity token and the visitor`() =
        runTest {
            val identity = jwt("""{"iss":"https://accounts.example","sub":"person_1","exp":1900000000}""")
            EntitlerIdentityClient(PUBLISHABLE_KEY, identity) { api.setUp(this) }.use { client ->
                api.replyFixture("registered", 201)
                val registered = client.register()
                assertEquals("user_1", registered.externalId)
                assertEquals("user_1", client.me.id)
                val request = api.take()
                assertEquals("PUT", request.method)
                assertEquals("/customers/me", request.path)
                assertEquals("", request.bodyText)
                assertNull(request.headers["Content-Type"])
                assertEquals("Bearer $PUBLISHABLE_KEY", request.headers["Authorization"])
                assertEquals(identity, request.headers["Entitler-Identity-Token"])
                assertEquals(client.visitor(), request.headers["Entitler-Visitor"])
                assertTrue(request.headers["Idempotency-Key"]!!.isNotEmpty())
            }
        }

    @Test
    fun `an identity client with a provider refreshes the identity token`() =
        runTest {
            val calls = AtomicInteger()
            EntitlerIdentityClient(PUBLISHABLE_KEY, {
                jwt("""{"iss":"https://accounts.example","sub":"p${calls.incrementAndGet()}","exp":1900000000}""")
            }) {
                api.setUp(this)
                cache = null
            }.use { client ->
                api.reply(401, """{"error":{"code":"unauthorised","message":"Expired."}}""")
                api.replyFixture("check")
                client.me.check("export_pdf")
                api.take()
                assertTrue(api.take().headers["Entitler-Identity-Token"]!!.isNotEmpty())
                assertEquals(2, calls.get())
            }
        }

    @Test
    fun `a 60-second token is asked for at most once per request`() =
        runTest {
            val calls = AtomicInteger()
            client {
                calls.incrementAndGet()
                jwt("""{"sub":"u","iat":${api.now / 1000},"exp":${api.now / 1000 + 60}}""")
            }.use { client ->
                repeat(3) { api.replyFixture("check") }
                client.me.check("export_pdf")
                assertEquals(1, calls.get())
                api.now += 29_000
                client.me.check("export_pdf")
                assertEquals(1, calls.get())
                api.now += 1_000
                client.me.check("export_pdf")
                assertEquals(2, calls.get())
            }
        }

    @Test
    fun `a waiter that cancels never aborts the shared refresh`() =
        runTest {
            val calls = AtomicInteger()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            client {
                calls.incrementAndGet()
                release.await()
                customerToken()
            }.use { client ->
                api.replyFixture("check")
                withContext(Dispatchers.Default) {
                    val first = async { client.me.check("export_pdf") }
                    val second = async { client.me.check("export_pdf") }
                    delay(100)
                    first.cancel()
                    release.complete(Unit)
                    assertTrue(second.await().entitled)
                }
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `a failed shared refresh is never left in place, so each request asks the provider again`() =
        runTest {
            val calls = AtomicInteger()
            client {
                calls.incrementAndGet()
                error("offline")
            }.use { client ->
                withContext(Dispatchers.Default) {
                    repeat(300) { assertFailsWith<EntitlerTokenException> { client.me.check("export_pdf") } }
                }
                assertEquals(300, calls.get())
            }
        }

    @Test
    fun `a waiter cancelled during a refresh that fails leaves the next request to ask again`() =
        runTest {
            val calls = AtomicInteger()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            client {
                if (calls.incrementAndGet() == 1) {
                    release.await()
                    error("offline")
                }
                customerToken()
            }.use { client ->
                api.replyFixture("check")
                withContext(Dispatchers.Default) {
                    val waiter = async { client.me.check("export_pdf") }
                    delay(100)
                    waiter.cancel()
                    release.complete(Unit)
                    delay(100)
                    assertTrue(client.me.check("export_pdf").entitled)
                }
                assertEquals(2, calls.get())
            }
        }

    @Test
    fun `a hanging provider fails after the client's timeout, and the next request asks again`() =
        runTest {
            val calls = AtomicInteger()
            EntitlerClient({
                if (calls.incrementAndGet() == 1) kotlinx.coroutines.awaitCancellation()
                customerToken()
            }) {
                api.setUp(this)
                timeout = kotlin.time.Duration.parse("200ms")
                cache = null
            }.use { client ->
                withContext(Dispatchers.Default) {
                    assertFailsWith<EntitlerTokenException> { client.me.check("export_pdf") }
                    api.replyFixture("check")
                    assertTrue(client.me.check("export_pdf").entitled)
                }
                assertEquals(2, calls.get())
            }
        }

    @Test
    fun `503 then 401 then 200 retries, refreshes once, and keeps the idempotency key`() =
        runTest {
            val calls = AtomicInteger()
            EntitlerClient({ customerToken(sub = "u${calls.incrementAndGet()}") }) {
                api.setUp(this)
                cache = null
            }.use { client ->
                api.reply(503, "")
                api.reply(401, "")
                api.replyFixture("usage-result")
                client.me.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
                assertEquals(2, calls.get())
                assertEquals(1, List(3) { api.take().headers["Idempotency-Key"] }.toSet().size)
            }
        }

    @Test
    fun `snapshot keys send no credential, visitor or as-of and never ask the provider`() =
        runTest {
            EntitlerClient({ error("offline") }) {
                api.setUp(this)
            }.use { client ->
                api.replyFixture("snapshot-keys")
                client.snapshotKeys()
                val request = api.take()
                assertNull(request.headers["Authorization"])
                assertNull(request.headers["Entitler-Visitor"])
                assertNull(request.headers["Entitler-As-Of"])
                assertNull(request.headers["Entitler-Identity-Token"])
            }
        }
}
