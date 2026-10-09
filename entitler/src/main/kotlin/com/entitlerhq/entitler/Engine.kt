package com.entitlerhq.entitler

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class ApiRequest(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val body: JsonElement? = null,
    val idempotencyKey: String? = null,
    val visitor: String? = null,
    val timeout: Duration? = null,
    val credentialed: Boolean = true,
)

internal class Answer(
    val status: Int,
    val headers: Headers,
    val body: String,
)

private val jsonType = "application/json".toMediaType()
private val retriedStatuses = setOf(408, 429, 500, 502, 503, 504)
private const val MAX_BACKOFF_SECONDS = 8.0
private const val IDEMPOTENCY_KEY_MESSAGE = "Pass idempotencyKey as 1 to 200 printable ASCII characters."
private const val MAX_KEY_LENGTH = 200

internal fun checkIdempotencyKey(key: String?): String? {
    if (key != null) require(key.length in 1..MAX_KEY_LENGTH && key.all { it in ' '..'~' }) { IDEMPOTENCY_KEY_MESSAGE }
    return key
}

internal fun newIdempotencyKey(): String = UUID.randomUUID().toString()

internal class Engine(
    config: EntitlerConfig,
    private val credentials: Credentials,
    val visitor: String?,
) {
    val baseUrl: String = config.baseUrl.trim().trimEnd('/')
    private val timeout = config.timeout
    private val maxRetries = config.maxRetries
    private val maxRetryDelay = config.maxRetryDelay
    private val cache = config.cache
    private val staleFor = config.staleFor
    private val onError = config.onError
    private val asOf = config.asOf?.let(::formatInstant)
    private val ownsHttp = config.httpClient == null
    private val http = config.httpClient ?: defaultHttpClient()
    val clock = config.clock
    private val random = config.random
    private val writes = ConcurrentHashMap<String, Long>()

    val kind: String get() = credentials.kind

    fun report(error: EntitlerException) {
        runCatching { onError?.invoke(error) }
    }

    fun wrote(customer: String) {
        writes[customer] = clock()
    }

    fun close() {
        if (ownsHttp) {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    suspend fun <T> call(
        request: ApiRequest,
        deserializer: DeserializationStrategy<T>,
    ): T = decode(send(request).body, deserializer)

    suspend fun <T> cached(
        request: ApiRequest,
        customer: String?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val store = cache ?: return call(request, deserializer)
        val key = cacheKey(request)
        val kept = store.get(key)
        val now = clock()
        if (kept != null && isFresh(kept, now, customer)) return decode(kept.body, deserializer)
        val answer =
            try {
                send(request, kept?.etag)
            } catch (error: EntitlerException) {
                return decode(standIn(kept, error, now).body, deserializer, stale = true)
            }
        val revalidated = if (answer.status == HTTP_NOT_MODIFIED) kept else null
        val body = revalidated?.body ?: answer.body
        if (!cacheControl(answer.headers).noStore) {
            entryOf(answer, body, revalidated?.etag).takeIf { it.etag != null || it.maxAge != null }?.let { store.set(key, it) }
        }
        return decode(body, deserializer)
    }

    private fun standIn(
        kept: CacheEntry?,
        error: EntitlerException,
        now: Long,
    ): CacheEntry {
        val young = kept != null && now - kept.receivedAt.toEpochMilli() < staleFor.inWholeMilliseconds
        if (!young || !isUnreachable(error)) throw error
        report(error)
        return kept
    }

    suspend fun send(
        request: ApiRequest,
        ifNoneMatch: String? = null,
    ): Answer {
        val idempotencyKey = if (request.method == "GET") null else request.idempotencyKey ?: newIdempotencyKey()
        var retries = 0
        var refreshed = false
        while (true) {
            val credential = if (request.credentialed) credentials.current() else null
            val failure =
                try {
                    val answer = attempt(request, idempotencyKey, credential, ifNoneMatch)
                    when {
                        isSuccess(answer, ifNoneMatch) -> {
                            return answer
                        }

                        credential != null && !refreshed && canRefresh(answer) -> {
                            refreshed = true
                            credentials.refresh(credential)
                            continue
                        }

                        else -> {
                            apiException(answer, idempotencyKey)
                        }
                    }
                } catch (error: EntitlerException) {
                    error
                }
            delay(waitBefore(failure, retries))
            retries++
        }
    }

    private fun isSuccess(
        answer: Answer,
        ifNoneMatch: String?,
    ): Boolean = answer.status in HTTP_OK_RANGE || (answer.status == HTTP_NOT_MODIFIED && ifNoneMatch != null)

    private fun canRefresh(answer: Answer): Boolean = answer.status == HTTP_UNAUTHORISED && credentials.refreshable

    private fun waitBefore(
        failure: EntitlerException,
        retries: Int,
    ): Duration {
        if (!isRetried(failure) || retries >= maxRetries) throw failure
        val retryAfter = (failure as? EntitlerApiException)?.retryAfter
        if (retryAfter != null && retryAfter > maxRetryDelay) throw failure
        return retryAfter ?: backoff(retries)
    }

    private fun backoff(retry: Int): Duration {
        val ceiling = min(MAX_BACKOFF_SECONDS, BACKOFF_BASE_SECONDS * 2.0.pow(retry))
        return (random.nextDouble() * ceiling * MILLIS_PER_SECOND).toLong().milliseconds
    }

    private suspend fun attempt(
        request: ApiRequest,
        idempotencyKey: String?,
        credential: Credential?,
        ifNoneMatch: String?,
    ): Answer {
        val call = http.newCall(httpRequest(request, idempotencyKey, credential, ifNoneMatch))
        call.timeout().timeout((request.timeout ?: timeout).inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(transportException(e, idempotencyKey))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val answer =
                            try {
                                response.use { Answer(it.code, it.headers, it.body.string()) }
                            } catch (e: IOException) {
                                continuation.resumeWithException(transportException(e, idempotencyKey))
                                return
                            }
                        continuation.resume(answer)
                    }
                },
            )
        }
    }

    private fun httpRequest(
        request: ApiRequest,
        idempotencyKey: String?,
        credential: Credential?,
        ifNoneMatch: String?,
    ): Request {
        val builder =
            Request
                .Builder()
                .url(urlOf(request))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
        val body =
            when {
                request.body != null -> {
                    request.body
                        .toString()
                        .encodeUtf8()
                        .toByteArray()
                        .toRequestBody(jsonType)
                }

                request.method == "GET" || request.method == "DELETE" -> {
                    null
                }

                else -> {
                    ByteArray(0).toRequestBody(null)
                }
            }
        builder.method(request.method, body)
        credential?.let {
            builder.header("Authorization", "Bearer ${it.bearer}")
            it.identityToken?.let { token -> builder.header("Entitler-Identity-Token", token) }
        }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        (request.visitor ?: visitor)?.let { builder.header("Entitler-Visitor", it) }
        asOf?.let { builder.header("Entitler-As-Of", it) }
        ifNoneMatch?.let { builder.header("If-None-Match", it) }
        return builder.build()
    }

    private fun urlOf(request: ApiRequest): String {
        val query = request.query.joinToString("&") { (name, value) -> "${encodeComponent(name)}=${encodeComponent(value)}" }
        return baseUrl + request.path + (if (query.isEmpty()) "" else "?$query")
    }

    private suspend fun cacheKey(request: ApiRequest): String {
        val principal = if (request.credentialed) credentials.principal(credentials.current()) else ""
        val visitor = request.visitor ?: visitor
        return listOf(request.method, urlOf(request).toHttpUrl().toString(), asOf.orEmpty(), visitor.orEmpty(), principal)
            .joinToString("\n")
            .encodeUtf8()
            .sha256()
            .hex()
    }

    private fun isFresh(
        entry: CacheEntry,
        now: Long,
        customer: String?,
    ): Boolean {
        val maxAge = entry.maxAge ?: return false
        val received = entry.receivedAt.toEpochMilli()
        val wroteSince = customer != null && (writes[customer] ?: Long.MIN_VALUE) >= received
        return !wroteSince && now - received < maxAge.inWholeMilliseconds
    }

    private fun entryOf(
        answer: Answer,
        body: String,
        keptEtag: String?,
    ): CacheEntry =
        CacheEntry(
            body = body,
            etag = answer.headers["ETag"] ?: keptEtag,
            maxAge = cacheControl(answer.headers).maxAge,
            receivedAt = Instant.ofEpochMilli(clock()),
        )

    private fun apiException(
        answer: Answer,
        idempotencyKey: String?,
    ): EntitlerApiException {
        val error = runCatching { (json.parseToJsonElement(answer.body) as JsonObject)["error"] as JsonObject }.getOrNull()
        val body = error?.let { runCatching { json.decodeFromJsonElement<ErrorBody>(it) }.getOrNull() }
        return EntitlerApiException(
            status = answer.status,
            code = body?.code?.let(::ErrorCode) ?: ErrorCode.HttpError,
            message = body?.message ?: "Entitler request failed with HTTP ${answer.status}.",
            requestId = answer.headers["x-request-id"],
            retryAfter = answer.headers["Retry-After"]?.let(::retryAfterOf),
            idempotencyKey = idempotencyKey,
            payment =
                error?.get("payment")?.let { runCatching { json.decodeFromJsonElement<Payment>(it) }.getOrNull() }?.takeIf {
                    it.status in
                        knownPayments
                },
            listingGaps = body?.listingGaps.orEmpty(),
            listingProblems = body?.listingProblems.orEmpty(),
        )
    }

    private fun retryAfterOf(value: String): Duration? {
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return seconds.coerceAtLeast(0).seconds
        val date = runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
        return date?.let { (it.toInstant().toEpochMilli() - clock()).coerceAtLeast(0).milliseconds }
    }

    private fun defaultHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private companion object {
        val HTTP_OK_RANGE = 200..299
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_UNAUTHORISED = 401
        const val HTTP_SERVER_ERRORS = 500
        const val HTTP_TOO_MANY = 429
        const val BACKOFF_BASE_SECONDS = 0.5
        const val MILLIS_PER_SECOND = 1000
        val knownPayments = setOf(PaymentStatus.Declined, PaymentStatus.RequiresAction, PaymentStatus.Processing, PaymentStatus.Pending)

        fun isRetried(error: EntitlerException): Boolean =
            error !is EntitlerApiException || error.status in retriedStatuses

        fun isUnreachable(error: EntitlerException): Boolean =
            error is EntitlerConnectionException ||
                error is EntitlerTimeoutException ||
                (error is EntitlerApiException && (error.status == HTTP_TOO_MANY || error.status >= HTTP_SERVER_ERRORS))
    }
}

@kotlinx.serialization.Serializable
private class ErrorBody(
    val code: String,
    val message: String,
    val listingGaps: List<ListingGap>? = null,
    val listingProblems: List<ListingProblem>? = null,
)

private class CacheControl(
    val maxAge: Duration?,
    val noStore: Boolean,
)

private fun cacheControl(headers: Headers): CacheControl {
    val directives = headers.values("Cache-Control").flatMap { it.split(',') }.map { it.trim().lowercase() }
    val maxAge = directives.firstNotNullOfOrNull { it.removePrefix("max-age=").takeIf { v -> v != it }?.toLongOrNull() }
    return CacheControl(maxAge?.seconds, "no-store" in directives)
}

private fun transportException(
    error: IOException,
    idempotencyKey: String?,
): EntitlerException =
    if (error is InterruptedIOException) {
        EntitlerTimeoutException("Entitler did not answer in time.", error, idempotencyKey)
    } else {
        EntitlerConnectionException("Entitler could not be reached: ${error.javaClass.simpleName}.", error, idempotencyKey)
    }

internal fun <T> decode(
    body: String,
    deserializer: DeserializationStrategy<T>,
    stale: Boolean = false,
): T =
    try {
        val element = json.parseToJsonElement(body)
        val marked = if (stale && element is JsonObject) JsonObject(element + ("stale" to JsonPrimitive(true))) else element
        json.decodeFromJsonElement(deserializer, marked)
    } catch (error: SerializationException) {
        throw EntitlerConnectionException("Entitler sent an answer this SDK cannot read.", error)
    }

private val USER_AGENT = "entitler-kotlin/$SDK_VERSION java/${System.getProperty("java.version") ?: "unknown"}"
