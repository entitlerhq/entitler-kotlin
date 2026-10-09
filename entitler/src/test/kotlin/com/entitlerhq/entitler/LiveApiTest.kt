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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveApiTest {
    private val key: String? = System.getenv("ENTITLER_TEST_KEY")?.takeIf { it.isNotBlank() }
    private val created = Collections.synchronizedList(mutableListOf<String>())
    private val statuses = Collections.synchronizedList(mutableListOf<Int>())
    private lateinit var server: EntitlerServer
    private val credits = Feature.Metered("ai_credits")

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
            created.forEach { id -> runCatching { server.customer(id).delete(erase = true) } }
        }
        server.close()
    }

    private fun newId(): String =
        "sdk-kotlin-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(16)

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
    fun `checks, entitlements and a group on the default plan, revalidated with a 304`() =
        runBlocking<Unit> {
            val customer = registered()
            val check = customer.check(Feature.OnOff("export_pdf"))
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
            customer.check(Feature.OnOff("export_pdf"))
            assertEquals(304, statuses.drop(before).single())
            assertFalse(customer.isEntitled(Feature.OnOff("export_pdf")))
        }

    @Test
    fun `usage reports in gate and observe modes`() =
        runBlocking<Unit> {
            val customer = registered()
            val key = "report-" + UUID.randomUUID()
            val recorded = customer.recordUsage(credits, 5, idempotencyKey = key)
            assertEquals(UsageOutcome.Recorded, recorded.outcome)
            assertEquals(5, recorded.used)
            assertEquals(UsageOutcome.Duplicate, customer.recordUsage(credits, 5, idempotencyKey = key).outcome)
            val refused = customer.recordUsage(credits, 1000)
            assertEquals(UsageOutcome.Refused, refused.outcome)
            assertEquals(UsageRefusal.OverAllowance, refused.refusal)
            val observed = customer.recordUsage(credits, 30, mode = UsageMode.Observe)
            assertEquals(UsageOutcome.Recorded, observed.outcome)
            assertTrue(observed.overBy > 0)
            val earlier = Instant.now().minusSeconds(60)
            val dated = customer.recordUsage(credits, 1, mode = UsageMode.Observe, occurredAt = earlier)
            assertEquals(earlier.toEpochMilli() / 1000, dated.occurredAt!!.toEpochMilli() / 1000)
            val log = customer.usageLog().toList()
            assertTrue(log.size >= 3)
            val cancelled = customer.vendor.cancelUsage(recorded.id!!)
            assertEquals(UsageOutcome.Cancelled, cancelled.outcome)
            assertEquals(-5, cancelled.meterChange)
            val set = customer.vendor.setMeter(credits, 2)
            assertEquals(UsageOutcome.Adjusted, set.outcome)
            assertEquals(2, set.used)
        }

    @Test
    fun `holds settle, release, read back, and wrap work`() =
        runBlocking<Unit> {
            val customer = registered()
            val held = customer.holdUsage(credits, 10, ttl = 2.minutes)
            assertEquals(UsageOutcome.Held, held.outcome)
            val holdId = assertNotNull(held.holdId)
            assertEquals(HoldState.Open, customer.hold(holdId).state)
            assertEquals(UsageOutcome.Settled, customer.settleUsage(holdId, 4).outcome)
            assertEquals(HoldState.Settled, customer.hold(holdId).state)
            val second = customer.holdUsage(credits, 3)
            assertEquals(UsageOutcome.Released, customer.releaseUsage(second.holdId!!).outcome)
            val settled = customer.withHold(credits, 5) { 2 }
            assertEquals(UsageOutcome.Settled, settled.outcome)
            assertFailsWith<IllegalStateException> { customer.withHold(credits, 5) { error("work failed") } }
            assertEquals(6, customer.check(credits).used)
            assertEquals(0, customer.check(credits).held)
        }

    @Test
    fun `a batch with a replayed event`() =
        runBlocking<Unit> {
            val customer = registered()
            val key = "batch-" + UUID.randomUUID()
            val events =
                listOf(
                    UsageBatchEvent(customer.id, credits, 2, idempotencyKey = key),
                    UsageBatchEvent(customer.id, credits, 2, idempotencyKey = key),
                )
            val result = server.recordUsageBatch(events)
            assertEquals(listOf(UsageOutcome.Recorded, UsageOutcome.Duplicate), result.results.map { it.outcome })
            assertEquals(1, result.recorded)
            assertEquals(1, result.duplicates)
        }

    @Test
    fun `plan space and customer pricing`() =
        runBlocking<Unit> {
            val customer = registered()
            val space = customer.planSpace()
            assertTrue(space.held.any { it.plan.key == "free" })
            assertTrue(space.options.any { it.plan.key == "pro" })
            assertEquals(customer.id, customer.pricing().customer)
        }

    @Test
    fun `an in-app client with a customer token provider`() =
        runBlocking<Unit> {
            val customer = registered()
            EntitlerClient({ customer.token(ttl = 5.minutes).token }).use { client ->
                val scopes = client.scopes()
                assertEquals(listOf(Scope.EntitlementsRead, Scope.UsageRead, Scope.UsageWrite), scopes.granted)
                assertFalse(client.me.check(Feature.OnOff("export_pdf")).entitled)
                assertEquals(customer.id, client.me.id)
                assertEquals(UsageOutcome.Recorded, client.me.recordUsage(credits, 1).outcome)
            }
            EntitlerClient(customer.token(scopes = listOf(Scope.EntitlementsRead)).token).use { client ->
                val failure = assertFailsWith<EntitlerApiException> { client.me.recordUsage(credits, 1) }
                assertTrue(failure.code == ErrorCode.ScopeRequired || failure.code == ErrorCode.CredentialNotAllowed)
                assertEquals(403, failure.status)
            }
        }

    @Test
    fun `a snapshot verifies against the snapshot keys`() =
        runBlocking<Unit> {
            val customer = registered()
            val environment = customer.check(credits).environment.id
            val snapshot = customer.snapshot(ttl = 10.minutes)
            val verified = server.verifySnapshot(snapshot.token, server.snapshotKeys(), customer.id, environment)
            assertEquals(customer.id, verified.customer)
            assertFalse(verified.entitlements.has("export_pdf"))
        }

    @Test
    fun `tracks move a customer to Beta and back`() =
        runBlocking<Unit> {
            val customer = registered()
            val beta = server.customer("test_1013").check(Feature.OnOff("sso")).track
            assertEquals("Beta", beta.name)
            assertEquals(beta, customer.setTrack(beta.id).track)
            assertEquals("All customers", customer.setTrack(null).track.name)
        }

    @Test
    fun `as-of reads answer or are refused with limit_reached`() =
        runBlocking<Unit> {
            val customer = registered()
            EntitlerServer(key!!) { asOf = Instant.now().minusSeconds(3600) }.use { past ->
                try {
                    past.customer(customer.id).check(credits)
                } catch (error: EntitlerApiException) {
                    assertEquals(409, error.status)
                    assertEquals(ErrorCode.LimitReached, error.code)
                }
            }
        }

    @Test
    fun `billing moves, add-ons, grants, and provider pages without Stripe`() =
        runBlocking<Unit> {
            val customer = registered()
            assertEquals(
                "pro",
                customer
                    .subscribe("pro")
                    .customer.plan
                    ?.key,
            )
            assertEquals(
                "sso_addon",
                customer
                    .addAddOn("sso_addon")
                    .addOns
                    .single()
                    .plan.key,
            )
            assertTrue(customer.removeAddOn("sso_addon").addOns.none { it.plan.key == "sso_addon" && it.movingTo == null })
            assertEquals(
                "free",
                customer
                    .subscribe("free")
                    .customer.plan
                    ?.key,
            )
            assertEquals(
                "team",
                customer.vendor
                    .subscribe("team")
                    .customer.plan
                    ?.key,
            )
            val granted = customer.vendor.grant(Feature.OnOff("sso"), days = 1, reason = "SDK test")
            val grant = granted.grants.first { it.feature == "sso" && it.revokedAt == null }
            assertNotNull(
                customer.vendor
                    .revokeGrant(grant.id)
                    .grants
                    .first { it.id == grant.id }
                    .revokedAt,
            )
            assertEquals(UsageOutcome.Adjusted, customer.vendor.setMeter(credits, 1).outcome)
            val checkout =
                assertFailsWith<EntitlerApiException> { customer.checkout("pro", "https://example.com/ok", "https://example.com/no") }
            assertEquals(409, checkout.status)
            assertEquals(ErrorCode.Stale, checkout.code)
            val portal = assertFailsWith<EntitlerApiException> { customer.billingPortal("https://example.com") }
            assertEquals(ErrorCode.Stale, portal.code)
        }

    @Test
    fun `the customer list finds a customer, and delete erases them`() =
        runBlocking<Unit> {
            val customer = registered()
            assertEquals(
                customer.id,
                server.customers
                    .list(q = customer.id)
                    .first()
                    .externalId,
            )
            assertTrue(customer.delete(erase = true).externalId.startsWith("erased_"))
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
            val notFound = assertFailsWith<EntitlerApiException> { unregistered.recordUsage(credits, 1) }
            assertEquals(ErrorCode.CustomerNotFound, notFound.code)
            assertNotNull(notFound.requestId)
            val unknown = assertFailsWith<EntitlerApiException> { server.customer("test_1001").check("no_such_feature") }
            assertEquals(ErrorCode.FeatureNotFound, unknown.code)
            assertNotNull(unknown.requestId)
        }
}
