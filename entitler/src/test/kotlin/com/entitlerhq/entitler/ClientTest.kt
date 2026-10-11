package com.entitlerhq.entitler

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClientTest {
    private val api = FakeApi()

    @AfterEach
    fun close() = api.close()

    private fun message(block: () -> Unit): String? = assertFailsWith<IllegalArgumentException>(block = block).message

    @Test
    fun `construction validates every credential with the documented messages`() {
        assertEquals("Provide an Entitler API key from the dashboard.", message { EntitlerServer(" ") })
        assertEquals("Provide a customer token minted by your server.", message { EntitlerClient("") })
        assertEquals("Provide an Entitler API key from the dashboard.", message { EntitlerIdentityClient("", "token") })
        assertEquals("Provide an Entitler API key from the dashboard.", message { EntitlerIdentityClient(" ", { "token" }) })
        assertEquals("Provide the identity token your sign-in provider issued.", message { EntitlerIdentityClient(PUBLISHABLE_KEY, "\t") })
        assertEquals("Provide an Entitler API key from the dashboard.", message { EntitlerPublishableClient(" \r\n") })
        assertEquals("Provide the id your app uses for the customer.", message { EntitlerServer("key").customer(" ") })
        assertEquals("Provide the id your app uses for the customer.", message { EntitlerServer("key").customer("") })
        assertEquals("Pass an id that is not made only of dots.", message { EntitlerServer("key").customer("..") })
        assertEquals("Pass an id that is not made only of dots.", message { EntitlerServer("key").customer(".") })
        assertEquals("Pass an id that is valid Unicode.", message { EntitlerServer("key").customer("a\uD800") })
        assertEquals("Pass an id that is not made only of dots.", message { Plan("..") })
        val anyone = EntitlerServer("key").customer("c")
        assertEquals(
            "Name the feature by its key.",
            message { kotlinx.coroutines.runBlocking { anyone.recordUsage(Feature.Metered(""), 1, "k") } },
        )
        assertEquals("Name the feature by its key.", message { kotlinx.coroutines.runBlocking { anyone.isEntitled(Feature.OnOff(" ")) } })
        assertEquals("Name the feature by its key.", message { kotlinx.coroutines.runBlocking { anyone.grant(Feature.Config("")) } })
        assertEquals(
            "Name the feature by its key.",
            message { kotlinx.coroutines.runBlocking { anyone.holdUsage(Feature.Metered("\t"), 1, "k") } },
        )
        EntitlerClient({ "token" }).close()
    }

    @Test
    fun `each client refuses the other kind of key, after the blank checks`() {
        val secretInApp = "A secret key belongs on your server, in EntitlerServer. Use a publishable key (ent_pk_…) in an app."
        assertEquals(
            "A publishable key belongs in EntitlerClient. Use a secret key from the dashboard on your server.",
            message { EntitlerServer(" ent_pk_live_abc ") },
        )
        assertEquals(secretInApp, message { EntitlerIdentityClient(SERVER_KEY, "identity") })
        assertEquals(secretInApp, message { EntitlerIdentityClient(SERVER_KEY, { "identity" }) })
        assertEquals(secretInApp, message { EntitlerPublishableClient(SERVER_KEY) })
        assertEquals("Provide the identity token your sign-in provider issued.", message { EntitlerIdentityClient(SERVER_KEY, " ") })
        assertEquals("Provide an Entitler API key from the dashboard.", message { EntitlerPublishableClient("") })
    }

    @Test
    fun `credentials are trimmed of spaces, tabs and line breaks only`() =
        runTest {
            EntitlerServer("\t$SERVER_KEY \r\n") { api.setUp(this) }.use { server ->
                api.replyFixture("check")
                server.customer("u").check("export_pdf")
                assertEquals("Bearer $SERVER_KEY", api.take().headers["Authorization"])
            }
            assertEquals("\u00a0key\u00a0", trimCredential(" \t\u00a0key\u00a0\r\n"))
        }

    @Test
    fun `a publishable client reads signed-out pricing with its visitor and the key alone`() =
        runTest {
            EntitlerPublishableClient(PUBLISHABLE_KEY) { api.setUp(this) }.use { client ->
                api.replyFixture("pricing")
                api.replyFixture("snapshot-keys")
                client.pricing()
                client.snapshotKeys()
                val request = api.take()
                assertEquals("/pricing", request.path)
                assertEquals("Bearer $PUBLISHABLE_KEY", request.headers["Authorization"])
                assertEquals(client.visitor(), request.headers["Entitler-Visitor"])
                assertNull(request.headers["Entitler-Identity-Token"])
                assertNull(api.take().headers["Authorization"])
                assertEquals("EntitlerPublishableClient(baseUrl=${api.baseUrl.trimEnd('/')})", client.toString())
            }
        }

    @Test
    fun `a visitor source is called once, before the first request`() =
        runTest {
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val kept = newVisitorId()
            api
                .client {
                    visitorSource = {
                        calls.incrementAndGet()
                        kept
                    }
                }.use { client ->
                    assertEquals(0, calls.get())
                    repeat(2) { api.replyFixture("check") }
                    client.me.check("export_pdf")
                    client.me.check("sso")
                    assertEquals(kept, api.take().headers["Entitler-Visitor"])
                    assertEquals(kept, api.take().headers["Entitler-Visitor"])
                    assertEquals(kept, client.visitor())
                    assertEquals(1, calls.get())
                }
        }

    @Test
    fun `a closed client refuses every later call, and closing twice is safe`() =
        runTest {
            val server = api.server()
            server.close()
            server.close()
            val closed = assertFailsWith<IllegalStateException> { server.customer("u").check("export_pdf") }
            assertEquals("This Entitler client is closed. Create a new one.", closed.message)
            assertFailsWith<IllegalStateException> { server.pricing() }
            assertFailsWith<IllegalStateException> {
                server.customer("u").recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
            }
            val client = api.client()
            client.close()
            assertFailsWith<IllegalStateException> { client.me.isEntitled("export_pdf") }
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `closing cancels a call in flight with the closed error`() =
        runTest {
            val server = api.server { maxRetries = 0 }
            api.server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .body(fixture("check"))
                    .headersDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val call = async { runCatching { server.customer("u").check("export_pdf") } }
                api.take()
                server.close()
                val failure = call.await().exceptionOrNull()
                assertTrue(failure is IllegalStateException, "$failure")
                assertEquals("This Entitler client is closed. Create a new one.", failure.message)
            }
        }

    @Test
    fun `options are validated`() {
        assertEquals("Pass maxRetries as 0 or more.", message { EntitlerServer("key") { maxRetries = -1 } })
        assertEquals("Pass timeout as a positive duration.", message { EntitlerServer("key") { timeout = 0.seconds } })
        assertEquals("Pass maxRetryDelay as 0 or more.", message { EntitlerServer("key") { maxRetryDelay = (-1).seconds } })
        assertEquals(
            "Pass visitor as an id of 16 to 64 letters, numbers, hyphens or underscores.",
            message { EntitlerClient("token") { visitor = "short" } },
        )
    }

    @Test
    fun `string forms never show a credential`() {
        val server = EntitlerServer(SERVER_KEY) { baseUrl = "https://api.example///" }
        val client = EntitlerClient(customerToken())
        val identity = EntitlerIdentityClient(PUBLISHABLE_KEY, "identity-secret")
        val publishable = EntitlerPublishableClient(PUBLISHABLE_KEY)
        assertEquals("EntitlerServer(baseUrl=https://api.example)", server.toString())
        assertEquals("EntitlerClient(baseUrl=https://api.entitler.dev, kind=in-app with a customer token)", client.toString())
        assertEquals("EntitlerClient(baseUrl=https://api.entitler.dev, kind=in-app with an identity token)", identity.toString())
        assertEquals("ServerCustomer(id=u)", server.customer("u").toString())
        assertEquals("Customer(id=null)", client.me.toString())
        val token = IssuedCustomerToken("secret.token.value", "u", listOf(Scope.UsageRead), Instant.EPOCH)
        assertFalse(token.toString().contains("secret"))
        assertFalse(IssuedSnapshot("secret.snap.value", Instant.EPOCH, "k").toString().contains("secret"))
        assertFalse(publishable.toString().contains(PUBLISHABLE_KEY))
        listOf(server, client, identity, publishable).forEach { it.close() }
    }

    @Test
    fun `an injected HTTP client is used and kept open`() =
        runTest {
            val http = OkHttpClient()
            api.server { httpClient = http }.use { server ->
                api.replyFixture("check")
                server.customer("u").check("export_pdf")
            }
            assertFalse(http.dispatcher.executorService.isShutdown)
        }

    @Test
    fun `the server client never sends a visitor it was not given`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("pricing")
                api.replyFixture("pricing")
                api.replyFixture("registered")
                server.pricing()
                val visitor = server.newVisitorId()
                server.pricing(visitor = visitor)
                server.customer("u").register(visitor = visitor)
                assertNull(api.take().headers["Entitler-Visitor"])
                assertEquals(visitor, api.take().headers["Entitler-Visitor"])
                assertEquals(visitor, api.take().headers["Entitler-Visitor"])
                assertFailsWith<IllegalArgumentException> { server.pricing(visitor = "bad visitor id!!") }
                assertFailsWith<IllegalArgumentException> { server.customer("u").pricing(visitor = "x") }
            }
        }

    @Test
    fun `the in-app client generates one visitor and sends it on every request`() =
        runTest {
            api.client().use { client ->
                val visitor = client.visitor()
                assertTrue(Regex(VISITOR_ID_PATTERN).matches(visitor))
                assertEquals(32, visitor.length)
                repeat(2) { api.replyFixture("check") }
                client.me.check("export_pdf")
                client.me.check("sso")
                assertEquals(visitor, api.take().headers["Entitler-Visitor"])
                assertEquals(visitor, api.take().headers["Entitler-Visitor"])
                assertEquals("test_1001", client.me.id)
            }
            val kept = newVisitorId()
            api.client { visitor = kept }.use { assertEquals(kept, it.visitor()) }
            assertNotEquals(newVisitorId(), newVisitorId())
        }

    @Test
    fun `as-of is sent only on the server reads given it, as UTC with milliseconds`() =
        runTest {
            api.server().use { server ->
                val customer = server.customer("u")
                val asOf = Instant.parse("2026-07-01T19:30:00+10:00")
                api.replyFixture("check")
                api.replyFixture("entitlements")
                api.replyFixture("plan-space")
                api.replyFixture("usage")
                api.replyFixture("details")
                api.replyFixture("check")
                api.replyFixture("usage-result")
                customer.check("export_pdf", asOf = asOf)
                customer.entitlements(asOf = asOf)
                customer.plans(asOf = asOf)
                customer.usage(asOf = asOf)
                customer.details(asOf = asOf)
                customer.check("export_pdf")
                customer.recordUsage(Feature.Metered("ai_credits"), 1, idempotencyKey = "k")
                repeat(5) { assertEquals("2026-07-01T09:30:00.000Z", api.take().headers["Entitler-As-Of"]) }
                assertNull(api.take().headers["Entitler-As-Of"])
                assertNull(api.take().headers["Entitler-As-Of"])
                assertEquals(
                    "Pass asOf as a valid date.",
                    message { kotlinx.coroutines.runBlocking { customer.check("sso", asOf = Instant.MAX) } },
                )
            }
        }

    @Test
    fun `scopes are kept to the known ones in order, asked again on each call`() =
        runTest {
            api.server().use { server ->
                api.reply(
                    body =
                        """{"id":"k","scopes":["tracks:assign","usage:sandbox","billing:self","plans:read","customers:write"],""" +
                            """"registration":null}""",
                )
                api.reply(body = """{"id":"k","scopes":["usage:read"]}""")
                val scopes = server.scopes()
                assertEquals(listOf(Scope.PlansRead, Scope.BillingSelf, Scope.CustomersWrite, Scope.TracksAssign), scopes.granted)
                assertTrue(Scope.PlansRead in scopes)
                assertFalse(Scope.UsageRead in scopes)
                assertNull(scopes.registration)
                assertEquals(listOf(Scope.UsageRead), server.scopes().granted)
                assertEquals("/keys/self", api.take().path)
            }
        }

    @Test
    fun `an identity client's scopes carry registration`() =
        runTest {
            EntitlerIdentityClient(PUBLISHABLE_KEY, "identity") { api.setUp(this) }.use { client ->
                api.reply(body = """{"scopes":["entitlements:read","customers:register"],"registration":true}""")
                val scopes = client.scopes()
                assertEquals(true, scopes.registration)
                assertEquals("identity", api.take().headers["Entitler-Identity-Token"])
            }
        }

    @Test
    fun `snapshot keys are read without a credential`() =
        runTest {
            api.client().use { client ->
                api.replyFixture("snapshot-keys")
                val keys = client.snapshotKeys()
                assertEquals("EC", keys.keys.first().kty)
                assertNull(api.take().headers["Authorization"])
            }
            api.server().use { server ->
                api.replyFixture("snapshot-keys")
                server.snapshotKeys()
                assertNull(api.take().headers["Authorization"])
            }
        }

    @Test
    fun `path segments are encoded as encodeURIComponent does`() {
        assertEquals("AZaz09-_.!~*'()", encodeComponent("AZaz09-_.!~*'()"))
        assertEquals("a%2Fb%20c%3Fd%23e%25f%2Bg%3Ah%40i", encodeComponent("a/b c?d#e%f+g:h@i"))
        assertEquals("%C3%A9%F0%9F%98%80", encodeComponent("é😀"))
    }

    @Test
    fun `unusual path characters reach the API as encoded`() =
        runTest {
            api.server().use { server ->
                api.replyFixture("check")
                server.customer("d1:it's (me)!").check("export_pdf")
                assertEquals("/customers/d1%3Ait's%20(me)!/entitlements/export_pdf", api.take().path)
            }
        }

    @Test
    fun `blank arguments fail with the documented messages`() =
        runTest {
            api.server().use { server ->
                val customer = server.customer("u")
                assertEquals("Name the plan by its id or its key.", message { kotlinx.coroutines.runBlocking { customer.subscribe(" ") } })
                assertEquals(
                    "Name the plan by its id or its key.",
                    message { kotlinx.coroutines.runBlocking { customer.cancel(addOn = "") } },
                )
                assertEquals("Name the plan by its id or its key.", message { kotlinx.coroutines.runBlocking { customer.setPlan("") } })
                assertEquals(
                    "Name the plan by its id or its key.",
                    message { kotlinx.coroutines.runBlocking { customer.setAddOn(" ", 1) } },
                )
                assertEquals("Name the feature by its key.", message { kotlinx.coroutines.runBlocking { customer.check(" ") } })
                assertEquals("Provide the id of the grant.", message { kotlinx.coroutines.runBlocking { customer.revokeGrant("") } })
                assertEquals("Provide the id of the hold.", message { kotlinx.coroutines.runBlocking { customer.settleUsage("", 1) } })
                assertEquals("Provide the id of the hold.", message { kotlinx.coroutines.runBlocking { customer.releaseUsage(" ") } })
                assertEquals("Name the track by its name.", message { kotlinx.coroutines.runBlocking { customer.setTrack(" ") } })
                assertEquals(
                    "Provide the id of the usage report.",
                    message { kotlinx.coroutines.runBlocking { customer.cancelUsage(" ") } },
                )
                assertEquals(
                    "Provide the id your app uses for the customer.",
                    message { kotlinx.coroutines.runBlocking { server.customers.create(" ", "Name") } },
                )
                assertEquals(0, api.server.requestCount)
            }
        }

    @Test
    fun `close releases a client the SDK built`() {
        val server = EntitlerServer("key")
        server.close()
        server.close()
    }

    @Test
    fun `values decode as on, amounts and unlimited, and encode back`() {
        val json = Json
        assertEquals(FeatureValue.On, json.decodeFromString(FeatureValue.serializer(), "true"))
        assertEquals(FeatureValue.Amount(0), json.decodeFromString(FeatureValue.serializer(), "0"))
        assertEquals(FeatureValue.Unlimited, json.decodeFromString(FeatureValue.serializer(), "\"unlimited\""))
        assertEquals(FeatureValue.Unlimited, json.decodeFromString(Limit.serializer(), "\"unlimited\""))
        assertFailsWith<kotlinx.serialization.SerializationException> { json.decodeFromString(FeatureValue.serializer(), "\"lots\"") }
        assertFailsWith<kotlinx.serialization.SerializationException> { json.decodeFromString(FeatureValue.serializer(), "false") }
        assertEquals("true", json.encodeToString(FeatureValue.serializer(), FeatureValue.On))
        assertEquals("7", json.encodeToString(FeatureValue.serializer(), FeatureValue.Amount(7)))
        assertEquals("\"unlimited\"", json.encodeToString(Limit.serializer(), FeatureValue.Unlimited))
        assertEquals("\"usage:write\"", json.encodeToString(Scope.serializer(), Scope.UsageWrite))
        assertEquals("usage:write", Scope.UsageWrite.toString())
    }

    @Test
    fun `features carry their type`() {
        assertEquals(FeatureType.Boolean, Feature.OnOff("a").type)
        assertEquals(FeatureType.Config, Feature.Config("a").type)
        assertEquals(FeatureType.Metered, Feature.Metered("a").type)
        assertEquals(FeatureType.Group, Feature.Group("a", listOf("b")).type)
        assertEquals(FeatureType("future"), Feature.Unknown("a", FeatureType("future")).type)
        assertEquals(listOf("b"), Feature.Group("a", listOf("b")).includes)
    }
}
