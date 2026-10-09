package com.entitlerhq.entitler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
        }

    @Test
    fun `cancellation inside the provider is not a token error`() =
        runTest {
            client { throw CancellationException("stop") }.use {
                assertFailsWith<CancellationException> { it.me.check("export_pdf") }
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
                assertEquals(client.visitor, request.headers["Entitler-Visitor"])
                assertTrue(request.headers["Idempotency-Key"]!!.isNotEmpty())
            }
        }

    @Test
    fun `an identity client with a provider refreshes the identity token`() =
        runTest {
            val calls = AtomicInteger()
            EntitlerIdentityClient(PUBLISHABLE_KEY, {
                jwt("""{"iss":"https://accounts.example","sub":"p${calls.incrementAndGet()}"}""")
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
}
