package com.entitlerhq.entitler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
                    null -> JsonNull
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
        }
        return JsonObject(base).toString()
    }

    private val holdOpen =
        """{"id":"h_1","customer":"u","feature":"ai_credits","amount":100,"state":"open",""" +
            """"expiresAt":"2026-10-09T01:05:00.000Z","createdAt":"2026-10-09T01:00:00.000Z"}"""

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
            assertEquals(UsageMode.Gate, recorded.mode)
            assertEquals(UsageSource.Api, recorded.reportedAs)
            assertEquals(FeatureValue.Amount(295), recorded.remaining)
            assertEquals(Instant.parse("2026-10-09T01:00:00Z"), recorded.occurredAt)
            assertNull(recorded.refusal)
        }

    @Test
    fun `amounts are checked before any request`() =
        runTest {
            val message = "Pass amount as a whole number from 1 to 9007199254740991."
            for (amount in listOf(0L, -1L, 9_007_199_254_740_992L)) {
                assertEquals(message, assertFailsWith<IllegalArgumentException> { customer.recordUsage(credits, amount) }.message)
                assertEquals(message, assertFailsWith<IllegalArgumentException> { customer.holdUsage(credits, amount) }.message)
            }
            assertEquals(
                "Pass amount as a whole number from 0 to the held amount.",
                assertFailsWith<IllegalArgumentException> { customer.settleUsage("h_1", -1) }.message,
            )
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `withHold settles the amount the work reports and answers the work's result`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            val answer =
                customer.withHold(credits, 100, idempotencyKey = "job-1") { hold ->
                    assertEquals("h_1", hold.holdId)
                    assertEquals(100, hold.amount)
                    hold.used = 50
                    hold.used = 40
                    "summary"
                }
            assertEquals("summary", answer)
            val hold = api.take()
            assertEquals("/customers/u/usage/holds", hold.path)
            assertEquals("job-1", hold.headers["Idempotency-Key"])
            val settle = api.take()
            assertEquals("/customers/u/usage/holds/h_1/settle", settle.path)
            assertEquals("""{"amount":40}""", settle.bodyText)
        }

    @Test
    fun `withHold settles the held amount when the work reports none`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            customer.withHold(credits, 100) { }
            api.take()
            assertEquals("""{"amount":100}""", api.take().bodyText)
        }

    @Test
    fun `withHold records the excess in observe mode with the hold's key`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            api.reply(body = result("usage-result", "mode" to "observe", "amount" to 30))
            customer.withHold(credits, 100, idempotencyKey = "job-2") { it.used = 130 }
            api.take()
            assertEquals("""{"amount":100}""", api.take().bodyText)
            val report = api.take()
            assertEquals("job-2:excess", report.headers["Idempotency-Key"])
            assertEquals("""{"feature":"ai_credits","amount":30,"mode":"observe"}""", report.bodyText)
        }

    @Test
    fun `a hold that expired during the work records the whole amount as excess`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_expired","message":"The hold expired."}}""")
            api.replyFixture("usage-result")
            customer.withHold(credits, 100, idempotencyKey = "job-3") { it.used = 60 }
            api.take()
            api.take()
            val report = api.take()
            assertEquals("job-3:excess", report.headers["Idempotency-Key"])
            assertEquals("""{"feature":"ai_credits","amount":60,"mode":"observe"}""", report.bodyText)
        }

    @Test
    fun `a refused hold or a settled or released replay never runs the work`() =
        runTest {
            for (answer in listOf(result("usage-refused", "holdId" to null), result("usage-settled"), result("usage-released"))) {
                api.reply(body = answer)
                var ran = false
                assertFailsWith<EntitlerUsageRefusedException> { customer.withHold(credits, 1000) { ran = true } }
                assertFalse(ran)
            }
            assertEquals(3, api.server.requestCount)
        }

    @Test
    fun `a replayed hold runs the work only while it is open`() =
        runTest {
            api.reply(body = result("usage-held", "outcome" to "duplicate"))
            api.reply(body = holdOpen)
            api.replyFixture("usage-settled")
            assertEquals(1, customer.withHold(credits, 100) { 1 })
            api.reply(body = result("usage-held", "outcome" to "duplicate"))
            api.reply(body = holdOpen.replace("\"open\"", "\"expired\""))
            var ran = false
            assertFailsWith<EntitlerUsageRefusedException> { customer.withHold(credits, 100) { ran = true } }
            assertFalse(ran)
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
    fun `a used amount outside the range fails`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val failure = assertFailsWith<IllegalArgumentException> { customer.withHold(credits, 100) { it.used = -1 } }
            assertEquals("Pass the amount used as a whole number of 0 or more.", failure.message)
            api.take()
            assertEquals("DELETE", api.take().method)
        }

    @Test
    fun `a failed settlement throws with the work's result`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_settled","message":"Settled with another amount."}}""")
            val output = listOf("kept")
            val failure =
                assertFailsWith<EntitlerUsageSettlementException> {
                    customer.withHold(credits, 100) {
                        output.also { _ ->
                            it.used =
                                10
                        }
                    }
                }
            assertEquals("h_1", failure.holdId)
            assertEquals(10, failure.amount)
            assertNull(failure.excess)
            assertSame(output, failure.result)
            assertEquals(ErrorCode.HoldSettled, (failure.cause as EntitlerApiException).code)
        }

    @Test
    fun `withHold keys are at most 193 characters`() =
        runTest {
            val failure = assertFailsWith<IllegalArgumentException> { customer.withHold(credits, 1, idempotencyKey = "k".repeat(194)) { } }
            assertEquals("Pass idempotencyKey as 1 to 193 printable ASCII characters.", failure.message)
            assertEquals(0, api.server.requestCount)
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
            assertFailsWith<IllegalArgumentException> { customer.hold("..") }
            assertFailsWith<IllegalArgumentException> { customer.settleUsage("", 1) }
            assertFailsWith<IllegalArgumentException> { customer.releaseUsage("") }
        }

    private fun replyBatch(size: Int) {
        val results = (0 until size).joinToString(",") { """{"index":$it,"outcome":"recorded","id":"u$it","late":false,"error":null}""" }
        api.reply(body = """{"results":[$results],"recorded":$size,"duplicates":0,"errors":0}""")
    }

    @Test
    fun `batches split at 500 events with one key per request, in input order`() =
        runTest {
            val events = (0 until 1001).map { UsageBatchEvent("c${it % 3}", credits, 1, idempotencyKey = if (it == 0) "mine" else null) }
            listOf(500, 500, 1).forEach(::replyBatch)
            val answer = server.recordUsageBatch(events, register = true, idempotencyKey = "batch-7")
            assertEquals(1001, answer.results.size)
            assertEquals((0 until 1001).toList(), answer.results.map { it.index })
            assertEquals(1001, answer.recorded)
            assertEquals("mine", answer.results[0].idempotencyKey)
            val requests = List(3) { api.take() }
            assertEquals(listOf("batch-7:0", "batch-7:1", "batch-7:2"), requests.map { it.headers["Idempotency-Key"] })
            val bodies = requests.map { Json.parseToJsonElement(it.bodyText).jsonObject }
            assertEquals(listOf(500, 500, 1), bodies.map { it["events"]!!.jsonArray.size })
            assertTrue(bodies.all { it["register"]!!.jsonPrimitive.content == "true" })
            val first = (bodies[0]["events"] as JsonArray)[0].jsonObject
            assertEquals("mine", first["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(1, first["amount"]!!.jsonPrimitive.int)
            val keys = bodies.flatMap { it["events"]!!.jsonArray }.map { it.jsonObject["idempotencyKey"]!!.jsonPrimitive.content }
            assertEquals(1001, keys.toSet().size)
            assertEquals(keys, answer.results.map { it.idempotencyKey })
        }

    @Test
    fun `a failed batch request answers its events with error and the next request still goes`() =
        runTest {
            val events = (0 until 600).map { UsageBatchEvent("c", credits, 1) }
            api.reply(403, """{"error":{"code":"scope_required","message":"Needs usage:write."}}""")
            replyBatch(100)
            val answer = server.recordUsageBatch(events)
            assertEquals(600, answer.results.size)
            assertTrue(answer.results.take(500).all { it.outcome == UsageOutcome.Error && it.error?.code == ErrorCode.ScopeRequired })
            assertEquals(500, answer.errors)
            assertEquals(100, answer.recorded)
            val headers = List(2) { api.take().headers["Idempotency-Key"] }
            assertTrue(headers.all { it != null } && headers[0] != headers[1])
        }

    @Test
    fun `a batch request with no answer answers connection_failed`() =
        runTest {
            api.server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            val answer =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    server.recordUsageBatch(listOf(UsageBatchEvent("c", credits, 1)))
                }
            assertEquals(
                ErrorCode.ConnectionFailed,
                answer.results
                    .single()
                    .error
                    ?.code,
            )
        }

    @Test
    fun `an empty batch sends nothing, and bad batch arguments fail first`() =
        runTest {
            assertEquals(UsageEventsResult(emptyList(), 0, 0, 0), server.recordUsageBatch(emptyList()))
            assertEquals(
                "Pass idempotencyKey as 1 to 190 printable ASCII characters.",
                assertFailsWith<IllegalArgumentException> {
                    server.recordUsageBatch(
                        emptyList(),
                        idempotencyKey = "k".repeat(191),
                    )
                }.message,
            )
            assertFailsWith<IllegalArgumentException> { server.recordUsageBatch(listOf(UsageBatchEvent("c", credits, 0))) }
            assertEquals(0, api.server.requestCount)
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
            assertEquals(ErrorCode.NotMetered, answer.results[1].error!!.code)
            assertEquals(listOf("k1", "k2"), answer.results.map { it.idempotencyKey })
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
