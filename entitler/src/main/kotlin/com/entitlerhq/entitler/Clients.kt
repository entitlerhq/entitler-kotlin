@file:JvmName("Entitler")
@file:JvmMultifileClass

package com.entitlerhq.entitler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.Closeable
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Options every client takes, set in the factory's configuration lambda:
 *
 * ```kotlin
 * val server = EntitlerServer(System.getenv("ENTITLER_KEY")) {
 *     timeout = 5.seconds
 *     maxRetries = 3
 * }
 * ```
 */
public abstract class EntitlerConfig internal constructor() {
    /** The API's base URL; trailing slashes are removed. */
    public var baseUrl: String = "https://api.entitler.dev"

    /**
     * How long each attempt may take, from connecting to the last byte of the answer: a positive duration,
     * or [Duration.INFINITE] for no deadline. A call's own `timeout` follows the same rule and is checked
     * before any token provider runs or anything is sent.
     */
    public var timeout: Duration = 10.seconds

    /** How many times a failed request is retried after the first attempt. */
    public var maxRetries: Int = 2

    /** The longest `Retry-After` the SDK waits for; a longer one fails at once. */
    public var maxRetryDelay: Duration = 10.seconds

    /** How long a kept answer may stand in when Entitler is unreachable. */
    public var staleFor: Duration = 24.hours

    /**
     * Called with each error a fallback absorbed: a stale answer, `isEntitled`'s default, a hold's failed
     * release or disposal, or a custom cache store's own failure. An `onError` that throws is ignored.
     */
    public var onError: ((Throwable) -> Unit)? = null

    /**
     * The OkHttp client to send requests with, to share its connection pool or add interceptors. Its own
     * timeouts apply beside [timeout]. A client the SDK builds is shut down on `close()`; yours is not.
     */
    public var httpClient: OkHttpClient? = null

    internal abstract val store: CacheStore?

    internal var clock: () -> Long = System::currentTimeMillis

    internal var random: Random = Random.Default

    internal open fun validate() {
        require(maxRetries >= 0) { "Pass maxRetries as 0 or more." }
        require(timeout.isPositive()) { "Pass timeout as a positive duration." }
        require(!maxRetryDelay.isNegative()) { "Pass maxRetryDelay as 0 or more." }
    }
}

/** Options for [EntitlerServer]: those of [EntitlerConfig] plus a [cache] that may be your own store. */
public class EntitlerServerConfig internal constructor() : EntitlerConfig() {
    /**
     * Where answers are kept: the in-memory store of 1,000 answers by default, a store of your own (Redis,
     * for example) to share answers between servers, or null to turn the answer cache off.
     */
    public var cache: CacheStore? = MemoryCacheStore()

    override val store: CacheStore? get() = cache
}

/**
 * Options for the in-app clients: those of [EntitlerConfig] plus an in-memory [cache] and the
 * [visitor].
 */
public class EntitlerClientConfig internal constructor() : EntitlerConfig() {
    /**
     * The in-memory answer cache, 1,000 answers by default, or null to turn it off. In-app clients keep
     * answers in memory only: a customer token changes with every refresh, so a store kept across launches
     * would never be read again. Only snapshots survive a relaunch.
     */
    public var cache: MemoryCacheStore? = MemoryCacheStore()

    /**
     * The visitor id to send, kept by your app from an earlier launch; null to generate one for this
     * client's lifetime, or to read it from [visitorSource].
     */
    public var visitor: String? = null

    /**
     * Where to read the visitor id when the app keeps it in asynchronous storage, such as DataStore. The
     * client calls it once, before its first request, so the client is built at once.
     */
    public var visitorSource: (suspend () -> String)? = null

    override fun validate() {
        super.validate()
        require(visitor == null || visitorSource == null) { "Pass either visitor or visitorSource, not both." }
    }

    override val store: CacheStore? get() = cache
}

/**
 * The server client, built from a secret project key. It runs on your servers and acts on any customer.
 * Never ship a secret key in an app: anyone can read it there. Apps use [EntitlerClient].
 *
 * Thread-safe: build one per process, share it, and close it on shutdown.
 *
 * ```kotlin
 * val entitler = EntitlerServer(System.getenv("ENTITLER_KEY"))
 * val customer = entitler.customer("user_123")
 * customer.register(name = "Ada", email = "ada@example.com")
 * if (customer.isEntitled(Features.exportPdf)) exportPdf()
 * customer.recordUsage(Features.aiCredits, 5, idempotencyKey = job.id)
 * ```
 */
public interface EntitlerServer : Closeable {
    /** The customers, to list and create. */
    public val customers: ServerCustomers

    /** The customer with the [id] your app uses for them. Makes no request. */
    public fun customer(id: String): ServerCustomer

    /**
     * Records usage events of any customers in observe mode, `POST /usage/events`, in requests of at most
     * 500 events sent in order. Needs `usage:write`.
     *
     * It answers one result per event, in input order, and never throws but for cancellation. An event the
     * SDK refuses itself (a blank customer, an amount out of range, an invalid key) is answered
     * [UsageOutcome.Error] and not sent. A request that fails after its retries answers its events
     * [UsageOutcome.Error] and the next request still goes. Each request's `Idempotency-Key` derives from
     * its events, so resending the events answered `error` is safe.
     *
     * @param register Register customers who are not registered yet.
     */
    public suspend fun recordUsageBatch(
        events: List<UsageBatchEvent>,
        register: Boolean = false,
        timeout: Duration? = null,
    ): UsageEventsResult

    /**
     * Signed-out pricing, `GET /pricing`, through the cache. Needs `plans:read`.
     *
     * @param visitor The visitor's id, from [newVisitorId], so they keep their experiment arm.
     * @param revalidate Skip a fresh kept answer and revalidate it.
     */
    public suspend fun pricing(
        visitor: String? = null,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Pricing

    /** The catalogue's features, `GET /pricing/features`, through the cache. Needs `plans:read`. */
    public suspend fun features(
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): FeatureList

    /** The scopes the key holds, `GET /keys/self`. Each call asks again. */
    public suspend fun scopes(timeout: Duration? = null): Scopes

    /** The keys that verify snapshots, `GET /customers/snapshot-keys`. Sends no credential. */
    public suspend fun snapshotKeys(timeout: Duration? = null): SnapshotKeys

    /** Verifies a snapshot with no request; see the top-level [com.entitlerhq.entitler.verifySnapshot]. */
    public fun verifySnapshot(
        token: String,
        expected: SnapshotExpectation,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, expected)

    /** A new visitor id for your app to keep, for example in a first-party cookie. */
    public fun newVisitorId(): String = com.entitlerhq.entitler.newVisitorId()

    /**
     * Closes the client: calls in flight end with an [IllegalStateException], and every later call throws
     * one. Closing twice is safe. An `OkHttpClient` you passed stays open.
     */
    override fun close()
}

/**
 * The in-app client for mobile, desktop and browser apps, acting on the signed-in customer, [me].
 *
 * Built from a customer token your server minted with [ServerCustomer.token], or a provider that
 * fetches one. It never takes a secret key. Create one when the customer signs in, keep it app-scoped
 * (ViewModels take its [Customer], never a client of their own), and close it at sign-out, so nothing
 * kept for one person is ever answered to the next.
 *
 * ```kotlin
 * val entitler = EntitlerClient({ api.fetchEntitlerToken() })
 * if (entitler.me.isEntitled(Features.exportPdf)) showExportButton()
 * ```
 */
public interface EntitlerClient : Closeable {
    /** The signed-in customer. Makes no request. */
    public val me: Customer

    /**
     * The client's visitor id, sent on every request so an experiment's arm stays the same: the one given,
     * the one [EntitlerClientConfig.visitorSource] answers, or one generated for this client's lifetime.
     */
    public suspend fun visitor(): String

    /** The scopes the credential holds, `GET /keys/self`. Each call asks again. */
    public suspend fun scopes(timeout: Duration? = null): Scopes

    /** The keys that verify snapshots, `GET /customers/snapshot-keys`. Sends no credential. */
    public suspend fun snapshotKeys(timeout: Duration? = null): SnapshotKeys

    /** Verifies a snapshot with no request; see the top-level [com.entitlerhq.entitler.verifySnapshot]. */
    public fun verifySnapshot(
        token: String,
        expected: SnapshotExpectation,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, expected)

    /**
     * Closes the client at sign-out: calls in flight and the pending token refresh end, the in-memory cache
     * is dropped, and every later call throws an [IllegalStateException]. Closing twice is safe. An
     * `OkHttpClient` you passed stays open.
     */
    override fun close()
}

/**
 * An in-app client built from a publishable key and an identity token from a sign-in provider
 * registered on the project. It can also [register] the person.
 */
public interface EntitlerIdentityClient : EntitlerClient {
    /**
     * Registers the signed-in person as a customer, `PUT /customers/me`, answering one who exists without
     * creating them again. Needs `customers:register`; a provider that does not let people register
     * themselves answers `403 registration_closed`.
     */
    public suspend fun register(
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): RegisteredCustomer
}

/**
 * An in-app client built from a publishable key alone, for a signed-out paywall or pricing page in an app
 * with no backend route for it.
 *
 * ```kotlin
 * val entitler = EntitlerPublishableClient(BuildConfig.ENTITLER_PUBLISHABLE_KEY)
 * val pricing = entitler.pricing()
 * ```
 */
public interface EntitlerPublishableClient : Closeable {
    /**
     * Signed-out pricing, `GET /pricing`, with the client's visitor, through the cache. Needs `plans:read`.
     *
     * @param visitor A visitor id for this request only, in place of the client's.
     * @param revalidate Skip a fresh kept answer and revalidate it.
     */
    public suspend fun pricing(
        visitor: String? = null,
        revalidate: Boolean = false,
        timeout: Duration? = null,
    ): Pricing

    /** The client's visitor id, as [EntitlerClient.visitor]. */
    public suspend fun visitor(): String

    /** The keys that verify snapshots, `GET /customers/snapshot-keys`. Sends no credential. */
    public suspend fun snapshotKeys(timeout: Duration? = null): SnapshotKeys

    /** Closes the client, as [EntitlerClient.close]. */
    override fun close()
}

/** Builds the server client from a secret project [key]. A publishable key is refused. */
@JvmOverloads
public fun EntitlerServer(
    key: String,
    configure: EntitlerServerConfig.() -> Unit = {},
): EntitlerServer {
    val secret = requireKey(key)
    require(!secret.startsWith(PUBLISHABLE_PREFIX)) {
        "A publishable key belongs in EntitlerClient. Use a secret key from the dashboard on your server."
    }
    return ServerClient(secret, EntitlerServerConfig().apply(configure).also { it.validate() })
}

/** Builds an in-app client from a customer [token] your server minted. A fixed token cannot be refreshed. */
@JvmOverloads
public fun EntitlerClient(
    token: String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerClient = AppClient(null, requireToken(token, CUSTOMER_TOKEN_REQUIRED), null, configure)

/** Builds an in-app client from a provider that answers a fresh customer token, asked again before expiry and after a 401. */
@JvmOverloads
public fun EntitlerClient(
    token: suspend () -> String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerClient = AppClient(null, null, token, configure)

/** Builds an identity client from a publishable [key] and an [identityToken] from your sign-in provider. */
@JvmOverloads
public fun EntitlerIdentityClient(
    key: String,
    identityToken: String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerIdentityClient {
    requireKey(key)
    val token = requireToken(identityToken, IDENTITY_TOKEN_REQUIRED)
    return AppClient(requirePublishable(key), token, null, configure)
}

/** Builds an identity client from a publishable [key] and a provider that answers a fresh identity token. */
@JvmOverloads
public fun EntitlerIdentityClient(
    key: String,
    identityToken: suspend () -> String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerIdentityClient = AppClient(requirePublishable(key), null, identityToken, configure)

/** Builds an in-app client from a publishable [key] alone, for signed-out pricing. */
@JvmOverloads
public fun EntitlerPublishableClient(
    key: String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerPublishableClient = PublishableClient(requirePublishable(key), configure)

private const val PUBLISHABLE_PREFIX = "ent_pk_"
internal const val CUSTOMER_TOKEN_REQUIRED = "Provide a customer token minted by your server."
internal const val IDENTITY_TOKEN_REQUIRED = "Provide the identity token your sign-in provider issued."
private const val BATCH_SIZE = 500

private fun requireKey(key: String): String =
    trimCredential(key).also {
        require(it.isNotEmpty()) { "Provide an Entitler API key from the dashboard." }
    }

internal fun requirePublishable(key: String): String =
    requireKey(key).also {
        require(it.startsWith(PUBLISHABLE_PREFIX)) {
            "A secret key belongs on your server, in EntitlerServer. Use a publishable key (ent_pk_…) in an app."
        }
    }

internal fun requireToken(
    token: String,
    message: String,
): String = trimCredential(token).also { require(it.isNotEmpty()) { message } }

private val knownScopes =
    listOf(
        Scope.PlansRead,
        Scope.EntitlementsRead,
        Scope.UsageRead,
        Scope.UsageWrite,
        Scope.BillingSelf,
        Scope.CustomersRegister,
        Scope.CustomersRead,
        Scope.CustomersWrite,
        Scope.CustomersProfile,
        Scope.CustomersSample,
        Scope.TokensMint,
        Scope.PlansWrite,
        Scope.PlansRelease,
        Scope.TracksManage,
        Scope.TracksPromote,
        Scope.TracksAssign,
        Scope.KeysManage,
        Scope.MembersManage,
        Scope.ProjectsManage,
        Scope.OrgManage,
    )

@kotlinx.serialization.Serializable
private class KeySelf(
    val scopes: List<String>,
    val registration: Boolean? = null,
)

private suspend fun scopesOf(
    engine: Engine,
    timeout: Duration?,
): Scopes {
    val self = engine.call(ApiRequest("GET", "/keys/self", timeout = timeout), KeySelf.serializer())
    return Scopes(knownScopes.filter { it.value in self.scopes }, self.registration)
}

private suspend fun snapshotKeysOf(
    engine: Engine,
    timeout: Duration?,
): SnapshotKeys =
    engine.call(
        ApiRequest("GET", "/customers/snapshot-keys", timeout = timeout, anonymous = true),
        SnapshotKeys.serializer(),
    )

private suspend fun pricingOf(
    engine: Engine,
    visitor: String?,
    revalidate: Boolean,
    timeout: Duration?,
): Pricing =
    engine.cached(
        ApiRequest("GET", "/pricing", visitor = checkVisitor(visitor), timeout = timeout),
        null,
        Pricing.serializer(),
        revalidate,
    )

private fun clientScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal class ServerClient(
    key: String,
    config: EntitlerServerConfig,
) : EntitlerServer {
    private val engine = Engine(config, Credentials(key, null), null, clientScope())

    override val customers: ServerCustomers = Customers(engine)

    override fun customer(id: String): ServerCustomer = ServerCustomerImpl(engine, checkCustomerId(id))

    override suspend fun recordUsageBatch(
        events: List<UsageBatchEvent>,
        register: Boolean,
        timeout: Duration?,
    ): UsageEventsResult {
        engine.ensureOpen()
        val results = arrayOfNulls<UsageEventResult>(events.size)
        val sendable = mutableListOf<IndexedValue<UsageBatchEvent>>()
        events.forEachIndexed { index, event ->
            val refusal = batchRefusal(event)
            if (refusal == null) {
                sendable += IndexedValue(index, event)
            } else {
                results[index] = UsageEventResult(index, UsageOutcome.Error, null, false, refusal, event.idempotencyKey)
            }
        }
        sendable.chunked(BATCH_SIZE).forEach { slice ->
            sendBatch(slice, register, timeout).forEachIndexed { position, result -> results[slice[position].index] = result }
        }
        val all = results.map { checkNotNull(it) }
        return UsageEventsResult(
            all,
            all.count { it.outcome == UsageOutcome.Recorded },
            all.count { it.outcome == UsageOutcome.Duplicate },
            all.count { it.outcome == UsageOutcome.Error },
        )
    }

    private suspend fun sendBatch(
        slice: List<IndexedValue<UsageBatchEvent>>,
        register: Boolean,
        timeout: Duration?,
    ): List<UsageEventResult> {
        val events = slice.map { it.value }
        val body =
            buildJsonObject {
                if (register) put("register", true)
                put("events", JsonArray(events.map(::eventJson)))
            }
        events.map { it.customer }.distinct().forEach(engine::bump)
        return try {
            val answer =
                engine.call(
                    ApiRequest("POST", "/usage/events", body = body, idempotencyKey = batchKey(events, register), timeout = timeout),
                    UsageEventsResult.serializer(),
                )
            val byIndex = answer.results.associateBy { it.index }
            if (answer.results.size != slice.size || byIndex.keys != slice.indices.toSet()) return failed(slice, missingResult)
            slice.mapIndexed { position, (index, event) ->
                byIndex.getValue(position).copy(index = index, idempotencyKey = event.idempotencyKey, replayed = answer.replayed)
            }
        } catch (error: EntitlerException) {
            failed(slice, batchError(error))
        }
    }

    private fun failed(
        slice: List<IndexedValue<UsageBatchEvent>>,
        failure: UsageEventError,
    ): List<UsageEventResult> =
        slice.map { (index, event) ->
            UsageEventResult(index, UsageOutcome.Error, null, false, failure, event.idempotencyKey)
        }

    override suspend fun pricing(
        visitor: String?,
        revalidate: Boolean,
        timeout: Duration?,
    ): Pricing = pricingOf(engine, visitor, revalidate, timeout)

    override suspend fun features(
        revalidate: Boolean,
        timeout: Duration?,
    ): FeatureList = engine.cached(ApiRequest("GET", "/pricing/features", timeout = timeout), null, FeatureList.serializer(), revalidate)

    override suspend fun scopes(timeout: Duration?): Scopes = scopesOf(engine, timeout)

    override suspend fun snapshotKeys(timeout: Duration?): SnapshotKeys = snapshotKeysOf(engine, timeout)

    override fun close() = engine.close()

    override fun toString(): String = "EntitlerServer(baseUrl=${engine.baseUrl})"
}

private fun batchError(error: EntitlerException): UsageEventError =
    when (error) {
        is EntitlerApiException -> UsageEventError(error.code, error.message.orEmpty())
        is EntitlerTimeoutException -> UsageEventError(ErrorCode.TimedOut, error.message.orEmpty())
        else -> UsageEventError(ErrorCode.ConnectionFailed, error.message.orEmpty())
    }

private fun batchRefusal(event: UsageBatchEvent): UsageEventError? {
    val checks =
        listOf(
            ErrorCode.InvalidBody to { checkCustomerId(event.customer) },
            ErrorCode.InvalidBody to { checkId(event.feature.key, FEATURE_REQUIRED) },
            ErrorCode.InvalidAmount to { checkAmount(event.amount) },
            ErrorCode.InvalidIdempotencyKey to { checkIdempotencyKey(event.idempotencyKey) },
        )
    for ((code, check) in checks) {
        try {
            check()
        } catch (error: IllegalArgumentException) {
            return UsageEventError(code, error.message.orEmpty())
        }
    }
    return null
}

private fun eventJson(event: UsageBatchEvent): JsonObject =
    buildJsonObject {
        put("customer", event.customer)
        put("feature", event.feature.key)
        put("amount", event.amount)
        event.occurredAt?.let { put("occurredAt", formatInstant(it)) }
        put("idempotencyKey", event.idempotencyKey)
    }

private val missingResult = UsageEventError(ErrorCode.InvalidResponse, "Entitler sent an answer this SDK cannot read.")

internal fun batchKey(
    events: List<UsageBatchEvent>,
    register: Boolean,
): String {
    val rows =
        events.map { event ->
            buildJsonArray {
                add(JsonPrimitive(event.customer))
                add(JsonPrimitive(event.feature.key))
                add(JsonPrimitive(event.amount))
                add(event.occurredAt?.let { JsonPrimitive(formatInstant(it)) } ?: JsonNull)
                add(JsonPrimitive(event.idempotencyKey))
            }
        }
    return "batch:" + sha256(JsonArray(listOf(JsonPrimitive("entitler-batch-v1"), JsonPrimitive(register), JsonArray(rows))).toString())
}

internal class AppClient(
    key: String?,
    token: String?,
    provider: (suspend () -> String)?,
    configure: EntitlerClientConfig.() -> Unit,
    store: CacheStore? = null,
) : EntitlerIdentityClient {
    private val config = EntitlerClientConfig().apply(configure).also { it.validate() }
    private val scope = clientScope()
    private val visitorSource = Visitor(config.visitor, config.visitorSource)
    private val engine =
        Engine(
            config,
            Credentials(key, TokenSource(token, provider, config.clock, config.timeout, scope)),
            visitorSource,
            scope,
            store ?: config.store,
        )

    override val me: Customer = CustomerImpl(engine, "me", null)

    override suspend fun visitor(): String = visitorSource.get()

    override suspend fun register(
        idempotencyKey: String?,
        timeout: Duration?,
    ): RegisteredCustomer {
        engine.bump("me")
        return engine
            .call(
                ApiRequest("PUT", "/customers/me", idempotencyKey = checkIdempotencyKey(idempotencyKey), timeout = timeout),
                RegisteredCustomer.serializer(),
            ).also { (me as CustomerImpl).named(it.externalId) }
    }

    override suspend fun scopes(timeout: Duration?): Scopes = scopesOf(engine, timeout)

    override suspend fun snapshotKeys(timeout: Duration?): SnapshotKeys = snapshotKeysOf(engine, timeout)

    override fun close() = engine.close()

    override fun toString(): String = "EntitlerClient(baseUrl=${engine.baseUrl}, kind=${engine.kind})"
}

internal class PublishableClient(
    key: String,
    configure: EntitlerClientConfig.() -> Unit,
    store: CacheStore? = null,
) : EntitlerPublishableClient {
    private val config = EntitlerClientConfig().apply(configure).also { it.validate() }
    private val visitorSource = Visitor(config.visitor, config.visitorSource)
    private val engine = Engine(config, Credentials(key, null, publishable = true), visitorSource, clientScope(), store ?: config.store)

    override suspend fun pricing(
        visitor: String?,
        revalidate: Boolean,
        timeout: Duration?,
    ): Pricing = pricingOf(engine, visitor, revalidate, timeout)

    override suspend fun visitor(): String = visitorSource.get()

    override suspend fun snapshotKeys(timeout: Duration?): SnapshotKeys = snapshotKeysOf(engine, timeout)

    override fun close() = engine.close()

    override fun toString(): String = "EntitlerPublishableClient(baseUrl=${engine.baseUrl})"
}

/** The server's customers. */
public interface ServerCustomers {
    /**
     * Every customer, `GET /customers`, fetching each page only when collection reaches it. Needs
     * `customers:read`.
     *
     * @param q Search by external id, name or email.
     * @param cohort A cohort, as `<plan key or id>:<n>`.
     * @param track A track's id.
     * @param includeTest Include test customers.
     * @param cursor Start at the page this cursor names, such as a page's [CustomerPage.next].
     */
    public fun list(
        q: String? = null,
        cohort: String? = null,
        track: String? = null,
        includeTest: Boolean = false,
        cursor: String? = null,
        timeout: Duration? = null,
    ): Flow<CustomerSummary>

    /** One page of the customer list, from [cursor] (null for the first), with the list's `used` and `limit`. */
    public suspend fun page(
        q: String? = null,
        cohort: String? = null,
        track: String? = null,
        includeTest: Boolean = false,
        cursor: String? = null,
        timeout: Duration? = null,
    ): CustomerPage

    /**
     * Creates a customer, `POST /customers`. Needs `customers:write`. Prefer
     * [ServerCustomer.register], which keeps a customer's details current at sign-up and sign-in.
     *
     * @param id The id your app uses for the customer.
     * @param plan A plan to start them on, by key or id.
     * @param period The plan's period, by its key, such as `monthly`.
     */
    public suspend fun create(
        id: String,
        name: String,
        email: String? = null,
        plan: String? = null,
        period: String? = null,
        metadata: Map<String, String>? = null,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): CustomerDetail
}

internal class Customers(
    private val engine: Engine,
) : ServerCustomers {
    override fun list(
        q: String?,
        cohort: String?,
        track: String?,
        includeTest: Boolean,
        cursor: String?,
        timeout: Duration?,
    ): Flow<CustomerSummary> =
        flow {
            var next: String? = cursor
            do {
                val page = page(q, cohort, track, includeTest, next, timeout)
                page.items.forEach { emit(it) }
                next = page.next
            } while (next != null)
        }

    override suspend fun page(
        q: String?,
        cohort: String?,
        track: String?,
        includeTest: Boolean,
        cursor: String?,
        timeout: Duration?,
    ): CustomerPage {
        val query =
            listOfNotNull(
                q?.let { "q" to it },
                cohort?.let { "cohort" to it },
                track?.let { "track" to it },
                if (includeTest) "includeTest" to "true" else null,
                cursor?.let { "cursor" to it },
            )
        return engine.call(ApiRequest("GET", "/customers", query, timeout = timeout), CustomerPage.serializer())
    }

    override suspend fun create(
        id: String,
        name: String,
        email: String?,
        plan: String?,
        period: String?,
        metadata: Map<String, String>?,
        idempotencyKey: String?,
        timeout: Duration?,
    ): CustomerDetail {
        val externalId = checkCustomerId(id)
        val body =
            buildJsonObject {
                put("externalId", externalId)
                put("name", name)
                email?.let { put("email", it) }
                plan?.let { put("plan", it) }
                period?.let { put("period", it) }
                metadata?.let { put("metadata", JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) })) }
            }
        engine.bump(externalId)
        return engine
            .call(
                ApiRequest("POST", "/customers", body = body, idempotencyKey = checkIdempotencyKey(idempotencyKey), timeout = timeout),
                CustomerDetail.serializer(),
            )
    }
}

internal fun scopesJson(scopes: List<Scope>) = buildJsonArray { scopes.forEach { add(JsonPrimitive(it.value)) } }
