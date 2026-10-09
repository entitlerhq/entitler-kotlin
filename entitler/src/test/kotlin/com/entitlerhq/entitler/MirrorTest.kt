package com.entitlerhq.entitler

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MirrorTest {
    private fun <T> mirrors(
        fixture: String,
        deserializer: DeserializationStrategy<T>,
    ) {
        val decoded = decode(fixture(fixture), deserializer)
        compare(decoded, fixtureJson(fixture), fixture)
    }

    private fun compare(
        value: Any?,
        json: JsonElement,
        path: String,
    ) {
        when (json) {
            is JsonNull -> {
                assertNull(value, path)
            }

            is JsonArray -> {
                val list = value as? List<*> ?: fail("$path is not a list")
                assertEquals(json.size, list.size, path)
                json.forEachIndexed { index, element -> compare(list[index], element, "$path[$index]") }
            }

            is JsonObject -> {
                compareObject(value, json, path)
            }

            is JsonPrimitive -> {
                comparePrimitive(value, json, path)
            }
        }
    }

    private fun compareObject(
        value: Any?,
        json: JsonObject,
        path: String,
    ) {
        if (value is Map<*, *>) {
            assertEquals(json.keys, value.keys, path)
            json.forEach { (key, element) -> compare(value[key], element, "$path.$key") }
            return
        }
        val target = value ?: fail("$path is null")
        val getters =
            target.javaClass.methods.filter {
                it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" && !Modifier.isStatic(it.modifiers)
            }
        assertTrue(getters.isNotEmpty(), path)
        for (getter in getters) {
            val property = getter.name.removePrefix("get").replaceFirstChar { it.lowercase() }
            val key = if (property == "timing") "when" else property
            val result = getter.invoke(target)
            val element = json[key]
            if (element == null) {
                assertTrue(result == null || result == false || result == emptyList<Any>(), "$path.$key is missing but decoded as $result")
            } else {
                compare(result, element, "$path.$key")
            }
        }
    }

    private fun comparePrimitive(
        value: Any?,
        json: JsonPrimitive,
        path: String,
    ) {
        val expected = json.content
        val actual =
            when (value) {
                is Instant -> {
                    return assertEquals(OffsetDateTime.parse(expected).toInstant(), value, path)
                }

                FeatureValue.On -> {
                    "true"
                }

                FeatureValue.Unlimited -> {
                    "unlimited"
                }

                is FeatureValue.Amount -> {
                    value.value.toString()
                }

                is String, is Number, is Boolean -> {
                    value.toString()
                }

                null -> {
                    fail("$path is null, expected $expected")
                }

                else -> {
                    value.javaClass
                        .getMethod("getValue")
                        .invoke(value)
                        .toString()
                }
            }
        assertEquals(expected, actual, path)
    }

    @Test
    fun `every field of every answer decodes`() {
        mirrors("check-full", Check.serializer())
        mirrors("check-metered", MeteredCheck.serializer())
        mirrors("check-beta", Check.serializer())
        mirrors("entitlements", Entitlements.serializer())
        mirrors("plan-space", PlanSpace.serializer())
        mirrors("pricing-full", Pricing.serializer())
        mirrors("customer-pricing", Pricing.serializer())
        mirrors("features", FeatureList.serializer())
        mirrors("usage", CustomerUsage.serializer())
        mirrors("usage-result", UsageResult.serializer())
        mirrors("usage-refused", UsageResult.serializer())
        mirrors("usage-held", UsageResult.serializer())
        mirrors("hold", UsageHold.serializer())
        mirrors("details-full", CustomerDetail.serializer())
        mirrors("change", CustomerDetail.serializer())
        mirrors("customers", CustomerPage.serializer())
        mirrors("summary", CustomerSummary.serializer())
        mirrors("registered", RegisteredCustomer.serializer())
        mirrors("token", IssuedCustomerToken.serializer())
        mirrors("snapshot", IssuedSnapshot.serializer())
        mirrors("snapshot-keys", SnapshotKeys.serializer())
        mirrors("track", CustomerTrack.serializer())
        mirrors("billing", CustomerBilling.serializer())
        mirrors("billing-full", CustomerBilling.serializer())
        mirrors("providers", CustomerProviders.serializer())
        mirrors("providers-full", CustomerProviders.serializer())
        mirrors("provider-page", ProviderPage.serializer())
    }
}
