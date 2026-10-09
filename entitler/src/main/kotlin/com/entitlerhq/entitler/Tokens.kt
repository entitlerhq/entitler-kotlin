package com.entitlerhq.entitler

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.cancellation.CancellationException

private const val REFRESH_BEFORE_MILLIS = 60_000L
private const val MILLIS_PER_SECOND = 1000L

internal class TokenSource(
    fixed: String?,
    private val provider: (suspend () -> String)?,
    private val clock: () -> Long,
) {
    @Volatile private var current: String? = fixed
    private val mutex = Mutex()

    val refreshable: Boolean get() = provider != null

    suspend fun get(): String {
        val token = current
        if (token != null && !(refreshable && expiresSoon(token))) return token
        return refresh(token)
    }

    suspend fun refresh(seen: String?): String =
        mutex.withLock {
            val token = current
            if (token != null && token != seen && !expiresSoon(token)) return token
            fetch().also { current = it }
        }

    private suspend fun fetch(): String {
        val provide = provider ?: error("A fixed token cannot be refreshed.")
        val answer =
            runCatching { provide() }.getOrElse {
                if (it is CancellationException) throw it
                throw EntitlerTokenException("The token provider failed: ${it.javaClass.simpleName}.", it)
            }
        val token = answer.trim()
        if (token.isEmpty()) throw EntitlerTokenException("The token provider answered a blank token.")
        if (claimsOf(token) == null) throw EntitlerTokenException("The token provider answered a token that cannot be read as a JWT.")
        return token
    }

    private fun expiresSoon(token: String): Boolean {
        val exp = claimsOf(token)?.long("exp") ?: return false
        return exp * MILLIS_PER_SECOND - REFRESH_BEFORE_MILLIS <= clock()
    }
}

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

    val refreshable: Boolean get() = token?.refreshable == true

    suspend fun current(): Credential {
        val token = token?.get() ?: return Credential(checkNotNull(key), null)
        return if (key == null) Credential(token, null) else Credential(key, token)
    }

    suspend fun refresh(seen: Credential) {
        token?.refresh(seen.identityToken ?: seen.bearer)
    }

    fun principal(credential: Credential): String =
        when {
            token == null -> "key\n${credential.bearer}"
            credential.identityToken == null -> claimed(credential.bearer, "iss", "eid", "sub")
            else -> "identity\n$key\n" + claimed(credential.identityToken, "iss", "sub")
        }

    private fun claimed(
        token: String,
        vararg names: String,
    ): String {
        val claims = claimsOf(token) ?: return "token\n" + token.encodeUtf8().sha256().hex()
        return names.joinToString("\n", prefix = "claims\n") { claims.string(it).orEmpty() }
    }
}

internal class Credential(
    val bearer: String,
    val identityToken: String?,
)
