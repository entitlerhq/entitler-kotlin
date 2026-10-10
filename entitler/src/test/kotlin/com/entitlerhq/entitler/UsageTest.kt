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
import kotlin.time.Duration.Companion.minutes

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
                assertEquals(outcome, customer.recordUsage(credits, 1, idempotencyKey = "k-$outcome").outcome.value)
            }
            api.replyFixture("usage-refused")
            val refused = customer.recordUsage(credits, 1000, idempotencyKey = "k1")
            assertEquals(UsageOutcome.Refused, refused.outcome)
            assertEquals(UsageRefusal.OverAllowance, refused.refusal)
            api.replyFixture("usage-result")
            val recorded = customer.recordUsage(credits, 5, idempotencyKey = "k2")
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
                assertEquals(message, assertFailsWith<IllegalArgumentException> { customer.recordUsage(credits, amount, "k") }.message)
                assertEquals(message, assertFailsWith<IllegalArgumentException> { customer.holdUsage(credits, amount, "k") }.message)
                assertEquals(message, assertFailsWith<IllegalArgumentException> { customer.startHold(credits, amount, "k") }.message)
            }
            assertEquals(
                "Pass amount as a whole number from 0 to the held amount.",
                assertFailsWith<IllegalArgumentException> { customer.settleUsage("h_1", -1) }.message,
            )
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `usage writes refuse an invalid key before any request`() =
        runTest {
            val general = "Pass idempotencyKey as 1 to 200 printable ASCII characters."
            assertEquals(general, assertFailsWith<IllegalArgumentException> { customer.recordUsage(credits, 1, "") }.message)
            assertEquals(general, assertFailsWith<IllegalArgumentException> { customer.holdUsage(credits, 1, " k") }.message)
            val hold = "Pass idempotencyKey as 1 to 193 printable ASCII characters."
            assertEquals(hold, assertFailsWith<IllegalArgumentException> { customer.startHold(credits, 1, "k".repeat(194)) }.message)
            assertEquals(hold, assertFailsWith<IllegalArgumentException> { customer.withHold(credits, 1, "k".repeat(194)) { } }.message)
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `startHold answers a handle for held and for an open duplicate`() =
        runTest {
            api.replyFixture("usage-held")
            val hold = customer.startHold(credits, 100, idempotencyKey = "msg-1", ttl = 2.minutes)
            assertEquals("h_1", hold.id)
            assertEquals(100, hold.amount)
            assertFalse(hold.isDuplicate)
            assertEquals(UsageOutcome.Held, hold.result.outcome)
            val request = api.take()
            assertEquals("msg-1", request.headers["Idempotency-Key"])
            assertEquals("""{"feature":"ai_credits","amount":100,"ttlSeconds":120}""", request.bodyText)
            api.reply(body = result("usage-held", "outcome" to "duplicate"))
            assertTrue(customer.startHold(credits, 100, idempotencyKey = "msg-1").isDuplicate)
        }

    @Test
    fun `a refused hold and a settled, released or expired replay fail with the answer`() =
        runTest {
            api.reply(body = result("usage-refused", "holdId" to null))
            val refused = assertFailsWith<EntitlerUsageRefusedException> { customer.startHold(credits, 1000, "k") }
            assertEquals(UsageRefusal.OverAllowance, refused.result.refusal)
            for (answer in listOf(result("usage-settled"), result("usage-released"))) {
                api.reply(body = answer)
                var ran = false
                val replayed = assertFailsWith<EntitlerUsageReplayedException> { customer.withHold(credits, 100, "k") { ran = true } }
                assertFalse(ran)
                assertTrue(replayed.result.outcome in listOf(UsageOutcome.Settled, UsageOutcome.Released))
            }
            assertEquals(3, api.server.requestCount)
        }

    @Test
    fun `finish settles the reported amount, the held amount when none, and 0 after use(0)`() =
        runTest {
            for ((used, settled) in listOf(40L to 40L, null to 100L, 0L to 0L)) {
                api.replyFixture("usage-held")
                api.replyFixture("usage-settled")
                val hold = customer.startHold(credits, 100, "job-$used")
                hold.used = 50
                hold.used = used
                assertEquals(UsageOutcome.Settled, hold.finish().outcome)
                api.take()
                val settle = api.take()
                assertEquals("/customers/u/usage/holds/h_1/settle", settle.path)
                assertEquals("""{"amount":$settled}""", settle.bodyText)
            }
        }

    @Test
    fun `only the first of finish, release and close acts, and later calls answer its result`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val hold = customer.startHold(credits, 100, "job-1")
            val released = hold.release()
            assertSame(released, hold.finish())
            assertSame(released, hold.release())
            hold.close()
            assertEquals(2, api.server.requestCount)
        }

    @Test
    fun `withHold settles the amount the work reports and answers the work's result`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            val answer =
                customer.withHold(credits, 100, idempotencyKey = "job-1") { hold ->
                    assertEquals("h_1", hold.id)
                    hold.used = 40
                    "summary"
                }
            assertEquals("summary", answer)
            assertEquals("job-1", api.take().headers["Idempotency-Key"])
            assertEquals("""{"amount":40}""", api.take().bodyText)
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
    fun `a hold that expired records the whole reported amount as excess and answers that report`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_expired","message":"The hold expired."}}""")
            api.reply(body = result("usage-result", "mode" to "observe", "amount" to 60))
            val hold = customer.startHold(credits, 100, idempotencyKey = "job-3")
            hold.used = 60
            assertEquals(60, hold.finish().amount)
            api.take()
            api.take()
            val report = api.take()
            assertEquals("job-3:excess", report.headers["Idempotency-Key"])
            assertEquals("""{"feature":"ai_credits","amount":60,"mode":"observe"}""", report.bodyText)
        }

    @Test
    fun `failed work settles the amount it reported, or releases when it reported none`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val failure = assertFailsWith<IllegalStateException> { customer.withHold(credits, 100, "j1") { error("model failed") } }
            assertEquals("model failed", failure.message)
            api.take()
            val release = api.take()
            assertEquals("DELETE", release.method)
            assertEquals("/customers/u/usage/holds/h_1", release.path)
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            assertFailsWith<IllegalStateException> {
                customer.withHold(credits, 100, "j2") {
                    it.used = 3000
                    error("disconnected")
                }
            }
            api.take()
            assertEquals("""{"amount":100}""", api.take().bodyText)
        }

    @Test
    fun `a failed release goes to onError while the work's error propagates`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(500, "")
            assertFailsWith<IllegalStateException> { customer.withHold(credits, 100, "k") { error("model failed") } }
            assertEquals(500, (api.errors.single() as EntitlerApiException).status)
        }

    @Test
    fun `cancelled work settles the amount reported so far`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            val started = CompletableDeferred<Unit>()
            val job =
                async {
                    customer.withHold(credits, 100, "k") {
                        it.used = 30
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            api.take()
            assertEquals("""{"amount":30}""", api.take().bodyText)
        }

    @Test
    fun `a used amount outside the range fails`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-released")
            val failure = assertFailsWith<IllegalArgumentException> { customer.withHold(credits, 100, "k") { it.used = -1 } }
            assertEquals("Pass the amount used as a whole number of 0 or more.", failure.message)
            api.take()
            assertEquals("DELETE", api.take().method)
        }

    @Test
    fun `a failed settlement throws with the work's result, and from finish with none`() =
        runTest {
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_settled","message":"Settled with another amount."}}""")
            val output = listOf("kept")
            val failure =
                assertFailsWith<EntitlerUsageSettlementException> {
                    customer.withHold(credits, 100, "k") { hold -> output.also { hold.used = 10 } }
                }
            assertEquals("h_1", failure.holdId)
            assertEquals(10, failure.amount)
            assertNull(failure.excess)
            assertSame(output, failure.result)
            assertEquals(ErrorCode.HoldSettled, (failure.cause as EntitlerApiException).code)
            api.replyFixture("usage-held")
            api.reply(409, """{"error":{"code":"hold_settled","message":"Settled with another amount."}}""")
            val hold = customer.startHold(credits, 100, "k2")
            assertNull(assertFailsWith<EntitlerUsageSettlementException> { hold.finish() }.result)
        }

    @Test
    fun `closing a hold no one finished settles what was reported, else releases, with failures to onError`() =
        runTest {
            api.replyFixture("usage-held")
            api.replyFixture("usage-settled")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                customer.startHold(credits, 100, "k1").use { it.used = 20 }
                api.take()
                assertEquals("""{"amount":20}""", api.take().bodyText)
            }
            api.replyFixture("usage-held")
            api.reply(500, "")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                customer.startHold(credits, 100, "k2").use { }
                api.take()
                assertEquals("DELETE", api.take().method)
                repeat(50) { if (api.errors.isEmpty()) kotlinx.coroutines.delay(20) }
            }
            assertEquals(500, (api.errors.single() as EntitlerApiException).status)
        }

    private fun replyBatch(size: Int) {
        val results = (0 until size).joinToString(",") { """{"index":$it,"outcome":"recorded","id":"u$it","late":false,"error":null}""" }
        api.reply(body = """{"results":[$results],"recorded":$size,"duplicates":0,"errors":0}""")
    }

    @Test
    fun `batches split at 500 events in input order, each request keyed from its events`() =
        runTest {
            val events = (0 until 1001).map { UsageBatchEvent("c${it % 3}", credits, 1, idempotencyKey = "e$it") }
            listOf(500, 500, 1).forEach(::replyBatch)
            val answer = server.recordUsageBatch(events, register = true)
            assertEquals(1001, answer.results.size)
            assertEquals((0 until 1001).toList(), answer.results.map { it.index })
            assertEquals(1001, answer.recorded)
            val requests = List(3) { api.take() }
            val expected = events.chunked(500).map { batchKey(it, register = true) }
            assertEquals(expected, requests.map { it.headers["Idempotency-Key"] })
            assertTrue(expected.all { it.startsWith("batch:") && it.length == 70 })
            val bodies = requests.map { Json.parseToJsonElement(it.bodyText).jsonObject }
            assertEquals(listOf(500, 500, 1), bodies.map { it["events"]!!.jsonArray.size })
            assertTrue(bodies.all { it["register"]!!.jsonPrimitive.content == "true" })
            val first = (bodies[0]["events"] as JsonArray)[0].jsonObject
            assertEquals("e0", first["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(1, first["amount"]!!.jsonPrimitive.int)
            assertEquals(events.map { it.idempotencyKey }, answer.results.map { it.idempotencyKey })
        }

    @Test
    fun `a batch's derived key is the documented SHA-256, and only the same events repeat it`() {
        val events =
            listOf(
                UsageBatchEvent("a/b", credits, 2, "k1", Instant.parse("2026-10-08T10:00:00+10:00")),
                UsageBatchEvent("c", credits, 1, "k2"),
            )
        val text = """["entitler-batch-v1",false,[["a/b","ai_credits",2,"2026-10-08T00:00:00.000Z","k1"],["c","ai_credits",1,null,"k2"]]]"""
        assertEquals("batch:" + sha256(text), batchKey(events, register = false))
        assertEquals(batchKey(events, false), batchKey(events.toList(), false))
        assertTrue(batchKey(events, false) != batchKey(events.take(1), false))
        assertTrue(batchKey(events, false) != batchKey(events, true))
    }

    @Test
    fun `events the SDK refuses are answered error and never sent, and the rest still go`() =
        runTest {
            replyBatch(1)
            val answer =
                server.recordUsageBatch(
                    listOf(
                        UsageBatchEvent(" ", credits, 1, "k0"),
                        UsageBatchEvent("c", credits, 0, "k1"),
                        UsageBatchEvent("c", credits, 1, ""),
                        UsageBatchEvent("c", credits, 1, "k3"),
                    ),
                )
            assertEquals(
                listOf(ErrorCode.InvalidBody, ErrorCode.InvalidAmount, ErrorCode.InvalidIdempotencyKey, null),
                answer.results.map { it.error?.code },
            )
            assertEquals("Provide the id your app uses for the customer.", answer.results[0].error!!.message)
            assertEquals("Pass amount as a whole number from 1 to 9007199254740991.", answer.results[1].error!!.message)
            assertEquals(UsageOutcome.Recorded, answer.results[3].outcome)
            assertEquals(3, answer.errors)
            val sent = Json.parseToJsonElement(api.take().bodyText).jsonObject["events"]!!.jsonArray
            assertEquals(listOf("k3"), sent.map { it.jsonObject["idempotencyKey"]!!.jsonPrimitive.content })
            assertEquals(1, api.server.requestCount)
        }

    @Test
    fun `a failed batch request answers its events with error and the next request still goes`() =
        runTest {
            val events = (0 until 600).map { UsageBatchEvent("c", credits, 1, "e$it") }
            api.reply(403, """{"error":{"code":"scope_required","message":"Needs usage:write."}}""")
            replyBatch(100)
            val answer = server.recordUsageBatch(events)
            assertEquals(600, answer.results.size)
            assertTrue(answer.results.take(500).all { it.outcome == UsageOutcome.Error && it.error?.code == ErrorCode.ScopeRequired })
            assertEquals(500, answer.errors)
            assertEquals(100, answer.recorded)
        }

    @Test
    fun `a batch request with no answer answers connection_failed`() =
        runTest {
            api.server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            val answer =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    server.recordUsageBatch(listOf(UsageBatchEvent("c", credits, 1, "k")))
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
    fun `an empty batch, or one with nothing left to send, sends nothing`() =
        runTest {
            assertEquals(UsageEventsResult(emptyList(), 0, 0, 0), server.recordUsageBatch(emptyList()))
            assertEquals(1, server.recordUsageBatch(listOf(UsageBatchEvent("c", credits, 0, "k"))).errors)
            assertEquals(0, api.server.requestCount)
        }

    @Test
    fun `a replayed batch request marks its events replayed`() =
        runTest {
            api.reply(
                200,
                """{"results":[{"index":0,"outcome":"duplicate","id":"u1","late":false,"error":null},
                {"index":1,"outcome":"error","id":null,"late":false,"error":{"code":"not_metered","message":"Not metered."}}],
                "recorded":0,"duplicates":1,"errors":1}""",
                "Idempotent-Replayed" to "true",
            )
            val answer =
                server.recordUsageBatch(
                    listOf(
                        UsageBatchEvent("a", credits, 2, "k1", Instant.parse("2026-10-08T00:00:00Z")),
                        UsageBatchEvent("b", credits, 1, "k2"),
                    ),
                )
            assertEquals(UsageOutcome.Duplicate, answer.results[0].outcome)
            assertEquals(ErrorCode.NotMetered, answer.results[1].error!!.code)
            assertTrue(answer.results.all { it.replayed })
            val body = api.take().bodyText
            assertFalse(body.contains("register"))
            assertTrue(body.contains(""""occurredAt":"2026-10-08T00:00:00.000Z""""))
        }

    @Test
    fun `a replayed write answers replayed`() =
        runTest {
            api.reply(200, fixture("usage-result"), "Idempotent-Replayed" to "true")
            assertTrue(customer.recordUsage(credits, 1, "k").replayed)
            api.replyFixture("usage-result")
            assertFalse(customer.recordUsage(credits, 1, "k").replayed)
        }

    @Test
    fun `a cancel and a meter adjustment answer usage results`() =
        runTest {
            api.reply(body = result("usage-result", "outcome" to "cancelled", "meterChange" to -5))
            assertEquals(-5, customer.cancelUsage("u_1").meterChange)
            api.reply(body = result("usage-result", "outcome" to "adjusted", "id" to null))
            val set = customer.adjustMeter(credits, idempotencyKey = "t1", to = 0)
            assertEquals(UsageOutcome.Adjusted, set.outcome)
            assertNull(set.id)
            assertFailsWith<IllegalArgumentException> { customer.cancelUsage("") }
        }
}
