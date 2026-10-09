@file:JvmName("Entitler")
@file:JvmMultifileClass

package com.entitlerhq.entitler

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.time.Instant

/**
 * A public key that verifies snapshots, as a JSON Web Key.
 *
 * @property kty The key type, `EC`.
 * @property crv The curve, `P-256`.
 * @property x The point's x coordinate, base64url.
 * @property y The point's y coordinate, base64url.
 * @property kid The key's id.
 * @property alg The algorithm, `ES256`.
 * @property use The key's use, `sig`.
 */
@Serializable
public data class SnapshotKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val kid: String,
    val alg: String,
    val use: String,
)

/**
 * The keys that verify snapshots, from `snapshotKeys()`.
 *
 * Ship them with your app (fetched at build time) and replace them only with keys fetched from
 * Entitler over HTTPS. Never store them beside the snapshot or load them from the same record: anyone
 * who can edit that record could replace both.
 *
 * @property keys The keys.
 */
@Serializable
public data class SnapshotKeys(
    val keys: List<SnapshotKey>,
)

/**
 * The environment a snapshot was signed in.
 *
 * @property id The environment's id.
 */
public data class SnapshotEnvironment(
    val id: String,
)

/**
 * A snapshot that passed verification. Meters in it are frozen at the instant it was signed.
 *
 * @property customer The customer's external id.
 * @property environment The environment.
 * @property track The customer's track.
 * @property release The catalogue release, or null when the track follows a change.
 * @property change The change the track follows, or null.
 * @property testers True on a track for testers and in a test environment.
 * @property expiresAt When it stops verifying.
 * @property entitlements The customer's entitlements, groups included, as of the instant it was signed.
 */
public data class VerifiedSnapshot(
    val customer: String,
    val environment: SnapshotEnvironment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val expiresAt: Instant,
    val entitlements: Entitlements,
)

private const val SNAPSHOT_TYPE = "entitlements+jwt"
private const val NOT_A_SNAPSHOT = "That is not an entitlements snapshot. Pass the token snapshot() returned."
private const val SIGNATURE_BYTES = 64
private const val MAX_SKEW_SECONDS = 300
private const val MILLIS = 1000L

/**
 * Verifies a snapshot from `snapshot()` with no request, against keys from `snapshotKeys()`.
 *
 * ```kotlin
 * val verified = verifySnapshot(stored.token, bundledKeys, customer = userId, environment = environmentId)
 * if (verified.entitlements.has(Features.exportPdf)) showExportButton()
 * ```
 *
 * @param customer The customer the app expects.
 * @param environment The id of the environment the app expects.
 * @param clockSkewSeconds How far the snapshot's signing instant may be ahead of [now], in seconds from 0 to 300.
 * @throws EntitlerSnapshotException when the snapshot is not valid or has expired.
 */
@JvmOverloads
public fun verifySnapshot(
    token: String,
    keys: List<SnapshotKey>,
    customer: String,
    environment: String,
    issuer: String = SNAPSHOT_ISSUER,
    now: Instant = Instant.now(),
    clockSkewSeconds: Int = DEFAULT_CLOCK_SKEW_SECONDS,
): VerifiedSnapshot {
    require(clockSkewSeconds in 0..MAX_SKEW_SECONDS) { "Pass clockSkewSeconds as a whole number from 0 to 300." }
    require(customer.isNotBlank() && environment.isNotBlank()) { "Provide the customer and environment the snapshot must be for." }
    val parts = token.split('.')
    val header = parts.takeIf { it.size == 3 && decodeBase64Url(it[1]) != null }?.let { decodeJwtPart(it[0]) }
    val signature = parts.getOrNull(2)?.let(::decodeBase64Url)
    val isSnapshot = header?.string("typ") == SNAPSHOT_TYPE && header.string("alg") == "ES256" && !header.containsKey("crit")
    if (header == null || signature == null || !isSnapshot) invalid(NOT_A_SNAPSHOT)
    val key =
        keys.firstOrNull { it.kid == header.string("kid") }
            ?: invalid("None of the keys passed signed this snapshot. Fetch them again with snapshotKeys().")
    if (!signatureHolds(key, "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII), signature)) {
        invalid("This snapshot was changed after Entitler signed it.")
    }
    val claims = decodeJwtPart(parts[1])?.let(::snapshotClaimsOf) ?: invalid(NOT_A_SNAPSHOT)
    checkClaims(claims, issuer, customer, environment, now, clockSkewSeconds)
    val expiresAt = Instant.ofEpochSecond(claims.exp)
    return VerifiedSnapshot(
        customer = claims.sub,
        environment = SnapshotEnvironment(claims.environment.id),
        track = claims.track,
        release = claims.release,
        change = claims.change,
        testers = claims.testers,
        expiresAt = expiresAt,
        entitlements =
            Entitlements(
                customer = claims.sub,
                entitlements = claims.entitlements,
                asOf = Instant.ofEpochSecond(claims.iat),
                track = claims.track,
                release = claims.release,
                change = claims.change,
                testers = claims.testers,
            ),
    )
}

/** Verifies a snapshot against the key set from `snapshotKeys()`; see the overload taking a key list. */
@JvmOverloads
public fun verifySnapshot(
    token: String,
    keys: SnapshotKeys,
    customer: String,
    environment: String,
    issuer: String = SNAPSHOT_ISSUER,
    now: Instant = Instant.now(),
    clockSkewSeconds: Int = DEFAULT_CLOCK_SKEW_SECONDS,
): VerifiedSnapshot = verifySnapshot(token, keys.keys, customer, environment, issuer, now, clockSkewSeconds)

private fun checkClaims(
    claims: Claims,
    issuer: String,
    customer: String,
    environment: String,
    now: Instant,
    clockSkewSeconds: Int,
) {
    if (claims.iss != issuer) invalid("This snapshot was not issued by $issuer.")
    if (claims.iat * MILLIS > now.toEpochMilli() + clockSkewSeconds * MILLIS) {
        val signedFor = formatInstant(Instant.ofEpochSecond(claims.iat))
        invalid("This snapshot was signed for $signedFor, which is still to come. Fetch a new one while online.")
    }
    val expiresAt = Instant.ofEpochSecond(claims.exp)
    if (!now.isBefore(expiresAt)) {
        val expired = formatInstant(expiresAt)
        throw EntitlerSnapshotException(ErrorCode.SnapshotExpired, "This snapshot expired at $expired. Fetch a new one while online.")
    }
    if (claims.sub != customer) {
        invalid("This snapshot is for another customer, not $customer. Fetch one for the signed-in customer while online.")
    }
    if (claims.environment.id != environment) {
        invalid("This snapshot is from another environment, not $environment. Fetch one from your app's environment while online.")
    }
}

private fun invalid(message: String): Nothing = throw EntitlerSnapshotException(ErrorCode.SnapshotInvalid, message)

@Serializable
private class ClaimedEnvironment(
    val id: String,
)

@Serializable
private class Claims(
    val iss: String,
    val sub: String,
    val environment: ClaimedEnvironment,
    val track: Track,
    val release: Long?,
    val change: String?,
    val testers: Boolean,
    val entitlements: List<Entitlement>,
    val iat: Long,
    val exp: Long,
)

private fun snapshotClaimsOf(claims: JsonObject): Claims? =
    runCatching {
        checkShape(Claims.serializer().descriptor, claims)
        strictJson.decodeFromJsonElement(Claims.serializer(), claims)
    }.getOrNull()
        ?.takeIf { instantOf(it.iat) != null && instantOf(it.exp) != null }

private fun instantOf(seconds: Long): Instant? = runCatching { Instant.ofEpochSecond(seconds) }.getOrNull()?.takeIf(::isInRange)

private val strictJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun signatureHolds(
    key: SnapshotKey,
    signed: ByteArray,
    signature: ByteArray,
): Boolean {
    if (signature.size != SIGNATURE_BYTES || key.kty != "EC" || key.crv != "P-256") return false
    val x = decodeBase64Url(key.x) ?: return false
    val y = decodeBase64Url(key.y) ?: return false
    return runCatching {
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, P256))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(signed)
            verify(derOf(signature))
        }
    }.getOrDefault(false)
}

private fun derOf(raw: ByteArray): ByteArray {
    val half = raw.size / 2
    val r = derInteger(raw.copyOfRange(0, half))
    val s = derInteger(raw.copyOfRange(half, raw.size))
    return byteArrayOf(DER_SEQUENCE, (r.size + s.size).toByte()) + r + s
}

private fun derInteger(bytes: ByteArray): ByteArray {
    val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray()
    val positive = if (trimmed.isEmpty() || trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
    return byteArrayOf(DER_INTEGER, positive.size.toByte()) + positive
}

private const val DER_SEQUENCE: Byte = 0x30
private const val DER_INTEGER: Byte = 0x02
private const val HEX = 16

private val P256 =
    ECParameterSpec(
        EllipticCurve(
            ECFieldFp(BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", HEX)),
            BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", HEX),
            BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", HEX),
        ),
        ECPoint(
            BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", HEX),
            BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", HEX),
        ),
        BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", HEX),
        1,
    )
