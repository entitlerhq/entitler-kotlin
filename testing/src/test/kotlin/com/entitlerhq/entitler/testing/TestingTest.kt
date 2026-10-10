package com.entitlerhq.entitler.testing

import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.EntitlerUsageReplayedException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.Feature
import com.entitlerhq.entitler.FeatureValue
import com.entitlerhq.entitler.PlanBilling
import com.entitlerhq.entitler.Scope
import com.entitlerhq.entitler.SubscribeStep
import com.entitlerhq.entitler.UsageBatchEvent
import com.entitlerhq.entitler.UsageEventResult
import com.entitlerhq.entitler.UsageMode
import com.entitlerhq.entitler.UsageOutcome
import com.entitlerhq.entitler.UsageRefusal
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestingTest {
    private val credits = Feature.Metered("ai_credits")
    private val exportPdf = Feature.OnOff("export_pdf")
    private val web = MockWebServer().apply { start() }
    private val server =
        EntitlerServer("ent_test_key") {
            baseUrl = web.url("/").toString()
            maxRetries = 0
            cache = null
        }

    @AfterEach
    fun close() {
        server.close()
        web.close()
    }

    private fun serve(body: String) = web.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun `reads answer complete checks and entitlements from the values`() =
        runTest {
            val customer =
                fakeCustomer(
                    "export_pdf" to true,
                    "sso" to false,
                    "team_seats" to 5,
                    "ai_credits" to metered(value = 100, used = 97),
                )
            assertTrue(customer.isEntitled(exportPdf))
            assertFalse(customer.isEntitled("sso"))
            assertEquals(FeatureValue.Amount(5), customer.check("team_seats").value)
            val meter = customer.check(credits)
            assertEquals(97, meter.used)
            assertEquals(FeatureValue.Amount(3), meter.remaining)
            assertEquals(listOf("ai_credits", "export_pdf", "sso", "team_seats"), customer.entitlements().entitlements.map { it.key })
            assertTrue(customer.entitlements().has("export_pdf"))
            val missing = assertFailsWith<EntitlerApiException> { customer.check("nope") }
            assertEquals(ErrorCode.FeatureNotFound, missing.code)
            assertEquals(404, missing.status)
            assertTrue(customer.isEntitled("nope", default = true))
            assertEquals("user_1", customer.id)
            assertEquals(1, customer.usage().features.size)
            assertFailsWith<IllegalArgumentException> { fakeCustomer("bad" to "value") }
        }

    @Test
    fun `usage writes move the meters, refuse past the allowance and replay a reused key`() =
        runTest {
            val customer = fakeCustomer("ai_credits" to metered(value = 100, used = 97))
            assertEquals(UsageOutcome.Recorded, customer.recordUsage(credits, 2, idempotencyKey = "a").outcome)
            val refused = customer.recordUsage(credits, 5, idempotencyKey = "b")
            assertEquals(UsageOutcome.Refused, refused.outcome)
            assertEquals(UsageRefusal.OverAllowance, refused.refusal)
            val replay = customer.recordUsage(credits, 2, idempotencyKey = "a")
            assertEquals(UsageOutcome.Duplicate, replay.outcome)
            assertTrue(replay.replayed)
            val observed = customer.recordUsage(credits, 5, idempotencyKey = "c", mode = UsageMode.Observe)
            assertEquals(4, observed.overBy)
            assertEquals(104, customer.check(credits).used)
            assertEquals(4, customer.writes.size)
            assertEquals(
                mapOf("feature" to credits, "amount" to 2L, "idempotencyKey" to "a", "mode" to null),
                customer.writes.first().arguments,
            )
            assertEquals(UsageOutcome.Adjusted, customer.adjustMeter(credits, idempotencyKey = "d", to = 0).outcome)
            assertEquals(0, customer.check(credits).used)
        }

    @Test
    fun `holds settle, release, refuse and replay`() =
        runTest {
            val customer = fakeCustomer("ai_credits" to metered(value = 100))
            val answer =
                customer.withHold(credits, 50, idempotencyKey = "job") { hold ->
                    assertEquals(50, customer.check(credits).held)
                    "done".also { hold.used = 20 }
                }
            assertEquals("done", answer)
            assertEquals(20, customer.check(credits).used)
            assertEquals(0, customer.check(credits).held)
            assertFailsWith<EntitlerUsageReplayedException> { customer.startHold(credits, 50, idempotencyKey = "job") }
            assertFailsWith<EntitlerUsageRefusedException> { customer.startHold(credits, 500, idempotencyKey = "big") }
            val hold = customer.startHold(credits, 10, idempotencyKey = "stream")
            assertEquals(UsageOutcome.Released, hold.release().outcome)
            assertEquals(UsageOutcome.Released, hold.finish().outcome)
            assertFailsWith<IllegalStateException> { customer.withHold(credits, 10, "fails") { error("failed") } }
            customer.startHold(credits, 10, idempotencyKey = "closed").use { it.used = 4 }
            assertEquals(24, customer.check(credits).used)
        }

    @Test
    fun `billing writes answer a plain success, and a replaced answer stands in`() =
        runTest {
            val customer = fakeCustomer()
            assertIs<SubscribeStep.Done>(customer.subscribe("pro", period = "monthly"))
            customer.answer("subscribe") { SubscribeStep.Pay("https://pay.example.com") }
            assertEquals(SubscribeStep.Pay("https://pay.example.com"), customer.subscribe("pro"))
            customer.answer("cancel") { throw EntitlerApiException(403, ErrorCode.NotSelfServe, "Not self-serve.") }
            assertFailsWith<EntitlerApiException> { customer.cancel() }
            assertEquals("enterprise", customer.setPlan("enterprise", billing = PlanBilling.End).plan?.key)
            assertEquals(150, customer.setAddOn("extra_seats", 150).quantity)
            assertEquals("agent", customer.grant(Feature.OnOff("sso"), actor = "agent").grant.actor)
            customer.revokeGrant("g_1")
            customer.undoPendingChange()
            customer.billingPortal("https://example.com")
            customer.syncBilling()
            customer.register(name = "Ada")
            customer.update(name = "Ada")
            customer.token(listOf(Scope.EntitlementsRead))
            customer.setTrack("Beta")
            customer.snapshot()
            customer.erase()
            assertEquals(
                listOf(
                    "subscribe",
                    "subscribe",
                    "cancel",
                    "setPlan",
                    "setAddOn",
                    "grant",
                    "revokeGrant",
                    "undoPendingChange",
                    "billingPortal",
                    "syncBilling",
                    "register",
                    "update",
                    "token",
                    "setTrack",
                    "snapshot",
                    "erase",
                ),
                customer.writes.map { it.method },
            )
            assertEquals(customer.plans(), Answers.customerPlans())
            assertEquals(customer.pricing().customer, "user_1")
            customer.details()
            customer.billing()
        }

    @Test
    fun `every answer builder's body decodes with the real decoder`() =
        runTest {
            val customer = server.customer("user_1")
            serve(Answers.body(Answers.check()))
            assertTrue(customer.check(exportPdf).entitled)
            serve(Answers.body(Answers.meteredCheck(used = 10)))
            assertEquals(10, customer.check(credits).used)
            serve(Answers.body(Answers.entitlements()))
            assertTrue(customer.entitlements().has("export_pdf"))
            serve(Answers.body(Answers.customerPlans()))
            assertEquals(
                "free",
                customer
                    .plans()
                    .held
                    .single()
                    .plan.key,
            )
            serve(Answers.body(Answers.pricing()))
            customer.pricing()
            serve(Answers.body(Answers.pricing()))
            server.pricing()
            serve(Answers.body(Answers.featureList()))
            server.features()
            serve(Answers.body(Answers.customerUsage()))
            customer.usage()
            serve(Answers.body(Answers.usageResult()))
            assertEquals(UsageOutcome.Recorded, customer.recordUsage(credits, 1, "k").outcome)
            serve(Answers.body(Answers.usageEventsResult(listOf(UsageEventResult(0, UsageOutcome.Recorded, "u", false)))))
            assertEquals(1, server.recordUsageBatch(listOf(UsageBatchEvent("user_1", credits, 1, "k"))).recorded)
        }

    @Test
    fun `the builders of customers, tokens, billing and steps decode with the real decoder`() =
        runTest {
            val customer = server.customer("user_1")
            serve(Answers.body(Answers.customerDetail()))
            customer.details()
            serve(Answers.body(Answers.customerSummary()))
            customer.update(name = "Ada")
            serve(Answers.body(Answers.customerPage()))
            server.customers.page()
            serve(Answers.body(Answers.registeredCustomer()))
            customer.register()
            serve(Answers.body(Answers.issuedCustomerToken()))
            customer.token()
            serve(Answers.body(Answers.issuedSnapshot()))
            customer.snapshot()
            serve(Answers.body(Answers.snapshotKeys()))
            server.snapshotKeys()
            serve(Answers.body(Answers.customerTrack()))
            customer.setTrack(null)
            serve(Answers.body(Answers.customerBilling()))
            customer.billing()
            serve(Answers.body(Answers.providerPage()))
            customer.billingPortal("https://example.com")
            serve(Answers.body(Answers.billingSync()))
            customer.syncBilling()
            serve(Answers.body(Answers.planChange()))
            customer.setPlan("pro")
            for (step in listOf(
                SubscribeStep.Done(Answers.planChange()),
                SubscribeStep.Pay("https://pay.example.com"),
                SubscribeStep.Confirming,
                SubscribeStep.Manage(com.entitlerhq.entitler.Provider.Apple),
                SubscribeStep.Unknown("later"),
            )) {
                serve(Answers.body(step))
                assertEquals(step, customer.subscribe("pro"))
            }
            serve(Answers.body(Answers.grantChange()))
            customer.grant(Feature.OnOff("sso"))
            serve(Answers.scopesBody(listOf(Scope.PlansRead, Scope.BillingSelf), registration = true))
            assertEquals(listOf(Scope.PlansRead, Scope.BillingSelf), server.scopes().granted)
        }
}
