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
            expect("GET", "$encoded/usage/holds/h_1", null, "hold") { customer.hold("h_1") }
            expect("GET", encoded, null, "details") { customer.details() }
            expect("GET", "$encoded?cursor=next", null, "details") { customer.details(cursor = "next") }
            expect("GET", "$encoded/billing", null, "billing") { customer.billing() }
            expect("GET", "$encoded/providers", null, "providers") { customer.providers() }
        }

    @Test
    fun `usage writes send the documented bodies`() =
        runTest {
            val credits = Feature.Metered("ai_credits")
            expect("POST", "$encoded/usage", """{"feature":"ai_credits","amount":5}""", "usage-result") {
                customer.recordUsage(credits, 5)
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
                    mode = UsageMode.Observe,
                    occurredAt = Instant.parse("2026-10-09T10:30:00.25+10:00"),
                    register = true,
                )
            }
            expect("POST", "$encoded/usage/holds", """{"feature":"ai_credits","amount":100}""", "usage-held") {
                customer.holdUsage(credits, 100)
            }
            expect("POST", "$encoded/usage/holds", """{"feature":"ai_credits","amount":100,"ttlSeconds":120}""", "usage-held") {
                customer.holdUsage(credits, 100, ttl = 2.minutes)
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
            expect("DELETE", encoded, null, "summary") { customer.delete() }
            expect("DELETE", "$encoded?erase=true", null, "summary") { customer.delete(erase = true) }
            expect("POST", "$encoded/tokens", "{}", "token") { customer.token() }
            expect("POST", "$encoded/tokens", """{"scopes":["entitlements:read"],"ttlSeconds":300}""", "token") {
                customer.token(scopes = listOf(Scope.EntitlementsRead), ttl = 5.minutes)
            }
            expect("PUT", "$encoded/track", """{"trackId":"j_beta"}""", "track") { customer.setTrack("j_beta") }
            expect("PUT", "$encoded/track", """{"trackId":null}""", "track") { customer.setTrack(null) }
        }

    @Test
    fun `self-serve billing sends selfServe true`() =
        runTest {
            val sku = Sku("stripe", mapOf("price" to "price_1"))
            expect("POST", "$encoded/subscription", """{"plan":"pro","selfServe":true}""", "change") { customer.subscribe("pro") }
            expect("POST", "$encoded/subscription", """{"plan":"pro","period":"Yearly","when":"end","selfServe":true}""", "change") {
                customer.subscribe("pro", period = "Yearly", timing = ChangeTiming.End)
            }
            expect(
                "POST",
                "$encoded/subscription",
                """{"sku":{"connector":"stripe","ids":{"price":"price_1"}},"selfServe":true}""",
                "change",
            ) {
                customer.subscribe(sku)
            }
            expect(
                "POST",
                "$encoded/checkout",
                """{"plan":"pro","selfServe":true,"successUrl":"https://a.example/ok","cancelUrl":"https://a.example/no"}""",
                "provider-page",
            ) { customer.checkout("pro", "https://a.example/ok", "https://a.example/no") }
            expect(
                "POST",
                "$encoded/checkout",
                """{"sku":{"connector":"stripe","ids":{"price":"price_1"}},"selfServe":true,""" +
                    """"successUrl":"s","cancelUrl":"c","connection":"x"}""",
                "provider-page",
            ) { customer.checkout(sku, "s", "c", connection = "x") }
            expect("DELETE", "$encoded/subscription", null, "details") { customer.cancel() }
            expect("DELETE", "$encoded/subscription?product=main&when=end", null, "details") {
                customer.cancel(timing = ChangeTiming.End, product = "main")
            }
            expect("DELETE", "$encoded/subscription/pending", null, "details") { customer.undoPendingChange() }
            expect("DELETE", "$encoded/subscription/pending?product=main", null, "details") { customer.undoPendingChange("main") }
            expect("POST", "$encoded/subscription/add-ons", """{"plan":"sso_addon","selfServe":true}""", "change") {
                customer.addAddOn("sso_addon")
            }
            expect(
                "POST",
                "$encoded/subscription/add-ons",
                """{"sku":{"connector":"stripe","ids":{"price":"price_1"}},"when":"now","selfServe":true,"quantity":2,"replaces":"old"}""",
                "change",
            ) { customer.addAddOn(sku, quantity = 2, replaces = "old", timing = ChangeTiming.Now) }
            expect("PATCH", "$encoded/subscription/add-ons/sso_addon", """{"quantity":3,"selfServe":true}""", "change") {
                customer.setAddOnQuantity("sso_addon", 3)
            }
            expect("DELETE", "$encoded/subscription/add-ons/sso_addon", null, "details") { customer.removeAddOn("sso_addon") }
            expect("DELETE", "$encoded/subscription/add-ons/sso_addon/pending", null, "details") { customer.undoAddOnChange("sso_addon") }
            expect("POST", "$encoded/billing-portal", """{"returnUrl":"https://a.example"}""", "provider-page") {
                customer.billingPortal("https://a.example")
            }
        }

    @Test
    fun `vendor actions send selfServe false`() =
        runTest {
            val vendor = customer.vendor
            val sku = Sku("apple", mapOf("productId" to "pro.monthly"))
            expect("POST", "$encoded/subscription", """{"plan":"team","selfServe":false}""", "change") { vendor.subscribe("team") }
            expect(
                "POST",
                "$encoded/subscription",
                """{"sku":{"connector":"apple","ids":{"productId":"pro.monthly"}},"selfServe":false}""",
                "change",
            ) {
                vendor.subscribe(sku)
            }
            expect(
                "POST",
                "$encoded/subscription",
                """{"plan":"team","period":"Monthly","selfServe":false,"override":true}""",
                "change",
            ) {
                vendor.override("team", period = "Monthly")
            }
            expect("DELETE", "$encoded/subscription/override", null, "details") { vendor.undoOverride() }
            expect("DELETE", "$encoded/subscription/override?product=main", null, "details") { vendor.undoOverride("main") }
            expect(
                "POST",
                "$encoded/checkout",
                """{"plan":"team","period":"Monthly","selfServe":false,"successUrl":"s","cancelUrl":"c"}""",
                "provider-page",
            ) {
                vendor.checkout("team", "s", "c", period = "Monthly")
            }
            expect(
                "POST",
                "$encoded/checkout",
                """{"sku":{"connector":"apple","ids":{"productId":"pro.monthly"}},"selfServe":false,"successUrl":"s","cancelUrl":"c"}""",
                "provider-page",
            ) {
                vendor.checkout(sku, "s", "c")
            }
            expect(
                "POST",
                "$encoded/subscription/add-ons",
                """{"plan":"sso_addon","when":"end","selfServe":false,"quantity":1}""",
                "change",
            ) {
                vendor.addAddOn("sso_addon", quantity = 1, timing = ChangeTiming.End)
            }
            expect(
                "POST",
                "$encoded/subscription/add-ons",
                """{"sku":{"connector":"apple","ids":{"productId":"pro.monthly"}},"selfServe":false}""",
                "change",
            ) {
                vendor.addAddOn(sku)
            }
            expect("PATCH", "$encoded/subscription/add-ons/sso_addon", """{"quantity":4,"selfServe":false}""", "change") {
                vendor.setAddOnQuantity("sso_addon", 4)
            }
        }

    @Test
    fun `vendor grants, meters and corrections send the documented requests`() =
        runTest {
            val vendor = customer.vendor
            expect("POST", "$encoded/grants", """{"feature":"sso"}""", "details") { vendor.grant(Feature.OnOff("sso")) }
            expect("POST", "$encoded/grants", """{"feature":"team_seats","value":"10","days":30,"reason":"Pilot"}""", "details") {
                vendor.grant(Feature.Config("team_seats"), FeatureValue.Amount(10), days = 30, reason = "Pilot")
            }
            expect("POST", "$encoded/grants", """{"feature":"ai_credits","value":"unlimited"}""", "details") {
                vendor.grant("ai_credits", FeatureValue.Unlimited)
            }
            expect("DELETE", "$encoded/grants/g_1", null, "details") { vendor.revokeGrant("g_1") }
            expect(
                "PUT",
                "$encoded/meters/ai_credits",
                """{"used":12}""",
                "usage-result",
            ) { vendor.setMeter(Feature.Metered("ai_credits"), 12) }
            expect("DELETE", "$encoded/usage/u_1", null, "usage-result") { vendor.cancelUsage("u_1") }
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
                """{"externalId":"u_9","name":"Ada","email":"a@example.com","plan":"pro","period":"Monthly","metadata":{"k":"v"}}""",
                "details",
            ) {
                server.customers.create(
                    "u_9",
                    "Ada",
                    email = "a@example.com",
                    plan = "pro",
                    period = "Monthly",
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
