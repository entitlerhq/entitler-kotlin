package com.entitlerhq.entitler

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The conformance suite's files, copied unchanged from the specification. */
object Conformance {
    private val manifest: JsonObject by lazy { Json.parseToJsonElement(text("manifest.json")).jsonObject }

    private fun bytes(name: String): ByteArray =
        checkNotNull(Conformance::class.java.getResourceAsStream("/conformance/$name")) { "Missing conformance file $name." }
            .use { it.readBytes() }

    private fun text(name: String): String = bytes(name).decodeToString()

    val files: List<JsonObject> get() = manifest["files"]!!.jsonArray.map { it.jsonObject }

    /** Checks every file's SHA-256 and number of cases against the manifest. */
    val verified: Unit by lazy {
        for (entry in files) {
            val path = entry.str("path")!!
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes(path)).joinToString("") { "%02x".format(it) }
            assertEquals(entry.str("sha256"), digest, "The SHA-256 of $path does not match the manifest.")
            assertEquals(
                entry["cases"]!!.jsonPrimitive.int,
                casesOf(path).size,
                "The number of cases in $path does not match the manifest.",
            )
        }
    }

    private fun casesOf(path: String): List<JsonObject> =
        Json
            .parseToJsonElement(text(path))
            .jsonObject["cases"]!!
            .jsonArray
            .map { it.jsonObject }

    fun cases(path: String): List<JsonObject> {
        verified
        return casesOf(path)
    }
}

/** A request the fake transport received, with the virtual instant it arrived. */
class Received(
    val method: String,
    val url: String,
    val headers: okhttp3.Headers,
    val body: String,
    val at: Long,
)

/** A canned answer: a status with headers and body, or a transport failure. */
sealed class Canned {
    data class Status(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
    ) : Canned()

    data object ConnectionFailure : Canned()

    data object TimeoutFailure : Canned()

    data object CutOff : Canned()

    companion object {
        fun of(answer: JsonObject): Canned =
            when (answer.str("failure")) {
                "connection" -> {
                    ConnectionFailure
                }

                "timeout" -> {
                    TimeoutFailure
                }

                "cut-off" -> {
                    CutOff
                }

                else -> {
                    Status(
                        answer["status"]!!.jsonPrimitive.int,
                        answer["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                        answer["body"]?.toString().orEmpty(),
                    )
                }
            }
    }
}

/**
 * The fake HTTP transport: an OkHttp interceptor that records each request and answers from a
 * queue, or from [answer] when the queue is empty, without touching the network.
 */
class FakeTransport(
    private val now: () -> Long,
) : Interceptor {
    val received = CopyOnWriteArrayList<Received>()
    val queue = ArrayDeque<Canned>()
    var answer: (Received) -> Canned = { Canned.Status(200, body = "{}") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        val seen = Received(request.method, request.url.toString(), request.headers, body, now())
        received += seen
        val canned = synchronized(queue) { queue.removeFirstOrNull() } ?: answer(seen)
        return when (canned) {
            Canned.ConnectionFailure -> {
                throw IOException("connection refused")
            }

            Canned.TimeoutFailure -> {
                throw InterruptedIOException("timeout")
            }

            Canned.CutOff -> {
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .header("Content-Type", "application/json")
                    .body(CutOffBody)
                    .build()
            }

            is Canned.Status -> {
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(canned.status)
                    .message("")
                    .headers(canned.headers.toHeaders())
                    .body(canned.body.toResponseBody())
                    .build()
            }
        }
    }

    companion object {
        private val shared = OkHttpClient()
    }

    val client: OkHttpClient by lazy { shared.newBuilder().addInterceptor(this).build() }
}

/** A JSON body whose first part arrives, then the read fails as a dropped connection does. */
object CutOffBody : okhttp3.ResponseBody() {
    override fun contentType() = "application/json".toMediaType()

    override fun contentLength() = -1L

    override fun source(): okio.BufferedSource =
        object : okio.Source {
            private var sent = false

            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (sent) throw IOException("connection reset")
                sent = true
                val part = """{"customer":"user_42","feature":"""
                sink.writeUtf8(part)
                return part.length.toLong()
            }

            override fun timeout() = okio.Timeout.NONE

            override fun close() = Unit
        }.buffer()
}

/** A clock that starts at [base] and moves with the test scheduler's virtual time once one is set. */
class FakeClock(
    var base: Long,
) {
    var scheduler: TestCoroutineScheduler? = null

    fun now(): Long = base + (scheduler?.currentTime ?: 0)
}

/** A random source whose doubles are all [value], so a backoff lands where a test wants it. */
class FixedRandom(
    private val value: Double,
) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

const val ALMOST_ONE = 0.999_999
const val CONFORMANCE_KEY = "ent_test_conformance_server_key"
const val CONFORMANCE_NOW = "2026-07-01T09:30:00.000Z"
const val MILLIS_PER_SECOND = 1000L
val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** A provider token valid for an hour from [CONFORMANCE_NOW]. */
val PROVIDED_TOKEN: String =
    base64Url("""{"alg":"HS256","typ":"JWT"}""") + "." +
        base64Url("""{"sub":"user_42","iat":1782898200,"exp":1782901800}""") + ".placeholder-signature-not-verified"

/** A check answer for `export_pdf`, entitled. */
const val CHECK_BODY =
    """{"customer":"user_42","asOf":"2026-07-01T09:30:00.000Z","feature":"export_pdf","type":"boolean",""" +
        """"entitled":true,"value":true,"sources":[],"upgrades":[],""" +
        """"environment":{"id":"env_conformance_test","name":"Development","kind":"test"},""" +
        """"track":{"id":"trk_all_customers","name":"All customers"},"release":2,"change":null,"testers":true,"experiment":null}"""

/** The `appliesWhere` conditions that hold in Kotlin. */
private val kotlinHolds =
    setOf("strings can hold lone surrogates", "the type of clockSkewSeconds can hold a negative number")

/** One dynamic test per case of a conformance [file], named after the case; a case whose `appliesWhere` fails in Kotlin is skipped. */
fun conformanceTests(
    file: String,
    run: (JsonObject) -> Unit,
): List<org.junit.jupiter.api.DynamicTest> =
    Conformance.cases(file).map { case ->
        org.junit.jupiter.api.DynamicTest.dynamicTest(case.str("name")) {
            case.str("appliesWhere")?.takeIf { it !in kotlinHolds }?.let {
                org.junit.jupiter.api.Assumptions
                    .abort<Unit>("Applies only where $it.")
            }
            run(case)
        }
    }

fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.isNull(name: String): Boolean = this[name] == null || this[name] is JsonNull

fun millisOf(text: String): Long =
    java.time.OffsetDateTime
        .parse(text)
        .toInstant()
        .toEpochMilli()

/** A string built from UTF-16 code units, which may hold lone surrogates. */
fun utf16(units: JsonElement): String = String(units.jsonArray.map { it.jsonPrimitive.int.toChar() }.toCharArray())

/** The values an open set's companion names. */
fun knownValues(type: Class<*>): Set<String> {
    val companion = type.getField("Companion").get(null)
    return companion.javaClass.methods
        .filter { it.parameterCount == 0 && it.returnType == type }
        .map { it.invoke(companion).toString() }
        .toSet()
}

/** Compares an open-set value with a case's plain string (known) or `{ "unknown": raw }`. */
fun assertOpen(
    expected: JsonElement,
    actual: Any,
    what: String,
) {
    val known = knownValues(actual.javaClass)
    if (expected is JsonObject) {
        val raw = expected.str("unknown")!!
        assertEquals(raw, actual.toString(), what)
        assertTrue(raw !in known, "$what: $raw should be unknown to this SDK.")
    } else {
        val raw = expected.jsonPrimitive.content
        assertEquals(raw, actual.toString(), what)
        assertTrue(raw in known, "$what: $raw should be a value this SDK knows.")
    }
}

fun featureValueOf(expected: JsonElement): FeatureValue? {
    if (expected is JsonNull) return null
    val value = expected.jsonObject
    return when (value.str("kind")) {
        "on" -> FeatureValue.On
        "amount" -> FeatureValue.Amount(value["amount"]!!.jsonPrimitive.long)
        "unlimited" -> FeatureValue.Unlimited
        else -> fail("Unknown value kind in $value.")
    }
}

fun instantOf(expected: JsonElement): Instant? {
    if (expected is JsonNull) return null
    val value = expected.jsonObject
    val instant = Instant.ofEpochMilli(value["epochMillis"]!!.jsonPrimitive.long)
    assertEquals(Instant.parse(value.str("instant")), instant)
    return instant
}

fun longOf(expected: JsonElement?): Long? = (expected as? JsonPrimitive)?.longOrNull

fun stringOf(expected: JsonElement?): String? = (expected as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Compares the meter fields of a check or an entitlement, honouring `absent`. */
fun assertMeters(
    expected: JsonObject,
    used: Long?,
    remaining: Limit?,
    held: Long?,
    resetsAt: Instant?,
) {
    val absent = expected["absent"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    for (name in absent) {
        val actual = mapOf("used" to used, "remaining" to remaining, "held" to held, "resetsAt" to resetsAt)[name]
        assertNull(actual, "$name should be absent.")
    }
    expected["used"]?.let { assertEquals(longOf(it), used, "used") }
    expected["remaining"]?.let { assertEquals<Any?>(featureValueOf(it), remaining, "remaining") }
    expected["held"]?.let { assertEquals(longOf(it), held, "held") }
    expected["resetsAt"]?.let { assertEquals(instantOf(it), resetsAt, "resetsAt") }
}

fun assertTrack(
    expected: JsonElement,
    actual: Track,
) {
    assertEquals(stringOf(expected.jsonObject["id"]), actual.id, "track.id")
    assertEquals(stringOf(expected.jsonObject["name"]), actual.name, "track.name")
}

fun assertExperiment(
    expected: JsonElement,
    actual: Experiment?,
) {
    if (expected is JsonNull) {
        assertNull(actual, "experiment")
    } else {
        val experiment = checkNotNull(actual) { "experiment is missing" }
        assertEquals(stringOf(expected.jsonObject["id"]), experiment.id, "experiment.id")
        assertOpen(expected.jsonObject["arm"]!!, experiment.arm, "experiment.arm")
    }
}

fun assertEntitlement(
    expected: JsonObject,
    actual: Entitlement,
) {
    assertEquals(expected.str("key"), actual.key, "key")
    assertOpen(expected["type"]!!, actual.type, "type")
    assertEquals(featureValueOf(expected["value"]!!), actual.value, "value")
    assertEquals(expected["entitled"]!!.jsonPrimitive.boolean, actual.entitled, "entitled")
    assertMeters(expected, actual.used, actual.remaining, actual.held, actual.resetsAt)
}

/** Compares an argument error: an [IllegalArgumentException] with the case's exact message. */
fun assertArgumentError(
    expected: JsonObject,
    error: Throwable?,
) {
    assertEquals("ArgumentError", expected.str("kind"))
    if (error !is IllegalArgumentException) fail("Expected an argument error, got $error.", error)
    assertEquals(expected.str("message"), error.message)
}

fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

fun jsonArrayOf(element: JsonElement?): JsonArray = element as? JsonArray ?: JsonArray(emptyList())
