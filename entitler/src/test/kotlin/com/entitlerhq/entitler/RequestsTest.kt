package com.entitlerhq.entitler

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RequestsTest {
    private val api = FakeApi()
    private val server = api.server()
    private val customer = server.customer("acme/user 1")
    private val encoded = "/customers/acme%2Fuser%201"

    @AfterEach
    fun close() {
        server.close()
        api.close()
    }

    private suspend fun expect(
        method: String,
        path: String,
        body: String?,
        fixture: String,
        call: suspend () -> Any?,
    ) {
        api.replyFixture(fixture)
        call()
        val request = api.take()
        assertEquals(method, request.method, path)
        assertEquals(path, request.path)
        assertEquals("Bearer $SERVER_KEY", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("entitler-kotlin/0.1.0 jvm/"))
        if (method == "GET") {
            assertNull(request.headers["Idempotency-Key"], path)
        } else {
            assertTrue(request.headers["Idempotency-Key"]!!.isNotEmpty(), path)
        }
        if (body == null) {
            assertEquals("", request.bodyText, path)
            assertNull(request.headers["Content-Type"], path)
        } else {
            assertEquals(json(body), json(request.bodyText), path)
            assertEquals("application/json", request.headers["Content-Type"], path)
        }
        assertNull(request.headers["Entitler-Visitor"], path)
        assertNull(request.headers["Entitler-As-Of"], path)
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `customer reads send the documented requests`() =
        runTest {
            expect("GET", "$encoded/entitlements/ai_credits", null, "check-metered") { customer.check(Feature.Metered("ai_credits")) }
            expect("GET", "$encoded/entitlements/export_pdf", null, "check") { customer.check(Feature.OnOff("export_pdf")) }
            expect("GET", "$encoded/entitlements/a%2Fb", null, "check") { customer.check("a/b") }
            expect("GET", "$encoded/entitlements", null, "entitlements") { customer.entitlements() }
            expect("GET", "$encoded/plans", null, "plan-space") { customer.plans() }
            expect("GET", "$encoded/pricing", null, "customer-pricing") { customer.pricing() }
            expect("GET", "$encoded/usage", null, "usage") { customer.usage() }
            expect("GET", "$encoded/usage?cursor=c%2B1", null, "usage") { customer.usage(cursor = "c+1") }
            expect("GET", encoded, null, "details") { customer.details() }
            expect("GET", "$encoded?cursor=next", null, "details") { customer.details(cursor = "next") }
            expect("GET", "$encoded/billing", null, "billing") { customer.billing() }
        }

    @Test
    fun `usage writes send the documented bodies`() =
        runTest {
            val credits = Feature.Metered("ai_credits")
            expect("POST", "$encoded/usage", """{"feature":"ai_credits","amount":5}""", "usage-result") {
                customer.recordUsage(credits, 5, idempotencyKey = "job-1")
            }
            expect(
                "POST",
                "$encoded/usage",
                """{"feature":"ai_credits","amount":5,"mode":"observe","occurredAt":"2026-10-09T00:30:00.250Z","register":true}""",
                "usage-result",
            ) {
                customer.recordUsage(
                    credits,
                    5,
                    idempotencyKey = "job-2",
                    mode = UsageMode.Observe,
                    occurredAt = Instant.parse("2026-10-09T10:30:00.25+10:00"),
                    register = true,
                )
            }
            expect("POST", "$encoded/usage/holds", """{"feature":"ai_credits","amount":100}""", "usage-held") {
                customer.holdUsage(credits, 100, idempotencyKey = "hold-1")
            }
            expect("POST", "$encoded/usage/holds", """{"feature":"ai_credits","amount":100,"ttlSeconds":120}""", "usage-held") {
                customer.holdUsage(credits, 100, idempotencyKey = "hold-2", ttl = 2.minutes)
            }
            expect("POST", "$encoded/usage/holds/h_1/settle", """{"amount":40}""", "usage-settled") { customer.settleUsage("h_1", 40) }
            expect("DELETE", "$encoded/usage/holds/h_1", null, "usage-released") { customer.releaseUsage("h_1") }
            expect("POST", "$encoded/snapshots", "{}", "snapshot") { customer.snapshot() }
            expect("POST", "$encoded/snapshots", """{"ttlSeconds":600}""", "snapshot") { customer.snapshot(ttl = 600.seconds) }
        }

    @Test
    fun `registration, details and tokens send the documented requests`() =
        runTest {
            expect("PUT", encoded, null, "registered") { customer.register() }
            expect("PUT", encoded, """{"name":"Ada","email":"ada@example.com","metadata":{"team":"core","old":null}}""", "registered") {
                customer.register(name = "Ada", email = "ada@example.com", metadata = mapOf("team" to "core", "old" to null))
            }
            expect("PATCH", encoded, """{"name":"Ada","metadata":{"team":null,"tier":"gold"}}""", "summary") {
                customer.update(name = "Ada", metadata = mapOf("team" to null, "tier" to "gold"))
            }
            expect("PATCH", encoded, "{}", "summary") { customer.update() }
            expect("DELETE", "$encoded?erase=true", null, "summary") { customer.erase() }
            expect("POST", "$encoded/tokens", "{}", "token") { customer.token() }
            expect("POST", "$encoded/tokens", """{"scopes":["entitlements:read"],"ttlSeconds":300}""", "token") {
                customer.token(scopes = listOf(Scope.EntitlementsRead), ttl = 5.minutes)
            }
            expect("PUT", "$encoded/track", """{"track":"Beta"}""", "track") { customer.setTrack("Beta") }
            expect("PUT", "$encoded/track", """{"track":null}""", "track") { customer.setTrack(null) }
        }

    @Test
    fun `the customer's billing choices send the documented requests`() =
        runTest {
            expect("POST", "$encoded/subscription", """{"plan":"pro"}""", "step-done") { customer.subscribe("pro") }
            expect(
                "POST",
                "$encoded/subscription",
                """{"plan":"sso_addon","period":"monthly","quantity":2,"returnUrl":"https://a.example/back","register":true}""",
                "step-pay",
            ) {
                customer.subscribe("sso_addon", period = "monthly", quantity = 2, returnUrl = "https://a.example/back", register = true)
            }
            expect("DELETE", "$encoded/subscription", null, "plan-change") { customer.cancel() }
            expect("DELETE", "$encoded/subscription?product=main", null, "plan-change") { customer.cancel(product = "main") }
            expect("DELETE", "$encoded/subscription/add-ons/sso_addon", null, "plan-change") { customer.cancel(addOn = "sso_addon") }
            expect("DELETE", "$encoded/subscription/pending", null, "plan-change") { customer.undoPendingChange() }
            expect("DELETE", "$encoded/subscription/pending?product=main", null, "plan-change") {
                customer.undoPendingChange(product = "main")
            }
            expect("DELETE", "$encoded/subscription/add-ons/a%2Fb/pending", null, "plan-change") {
                customer.undoPendingChange(addOn = "a/b")
            }
            expect("POST", "$encoded/billing-portal", """{"returnUrl":"https://a.example"}""", "provider-page") {
                customer.billingPortal("https://a.example")
            }
            expect("POST", "$encoded/billing/sync", null, "billing-sync") { customer.syncBilling() }
            val both = kotlin.test.assertFailsWith<IllegalArgumentException> { customer.cancel(addOn = "sso_addon", product = "main") }
            assertEquals("Pass either addOn or product, not both.", both.message)
            kotlin.test.assertFailsWith<IllegalArgumentException> { customer.undoPendingChange(addOn = "a", product = "b") }
            kotlin.test.assertFailsWith<IllegalArgumentException> { customer.cancel(addOn = " ") }
        }

    @Test
    fun `the company's plan decisions send the documented requests`() =
        runTest {
            val sku = Sku("apple", mapOf("productId" to "pro.monthly"))
            expect("PUT", "$encoded/plan", """{"plan":"team"}""", "plan-change") { customer.setPlan("team") }
            expect(
                "PUT",
                "$encoded/plan",
                """{"plan":"enterprise","period":"yearly","when":"end","billing":"end","until":"2027-01-01T00:00:00.000Z",""" +
                    """"register":true,"actor":"hubspot","reason":"Deal 42"}""",
                "plan-change",
            ) {
                customer.setPlan(
                    "enterprise",
                    period = "yearly",
                    timing = Timing.End,
                    billing = PlanBilling.End,
                    until = Instant.parse("2027-01-01T11:00:00+11:00"),
                    register = true,
                    actor = "hubspot",
                    reason = "Deal 42",
                )
            }
            expect(
                "PUT",
                "$encoded/plan",
                """{"sku":{"connector":"apple","ids":{"productId":"pro.monthly"}},"billing":"keep"}""",
                "plan-change",
            ) { customer.setPlan(sku, billing = PlanBilling.Keep) }
            expect("PUT", "$encoded/add-ons/extra_seats", """{"quantity":150}""", "plan-change") { customer.setAddOn("extra_seats", 150) }
            expect("PUT", "$encoded/add-ons/extra_seats", """{"quantity":0,"when":"now","actor":"agent_7"}""", "plan-change") {
                customer.setAddOn("extra_seats", 0, timing = Timing.Now, actor = "agent_7")
            }
            val refused = kotlin.test.assertFailsWith<IllegalArgumentException> { customer.setPlan(sku, period = "monthly") }
            assertEquals("A SKU names its own period; leave period out.", refused.message)
        }

    @Test
    fun `the company's grants, meters and corrections send the documented requests`() =
        runTest {
            val credits = Feature.Metered("ai_credits")
            expect("POST", "$encoded/grants", """{"feature":"sso"}""", "grant-change") { customer.grant(Feature.OnOff("sso")) }
            expect(
                "POST",
                "$encoded/grants",
                """{"feature":"team_seats","value":"10","days":30,"actor":"agent_7","reason":"Pilot"}""",
                "grant-change",
            ) {
                customer.grant(Feature.Config("team_seats"), FeatureValue.Amount(10), days = 30, actor = "agent_7", reason = "Pilot")
            }
            expect("POST", "$encoded/grants", """{"feature":"ai_credits","value":"unlimited"}""", "grant-change") {
                customer.grant(credits, FeatureValue.Unlimited)
            }
            expect("DELETE", "$encoded/grants/g_1", null, "grant-change") { customer.revokeGrant("g_1") }
            expect(
                "DELETE",
                "$encoded/grants/g_1",
                """{"actor":"agent_7"}""",
                "grant-change",
            ) { customer.revokeGrant("g_1", actor = "agent_7") }
            expect("POST", "$encoded/meters/ai_credits/adjustments", """{"by":-500,"reason":"Refund"}""", "usage-result") {
                customer.adjustMeter(credits, idempotencyKey = "ticket-9", by = -500, reason = "Refund")
            }
            expect("POST", "$encoded/meters/ai_credits/adjustments", """{"to":0}""", "usage-result") {
                customer.adjustMeter(credits, idempotencyKey = "ticket-10", to = 0)
            }
            expect("DELETE", "$encoded/usage/u_1", null, "usage-result") { customer.cancelUsage("u_1") }
            val message = "Pass either by, a whole number other than 0, or to, a whole number of 0 or more."
            for (call in listOf<suspend () -> Unit>(
                { customer.adjustMeter(credits, idempotencyKey = "k") },
                { customer.adjustMeter(credits, idempotencyKey = "k", by = 0) },
                { customer.adjustMeter(credits, idempotencyKey = "k", by = 1, to = 1) },
                { customer.adjustMeter(credits, idempotencyKey = "k", to = -1) },
            )) {
                assertEquals(message, kotlin.test.assertFailsWith<IllegalArgumentException> { call() }.message)
            }
        }

    @Test
    fun `server client calls send the documented requests`() =
        runTest {
            api.replyFixture("customers")
            server.customers.page(q = "acme", cohort = "pro:1", track = "j_1", includeTest = true, cursor = "c")
            assertEquals("/customers?q=acme&cohort=pro%3A1&track=j_1&includeTest=true&cursor=c", api.take().path)
            api.replyFixture("customers")
            server.customers.page()
            assertEquals("/customers", api.take().path)
            expect(
                "POST",
                "/customers",
                """{"externalId":"u_9","name":"Ada","email":"a@example.com","plan":"pro","period":"monthly","metadata":{"k":"v"}}""",
                "details",
            ) {
                server.customers.create(
                    "u_9",
                    "Ada",
                    email = "a@example.com",
                    plan = "pro",
                    period = "monthly",
                    metadata = mapOf("k" to "v"),
                )
            }
            expect("GET", "/pricing", null, "pricing") { server.pricing() }
            expect("GET", "/pricing/features", null, "features") { server.features() }
        }

    @Test
    fun `the customer list fetches pages as collection reaches them`() =
        runTest {
            val first = fixtureJson("customers").toMutableMap()
            first["next"] = kotlinx.serialization.json.JsonPrimitive("page2")
            api.reply(
                body =
                    kotlinx.serialization.json
                        .JsonObject(first)
                        .toString(),
            )
            api.replyFixture("customers")
            val all = server.customers.list(q = "test").toList()
            val perPage = (fixtureJson("customers")["items"] as kotlinx.serialization.json.JsonArray).size
            assertEquals(perPage * 2, all.size)
            assertEquals("/customers?q=test", api.take().path)
            assertEquals("/customers?q=test&cursor=page2", api.take().path)
            api.replyFixture("customers")
            server.customers.list(cursor = "page3").toList()
            assertEquals("/customers?cursor=page3", api.take().path)
        }

    @Test
    fun `the usage log fetches pages as collection reaches them`() =
        runTest {
            val first = fixtureJson("usage").toMutableMap()
            val log = (first["log"] as kotlinx.serialization.json.JsonObject).toMutableMap()
            log["next"] = kotlinx.serialization.json.JsonPrimitive("p2")
            first["log"] = kotlinx.serialization.json.JsonObject(log)
            api.reply(
                body =
                    kotlinx.serialization.json
                        .JsonObject(first)
                        .toString(),
            )
            api.replyFixture("usage")
            val entries = customer.usageLog().toList()
            val perPage =
                (
                    (
                        fixtureJson(
                            "usage",
                        )["log"] as kotlinx.serialization.json.JsonObject
                    )["items"] as kotlinx.serialization.json.JsonArray
                ).size
            assertEquals(perPage * 2, entries.size)
            assertEquals("$encoded/usage", api.take().path)
            assertEquals("$encoded/usage?cursor=p2", api.take().path)
        }
}
