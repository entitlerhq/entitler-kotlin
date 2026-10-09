package com.entitlerhq.entitler

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The conformance cases for values, errors and offline snapshots (sections 6.2, 8, 3.5 and 9). */
class ConformanceDecodingTest {
    @TestFactory
    fun values() = conformanceTests("values.json", ::value)

    @TestFactory
    fun errors() = conformanceTests("errors.json", ::error)

    @TestFactory
    fun snapshots() = conformanceTests("snapshots.json", ::snapshot)

    private fun value(case: JsonObject) =
        runTest {
            val transport = FakeTransport { 0 }
            val headers = case["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            transport.answer = { Canned.Status(200, headers, case["answer"]!!.toString()) }
            val path = case["request"]!!.jsonObject.str("path")!!.split('/')
            EntitlerServer(CONFORMANCE_KEY) {
                httpClient = transport.client
                maxRetries = 0
            }.use { server ->
                val result = runCatching { server.customer(path[2]).check(path[4]) }
                val outcome = case["outcome"]!!.jsonObject
                when (case.str("kind")) {
                    "value" -> {
                        val answer = outcome["answer"]!!.jsonObject
                        if (answer.str("kind") == "ApiError") {
                            assertNull(featureValueOf(outcome["value"]!!))
                            assertApiError(answer, result.exceptionOrNull())
                        } else {
                            val check = result.getOrThrow()
                            assertEquals(featureValueOf(outcome["value"]!!), check.value)
                            assertEquals(featureValueOf(answer["value"]!!), check.value)
                            assertEquals(answer["entitled"]!!.jsonPrimitive.boolean, check.entitled)
                        }
                    }

                    "check" -> {
                        assertCheck(outcome, result.getOrThrow())
                    }

                    else -> {
                        if (outcome.containsKey("instant")) {
                            val check = result.getOrThrow()
                            val actual = if (case.str("field") == "asOf") check.asOf else check.resetsAt
                            assertEquals(instantOf(outcome["instant"]!!), actual)
                        } else {
                            assertApiError(outcome, result.exceptionOrNull())
                        }
                    }
                }
            }
        }

    private fun assertCheck(
        expected: JsonObject,
        check: Check,
    ) {
        assertEquals(expected.str("customer"), check.customer)
        assertEquals(expected.str("feature"), check.feature)
        assertOpen(expected["type"]!!, check.type, "type")
        assertEquals(expected["entitled"]!!.jsonPrimitive.boolean, check.entitled)
        assertEquals(featureValueOf(expected["value"]!!), check.value)
        assertMeters(expected, check.used, check.remaining, check.held, check.resetsAt)
        val environment = expected["environment"]!!.jsonObject
        assertEquals(environment.str("id"), check.environment.id)
        assertEquals(environment.str("name"), check.environment.name)
        assertOpen(environment["kind"]!!, check.environment.kind, "environment.kind")
        assertTrack(expected["track"]!!, check.track)
        assertEquals(longOf(expected["release"]), check.release)
        assertEquals(stringOf(expected["change"]), check.change)
        assertEquals(expected["testers"]!!.jsonPrimitive.boolean, check.testers)
        assertExperiment(expected["experiment"]!!, check.experiment)
        assertEquals(instantOf(expected["asOf"]!!), check.asOf)
        assertEquals(expected["stale"]!!.jsonPrimitive.boolean, check.stale)
    }

    private fun error(case: JsonObject) =
        runTest {
            val clock = FakeClock(case.str("receivedAt")?.let(::millisOf) ?: millisOf(CONFORMANCE_NOW))
            val transport = FakeTransport(clock::now)
            val headers = case["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            transport.answer = { Canned.Status(case["status"]!!.jsonPrimitive.int, headers, case.str("body")!!) }
            val call = case["call"]!!.jsonObject
            EntitlerServer(CONFORMANCE_KEY) {
                httpClient = transport.client
                this.clock = clock::now
                maxRetries = 0
            }.use { server ->
                val customer = server.customer(call.str("customer")!!)
                val error =
                    runCatching {
                        when (call.str("method")) {
                            "check" -> {
                                customer.check(call.str("feature")!!)
                            }

                            "recordUsage" -> {
                                customer.recordUsage(
                                    Feature.Metered(call.str("feature")!!),
                                    call["amount"]!!.jsonPrimitive.long,
                                    idempotencyKey = call.str("idempotencyKey"),
                                )
                            }

                            "subscribe" -> {
                                customer.subscribe(call.str("plan")!!, call.str("period"), idempotencyKey = call.str("idempotencyKey"))
                            }

                            else -> {
                                fail("Unknown call.")
                            }
                        }
                    }.exceptionOrNull()
                assertApiError(case["outcome"]!!.jsonObject, error)
            }
        }

    private fun assertApiError(
        expected: JsonObject,
        error: Throwable?,
    ) {
        assertEquals("ApiError", expected.str("kind"))
        val failure = assertIs<EntitlerApiException>(error)
        assertEquals(expected["status"]!!.jsonPrimitive.int, failure.status, "status")
        assertOpen(expected["code"]!!, failure.code, "code")
        assertEquals(expected.str("message"), failure.message, "message")
        if (!expected.containsKey("requestId")) return
        assertEquals(stringOf(expected["requestId"]), failure.requestId, "requestId")
        assertEquals(longOf(expected["retryAfterSeconds"]), failure.retryAfter?.inWholeSeconds, "retryAfterSeconds")
        assertEquals(stringOf(expected["idempotencyKey"]), failure.idempotencyKey, "idempotencyKey")
        val payment = expected["payment"]
        if (payment == null || payment is JsonNull) {
            assertNull(failure.payment, "payment")
        } else {
            val actual = assertNotNull(failure.payment, "payment")
            assertOpen(payment.jsonObject["status"]!!, actual.status, "payment.status")
            assertEquals(stringOf(payment.jsonObject["url"]), actual.url, "payment.url")
        }
        val gaps = jsonArrayOf(expected["listingGaps"]).map { it.jsonObject }
        assertEquals(gaps.size, failure.listingGaps.size, "listingGaps")
        gaps.zip(failure.listingGaps).forEach { (gap, actual) ->
            assertOpen(gap["kind"]!!, actual.kind, "listingGaps.kind")
            assertEquals(gap.str("plan"), actual.plan)
            assertEquals(gap.str("key"), actual.key)
            assertEquals(gap.str("period"), actual.period)
            assertChannel(gap["channel"]!!, actual.channel)
        }
        val problems = jsonArrayOf(expected["listingProblems"]).map { it.jsonObject }
        assertEquals(problems.size, failure.listingProblems.size, "listingProblems")
        problems.zip(failure.listingProblems).forEach { (problem, actual) ->
            assertChannel(problem["channel"]!!, actual.channel)
            assertEquals(problem.str("plan"), actual.plan)
            assertEquals(problem.str("period"), actual.period)
            assertEquals(problem["ids"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }, actual.ids)
            assertOpen(problem["problem"]!!, actual.problem, "listingProblems.problem")
        }
    }

    private fun assertChannel(
        expected: kotlinx.serialization.json.JsonElement,
        actual: Channel?,
    ) {
        if (expected is JsonNull) {
            assertNull(actual, "channel")
        } else {
            val channel = assertNotNull(actual, "channel")
            assertEquals(expected.jsonObject.str("provider"), channel.provider)
            assertEquals(expected.jsonObject.str("connectionId"), channel.connectionId)
        }
    }

    private fun snapshot(case: JsonObject) {
        val expected = case["expected"]!!.jsonObject
        val keys = expected["keys"]!!
        val token = case.str("token")!!
        val customer = expected.str("customer")!!
        val environment = expected.str("environment")!!
        val issuer = expected.str("issuer") ?: SNAPSHOT_ISSUER
        val now = Instant.ofEpochMilli(millisOf(expected.str("now")!!))
        val skew = expected["clockSkewSeconds"]!!.jsonPrimitive.int
        val result =
            runCatching {
                if (keys is JsonObject) {
                    verifySnapshot(
                        token,
                        json.decodeFromJsonElement(SnapshotKeys.serializer(), keys),
                        customer,
                        environment,
                        issuer,
                        now,
                        skew,
                    )
                } else {
                    val list = json.decodeFromJsonElement(ListSerializer(SnapshotKey.serializer()), keys)
                    verifySnapshot(token, list, customer, environment, issuer, now, skew)
                }
            }
        val outcome = case["outcome"]!!.jsonObject
        when (outcome.str("kind")) {
            "ArgumentError" -> {
                assertArgumentError(outcome, result.exceptionOrNull())
            }

            "SnapshotError" -> {
                val error = assertIs<EntitlerSnapshotException>(result.exceptionOrNull())
                assertOpen(outcome["code"]!!, error.code, "code")
                assertEquals(outcome.str("message"), error.message)
            }

            else -> {
                assertSnapshot(outcome["snapshot"]!!.jsonObject, result.getOrThrow())
            }
        }
    }

    private fun assertSnapshot(
        expected: JsonObject,
        snapshot: VerifiedSnapshot,
    ) {
        assertEquals(expected.str("customer"), snapshot.customer)
        assertEquals(expected["environment"]!!.jsonObject.str("id"), snapshot.environment.id)
        assertTrack(expected["track"]!!, snapshot.track)
        assertEquals(longOf(expected["release"]), snapshot.release)
        assertEquals(stringOf(expected["change"]), snapshot.change)
        assertEquals(expected["testers"]!!.jsonPrimitive.boolean, snapshot.testers)
        assertEquals(instantOf(expected["expiresAt"]!!), snapshot.expiresAt)
        val entitlements = expected["entitlements"]!!.jsonObject
        assertEquals(instantOf(entitlements["asOf"]!!), snapshot.entitlements.asOf)
        assertExperiment(entitlements["experiment"]!!, snapshot.entitlements.experiment)
        val items = entitlements["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(items.map { it.str("key") }, snapshot.entitlements.entitlements.map { it.key })
        items.zip(snapshot.entitlements.entitlements).forEach { (item, actual) -> assertEntitlement(item, actual) }
        for ((key, has) in entitlements["has"]!!.jsonObject) {
            assertEquals(has.jsonPrimitive.boolean, snapshot.entitlements.has(key), "has($key)")
        }
        for ((key, item) in entitlements["get"]!!.jsonObject) {
            val actual = snapshot.entitlements[key]
            if (item is JsonNull) {
                assertNull(
                    actual,
                    "get($key)",
                )
            } else {
                assertEntitlement(item.jsonObject, assertNotNull(actual, "get($key)"))
            }
        }
    }
}
