package com.entitlerhq.entitler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private const val REFRESH_MARGIN_SECONDS = 60L
private const val MILLIS_PER_SECOND = 1000L

internal class HeldToken(
    val token: String,
    private val receivedAt: Long,
) {
    private val claims = claimsOf(token)
    private val exp = claims?.long("exp")
    private val iat = claims?.long("iat")

    fun expiresSoon(now: Long): Boolean {
        val exp = exp ?: return false
        val start = iat ?: (receivedAt / MILLIS_PER_SECOND)
        val margin = minOf(REFRESH_MARGIN_SECONDS, (exp - start) / 2)
        return now >= (exp - margin) * MILLIS_PER_SECOND
    }
}

internal fun isReadableToken(token: String): Boolean {
    val claims = claimsOf(token) ?: return false
    val exp = claims.long("exp") ?: return false
    if (!claims.containsKey("iat")) return true
    val iat = claims.long("iat") ?: return false
    return iat < exp
}

internal class TokenSource(
    fixed: String?,
    private val provider: (suspend () -> String)?,
    private val clock: () -> Long,
    private val timeout: Duration,
    private val scope: CoroutineScope,
) {
    private val lock = Any()

    @Volatile private var current: HeldToken? = fixed?.let { HeldToken(it, clock()) }

    private var pending: Deferred<Result<String>>? = null

    val refreshable: Boolean get() = provider != null

    val held: String? get() = current?.token

    suspend fun get(): String {
        val held = current
        if (held != null && !(refreshable && held.expiresSoon(clock()))) return held.token
        return refresh(held?.token)
    }

    suspend fun refresh(seen: String?): String {
        val provide = provider ?: return checkNotNull(current).token
        val waiting =
            synchronized(lock) {
                val held = current
                if (held != null && held.token != seen && !held.expiresSoon(clock())) return held.token
                pending ?: scope.async { runCatching { fetch(provide) } }.also { pending = it }
            }
        return waiting.await().getOrThrow()
    }

    private suspend fun fetch(provide: suspend () -> String): String {
        try {
            val answer =
                runCatching { withTimeoutOrNull(timeout) { provide() } }.getOrElse {
                    if (it is CancellationException) throw it
                    tokenError("The token provider failed: ${it.javaClass.simpleName}.", it)
                } ?: tokenError("The token provider did not answer within the client's timeout.")
            val token = answer.trim()
            if (token.isEmpty()) tokenError("The token provider answered a blank token.")
            if (!isReadableToken(token)) tokenError("The token provider answered a token that cannot be read.")
            synchronized(lock) { current = HeldToken(token, clock()) }
            return token
        } finally {
            synchronized(lock) { pending = null }
        }
    }
}

private fun tokenError(
    message: String,
    cause: Throwable? = null,
): Nothing = throw EntitlerTokenException(message, cause)

internal class Credential(
    val bearer: String,
    val identityToken: String?,
)

internal class Credentials(
    private val key: String?,
    private val token: TokenSource?,
) {
    val kind: String =
        when {
            token == null -> "server"
            key == null -> "in-app with a customer token"
            else -> "in-app with an identity token"
        }

    val principalKind: String =
        when {
            token == null -> "key"
            key == null -> "customer-token"
            else -> "identity"
        }

    val refreshable: Boolean get() = token?.refreshable == true

    suspend fun current(): Credential = credentialOf(token?.get())

    fun held(): Credential? = token?.held?.let(::credentialOf)

    suspend fun refresh(seen: Credential): Credential = credentialOf(token?.refresh(seen.identityToken ?: seen.bearer))

    private fun credentialOf(token: String?): Credential =
        when {
            token == null -> Credential(checkNotNull(key), null)
            key == null -> Credential(token, null)
            else -> Credential(key, token)
        }

    fun principal(credential: Credential): String =
        sha256(if (credential.identityToken == null) credential.bearer else credential.bearer + "\n" + credential.identityToken)
}
