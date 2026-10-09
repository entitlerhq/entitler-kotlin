package com.entitlerhq.entitler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertNotNull

const val SERVER_KEY = "ent_test_secret_key"
const val PUBLISHABLE_KEY = "ent_test_publishable_key"

class FakeApi : AutoCloseable {
    val server = MockWebServer().apply { start() }
    var now: Long = Instant.parse("2026-10-09T01:00:00Z").toEpochMilli()
    val errors = mutableListOf<Throwable>()
    val baseUrl: String get() = server.url("/").toString()

    fun setUp(config: EntitlerConfig) {
        config.baseUrl = baseUrl
        config.clock = { now }
        config.random = Random(7)
        config.onError = { errors += it }
    }

    fun server(extra: EntitlerConfig.() -> Unit = {}): EntitlerServer =
        EntitlerServer(SERVER_KEY) {
            setUp(this)
            extra()
        }

    fun client(
        token: String = customerToken(),
        extra: EntitlerClientConfig.() -> Unit = {},
    ): EntitlerClient =
        EntitlerClient(token) {
            setUp(this)
            extra()
        }

    fun reply(
        code: Int = 200,
        body: String = "{}",
        vararg headers: Pair<String, String>,
    ) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
    }

    fun replyFixture(
        name: String,
        code: Int = 200,
        vararg headers: Pair<String, String>,
    ) = reply(code, fixture(name), *headers)

    fun take(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "No request arrived.")

    override fun close() = server.close()
}

fun fixture(name: String): String = assertNotNull(FakeApi::class.java.getResource("/fixtures/$name.json"), name).readText()

fun fixtureJson(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject

fun base64Url(text: String): String = text.encodeUtf8().base64Url().trimEnd('=')

fun jwt(claims: String): String = base64Url("""{"alg":"HS256","typ":"customer+jwt"}""") + "." + base64Url(claims) + ".signature"

fun customerToken(
    sub: String = "user_1",
    exp: Long = 1_800_000_000,
): String = jwt("""{"iss":"https://api.entitler.dev/customers","eid":"e_1","sub":"$sub","exp":$exp}""")

val RecordedRequest.bodyText: String get() = body?.utf8().orEmpty()

val RecordedRequest.path: String get() = target
