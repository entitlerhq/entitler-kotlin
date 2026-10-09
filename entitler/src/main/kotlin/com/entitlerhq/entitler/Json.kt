package com.entitlerhq.entitler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private val rfc3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

internal fun formatInstant(instant: Instant): String = rfc3339.format(instant)

internal fun parseInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("com.entitlerhq.entitler.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val value = decoder.decodeString()
        return try {
            parseInstant(value)
        } catch (error: DateTimeParseException) {
            throw SerializationException("Expected an RFC 3339 instant.", error)
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(formatInstant(value))
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

internal fun encodeComponent(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeUtf8().toByteArray()) {
        val char = byte.toInt().toChar()
        if (byte >= 0 && char in UNRESERVED) {
            out.append(char)
        } else {
            out.append('%').append(ByteString.of(byte).hex().uppercase())
        }
    }
    return out.toString()
}

internal fun decodeJwtPart(part: String): JsonObject? =
    part
        .decodeBase64()
        ?.utf8()
        ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

internal fun claimsOf(token: String): JsonObject? {
    val parts = token.split('.')
    return if (parts.size == 3) decodeJwtPart(parts[1]) else null
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
