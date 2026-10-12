package com.entitlerhq.entitler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.encoding.Base64

internal val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private val typedPrimitives = setOf("kotlin.Boolean", "kotlin.Int", "kotlin.Long", "kotlin.Double")

/** Refuses an answer that leaves out a field the document requires, nullable ones included. */
private fun requireElements(
    descriptor: SerialDescriptor,
    fields: JsonObject,
) {
    for (index in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(index)
        if (!descriptor.isElementOptional(index) && name !in fields) throw SerializationException("Expected $name.")
    }
}

/**
 * Refuses a quoted boolean or number where the document has a JSON boolean or number, which tree
 * decoding would otherwise read from the string's content.
 */
internal fun checkShape(
    descriptor: SerialDescriptor,
    element: JsonElement,
) {
    if (element is JsonNull) return
    val name = descriptor.serialName.removeSuffix("?")
    if (name in typedPrimitives && (element !is JsonPrimitive || element.isString)) {
        throw SerializationException("Expected a JSON ${name.removePrefix("kotlin.")}.")
    }
    val children =
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                (element as? JsonObject)?.let { fields ->
                    requireElements(descriptor, fields)
                    (0 until descriptor.elementsCount).mapNotNull { index ->
                        fields[descriptor.getElementName(index)]?.let { descriptor.getElementDescriptor(index) to it }
                    }
                }
            }

            StructureKind.LIST -> {
                (element as? JsonArray)?.map { descriptor.getElementDescriptor(0) to it }
            }

            StructureKind.MAP -> {
                (element as? JsonObject)?.values?.map { descriptor.getElementDescriptor(1) to it }
            }

            else -> {
                null
            }
        }
    children?.forEach { (child, value) -> checkShape(child, value) }
}

private val rfc3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private const val MIN_YEAR = 1
private const val MAX_YEAR = 9999

internal fun isInRange(instant: Instant): Boolean =
    runCatching { instant.atOffset(ZoneOffset.UTC).year in MIN_YEAR..MAX_YEAR }.getOrDefault(false)

internal fun formatInstant(instant: Instant): String = rfc3339.format(instant)

internal fun parseInstant(value: String): Instant? =
    runCatching {
        OffsetDateTime.parse(value).toInstant()
    }.getOrNull()?.takeIf(::isInRange)

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("com.entitlerhq.entitler.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        parseInstant(decoder.decodeString()) ?: throw SerializationException("Expected an RFC 3339 instant in the years 0001 to 9999.")

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(formatInstant(value))
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private const val HEX_DIGITS = "0123456789ABCDEF"
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0xF
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80

internal fun encodeComponent(value: String): String {
    require(isValidUnicode(value)) { "Pass an id that is valid Unicode." }
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and BYTE_MASK
        val char = unsigned.toChar()
        if (unsigned < ASCII_LIMIT && char in UNRESERVED) {
            out.append(char)
        } else {
            out.append('%').append(HEX_DIGITS[unsigned shr NIBBLE]).append(HEX_DIGITS[unsigned and LOW_NIBBLE])
        }
    }
    return out.toString()
}

internal fun pathSegment(id: String): String {
    require(id.isEmpty() || id.any { it != '.' }) { "Pass an id that is not made only of dots." }
    return encodeComponent(id)
}

private fun isValidUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isLowSurrogate()) return false
        if (char.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index++
        }
        index++
    }
    return true
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

internal fun decodeBase64Url(value: String): ByteArray? = runCatching { base64Url.decode(value) }.getOrNull()

internal fun encodeBase64Url(bytes: ByteArray): String = base64Url.encode(bytes)

internal fun decodeJwtPart(part: String): JsonObject? =
    decodeBase64Url(part)
        ?.decodeToString()
        ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

internal fun claimsOf(token: String): JsonObject? {
    val parts = token.split('.')
    return if (parts.size == 3) decodeJwtPart(parts[1]) else null
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

internal fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") {
            val unsigned = it.toInt() and BYTE_MASK
            "${HEX_DIGITS[unsigned shr NIBBLE]}${HEX_DIGITS[unsigned and LOW_NIBBLE]}"
        }.lowercase()
