package com.entitlerhq.entitler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
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
    val anonymous: Boolean = false,
    val asOf: String? = null,
)

internal class Answer(
    val status: Int,
    val headers: Headers,
    val body: String,
    val idempotencyKey: String?,
    val credential: Credential?,
)

private val jsonType = "application/json".toMediaType()
private val retriedStatuses = setOf(408, 429, 500, 502, 503, 504)
private const val MAX_BACKOFF_SECONDS = 8.0
internal const val MAX_KEY_LENGTH = 200
private const val OUTAGE_WINDOW_MILLIS = 30_000L
private const val MILLIS_PER_SECOND = 1000L
private const val CANNOT_READ = "Entitler sent an answer this SDK cannot read."

internal fun checkIdempotencyKey(
    key: String?,
    maxLength: Int = MAX_KEY_LENGTH,
): String? {
    if (key != null) {
        val valid = key.length in 1..maxLength && key.all { it in ' '..'~' } && !key.startsWith(' ') && !key.endsWith(' ')
        require(valid) { "Pass idempotencyKey as 1 to $maxLength printable ASCII characters." }
    }
    return key
}

internal fun newIdempotencyKey(): String = UUID.randomUUID().toString()

private val imfDate = DateTimeFormatter.RFC_1123_DATE_TIME
private const val TWO_DIGIT_YEAR_WINDOW = 50L

private fun rfc850Date(now: Long): DateTimeFormatter =
    DateTimeFormatterBuilder()
        .appendPattern("EEEE, dd-MMM-")
        .appendValueReduced(
            ChronoField.YEAR,
            2,
            2,
            Instant
                .ofEpochMilli(now)
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .minusYears(TWO_DIGIT_YEAR_WINDOW),
        ).appendPattern(" HH:mm:ss 'GMT'")
        .toFormatter(Locale.US)

private val asctimeDate = DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)

internal fun retryAfterOf(
    value: String,
    now: Long,
): Duration? {
    val text = value.trim()
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) {
        return text
            .toBigInteger()
            .min(Long.MAX_VALUE.toBigInteger())
            .toLong()
            .seconds
    }
    val instant =
        listOf(
            { ZonedDateTime.parse(text, imfDate).toInstant() },
            { LocalDateTime.parse(text, rfc850Date(now)).toInstant(ZoneOffset.UTC) },
            { LocalDateTime.parse(text, asctimeDate).toInstant(ZoneOffset.UTC) },
        ).firstNotNullOfOrNull { parse ->
            try {
                parse()
            } catch (error: DateTimeParseException) {
                null
            }
        } ?: return null
    return (instant.toEpochMilli() - now).coerceAtLeast(0).milliseconds
}

private class Directives(
    val maxAge: Long?,
    val noStore: Boolean,
    val noCache: Boolean,
)

private fun directives(cacheControl: String?): Directives {
    val parts = cacheControl.orEmpty().split(',').map { it.trim().lowercase() }
    val maxAge =
        parts.firstNotNullOfOrNull { part ->
            part
                .takeIf { it.startsWith("max-age=") }
                ?.removePrefix("max-age=")
                ?.trim('"')
                ?.toLongOrNull()
        }
    return Directives(maxAge, "no-store" in parts, "no-cache" in parts)
}

private val USER_AGENT: String =
    "entitler-kotlin/$SDK_VERSION " +
        (
            runCatching { "android/" + Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null) }.getOrNull()
                ?: "jvm/${System.getProperty("java.version") ?: "unknown"}"
        )

internal const val CLOSED = "This Entitler client is closed. Create a new one."

internal class Engine(
    config: EntitlerConfig,
    private val credentials: Credentials,
    private val visitor: Visitor?,
    val scope: CoroutineScope,
) {
    val baseUrl: String = config.baseUrl.trim().trimEnd('/')
    val timeout = config.timeout
    private val maxRetries = config.maxRetries
    private val maxRetryDelay = config.maxRetryDelay
    private val cache = config.store
    private val staleFor = config.staleFor
    private val onError = config.onError
    private val ownsHttp = config.httpClient == null
    private val http =
        (config.httpClient ?: OkHttpClient())
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (ownsHttp) {
                    connectTimeout(0, TimeUnit.MILLISECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(0, TimeUnit.MILLISECONDS)
                }
            }.build()
    val clock = config.clock
    private val random = config.random
    private val generations = ConcurrentHashMap<String, Long>()
    private val bumpedAt = ConcurrentHashMap<String, Long>()

    @Volatile private var outageUntil = 0L

    @Volatile private var closed = false

    private val inFlight = ConcurrentHashMap.newKeySet<Job>()

    val kind: String get() = credentials.kind

    fun ensureOpen() = check(!closed) { CLOSED }

    fun report(error: Throwable) {
        runCatching { onError?.invoke(error) }
    }

    fun bump(customer: String) {
        generations.merge(customer, 1L, Long::plus)
        bumpedAt[customer] = clock()
    }

    fun close() {
        if (closed) return
        closed = true
        inFlight.forEach { it.cancel() }
        scope.cancel()
        (cache as? MemoryCacheStore)?.clear()
        if (ownsHttp) {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    suspend fun <T> call(
        request: ApiRequest,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val answer = send(request)
        return decodeAnswer(answer, answer.body, deserializer)
    }

    suspend fun <T> cached(
        request: ApiRequest,
        customer: String?,
        deserializer: DeserializationStrategy<T>,
        revalidate: Boolean = false,
    ): T {
        ensureOpen()
        val store = cache ?: return call(request, deserializer)
        val credential = if (request.anonymous) null else guarded { credentials.current() }
        val kept = storeGet(store, cacheKey(request, credential))
        val now = clock()
        val keptStale = kept != null && now < outageUntil && isYoung(kept, now)
        val fresh = !revalidate && isFresh(kept, now, customer)
        if (kept != null && (keptStale || fresh)) return decodeKept(kept, deserializer, keptStale)
        return fetchThrough(store, request, customer, credential, kept, deserializer)
    }

    private suspend fun <T> fetchThrough(
        store: CacheStore,
        request: ApiRequest,
        customer: String?,
        credential: Credential?,
        kept: CacheEntry?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val startedAt = clock()
        val generation = customer?.let { generations[it] }
        val (answer, result) =
            try {
                val answer = send(request, kept?.etag, credential)
                val revalidated = if (answer.status == HTTP_NOT_MODIFIED) kept else null
                answer to entryOf(answer, revalidated, startedAt).let { it to decodeAnswer(answer, it.body, deserializer) }
            } catch (error: EntitlerException) {
                return standIn(kept, error, deserializer)
            }
        val moved = customer != null && generations[customer] != generation
        val entry = if (moved) result.first else result.first.copy(receivedAt = Instant.ofEpochMilli(clock()))
        val directives = directives(entry.cacheControl)
        if (!directives.noStore && (entry.etag != null || directives.maxAge != null)) {
            storeSet(store, cacheKey(request, answer.credential), entry, staleFor + (directives.maxAge ?: 0).seconds)
        }
        return result.second
    }

    private fun <T> standIn(
        kept: CacheEntry?,
        error: EntitlerException,
        deserializer: DeserializationStrategy<T>,
    ): T {
        if (!isUnreachable(error)) throw error
        val now = clock()
        val retryAfter = ((error as? EntitlerApiException)?.retryAfter?.inWholeMilliseconds ?: 0).coerceAtLeast(OUTAGE_WINDOW_MILLIS)
        outageUntil = now + retryAfter
        if (kept == null || !isYoung(kept, now)) throw error
        report(error)
        return decodeKept(kept, deserializer, stale = true)
    }

    private fun isYoung(
        entry: CacheEntry,
        now: Long,
    ): Boolean = now - entry.receivedAt.toEpochMilli() < staleFor.inWholeMilliseconds

    private fun isFresh(
        entry: CacheEntry?,
        now: Long,
        customer: String?,
    ): Boolean {
        if (entry == null) return false
        val directives = directives(entry.cacheControl)
        val maxAge = directives.maxAge ?: return false
        val received = entry.receivedAt.toEpochMilli()
        val writtenSince = customer != null && (bumpedAt[customer] ?: Long.MIN_VALUE) >= received
        val age = now - received + (entry.age ?: 0) * MILLIS_PER_SECOND
        return !directives.noCache && !writtenSince && age < maxAge * MILLIS_PER_SECOND
    }

    private fun entryOf(
        answer: Answer,
        kept: CacheEntry?,
        receivedAt: Long,
    ): CacheEntry {
        val headers = answer.headers
        return CacheEntry(
            body = kept?.body ?: answer.body,
            etag = headers["ETag"] ?: kept?.etag,
            cacheControl = headers.values("Cache-Control").takeIf { it.isNotEmpty() }?.joinToString(", ") ?: kept?.cacheControl,
            age = headers["Age"]?.trim()?.toLongOrNull() ?: kept?.age,
            receivedAt = Instant.ofEpochMilli(receivedAt),
        )
    }

    private suspend fun storeGet(
        store: CacheStore,
        key: String,
    ): CacheEntry? =
        runCatching { store.get(key) }.getOrElse {
            if (it is CancellationException) throw it
            report(it)
            null
        }

    private suspend fun storeSet(
        store: CacheStore,
        key: String,
        entry: CacheEntry,
        ttl: Duration,
    ) {
        runCatching { store.set(key, entry, ttl) }.onFailure {
            if (it is CancellationException) throw it
            report(it)
        }
    }

    suspend fun cacheKey(
        request: ApiRequest,
        credential: Credential?,
    ): String {
        val visitor = if (request.anonymous) null else request.visitor ?: visitor?.get()
        val parts =
            listOf(
                "entitler-cache-v1",
                request.method,
                urlOf(request),
                if (credential == null) null else credentials.principalKind,
                credential?.let(credentials::principal),
                if (request.anonymous) null else request.asOf,
                visitor,
            )
        return sha256(JsonArray(parts.map { it?.let(::JsonPrimitive) ?: JsonNull }).toString())
    }

    private fun <T> decodeKept(
        entry: CacheEntry,
        deserializer: DeserializationStrategy<T>,
        stale: Boolean,
    ): T =
        try {
            decode(entry.body, deserializer, stale = stale)
        } catch (error: SerializationException) {
            throw EntitlerApiException(HTTP_OK, ErrorCode.InvalidResponse, CANNOT_READ, cause = error)
        }

    private fun <T> decodeAnswer(
        answer: Answer,
        body: String,
        deserializer: DeserializationStrategy<T>,
    ): T =
        try {
            decode(body, deserializer, replayed = answer.headers["Idempotent-Replayed"] == "true")
        } catch (error: SerializationException) {
            throw EntitlerApiException(
                status = answer.status,
                code = ErrorCode.InvalidResponse,
                message = CANNOT_READ,
                requestId = answer.headers["x-request-id"],
                idempotencyKey = answer.idempotencyKey,
                cause = error,
            )
        }

    suspend fun send(
        request: ApiRequest,
        ifNoneMatch: String? = null,
        first: Credential? = null,
    ): Answer = guarded { attempts(request, ifNoneMatch, first) }

    suspend fun <T> guarded(block: suspend () -> T): T {
        ensureOpen()
        val outcome =
            coroutineScope {
                val work = async { runCatching { block() } }
                inFlight += work
                try {
                    if (closed) work.cancel()
                    work.await()
                } catch (error: CancellationException) {
                    if (!closed) throw error
                    Result.failure(IllegalStateException(CLOSED, error))
                } finally {
                    inFlight -= work
                }
            }
        val failure = outcome.exceptionOrNull()
        if (failure is CancellationException && closed) throw IllegalStateException(CLOSED, failure)
        return outcome.getOrThrow()
    }

    private suspend fun attempts(
        request: ApiRequest,
        ifNoneMatch: String?,
        first: Credential?,
    ): Answer {
        val idempotencyKey = idempotencyKeyOf(request)
        var retries = 0
        var refreshed = false
        var credential = first ?: credentialOf(request)
        while (true) {
            val failure =
                try {
                    val answer = attempt(request, idempotencyKey, credential, ifNoneMatch)
                    when {
                        isSuccess(answer, ifNoneMatch) -> {
                            return answer
                        }

                        credential != null && !refreshed && canRefresh(answer) -> {
                            refreshed = true
                            credential = credentials.refresh(credential)
                            continue
                        }

                        else -> {
                            apiException(answer)
                        }
                    }
                } catch (error: EntitlerConnectionException) {
                    error
                } catch (error: EntitlerTimeoutException) {
                    error
                }
            delay(waitBefore(failure, retries))
            retries++
            if (credential != null) credential = credentials.current()
        }
    }

    private fun idempotencyKeyOf(request: ApiRequest): String? =
        if (request.method ==
            "GET"
        ) {
            null
        } else {
            request.idempotencyKey ?: newIdempotencyKey()
        }

    private suspend fun credentialOf(request: ApiRequest): Credential? = if (request.anonymous) null else credentials.current()

    private fun canRefresh(answer: Answer): Boolean = answer.status == HTTP_UNAUTHORISED && credentials.refreshable

    private fun isSuccess(
        answer: Answer,
        ifNoneMatch: String?,
    ): Boolean = answer.status in HTTP_OK_RANGE || (answer.status == HTTP_NOT_MODIFIED && ifNoneMatch != null)

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
                                response.use { Answer(it.code, it.headers, it.body.string(), idempotencyKey, credential) }
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

    private suspend fun httpRequest(
        request: ApiRequest,
        idempotencyKey: String?,
        credential: Credential?,
        ifNoneMatch: String?,
    ): Request {
        val visitor = if (request.anonymous) null else request.visitor ?: visitor?.get()
        val builder =
            Request
                .Builder()
                .url(urlOf(request))
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
        val body =
            when {
                request.body != null -> {
                    request.body
                        .toString()
                        .encodeToByteArray()
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
        visitor?.let { builder.header("Entitler-Visitor", it) }
        request.asOf?.let { builder.header("Entitler-As-Of", it) }
        ifNoneMatch?.let { builder.header("If-None-Match", it) }
        return builder.build()
    }

    private fun urlOf(request: ApiRequest): String {
        val query = request.query.joinToString("&") { (name, value) -> "${encodeComponent(name)}=${encodeComponent(value)}" }
        return baseUrl + request.path + (if (query.isEmpty()) "" else "?$query")
    }

    private fun apiException(answer: Answer): EntitlerApiException =
        apiExceptionOf(
            answer.status,
            answer.headers,
            answer.body,
            answer.idempotencyKey,
            clock(),
        )

    private companion object {
        val HTTP_OK_RANGE = 200..299
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_UNAUTHORISED = 401
        const val HTTP_SERVER_ERRORS = 500
        const val HTTP_TOO_MANY = 429
        const val BACKOFF_BASE_SECONDS = 0.5

        fun isRetried(error: EntitlerException): Boolean = error !is EntitlerApiException || error.status in retriedStatuses

        fun isUnreachable(error: EntitlerException): Boolean =
            error is EntitlerConnectionException ||
                error is EntitlerTimeoutException ||
                (
                    error is EntitlerApiException &&
                        (error.status == HTTP_TOO_MANY || error.status >= HTTP_SERVER_ERRORS || error.code == ErrorCode.InvalidResponse)
                )
    }
}

private const val HTTP_PAYMENT_REQUIRED = 402
private val REDIRECTS = 300..399
private val knownPayments = setOf(PaymentStatus.Declined, PaymentStatus.RequiresAction, PaymentStatus.Processing, PaymentStatus.Pending)

internal fun apiExceptionOf(
    status: Int,
    headers: Headers,
    body: String,
    idempotencyKey: String?,
    now: Long,
): EntitlerApiException {
    val error = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("error") as? JsonObject
    val readable = error?.takeIf { status !in REDIRECTS }
    return EntitlerApiException(
        status = status,
        code = readable?.string("code")?.takeIf { it.isNotEmpty() }?.let(::ErrorCode) ?: ErrorCode.HttpError,
        message = readable?.string("message")?.takeIf { it.isNotEmpty() } ?: "Entitler answered with HTTP $status.",
        requestId = headers["x-request-id"],
        retryAfter = headers["Retry-After"]?.let { retryAfterOf(it, now) },
        idempotencyKey = idempotencyKey,
        payment = if (status == HTTP_PAYMENT_REQUIRED) readable?.let(::paymentOf) else null,
    )
}

private fun paymentOf(error: JsonObject): Payment? {
    val payment = error["payment"] as? JsonObject ?: return null
    val status = payment.string("status")?.let(::PaymentStatus)?.takeIf { it in knownPayments } ?: return null
    return Payment(status, payment.string("url"))
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
    replayed: Boolean = false,
): T {
    val element = json.parseToJsonElement(body)
    val marks = listOfNotNull("stale".takeIf { stale }, "replayed".takeIf { replayed })
    val marked = if (marks.isNotEmpty() && element is JsonObject) JsonObject(element + marks.map { it to JsonPrimitive(true) }) else element
    checkShape(deserializer.descriptor, marked)
    return json.decodeFromJsonElement(deserializer, marked)
}
