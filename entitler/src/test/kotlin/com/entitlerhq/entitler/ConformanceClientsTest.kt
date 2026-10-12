package com.entitlerhq.entitler

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.TestFactory
import java.io.Closeable
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

/** The conformance cases for client construction and for write and billing answers (sections 4.3, 5.1 to 5.5, 6.2 and 7.2). */
class ConformanceClientsTest {
    @TestFactory
    fun construction() = conformanceTests("construction.json", ::construction)

    @TestFactory
    fun answers() = conformanceTests("answers.json", ::answer)

    private fun construction(case: JsonObject) {
        val credentials = case["credentials"]!!.jsonObject
        val outcome = case["outcome"]!!.jsonObject
        val result = runCatching { build(case.str("client")!!, credentials, case.str("cache") == "customStore") }
        if (outcome.str("kind") == "ArgumentError") {
            assertArgumentError(outcome, result.exceptionOrNull())
            return
        }
        val client = result.getOrThrow()
        client.use {
            assertTrue(outcome["valid"]!!.jsonPrimitive.booleanOrNull == true)
            val shown = client.toString()
            for (omitted in outcome["stringFormOmits"]!!.jsonArray) {
                assertFalse(shown.contains(omitted.jsonPrimitive.content), "$shown shows a credential")
            }
        }
    }

    private fun build(
        client: String,
        credentials: JsonObject,
        customStore: Boolean,
    ): Closeable {
        val key = credentials.str("key")
        val token = credentials["token"]
        val identityToken = credentials["identityToken"]
        if (client == "EntitlerServer") {
            assertFalse(customStore)
            return EntitlerServer(key!!) { cache = UnusedStore }
        }
        assertFalse(customStore, "Kotlin's in-app clients cannot be given a custom store.")
        val provider: suspend () -> String = { fail("Construction never calls a token provider.") }
        return when {
            token != null && key == null && identityToken == null -> {
                if (token is JsonObject) EntitlerClient(provider) else EntitlerClient(token.jsonPrimitive.content)
            }

            key != null && identityToken != null && token == null -> {
                if (identityToken is JsonObject) {
                    EntitlerIdentityClient(key, provider)
                } else {
                    EntitlerIdentityClient(key, identityToken.jsonPrimitive.content)
                }
            }

            key != null && token == null -> {
                EntitlerPublishableClient(key)
            }

            else -> {
                fail("Kotlin's factories cannot be given this combination.")
            }
        }
    }

    private object UnusedStore : CacheStore {
        override suspend fun get(key: String): CacheEntry? = null

        override suspend fun set(
            key: String,
            entry: CacheEntry,
            ttl: Duration,
        ) = Unit
    }

    private fun answer(case: JsonObject) =
        runTest {
            val transport = FakeTransport { millisOf(CONFORMANCE_NOW) }
            val headers = case["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            transport.answer = { Canned.Status(case["status"]!!.jsonPrimitive.int, headers, case.str("body")!!) }
            val call = case["call"]!!.jsonObject
            EntitlerServer(CONFORMANCE_KEY) {
                httpClient = transport.client
                maxRetries = 0
                cache = null
            }.use { server ->
                val result = runCatching { answerCall(server.customer(call.str("customer")!!), call) }
                val outcome = case["outcome"]!!.jsonObject
                if (outcome.str("kind") == "ApiError") {
                    assertAnswerError(outcome, result.exceptionOrNull())
                } else {
                    val answer = result.getOrThrow()
                    if (answer is SubscribeStep) assertStep(outcome, answer) else assertMatches(outcome, answer, "answer")
                }
            }
        }

    private suspend fun answerCall(
        customer: ServerCustomer,
        call: JsonObject,
    ): Any {
        val key = call.str("idempotencyKey")
        return when (call.str("method")) {
            "check" -> {
                customer.check(call.str("feature")!!)
            }

            "plans" -> {
                customer.plans()
            }

            "recordUsage" -> {
                customer.recordUsage(Feature.Metered(call.str("feature")!!), call["amount"]!!.jsonPrimitive.long, idempotencyKey = key!!)
            }

            else -> {
                billingCall(customer, call, key)
            }
        }
    }

    private suspend fun billingCall(
        customer: ServerCustomer,
        call: JsonObject,
        key: String?,
    ): Any =
        when (call.str("method")) {
            "subscribe" -> {
                customer.subscribe(call.str("plan")!!, call.str("period"), returnUrl = call.str("returnUrl"), idempotencyKey = key)
            }

            "cancel" -> {
                customer.cancel(addOn = call.str("addOn"), idempotencyKey = key)
            }

            "undoPendingChange" -> {
                customer.undoPendingChange(idempotencyKey = key)
            }

            "setPlan" -> {
                customer.setPlan(
                    call.str("plan")!!,
                    period = call.str("period"),
                    until = call.str("until")?.let(Instant::parse),
                    reason = call.str("reason"),
                    actor = call.str("actor"),
                    idempotencyKey = key,
                )
            }

            "setAddOn" -> {
                customer.setAddOn(call.str("addOn")!!, call["quantity"]!!.jsonPrimitive.long, idempotencyKey = key)
            }

            "grant" -> {
                customer.grant(
                    Feature.Config(call.str("feature")!!),
                    value = call["value"]?.jsonPrimitive?.longOrNull?.let { FeatureValue.Amount(it) },
                    days = call["days"]?.jsonPrimitive?.int,
                    actor = call.str("actor"),
                    reason = call.str("reason"),
                    idempotencyKey = key,
                )
            }

            "revokeGrant" -> {
                customer.revokeGrant(call.str("grantId")!!, idempotencyKey = key)
            }

            "billingPortal" -> {
                customer.billingPortal(call.str("returnUrl")!!)
            }

            "syncBilling" -> {
                customer.syncBilling()
            }

            else -> {
                fail("Unknown method.")
            }
        }

    private fun assertStep(
        expected: JsonObject,
        step: SubscribeStep,
    ) {
        val next = expected["next"]!!
        when (step) {
            is SubscribeStep.Done -> {
                assertEquals("done", next.jsonPrimitive.content)
                assertMatches(JsonObject(expected - "next"), step.change, "change")
            }

            is SubscribeStep.Pay -> {
                assertEquals("pay", next.jsonPrimitive.content)
                assertEquals(expected.str("url"), step.url)
            }

            SubscribeStep.Confirming -> {
                assertEquals("confirming", next.jsonPrimitive.content)
            }

            is SubscribeStep.Manage -> {
                assertEquals("manage", next.jsonPrimitive.content)
                assertOpen(expected["billedBy"]!!, step.billedBy, "billedBy")
            }

            is SubscribeStep.Unknown -> {
                assertEquals(next.jsonObject.str("unknown"), step.value)
            }
        }
    }

    private fun assertAnswerError(
        expected: JsonObject,
        error: Throwable?,
    ) {
        val failure = assertIs<EntitlerApiException>(error)
        assertEquals(expected["status"]!!.jsonPrimitive.int, failure.status, "status")
        assertOpen(expected["code"]!!, failure.code, "code")
        assertEquals(expected.str("message"), failure.message, "message")
        assertEquals(stringOf(expected["requestId"]), failure.requestId, "requestId")
        assertEquals(longOf(expected["retryAfterSeconds"]), failure.retryAfter?.inWholeSeconds, "retryAfterSeconds")
        assertEquals(stringOf(expected["idempotencyKey"]), failure.idempotencyKey, "idempotencyKey")
        assertNull(failure.payment, "payment")
    }

    /** Compares a decoded answer with the case's JSON, field by field, through each property's getter. */
    private fun assertMatches(
        expected: JsonElement,
        actual: Any?,
        path: String,
    ) {
        when {
            expected is JsonNull -> {
                assertNull(actual, path)
            }

            actual is Instant -> {
                assertEquals(instantOf(expected), actual, path)
            }

            actual is Limit -> {
                assertEquals<Any?>(featureValueOf(expected), actual, path)
            }

            expected is JsonArray -> {
                val list = assertIs<List<*>>(actual, path)
                assertEquals(expected.size, list.size, "$path size")
                expected.zip(list).forEachIndexed { index, (item, value) -> assertMatches(item, value, "$path[$index]") }
            }

            expected is JsonObject && expected.containsKey("unknown") && expected.size == 1 -> {
                assertOpen(expected, assertNotNull(actual, path), path)
            }

            expected is JsonObject && actual is Map<*, *> -> {
                assertEquals(expected.mapValues { it.value.jsonPrimitive.content }, actual, path)
            }

            expected is JsonObject -> {
                assertNotNull(actual, path)
                val absent = expected["absent"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                absent.forEach { assertNull(property(actual, it), "$path.$it is absent") }
                for ((name, value) in expected - "absent") assertMatches(value, property(actual, name), "$path.$name")
            }

            else -> {
                assertPrimitive(expected.jsonPrimitive, actual, path)
            }
        }
    }

    private fun assertPrimitive(
        expected: JsonPrimitive,
        actual: Any?,
        path: String,
    ) {
        when {
            expected.isString && actual !is String -> assertOpen(expected, assertNotNull(actual, path), path)
            expected.isString -> assertEquals(expected.content, actual, path)
            expected.booleanOrNull != null -> assertEquals(expected.booleanOrNull, actual, path)
            else -> assertEquals(expected.long, (actual as? Number)?.toLong(), path)
        }
    }

    private fun property(
        owner: Any,
        name: String,
    ): Any? {
        val field = if (name == "when") "timing" else name
        val capital = field.replaceFirstChar { it.uppercase() }
        val getter =
            owner.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get$capital" || it.name == "is$capital" || it.name == field)
            } ?: fail("${owner.javaClass.simpleName} has no $name.")
        return getter.invoke(owner)
    }
}
