package com.entitlerhq.entitler

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
            value ?: checkNotNull(checkVisitor(checkNotNull(source)())).also { value = it }
        }
}
