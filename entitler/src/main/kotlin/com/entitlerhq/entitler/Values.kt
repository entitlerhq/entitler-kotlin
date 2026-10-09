package com.entitlerhq.entitler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A feature's value for a customer: on, an amount, or unlimited.
 *
 * Whether the customer is entitled is the server's decision, carried as `entitled` beside the value.
 *
 * ```kotlin
 * val seats = when (val value = check.value) {
 *     FeatureValue.On -> "Included"
 *     is FeatureValue.Amount -> "${value.value} seats"
 *     FeatureValue.Unlimited -> "Unlimited seats"
 * }
 * ```
 */
@Serializable(with = FeatureValueSerializer::class)
public sealed interface FeatureValue {
    /** An on/off feature that is on (JSON `true`). */
    public data object On : FeatureValue

    /** A whole amount in the feature's unit, `0` included. */
    public data class Amount(
        /** The amount. */
        public val value: Long,
    ) : FeatureValue,
        Limit

    /** No limit (JSON `"unlimited"`). */
    public data object Unlimited : FeatureValue, Limit
}

/** A limit that is an amount or unlimited, such as what remains of a metered feature. */
@Serializable(with = LimitSerializer::class)
public sealed interface Limit

internal object FeatureValueSerializer : KSerializer<FeatureValue> {
    override val descriptor = PrimitiveSerialDescriptor("com.entitlerhq.entitler.FeatureValue", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): FeatureValue {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonPrimitive && !element.isString && element.booleanOrNull == true) return FeatureValue.On
        return decodeLimit(element) as FeatureValue
    }

    override fun serialize(encoder: Encoder, value: FeatureValue) {
        when (value) {
            FeatureValue.On -> encoder.encodeBoolean(true)
            is FeatureValue.Amount -> encoder.encodeLong(value.value)
            FeatureValue.Unlimited -> encoder.encodeString(UNLIMITED)
        }
    }
}

internal object LimitSerializer : KSerializer<Limit> {
    override val descriptor = PrimitiveSerialDescriptor("com.entitlerhq.entitler.Limit", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Limit = decodeLimit((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: Limit) = FeatureValueSerializer.serialize(encoder, value as FeatureValue)
}

private const val UNLIMITED = "unlimited"

private fun decodeLimit(element: kotlinx.serialization.json.JsonElement): Limit {
    if (element is JsonPrimitive) {
        if (element.isString && element.content == UNLIMITED) return FeatureValue.Unlimited
        if (!element.isString) element.longOrNull?.let { return FeatureValue.Amount(it) }
    }
    throw SerializationException("Expected true, a whole number or \"unlimited\".")
}

/**
 * A feature in the catalogue, named by its key.
 *
 * Generate constants for every feature with `entitler generate`, or declare one by hand:
 *
 * ```kotlin
 * val aiCredits = Feature.Metered("ai_credits")
 * val exportPdf = Feature.OnOff("export_pdf")
 * ```
 *
 * The subtype carries the feature's type into the answer: `check(Feature.Metered)` answers a
 * [MeteredCheck], and usage methods take only [Feature.Metered].
 */
public sealed class Feature {
    /** The feature's key, such as `ai_credits`. */
    public abstract val key: String

    /** The feature's type. */
    public abstract val type: FeatureType

    /** A feature that is on or off. */
    public data class OnOff(
        override val key: String,
    ) : Feature() {
        init {
            requireKey(key)
        }

        override val type: FeatureType get() = FeatureType.Boolean
    }

    /** A setting with an amount, such as seats or hours. */
    public data class Setting(
        override val key: String,
    ) : Feature() {
        init {
            requireKey(key)
        }

        override val type: FeatureType get() = FeatureType.Config
    }

    /** A metered feature with an allowance that usage counts against. */
    public data class Metered(
        override val key: String,
    ) : Feature() {
        init {
            requireKey(key)
        }

        override val type: FeatureType get() = FeatureType.Metered
    }

    /** A group of features, entitled when the customer is entitled to every member. */
    public data class Group(
        override val key: String,
        /** The group's leaf member keys, for documentation. */
        val includes: List<String> = emptyList(),
    ) : Feature() {
        init {
            requireKey(key)
        }

        override val type: FeatureType get() = FeatureType.Group
    }

    /** A feature of a type this SDK does not know yet. */
    public data class Unknown(
        override val key: String,
        override val type: FeatureType,
    ) : Feature() {
        init {
            requireKey(key)
        }
    }
}

internal const val FEATURE_REQUIRED = "Name the feature by its key."

private fun requireKey(key: String) = require(key.isNotBlank()) { FEATURE_REQUIRED }
