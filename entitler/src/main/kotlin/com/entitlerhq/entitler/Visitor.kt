package com.entitlerhq.entitler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class Visitor(
    given: String?,
    private val source: (suspend () -> String)?,
) {
    @Volatile private var value: String? = checkVisitor(given) ?: if (source == null) newVisitorId() else null

    private val lock = Mutex()

    suspend fun get(): String =
        value ?: lock.withLock {
            value ?: fromSource().also { value = it }
        }

    private suspend fun fromSource(): String {
        val answer =
            runCatching { checkNotNull(source)() }.getOrElse {
                if (it is CancellationException) currentCoroutineContext().ensureActive()
                if (it !is Exception) throw it
                throw EntitlerTokenException("The visitor source failed: ${it.javaClass.simpleName}.", it)
            }
        return try {
            checkNotNull(checkVisitor(answer))
        } catch (error: IllegalArgumentException) {
            throw EntitlerTokenException("The visitor source answered an id that is not a visitor id.", error)
        }
    }
}
