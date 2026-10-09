@file:JvmName("Entitler")
@file:JvmMultifileClass

package com.entitlerhq.entitler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.Closeable
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Options shared by both clients, set in the factory's configuration lambda:
 *
 * ```kotlin
 * val server = EntitlerServer(System.getenv("ENTITLER_KEY")) {
 *     timeout = 5.seconds
 *     maxRetries = 3
 * }
 * ```
 */
public open class EntitlerConfig internal constructor() {
    /** The API's base URL; trailing slashes are removed. */
    public var baseUrl: String = "https://api.entitler.dev"

    /** How long each attempt may take, from connecting to the last byte of the answer. */
    public var timeout: Duration = 10.seconds

    /** How many times a failed request is retried after the first attempt. */
    public var maxRetries: Int = 2

    /** The longest `Retry-After` the SDK waits for; a longer one fails at once. */
    public var maxRetryDelay: Duration = 10.seconds

    /** Where answers are kept, or null to turn the answer cache off. */
    public var cache: CacheStore? = MemoryCacheStore()

    /** How long a kept answer may stand in when Entitler is unreachable. */
    public var staleFor: Duration = 24.hours

    /**
     * Called with each error a fallback absorbed: a stale answer, `isEntitled`'s default, a failed release,
     * or a custom cache store's own failure. An `onError` that throws is ignored.
     */
    public var onError: ((Throwable) -> Unit)? = null

    /**
     * Read the API at another instant. Needs the organisation's `as_of` capability (`409 limit_reached`
     * otherwise); pricing is always computed now, and writes take it only in a test environment.
     */
    public var asOf: Instant? = null

    /**
     * The OkHttp client to send requests with, to share its connection pool or add interceptors. Its own
     * timeouts apply beside [timeout]. A client the SDK builds is released on `close()`; yours is not.
     */
    public var httpClient: OkHttpClient? = null

    internal var clock: () -> Long = System::currentTimeMillis

    internal var random: Random = Random.Default

    internal fun validate() {
        require(maxRetries >= 0) { "Pass maxRetries as 0 or more." }
        require(timeout.isPositive()) { "Pass timeout as a positive duration." }
        require(!maxRetryDelay.isNegative()) { "Pass maxRetryDelay as 0 or more." }
        asOf?.let {
            val year = runCatching { it.atOffset(java.time.ZoneOffset.UTC).year }.getOrNull()
            require(year != null && year in MIN_YEAR..MAX_YEAR) { "Pass asOf as a valid date." }
        }
    }

    private companion object {
        const val MIN_YEAR = 1
        const val MAX_YEAR = 9999
    }
}

/** Options for the in-app clients: those of [EntitlerConfig] plus [visitor]. */
public class EntitlerClientConfig internal constructor() : EntitlerConfig() {
    /**
     * The visitor id to send, kept from an earlier launch; null to generate one for this client's lifetime.
     * Keep [EntitlerClient.visitor] in DataStore or SharedPreferences and pass it back here.
     */
    public var visitor: String? = null
}

/**
 * The server client, built from a secret project key. It runs on your servers and acts on any customer.
 * Never ship a secret key in an app: anyone can read it there. Apps use [EntitlerClient].
 *
 * Thread-safe and meant to be shared: build one per process and close it on shutdown.
 *
 * ```kotlin
 * val entitler = EntitlerServer(System.getenv("ENTITLER_KEY"))
 * val customer = entitler.customer("user_123")
 * customer.register(name = "Ada", email = "ada@example.com")
 * if (customer.isEntitled(Features.exportPdf)) exportPdf()
 * customer.recordUsage(Features.aiCredits, 5)
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
     * It answers one result per event, in input order. A request that fails after its retries answers its
     * events with [UsageOutcome.Error] and carries on with the next; only argument errors and cancellation
     * escape. Resend the events answered `error` with the same keys.
     *
     * @param register Register customers who are not registered yet.
     * @param idempotencyKey The batch's key, 1 to 190 characters; request `n` sends it plus `:n`.
     */
    public suspend fun recordUsageBatch(
        events: List<UsageBatchEvent>,
        register: Boolean = false,
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): UsageEventsResult

    /** Signed-out pricing, `GET /pricing`, through the cache. Needs `plans:read`. */
    public suspend fun pricing(
        visitor: String? = null,
        timeout: Duration? = null,
    ): Pricing

    /** The catalogue's features, `GET /pricing/features`, through the cache. Needs `plans:read`. */
    public suspend fun features(timeout: Duration? = null): FeatureList

    /** The scopes the key holds, `GET /keys/self`. Each call asks again. */
    public suspend fun scopes(timeout: Duration? = null): Scopes

    /** The keys that verify snapshots, `GET /customers/snapshot-keys`. Sends no credential. */
    public suspend fun snapshotKeys(timeout: Duration? = null): SnapshotKeys

    /** Verifies a snapshot with no request; see the top-level [com.entitlerhq.entitler.verifySnapshot]. */
    public fun verifySnapshot(
        token: String,
        keys: List<SnapshotKey>,
        customer: String,
        environment: String,
        issuer: String = SNAPSHOT_ISSUER,
        now: Instant = Instant.now(),
        clockSkew: Duration = 60.seconds,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, keys, customer, environment, issuer, now, clockSkew)

    /** Verifies a snapshot against the key set from [snapshotKeys], with no request. */
    public fun verifySnapshot(
        token: String,
        keys: SnapshotKeys,
        customer: String,
        environment: String,
        issuer: String = SNAPSHOT_ISSUER,
        now: Instant = Instant.now(),
        clockSkew: Duration = 60.seconds,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, keys, customer, environment, issuer, now, clockSkew)

    /** A new visitor id for your app to keep, for example in a first-party cookie. */
    public fun newVisitorId(): String = com.entitlerhq.entitler.newVisitorId()
}

/**
 * The in-app client for browsers, mobile and desktop apps, acting on the signed-in customer, [me].
 *
 * Built from a customer token your server minted with [ServerCustomer.token], or a provider that
 * fetches one. It never takes a secret key. Thread-safe; close it when the app no longer needs it.
 *
 * ```kotlin
 * val entitler = EntitlerClient({ api.fetchEntitlerToken() })
 * if (entitler.me.isEntitled(Features.exportPdf)) showExportButton()
 * ```
 */
public interface EntitlerClient : Closeable {
    /** The signed-in customer. Makes no request. */
    public val me: Customer

    /** The client's visitor id, sent on every request so an experiment's arm stays the same. */
    public val visitor: String

    /** The scopes the credential holds, `GET /keys/self`. Each call asks again. */
    public suspend fun scopes(timeout: Duration? = null): Scopes

    /** The keys that verify snapshots, `GET /customers/snapshot-keys`. Sends no credential. */
    public suspend fun snapshotKeys(timeout: Duration? = null): SnapshotKeys

    /** Verifies a snapshot with no request; see the top-level [com.entitlerhq.entitler.verifySnapshot]. */
    public fun verifySnapshot(
        token: String,
        keys: List<SnapshotKey>,
        customer: String,
        environment: String,
        issuer: String = SNAPSHOT_ISSUER,
        now: Instant = Instant.now(),
        clockSkew: Duration = 60.seconds,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, keys, customer, environment, issuer, now, clockSkew)

    /** Verifies a snapshot against the key set from [snapshotKeys], with no request. */
    public fun verifySnapshot(
        token: String,
        keys: SnapshotKeys,
        customer: String,
        environment: String,
        issuer: String = SNAPSHOT_ISSUER,
        now: Instant = Instant.now(),
        clockSkew: Duration = 60.seconds,
    ): VerifiedSnapshot = com.entitlerhq.entitler.verifySnapshot(token, keys, customer, environment, issuer, now, clockSkew)
}

/**
 * An in-app client built from a publishable project key holding only product scopes and an identity
 * token from a sign-in provider registered on the project. It can also [register] the person.
 */
public interface EntitlerIdentityClient : EntitlerClient {
    /** Registers the signed-in person as a customer, `PUT /customers/me`. Needs `customers:register`. */
    public suspend fun register(
        idempotencyKey: String? = null,
        timeout: Duration? = null,
    ): RegisteredCustomer
}

/** Builds the server client from a secret project [key]. */
@JvmOverloads
public fun EntitlerServer(
    key: String,
    configure: EntitlerConfig.() -> Unit = {},
): EntitlerServer = ServerClient(requireKey(key), EntitlerConfig().apply(configure).also { it.validate() })

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
): EntitlerIdentityClient = AppClient(requireKey(key), requireToken(identityToken, IDENTITY_TOKEN_REQUIRED), null, configure)

/** Builds an identity client from a publishable [key] and a provider that answers a fresh identity token. */
@JvmOverloads
public fun EntitlerIdentityClient(
    key: String,
    identityToken: suspend () -> String,
    configure: EntitlerClientConfig.() -> Unit = {},
): EntitlerIdentityClient = AppClient(requireKey(key), null, identityToken, configure)

private const val CUSTOMER_TOKEN_REQUIRED = "Provide a customer token minted by your server."
private const val IDENTITY_TOKEN_REQUIRED = "Provide the identity token your sign-in provider issued."
private const val BATCH_SIZE = 500
private const val MAX_BATCH_KEY_LENGTH = 190

private fun batchError(error: EntitlerException): UsageEventError =
    when (error) {
        is EntitlerApiException -> UsageEventError(error.code, error.message.orEmpty())
        is EntitlerTimeoutException -> UsageEventError(ErrorCode.TimedOut, error.message.orEmpty())
        else -> UsageEventError(ErrorCode.ConnectionFailed, error.message.orEmpty())
    }

private fun requireKey(key: String): String =
    key.trim().also {
        require(it.isNotEmpty()) { "Provide an Entitler API key from the dashboard." }
    }

private fun requireToken(
    token: String,
    message: String,
): String = token.trim().also { require(it.isNotEmpty()) { message } }

private val knownScopes =
    listOf(
        Scope.PlansRead,
        Scope.EntitlementsRead,
        Scope.UsageRead,
        Scope.UsageWrite,
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

internal class ServerClient(
    key: String,
    config: EntitlerConfig,
) : EntitlerServer {
    private val engine = Engine(config, Credentials(key, null), null)

    override val customers: ServerCustomers = Customers(engine)

    override fun customer(id: String): ServerCustomer = ServerCustomerImpl(engine, checkCustomerId(id))

    override suspend fun recordUsageBatch(
        events: List<UsageBatchEvent>,
        register: Boolean,
        idempotencyKey: String?,
        timeout: Duration?,
    ): UsageEventsResult {
        checkIdempotencyKey(idempotencyKey, MAX_BATCH_KEY_LENGTH)
        val keyed =
            events.map { event ->
                checkAmount(event.amount)
                checkCustomerId(event.customer)
                event to (checkIdempotencyKey(event.idempotencyKey) ?: newIdempotencyKey())
            }
        val results = mutableListOf<UsageEventResult>()
        keyed.chunked(BATCH_SIZE).forEachIndexed { chunk, slice ->
            val body =
                buildJsonObject {
                    if (register) put("register", true)
                    put("events", JsonArray(slice.map { (event, key) -> eventJson(event, key) }))
                }
            val requestKey = idempotencyKey?.let { "$it:$chunk" }
            slice.map { it.first.customer }.distinct().forEach(engine::bump)
            val answered =
                try {
                    engine
                        .call(
                            ApiRequest("POST", "/usage/events", body = body, idempotencyKey = requestKey, timeout = timeout),
                            UsageEventsResult.serializer(),
                        ).results
                        .map { it.copy(index = it.index + chunk * BATCH_SIZE, idempotencyKey = slice.getOrNull(it.index)?.second) }
                } catch (error: EntitlerException) {
                    val failure = batchError(error)
                    slice.mapIndexed {
                        index,
                        (_, key),
                        ->
                        UsageEventResult(chunk * BATCH_SIZE + index, UsageOutcome.Error, null, false, failure, key)
                    }
                }
            results += answered
        }
        return UsageEventsResult(
            results,
            results.count { it.outcome == UsageOutcome.Recorded },
            results.count { it.outcome == UsageOutcome.Duplicate },
            results.count { it.outcome == UsageOutcome.Error },
        )
    }

    override suspend fun pricing(
        visitor: String?,
        timeout: Duration?,
    ): Pricing =
        engine.cached(
            ApiRequest("GET", "/pricing", visitor = checkVisitor(visitor), timeout = timeout),
            null,
            Pricing.serializer(),
        )

    override suspend fun features(timeout: Duration?): FeatureList =
        engine.cached(ApiRequest("GET", "/pricing/features", timeout = timeout), null, FeatureList.serializer())

    override suspend fun scopes(timeout: Duration?): Scopes = scopesOf(engine, timeout)

    override suspend fun snapshotKeys(timeout: Duration?): SnapshotKeys = snapshotKeysOf(engine, timeout)

    override fun close() = engine.close()

    override fun toString(): String = "EntitlerServer(baseUrl=${engine.baseUrl})"
}

private fun eventJson(
    event: UsageBatchEvent,
    key: String,
): JsonObject =
    buildJsonObject {
        put("customer", event.customer)
        put("feature", event.feature.key)
        put("amount", event.amount)
        event.occurredAt?.let { put("occurredAt", formatInstant(it)) }
        put("idempotencyKey", key)
    }

internal class AppClient(
    key: String?,
    token: String?,
    provider: (suspend () -> String)?,
    configure: EntitlerClientConfig.() -> Unit,
) : EntitlerIdentityClient {
    private val config = EntitlerClientConfig().apply(configure).also { it.validate() }
    override val visitor: String = checkVisitor(config.visitor) ?: newVisitorId()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine =
        Engine(config, Credentials(key, TokenSource(token, provider, config.clock, config.timeout, scope)), visitor, scope)

    override val me: Customer = CustomerImpl(engine, "me", null)

    override suspend fun register(
        idempotencyKey: String?,
        timeout: Duration?,
    ): RegisteredCustomer =
        engine
            .call(
                ApiRequest("PUT", "/customers/me", idempotencyKey = checkIdempotencyKey(idempotencyKey), timeout = timeout),
                RegisteredCustomer.serializer(),
            ).also {
                engine.bump("me")
                (me as CustomerImpl).named(it.externalId)
            }

    override suspend fun scopes(timeout: Duration?): Scopes = scopesOf(engine, timeout)

    override suspend fun snapshotKeys(timeout: Duration?): SnapshotKeys = snapshotKeysOf(engine, timeout)

    override fun close() = engine.close()

    override fun toString(): String = "EntitlerClient(baseUrl=${engine.baseUrl}, kind=${engine.kind})"
}

/** The server's customers. */
public interface ServerCustomers {
    /**
     * Every customer, `GET /customers`, fetching the next page only when collection reaches it.
     * Needs `customers:read`.
     *
     * @param q Search by external id, name or email.
     * @param cohort A cohort, as `<plan key or id>:<n>`.
     * @param track A track's id.
     * @param includeTest Include test customers.
     */
    public fun list(
        q: String? = null,
        cohort: String? = null,
        track: String? = null,
        includeTest: Boolean = false,
        timeout: Duration? = null,
    ): Flow<CustomerSummary>

    /** One page of the customer list, from [cursor] (null for the first). */
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
        timeout: Duration?,
    ): Flow<CustomerSummary> =
        flow {
            var cursor: String? = null
            do {
                val page = page(q, cohort, track, includeTest, cursor, timeout)
                page.items.forEach { emit(it) }
                cursor = page.next
            } while (cursor != null)
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
