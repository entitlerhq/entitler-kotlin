package com.entitlerhq.entitler

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DecodingTest {
    private val api = FakeApi()
    private val server = api.server { maxRetries = 0 }
    private val customer = server.customer("test_1001")

    @AfterEach
    fun close() {
        server.close()
        api.close()
    }

    @Test
    fun `an impossible date in an answer is an answer the SDK cannot read`() =
        runTest {
            for (date in listOf("2026-02-31T00:00:00Z", "2025-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-04-31T00:00:00Z")) {
                api.reply(
                    200,
                    kotlinx.serialization.json
                        .JsonObject(fixtureJson("check") + ("asOf" to kotlinx.serialization.json.JsonPrimitive(date)))
                        .toString(),
                )
                val failure = kotlin.test.assertFailsWith<EntitlerApiException>(date) { customer.check("export_pdf") }
                assertEquals(ErrorCode.InvalidResponse, failure.code, date)
            }
            assertEquals(null, parseInstant("2026-02-31T00:00:00Z"))
        }

    @Test
    fun `an unreadable answer's cause names the failure and the shape, never the body`() =
        runTest {
            for (body in listOf("""{"secret":"leaked_value"""", """{"secret":"leaked_value","entitled":"leaked_value"}""")) {
                api.reply(200, body)
                val failure = kotlin.test.assertFailsWith<EntitlerApiException> { customer.check("export_pdf") }
                assertEquals(ErrorCode.InvalidResponse, failure.code)
                val causes = generateSequence(failure.cause) { it.cause }.toList()
                assertTrue(causes.isNotEmpty())
                assertTrue(causes.none { it.message.orEmpty().contains("leaked_value") }, causes.joinToString { it.message.orEmpty() })
                assertTrue(causes.first().message!!.contains("Check"), causes.first().message)
            }
        }

    @Test
    fun `an answer missing a required field, nullable ones included, is an answer the SDK cannot read`() =
        runTest {
            val withoutRelease = fixtureJson("check") - "release"
            api.reply(
                200,
                kotlinx.serialization.json
                    .JsonObject(withoutRelease)
                    .toString(),
            )
            assertEquals(ErrorCode.InvalidResponse, kotlin.test.assertFailsWith<EntitlerApiException> { customer.check("export_pdf") }.code)
            val reads: List<suspend () -> Any> =
                listOf(
                    { server.features() },
                    { server.pricing() },
                    { server.snapshotKeys() },
                    { customer.usage() },
                    { customer.details() },
                    { server.customers.page() },
                    { customer.plans() },
                    {
                        server
                            .recordUsageBatch(
                                listOf(UsageBatchEvent("u", Feature.Metered("ai_credits"), 1, "k")),
                            ).results
                            .single()
                            .error!!
                            .let {
                                throw EntitlerApiException(200, it.code, it.message)
                            }
                    },
                )
            for (read in reads) {
                api.reply(200, "{}")
                assertEquals(ErrorCode.InvalidResponse, kotlin.test.assertFailsWith<EntitlerApiException> { read() }.code)
            }
        }

    @Test
    fun `a manage step without billedBy is an answer the SDK cannot read`() =
        runTest {
            api.reply(200, """{"next":"manage"}""")
            val error = kotlin.test.assertFailsWith<EntitlerApiException> { customer.subscribe("pro", idempotencyKey = "k") }
            assertEquals(ErrorCode.InvalidResponse, error.code)
        }

    @Test
    fun `a held plan's pending change decodes as a move, a cancel, a billing change or an unknown change`() {
        val pro = """{"id":"p_pro","key":"pro","name":"Pro"}"""

        fun pending(json: String): BookedChange = decode(json, BookedChange.serializer())
        assertEquals(BookedChange.Move(PlanSummary("p_pro", "pro", "Pro")), pending("""{"type":"move","plan":$pro}"""))
        assertEquals(BookedChange.Cancel(PlanSummary("p_pro", "pro", "Pro")), pending("""{"type":"cancel","movingTo":$pro}"""))
        assertEquals(BookedChange.Cancel(null), pending("""{"type":"cancel","movingTo":null}"""))
        val billing = pending("""{"type":"billing","billing":"outside"}""")
        assertEquals(BookedChange.Billing(BookedBilling.Outside), billing)
        assertEquals(BookedChangeType.Billing, billing.type)
        assertEquals(BookedBilling("partner"), (pending("""{"type":"billing","billing":"partner"}""") as BookedChange.Billing).billing)
        val unknown = pending("""{"type":"pause","until":"2027-01-01T00:00:00Z"}""")
        assertEquals(BookedChangeType("pause"), unknown.type)
        assertEquals("2027-01-01T00:00:00Z", (unknown as BookedChange.Unknown).raw.string("until"))
        for (broken in listOf("""{"type":"move"}""", """{"type":"billing","billing":1}""", """{"plan":$pro}""", "[]")) {
            kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> { pending(broken) }
        }
        for (change in listOf(billing, unknown, BookedChange.Cancel(null), BookedChange.Move(PlanSummary("p_pro", "pro", "Pro")))) {
            assertEquals(change, pending(json.encodeToString(BookedChange.serializer(), change)))
        }
    }

    @Test
    fun `checks decode with their context`() =
        runTest {
            api.replyFixture("check-metered")
            val metered = customer.check(Feature.Metered("ai_credits"))
            assertEquals(FeatureType.Metered, metered.type)
            assertEquals(FeatureValue.Amount(300), metered.value)
            assertEquals(0, metered.used)
            assertEquals(0, metered.held)
            assertEquals(FeatureValue.Amount(300), metered.remaining)
            assertNotNull(metered.resetsAt)
            assertEquals(EnvironmentKind.Test, metered.environment.kind)
            assertEquals("All customers", metered.track.name)
            assertEquals(2L, metered.release)
            assertTrue(metered.testers)
            assertNull(metered.experiment)
            assertEquals("plan", metered.sources.single().type)
            api.replyFixture("check-beta")
            val beta = customer.check("sso")
            assertNull(beta.release)
            assertNotNull(beta.change)
        }

    @Test
    fun `a field whose JSON type differs from its schema's does not decode`() =
        runTest {
            val changes =
                listOf(
                    "entitled" to kotlinx.serialization.json.JsonPrimitive("true"),
                    "release" to kotlinx.serialization.json.JsonPrimitive("2"),
                    "type" to kotlinx.serialization.json.JsonPrimitive(5),
                    "asOf" to kotlinx.serialization.json.JsonPrimitive(1_782_898_200),
                )
            for ((name, value) in changes) {
                api.reply(
                    body =
                        kotlinx.serialization.json
                            .JsonObject(fixtureJson("check") + (name to value))
                            .toString(),
                )
                val failure = kotlin.test.assertFailsWith<EntitlerApiException>(name) { customer.check("export_pdf") }
                assertEquals(ErrorCode.InvalidResponse, failure.code, name)
            }
        }

    @Test
    fun `unknown fields are ignored and unknown enum values kept`() =
        runTest {
            val check = fixtureJson("check").toMutableMap()
            check["type"] = kotlinx.serialization.json.JsonPrimitive("quantum")
            check["somethingNew"] = kotlinx.serialization.json.JsonPrimitive(true)
            api.reply(
                body =
                    kotlinx.serialization.json
                        .JsonObject(check)
                        .toString(),
            )
            val answer = customer.check("export_pdf")
            assertEquals("quantum", answer.type.value)
            assertEquals(FeatureType("quantum"), answer.type)
        }

    @Test
    fun `entitlement lists answer get and has from the server's decision`() =
        runTest {
            api.replyFixture("entitlements")
            val entitlements = customer.entitlements()
            assertTrue(entitlements.has(Feature.Group("collaboration")))
            assertFalse(entitlements.has("support_extras"))
            assertFalse(entitlements.has("team_essentials"))
            assertNull(entitlements["team_essentials"])
            assertEquals(FeatureValue.On, entitlements[Feature.OnOff("export_pdf")]!!.value)
            assertEquals("group", entitlements["collaboration"]!!.sources.single().type)
            assertFalse(entitlements.stale)
        }

    @Test
    fun `customer plans, pricing, usage and features decode`() =
        runTest {
            api.replyFixture("plan-space")
            val space = customer.plans()
            assertTrue(space.held.isNotEmpty())
            api.replyFixture("customer-pricing")
            val pricing = customer.pricing()
            assertEquals("test_1001", pricing.customer)
            assertTrue(pricing.plans.any { it.key == "pro" })
            api.replyFixture("usage")
            val usage = customer.usage()
            assertEquals("ai_credits", usage.features.first().feature)
            api.replyFixture("features")
            val features = server.features()
            assertEquals(11, features.features.size)
            assertEquals(2L, features.release)
            assertEquals(listOf("team_seats", "shared_folders"), features.features.first { it.key == "collaboration" }.includes)
        }

    @Test
    fun `customer details, lists and billing decode`() =
        runTest {
            api.replyFixture("details")
            val details = customer.details()
            assertEquals("test_1001", details.customer.externalId)
            api.replyFixture("customers")
            assertTrue(
                server.customers
                    .page()
                    .items
                    .isNotEmpty(),
            )
            api.replyFixture("billing")
            customer.billing()
            api.replyFixture("track")
            assertEquals("Beta", customer.setTrack("Beta").track.name)
            api.replyFixture("token")
            assertEquals(listOf(Scope.EntitlementsRead, Scope.UsageRead, Scope.UsageWrite), customer.token().scopes)
            api.replyFixture("provider-page")
            assertTrue(customer.billingPortal("https://a.example").url.startsWith("https://"))
        }

    @Test
    fun `api errors carry every documented field`() =
        runTest {
            api.reply(
                402,
                """{"error":{"code":"payment_required","message":"Confirm the payment.",
                "payment":{"status":"requires_action","url":"https://pay.example"}}}""",
                "x-request-id" to "req_9",
            )
            val payment = kotlin.test.assertFailsWith<EntitlerApiException> { customer.subscribe("pro", idempotencyKey = "sub-1") }
            assertEquals(402, payment.status)
            assertEquals(ErrorCode.PaymentRequired, payment.code)
            assertEquals("Confirm the payment.", payment.message)
            assertEquals("req_9", payment.requestId)
            assertEquals("sub-1", payment.idempotencyKey)
            assertEquals(Payment(PaymentStatus.RequiresAction, "https://pay.example"), payment.payment)

            api.reply(402, """{"error":{"code":"payment_required","message":"x","payment":{"status":"mystery","url":null}}}""")
            assertNull(kotlin.test.assertFailsWith<EntitlerApiException> { customer.subscribe("pro") }.payment)

            api.reply(
                409,
                """{"error":{"code":"rate_limited","message":"Slow down."}}""",
                "Retry-After" to "7",
            )
            val gaps = kotlin.test.assertFailsWith<EntitlerApiException> { customer.subscribe("pro") }
            assertEquals(7.seconds, gaps.retryAfter)

            api.reply(409, """{"error":{"code":"brand_new_code","message":"New."}}""")
            assertEquals(
                "brand_new_code",
                kotlin.test
                    .assertFailsWith<EntitlerApiException> { customer.subscribe("pro") }
                    .code.value,
            )
        }

    @Test
    fun `a stale-marked answer copies with stale true`() {
        val check = decode(fixture("check"), Check.serializer(), stale = true)
        assertTrue(check.stale)
        assertFalse(decode(fixture("check"), Check.serializer()).stale)
    }
}
