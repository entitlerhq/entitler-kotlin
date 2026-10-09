package com.entitlerhq.entitler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTest {
    private val api = FakeApi()
    private val server = api.server { maxRetries = 0 }
    private val customer = server.customer("u")
    private val credits = Feature.Metered("ai_credits")

    @AfterEach
    fun close() {
        server.close()
        api.close()
    }

    private fun result(
        fixture: String,
        vararg changes: Pair<String, Any?>,
    ): String {
        val base = fixtureJson(fixture).toMutableMap()
        changes.forEach { (name, value) ->
            base[name] =
                when (value) {
                    null -> kotlinx.serialization.json.JsonNull
                    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
                }
        }
        return JsonObject(base).toString()
    }

    @Test
    fun `every outcome decodes, unknown ones kept as their raw value`() =
        runTest {
            for (outcome in listOf(
                "recorded",
                "duplicate",
                "refused",
                "held",
                "settled",
                "released",
                "cancelled",
                "adjusted",
                "rolled_back",
            )) {
                api.reply(body = result("usage-result", "outcome" to outcome))
                assertEquals(outcome, customer.recordUsage(credits, 1).outcome.value)
            }
            api.replyFixture("usage-refused")
            val refused = customer.recordUsage(credits, 1000)
            assertEquals(UsageOutcome.Refused, refused.outcome)
            assertEquals(UsageRefusal.OverAllowance, refused.refusal)
            api.replyFixture("usage-result")
            val recorded = customer.recordUsage(credits, 5)
            assertEquals(UsageOutcome.Recorded, recorded.outcome)
            assertEquals(UsageMode.Gate, recorded.mode)
            assertEquals(UsageSource.Api, recorded.reportedAs)
            assertEquals(FeatureValue.Amount(295), recorded.remaining)
            assertEquals(Instant.parse("2026-10-09T01:00:00Z"), recorded.occurredAt)
            assertNull(recorded.refusal)
        }

    @Test
    fun `withHold holds, runs the work and settles its amount`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            val settled = customer.withHold(credits, 100, idempotencyKey = "job-1") { 40 }
            assertEquals(UsageOutcome.Settled, settled.outcome)
            val hold = api.take()
            assertEquals("/customers/u/usage/holds", hold.path)
            assertEquals("job-1", hold.headers["Idempotency-Key"])
            val settle = api.take()
            assertEquals("/customers/u/usage/holds/h_1/settle", settle.path)
            assertEquals("""{"amount":40}""", settle.bodyText)
        }

    @Test
    fun `withHold records the excess in observe mode with the hold's key`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            api.reply(body = result("usage-result", "mode" to "observe", "amount" to 30))
            val excess = customer.withHold(credits, 100, idempotencyKey = "job-2") { 130 }
            assertEquals(UsageMode.Observe, excess.mode)
            api.take()
            assertEquals("""{"amount":100}""", api.take().bodyText)
            val report = api.take()
            assertEquals("job-2:excess", report.headers["Idempotency-Key"])
            assertEquals("""{"feature":"ai_credits","amount":30,"mode":"observe"}""", report.bodyText)
        }

    @Test
    fun `a refused hold never runs the work`() =
        runTest {
            api.reply(body = result("usage-refused", "holdId" to null))
            var ran = false
            val failure =
                assertFailsWith<EntitlerUsageRefusedException> {
                    customer.withHold(credits, 1000) {
                        ran = true
                        1
                    }
                }
            assertFalse(ran)
            assertEquals(UsageRefusal.OverAllowance, failure.result.refusal)
            assertEquals(1, api.server.requestCount)
        }

    @Test
    fun `failed work releases the hold and propagates its error`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val failure = assertFailsWith<IllegalStateException> { customer.withHold(credits, 100) { error("model failed") } }
            assertEquals("model failed", failure.message)
            api.take()
            val release = api.take()
            assertEquals("DELETE", release.method)
            assertEquals("/customers/u/usage/holds/h_1", release.path)
        }

    @Test
    fun `a failed release goes to onError while the work's error propagates`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(500, "")
            assertFailsWith<IllegalStateException> { customer.withHold(credits, 100) { error("model failed") } }
            assertEquals(500, (api.errors.single() as EntitlerApiException).status)
        }

    @Test
    fun `cancelled work releases the hold`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val started = CompletableDeferred<Unit>()
            val job =
                async {
                    customer.withHold(credits, 100) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            api.take()
            assertEquals("DELETE", api.take().method)
        }

    @Test
    fun `a negative amount from the work releases the hold and fails`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            assertFailsWith<IllegalArgumentException> { customer.withHold(credits, 100) { -1 } }
            api.take()
            assertEquals("DELETE", api.take().method)
        }

    @Test
    fun `a failed settlement propagates with the hold id`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_expired","message":"The hold expired."}}""")
            val failure = assertFailsWith<EntitlerApiException> { customer.withHold(credits, 100) { 10 } }
            assertEquals(ErrorCode.HoldExpired, failure.code)
            assertEquals("h_1", failure.holdId)
        }

    @Test
    fun `holds read back`() =
        runTest {
            api.replyFixture("hold")
            val hold = customer.hold("h_1")
            assertEquals(HoldState.Open, hold.state)
            assertEquals(100, hold.amount)
            assertNull(hold.settledAmount)
            assertFailsWith<IllegalArgumentException> { customer.hold(" ") }
            assertFailsWith<IllegalArgumentException> { customer.settleUsage("", 1) }
            assertFailsWith<IllegalArgumentException> { customer.releaseUsage("") }
        }

    @Test
    fun `batches split at 500 events, keep input order and generate missing keys`() =
        runTest {
            val events = (0 until 1001).map { UsageBatchEvent("c${it % 3}", credits, 1, idempotencyKey = if (it == 0) "mine" else null) }
            for (size in listOf(500, 500, 1)) {
                val results =
                    (0 until size).joinToString(
                        ",",
                    ) { """{"index":$it,"outcome":"recorded","id":"u$it","late":false,"error":null}""" }
                api.reply(body = """{"results":[$results],"recorded":$size,"duplicates":0,"errors":0}""")
            }
            val answer = server.recordUsageBatch(events, register = true)
            assertEquals(1001, answer.results.size)
            assertEquals((0 until 1001).toList(), answer.results.map { it.index })
            assertEquals(1001, answer.recorded)
            val bodies = List(3) { Json.parseToJsonElement(api.take().bodyText).jsonObject }
            assertEquals(listOf(500, 500, 1), bodies.map { it["events"]!!.jsonArray.size })
            assertTrue(bodies.all { it["register"]!!.jsonPrimitive.content == "true" })
            val first = (bodies[0]["events"] as JsonArray)[0].jsonObject
            assertEquals("mine", first["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(1, first["amount"]!!.jsonPrimitive.int)
            val keys = bodies.flatMap { it["events"]!!.jsonArray }.map { it.jsonObject["idempotencyKey"]!!.jsonPrimitive.content }
            assertEquals(1001, keys.toSet().size)
        }

    @Test
    fun `a batch event with a replayed key and one in error decode`() =
        runTest {
            api.reply(
                body =
                    """{"results":[{"index":0,"outcome":"duplicate","id":"u1","late":false,"error":null},
                    {"index":1,"outcome":"error","id":null,"late":false,"error":{"code":"not_metered","message":"Not metered."}}],
                    "recorded":0,"duplicates":1,"errors":1}""",
            )
            val answer =
                server.recordUsageBatch(
                    listOf(
                        UsageBatchEvent("a", credits, 2, Instant.parse("2026-10-08T00:00:00Z"), "k1"),
                        UsageBatchEvent("b", credits, 1, idempotencyKey = "k2"),
                    ),
                )
            assertEquals(UsageOutcome.Duplicate, answer.results[0].outcome)
            assertEquals(UsageOutcome.Error, answer.results[1].outcome)
            assertEquals(ErrorCode.NotMetered, answer.results[1].error!!.code)
            val body = api.take().bodyText
            assertFalse(body.contains("register"))
            assertTrue(body.contains(""""occurredAt":"2026-10-08T00:00:00.000Z""""))
        }

    @Test
    fun `a vendor cancel and a meter set answer usage results`() =
        runTest {
            api.reply(body = result("usage-result", "outcome" to "cancelled", "meterChange" to -5))
            assertEquals(-5, customer.vendor.cancelUsage("u_1").meterChange)
            api.reply(body = result("usage-result", "outcome" to "adjusted", "id" to null))
            val set = customer.vendor.setMeter(credits, 0)
            assertEquals(UsageOutcome.Adjusted, set.outcome)
            assertNull(set.id)
            assertFailsWith<IllegalArgumentException> { customer.vendor.cancelUsage("") }
        }
}
