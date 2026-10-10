package com.entitlerhq.entitler

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The live API suite. The SDK test projects have no sign-in provider and no Stripe connection, so the
 * identity client and the `pay`, `confirming` and `manage` steps are covered by the unit tests only.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveApiTest {
    private val key: String? = System.getenv("ENTITLER_TEST_KEY")?.takeIf { it.isNotBlank() }
    private val publishableKey: String? = System.getenv("ENTITLER_TEST_PUBLISHABLE_KEY")?.takeIf { it.isNotBlank() }
    private val created = Collections.synchronizedList(mutableListOf<String>())
    private val statuses = Collections.synchronizedList(mutableListOf<Int>())
    private lateinit var server: EntitlerServer
    private val credits = Feature.Metered("ai_credits")
    private val exportPdf = Feature.OnOff("export_pdf")

    @BeforeAll
    fun setUp() {
        assumeTrue(key != null, "Live API tests skipped: set ENTITLER_TEST_KEY to run them.")
        val recordStatus = Interceptor { chain -> chain.proceed(chain.request()).also { statuses += it.code } }
        val http = OkHttpClient.Builder().addNetworkInterceptor(recordStatus).build()
        server = EntitlerServer(key!!) { httpClient = http }
    }

    @AfterAll
    fun tearDown() {
        if (key == null) return
        runBlocking<Unit> {
            created.forEach { id -> runCatching { server.customer(id).erase() } }
        }
        server.close()
    }

    private fun unique(prefix: String): String =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(16)

    private fun newId(): String = unique("sdk-kotlin-")

    private suspend fun registered(): ServerCustomer {
        val id = newId()
        created += id
        return server.customer(id).also { it.register(name = "SDK test", email = "$id@example.com") }
    }

    @Test
    fun `scopes, pricing with a visitor, and the sample features`() =
        runBlocking<Unit> {
            val scopes = server.scopes()
            assertTrue(Scope.PlansRead in scopes)
            assertTrue(Scope.TracksAssign in scopes)
            assertNull(scopes.registration)
            val pricing = server.pricing(visitor = server.newVisitorId())
            assertTrue(pricing.plans.any { it.key == "free" && it.default })
            assertTrue(pricing.plans.any { it.key == "pro" })
            val features = server.features()
            assertEquals(
                listOf(
                    "ai_credits",
                    "collaboration",
                    "export_pdf",
                    "priority_support",
                    "shared_folders",
                    "sso",
                    "support_contacts",
                    "support_extras",
                    "support_sla_hours",
                    "team_essentials",
                    "team_seats",
                ),
                features.features.map { it.key }.sorted(),
            )
            assertEquals(FeatureType.Metered, features.features.first { it.key == "ai_credits" }.type)
            assertEquals("All customers", features.track.name)
        }

    @Test
    fun `signed-out pricing through a publishable client`() =
        runBlocking<Unit> {
            assumeTrue(publishableKey != null, "Set ENTITLER_TEST_PUBLISHABLE_KEY to read signed-out pricing with a publishable key.")
            EntitlerPublishableClient(publishableKey!!).use { client ->
                val pricing = client.pricing()
                assertTrue(pricing.plans.any { it.key == "pro" })
                assertTrue(Regex(VISITOR_ID_PATTERN).matches(client.visitor()))
            }
        }

    @Test
    fun `register creates, then stores changed details, and details and update read them`() =
        runBlocking<Unit> {
            val id = newId()
            created += id
            val customer = server.customer(id)
            assertTrue(customer.register(name = "First", email = "$id@example.com", visitor = server.newVisitorId()).created)
            assertFalse(customer.register(name = "Second", metadata = mapOf("team" to "core")).created)
            val details = customer.details()
            assertEquals("Second", details.customer.name)
            assertEquals("core", details.customer.metadata["team"])
            assertEquals("Third", customer.update(name = "Third", metadata = mapOf("team" to null)).name)
            assertEquals(null, customer.details().customer.metadata["team"])
        }

    @Test
    fun `checks, entitlements and a group on the default plan, 304s, and revalidate after a change`() =
        runBlocking<Unit> {
            val customer = registered()
            val check = customer.check(exportPdf)
            assertFalse(check.entitled)
            assertEquals(
                "free",
                customer
                    .check(credits)
                    .sources
                    .first()
                    .plan,
            )
            val entitlements = customer.entitlements()
            assertFalse(entitlements.has(Feature.Group("collaboration")))
            assertNotNull(entitlements["collaboration"])
            val before = statuses.size
            customer.check(exportPdf)
            assertEquals(304, statuses.drop(before).single())
            val beforeList = statuses.size
            customer.entitlements()
            assertEquals(304, statuses.drop(beforeList).single())
            assertFalse(customer.isEntitled(exportPdf))
            customer.setPlan("pro", period = "monthly", idempotencyKey = unique("plan-"))
            assertTrue(customer.check(exportPdf, revalidate = true).entitled)
        }

    @Test
    fun `usage reports, replays, refusals, observe mode, the log, cancels and meter adjustments`() =
        runBlocking<Unit> {
            val customer = registered()
            val key = unique("report-")
            val recorded = customer.recordUsage(credits, 5, idempotencyKey = key)
            assertEquals(UsageOutcome.Recorded, recorded.outcome)
            assertEquals(5, recorded.used)
            val replay = customer.recordUsage(credits, 5, idempotencyKey = key)
            assertEquals(UsageOutcome.Duplicate, replay.outcome)
            assertTrue(replay.replayed)
            val refused = customer.recordUsage(credits, 1000, idempotencyKey = unique("big-"))
            assertEquals(UsageOutcome.Refused, refused.outcome)
            assertEquals(UsageRefusal.OverAllowance, refused.refusal)
            val observed = customer.recordUsage(credits, 30, idempotencyKey = unique("observe-"), mode = UsageMode.Observe)
            assertEquals(UsageOutcome.Recorded, observed.outcome)
            assertTrue(observed.overBy > 0)
            val earlier = Instant.now().minusSeconds(60)
            val dated = customer.recordUsage(credits, 1, idempotencyKey = unique("dated-"), mode = UsageMode.Observe, occurredAt = earlier)
            assertEquals(earlier.toEpochMilli() / 1000, dated.occurredAt!!.toEpochMilli() / 1000)
            assertTrue(customer.usageLog().toList().size >= 3)
            val cancelled = customer.cancelUsage(recorded.id!!, actor = "sdk-tests")
            assertEquals(UsageOutcome.Cancelled, cancelled.outcome)
            assertEquals(-5, cancelled.meterChange)
            val by = customer.adjustMeter(credits, idempotencyKey = unique("by-"), by = -1, reason = "SDK test")
            assertEquals(UsageOutcome.Adjusted, by.outcome)
            val to = customer.adjustMeter(credits, idempotencyKey = unique("to-"), to = 2)
            assertEquals(2, to.used)
        }

    @Test
    fun `hold handles finish and release, withHold settles or releases, and a finished key replays`() =
        runBlocking<Unit> {
            val customer = registered()
            val key = unique("hold-")
            val hold = customer.startHold(credits, 10, idempotencyKey = key, ttl = 2.minutes)
            assertFalse(hold.isDuplicate)
            hold.used = 4
            assertEquals(UsageOutcome.Settled, hold.finish().outcome)
            assertFailsWith<EntitlerUsageReplayedException> { customer.startHold(credits, 10, idempotencyKey = key) }
            val second = customer.startHold(credits, 3, idempotencyKey = unique("hold-"))
            assertEquals(UsageOutcome.Released, second.release().outcome)
            assertEquals("done", customer.withHold(credits, 5, unique("work-")) { hold -> "done".also { hold.used = 2 } })
            assertFailsWith<IllegalStateException> {
                customer.withHold(credits, 5, unique("work-")) {
                    it.used = 1
                    error("work failed after using 1")
                }
            }
            assertFailsWith<IllegalStateException> { customer.withHold(credits, 5, unique("work-")) { error("work failed") } }
            assertEquals(7, customer.check(credits).used)
            assertEquals(0, customer.check(credits).held)
        }

    @Test
    fun `a batch with a replayed event, then the same batch resent answering replayed`() =
        runBlocking<Unit> {
            val customer = registered()
            val key = unique("batch-")
            val events =
                listOf(
                    UsageBatchEvent(customer.id, credits, 2, idempotencyKey = key),
                    UsageBatchEvent(customer.id, credits, 2, idempotencyKey = key),
                )
            val result = server.recordUsageBatch(events)
            assertEquals(listOf(UsageOutcome.Recorded, UsageOutcome.Duplicate), result.results.map { it.outcome })
            assertEquals(1, result.recorded)
            assertEquals(1, result.duplicates)
            assertTrue(server.recordUsageBatch(events).results.all { it.replayed })
        }

    @Test
    fun `customer plans and customer pricing`() =
        runBlocking<Unit> {
            val customer = registered()
            val plans = customer.plans()
            val held = plans.held.first { it.plan.key == "free" }
            assertNull(held.billedBy)
            val pro = plans.options.first { it.plan.key == "pro" }
            assertEquals(MoveAction.Buy, pro.action)
            assertEquals(listOf("monthly", "yearly"), pro.periods.map { it.key })
            assertEquals(customer.id, customer.pricing().customer)
        }

    @Test
    fun `customer tokens hold the scopes asked for, and in-app billing needs billing self`() =
        runBlocking<Unit> {
            val customer = registered()
            assertEquals(listOf(Scope.EntitlementsRead), customer.token().scopes)
            val scopes = listOf(Scope.EntitlementsRead, Scope.UsageWrite, Scope.BillingSelf)
            EntitlerClient({ customer.token(scopes = scopes, ttl = 5.minutes).token }).use { client ->
                assertEquals(scopes, client.scopes().granted)
                assertFalse(client.me.check(exportPdf).entitled)
                assertEquals(customer.id, client.me.id)
                assertEquals(UsageOutcome.Recorded, client.me.recordUsage(credits, 1, idempotencyKey = unique("app-")).outcome)
                try {
                    assertIs<SubscribeStep.Done>(client.me.subscribe("pro", period = "monthly", returnUrl = "https://example.com/back"))
                } catch (error: EntitlerApiException) {
                    assertEquals(ErrorCode.CapabilityRequired, error.code)
                }
            }
            EntitlerClient(customer.token(scopes = listOf(Scope.EntitlementsRead, Scope.UsageWrite)).token).use { client ->
                val failure = assertFailsWith<EntitlerApiException> { client.me.subscribe("pro", period = "monthly") }
                assertEquals(ErrorCode.ScopeRequired, failure.code)
                assertEquals(403, failure.status)
            }
        }

    @Test
    fun `a snapshot verifies against the snapshot keys`() =
        runBlocking<Unit> {
            val customer = registered()
            val environment = customer.check(credits).environment.id
            val snapshot = customer.snapshot(ttl = 10.minutes)
            val verified = server.verifySnapshot(snapshot.token, SnapshotExpectation(server.snapshotKeys(), customer.id, environment))
            assertEquals(customer.id, verified.customer)
            assertFalse(verified.entitlements.has("export_pdf"))
        }

    @Test
    fun `tracks move a customer to Beta by name and back`() =
        runBlocking<Unit> {
            val customer = registered()
            assertEquals("Beta", customer.setTrack("Beta").track.name)
            assertEquals("All customers", customer.setTrack(null).track.name)
        }

    @Test
    fun `as-of reads answer or are refused with capability_required`() =
        runBlocking<Unit> {
            val customer = registered()
            try {
                customer.check(credits, asOf = Instant.now().minusSeconds(3600))
            } catch (error: EntitlerApiException) {
                assertEquals(409, error.status)
                assertEquals(ErrorCode.CapabilityRequired, error.code)
            }
        }

    @Test
    fun `the customer's billing choices without Stripe`() =
        runBlocking<Unit> {
            val customer = registered()
            val done = assertIs<SubscribeStep.Done>(customer.subscribe("pro", period = "monthly"))
            assertEquals("pro", done.change.plan?.key)
            assertIs<SubscribeStep.Done>(customer.subscribe("sso_addon"))
            assertTrue(customer.cancel(addOn = "sso_addon").changed)
            assertTrue(customer.cancel().changed)
            assertTrue(customer.undoPendingChange().changed)
            customer.cancel()
            customer.setPlan("free")
            assertFalse(customer.cancel().changed)
            assertFalse(customer.syncBilling().changed)
            val portal = assertFailsWith<EntitlerApiException> { customer.billingPortal("https://example.com") }
            assertEquals(ErrorCode.Stale, portal.code)
        }

    @Test
    fun `the company's decisions without Stripe`() =
        runBlocking<Unit> {
            val customer = registered()
            val period =
                customer
                    .plans()
                    .options
                    .first { it.plan.key == "pro_basic" }
                    .periods
                    .first()
                    .key
            assertEquals("pro_basic", customer.setPlan("pro_basic", period = period, actor = "sdk-tests").plan?.key)
            assertFalse(customer.setPlan("pro_basic", period = period).changed)
            assertEquals(1, customer.setAddOn("support_standard", 1).quantity)
            assertEquals(0, customer.setAddOn("support_standard", 0).quantity)
            val until = Instant.now().plusSeconds(30.days.inWholeSeconds)
            assertNotNull(customer.setPlan("pro", period = "monthly", until = until).until)
            val grant = customer.grant(Feature.OnOff("sso"), days = 1, actor = "sdk-tests", reason = "SDK test").grant
            assertEquals("sso", grant.feature)
            assertNotNull(customer.revokeGrant(grant.id).grant.revokedAt)
        }

    @Test
    fun `the customer list finds a customer with a search and a cursor, and erase removes them`() =
        runBlocking<Unit> {
            val customer = registered()
            assertEquals(
                customer.id,
                server.customers
                    .list(q = customer.id)
                    .first()
                    .externalId,
            )
            val page = server.customers.page()
            page.next?.let {
                assertTrue(
                    server.customers
                        .page(cursor = it)
                        .items
                        .isNotEmpty(),
                )
            }
            customer.erase()
            customer.erase()
            created -= customer.id
            assertTrue(
                server.customers
                    .list(q = customer.id)
                    .toList()
                    .isEmpty(),
            )
        }

    @Test
    fun `errors decode with their codes and request ids`() =
        runBlocking<Unit> {
            val unregistered = server.customer(newId())
            val notFound = assertFailsWith<EntitlerApiException> { unregistered.recordUsage(credits, 1, idempotencyKey = unique("k-")) }
            assertEquals(ErrorCode.CustomerNotFound, notFound.code)
            assertNotNull(notFound.requestId)
            val unknown = assertFailsWith<EntitlerApiException> { server.customer("test_1001").check("no_such_feature") }
            assertEquals(ErrorCode.FeatureNotFound, unknown.code)
            assertNotNull(unknown.requestId)
        }
}
