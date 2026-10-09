package com.entitlerhq.entitler

import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SnapshotTest {
    private val now = Instant.parse("2026-10-09T01:00:00Z")
    private val pair = keyPair()
    private val key = jwk(pair, "k1")
    private val entitlements =
        """[{"key":"export_pdf","type":"boolean","entitled":true,"value":true,"sources":[]},
        {"key":"collaboration","type":"group","entitled":false,"value":0,"sources":[]}]"""

    private fun keyPair(): KeyPair =
        KeyPairGenerator
            .getInstance(
                "EC",
            ).apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun jwk(
        pair: KeyPair,
        kid: String,
    ): SnapshotKey {
        val point = (pair.public as ECPublicKey).w
        return SnapshotKey(
            "EC",
            "P-256",
            okio.ByteString
                .of(*unsigned(point.affineX))
                .base64Url()
                .trimEnd('='),
            okio.ByteString
                .of(*unsigned(point.affineY))
                .base64Url()
                .trimEnd('='),
            kid,
            "ES256",
            "sig",
        )
    }

    private fun raw(der: ByteArray): ByteArray {
        var offset = 2
        val rLength = der[offset + 1].toInt()
        val r = BigInteger(der.copyOfRange(offset + 2, offset + 2 + rLength))
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val s = BigInteger(der.copyOfRange(offset + 2, offset + 2 + sLength))
        return unsigned(r) + unsigned(s)
    }

    private fun claims(
        iss: String = SNAPSHOT_ISSUER,
        sub: String = "user_1",
        environment: String = "e_1",
        iat: Long = now.epochSecond - 10,
        exp: Long = now.epochSecond + 3600,
        testers: String = "\"testers\":true",
    ) = """{"iss":"$iss","sub":"$sub","environment":{"id":"$environment"},"track":{"id":"j_1","name":"All customers"},
        "release":2,"change":null,$testers,"entitlements":$entitlements,"iat":$iat,"exp":$exp}"""

    private fun sign(
        payload: String,
        header: String = """{"typ":"entitlements+jwt","alg":"ES256","kid":"k1"}""",
        signer: KeyPair = pair,
    ): String {
        val signingInput = base64Url(header) + "." + base64Url(payload)
        val der =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(signer.private)
                update(signingInput.toByteArray())
                sign()
            }
        return signingInput + "." +
            okio.ByteString
                .of(*raw(der))
                .base64Url()
                .trimEnd('=')
    }

    private fun verify(
        token: String,
        keys: List<SnapshotKey> = listOf(key),
        customer: String = "user_1",
        environment: String = "e_1",
        clockSkew: kotlin.time.Duration = 60.seconds,
    ) = verifySnapshot(token, keys, customer, environment, now = now, clockSkew = clockSkew)

    private fun failure(
        code: ErrorCode,
        message: String,
        block: () -> Unit,
    ) {
        val error = assertFailsWith<EntitlerSnapshotException>(block = block)
        assertEquals(code, error.code)
        assertEquals(message, error.message)
    }

    private val notASnapshot = "That is not an entitlements snapshot. Pass the token snapshot() returned."

    @Test
    fun `a valid snapshot verifies with its entitlements`() {
        val verified = verify(sign(claims()))
        assertEquals("user_1", verified.customer)
        assertEquals(SnapshotEnvironment("e_1"), verified.environment)
        assertEquals(Track("j_1", "All customers"), verified.track)
        assertEquals(2L, verified.release)
        assertNull(verified.change)
        assertTrue(verified.testers)
        assertEquals(Instant.ofEpochSecond(now.epochSecond + 3600), verified.expiresAt)
        assertTrue(verified.entitlements.has(Feature.OnOff("export_pdf")))
        assertEquals(false, verified.entitlements.has("collaboration"))
        assertEquals(Instant.ofEpochSecond(now.epochSecond - 10), verified.entitlements.asOf)
        assertNull(verified.entitlements.experiment)
        assertNull(verified.entitlements.environment)
        assertEquals(
            verified,
            verifySnapshot(sign(claims()), SnapshotKeys(listOf(key)), "user_1", "e_1", now = now).copy(expiresAt = verified.expiresAt),
        )
    }

    @Test
    fun `the clients verify too`() {
        val server = EntitlerServer(SERVER_KEY)
        val client = EntitlerClient(customerToken())
        assertEquals("user_1", server.verifySnapshot(sign(claims()), listOf(key), "user_1", "e_1", now = now).customer)
        assertEquals("user_1", client.verifySnapshot(sign(claims()), SnapshotKeys(listOf(key)), "user_1", "e_1", now = now).customer)
        assertEquals("user_1", server.verifySnapshot(sign(claims()), SnapshotKeys(listOf(key)), "user_1", "e_1", now = now).customer)
        assertEquals("user_1", client.verifySnapshot(sign(claims()), listOf(key), "user_1", "e_1", now = now).customer)
        server.close()
        client.close()
    }

    @Test
    fun `step 1 refuses what is not a snapshot`() {
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify("one.two") }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify("!!!.e30.sig") }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign(claims(), header = """{"typ":"JWT","alg":"ES256","kid":"k1"}""")) }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) {
            verify(sign(claims(), header = """{"typ":"entitlements+jwt","alg":"HS256","kid":"k1"}"""))
        }
    }

    @Test
    fun `step 2 needs the signing key`() {
        failure(ErrorCode.SnapshotInvalid, "None of the keys passed signed this snapshot. Fetch them again with snapshotKeys().") {
            verify(sign(claims()), keys = listOf(key.copy(kid = "k2")))
        }
    }

    @Test
    fun `step 3 refuses a changed snapshot or an unusable key`() {
        val changed = "This snapshot was changed after Entitler signed it."
        val token = sign(claims())
        val parts = token.split('.')
        failure(ErrorCode.SnapshotInvalid, changed) { verify(parts[0] + "." + base64Url(claims(sub = "other")) + "." + parts[2]) }
        failure(ErrorCode.SnapshotInvalid, changed) { verify(sign(claims(), signer = keyPair())) }
        failure(ErrorCode.SnapshotInvalid, changed) { verify(parts[0] + "." + parts[1] + "." + base64Url("short")) }
        failure(ErrorCode.SnapshotInvalid, changed) { verify(token, keys = listOf(key.copy(crv = "P-384"))) }
        failure(ErrorCode.SnapshotInvalid, changed) { verify(token, keys = listOf(key.copy(x = "AAAA"))) }
        failure(ErrorCode.SnapshotInvalid, changed) { verify(token, keys = listOf(key.copy(x = "!!"))) }
    }

    @Test
    fun `step 4 checks the claims' shape, and needs testers`() {
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign("""{"iss":"x"}""")) }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign(claims(testers = "\"payments\":true"))) }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign(claims(testers = "\"testers\":\"yes\""))) }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign("not json")) }
    }

    @Test
    fun `steps 5 to 9 check issuer, times, customer and environment`() {
        failure(ErrorCode.SnapshotInvalid, "This snapshot was not issued by $SNAPSHOT_ISSUER.") {
            verify(sign(claims(iss = "https://evil.example")))
        }
        failure(
            ErrorCode.SnapshotInvalid,
            "This snapshot was signed for 2026-10-09T01:01:01.000Z, which is still to come. Fetch a new one while online.",
        ) { verify(sign(claims(iat = now.epochSecond + 61))) }
        verify(sign(claims(iat = now.epochSecond + 60)))
        failure(ErrorCode.SnapshotExpired, "This snapshot expired at 2026-10-09T01:00:00.000Z. Fetch a new one while online.") {
            verify(sign(claims(exp = now.epochSecond)))
        }
        failure(
            ErrorCode.SnapshotInvalid,
            "This snapshot is for another customer, not someone. Fetch one for the signed-in customer while online.",
        ) {
            verify(sign(claims()), customer = "someone")
        }
        failure(
            ErrorCode.SnapshotInvalid,
            "This snapshot is from another environment, not e_2. Fetch one from your app's environment while online.",
        ) {
            verify(sign(claims()), environment = "e_2")
        }
    }

    @Test
    fun `clock skew must be whole seconds from 0 to 300`() {
        verify(sign(claims(iat = now.epochSecond + 300)), clockSkew = 300.seconds)
        failure(
            ErrorCode.SnapshotInvalid,
            "This snapshot was signed for 2026-10-09T01:00:01.000Z, which is still to come. Fetch a new one while online.",
        ) {
            verify(sign(claims(iat = now.epochSecond + 1)), clockSkew = 0.seconds)
        }
        for (skew in listOf((-1).seconds, 301.seconds, 1500.milliseconds)) {
            val error = assertFailsWith<IllegalArgumentException> { verify(sign(claims()), clockSkew = skew) }
            assertEquals("Pass clockSkew as a whole number of seconds from 0 to 300.", error.message)
        }
    }

    @Test
    fun `the compact form, the header object and crit are enforced`() {
        val token = sign(claims())
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify("") }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(token + "=") }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(token.replaceFirst(".", "+.")) }
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(base64Url("[1]") + "." + token.substringAfter('.')) }
        for (crit in listOf("[\"exp\"]", "null", "[]")) {
            failure(ErrorCode.SnapshotInvalid, notASnapshot) {
                verify(sign(claims(), header = """{"typ":"entitlements+jwt","alg":"ES256","kid":"k1","crit":$crit}"""))
            }
        }
        failure(ErrorCode.SnapshotInvalid, "None of the keys passed signed this snapshot. Fetch them again with snapshotKeys().") {
            verify(sign(claims(), header = """{"typ":"entitlements+jwt","alg":"ES256","kid":1}"""))
        }
    }

    @Test
    fun `instants outside years 0001 to 9999 are a snapshot error`() {
        failure(ErrorCode.SnapshotInvalid, notASnapshot) { verify(sign(claims(exp = 253_402_300_800))) }
    }

    @Test
    fun `a blank expected customer or environment is an argument error`() {
        val message = "Provide the customer and environment the snapshot must be for."
        assertEquals(message, assertFailsWith<IllegalArgumentException> { verify(sign(claims()), customer = " ") }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { verify(sign(claims()), environment = "") }.message)
    }
}
