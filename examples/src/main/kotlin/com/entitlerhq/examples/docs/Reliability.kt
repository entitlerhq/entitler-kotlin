package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.CacheEntry
import com.entitlerhq.entitler.CacheStore
import com.entitlerhq.entitler.Customer
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.MemoryCacheStore
import com.entitlerhq.examples.generatedfeatures.Features
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Reliability: timeouts, retries and stale answers. */
fun reliableServer(): EntitlerServer =
    EntitlerServer(System.getenv("ENTITLER_KEY")) {
        timeout = 3.seconds
        maxRetries = 3
        maxRetryDelay = 5.seconds
        staleFor = 6.hours
        cache = MemoryCacheStore(5_000)
        onError = { error -> println("Entitler fallback: ${error.message}") }
    }

/** Reliability: a per-call timeout. */
suspend fun quickCheck(customer: Customer): Boolean = customer.check(Features.exportPdf, timeout = 1.seconds).entitled

/** Reliability: a stale answer. */
suspend fun staleAnswer(customer: Customer) {
    val check = customer.check(Features.exportPdf)
    if (check.stale) println("Entitler is unreachable; this answer was kept from earlier.")
}

/** Reliability: a custom cache store. */
class MapCacheStore : CacheStore {
    private val entries = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun get(key: String): CacheEntry? = entries[key]

    override suspend fun set(
        key: String,
        entry: CacheEntry,
    ) {
        entries[key] = entry
    }
}

/** Reliability: turning the cache off. */
fun uncachedServer(): EntitlerServer = EntitlerServer(System.getenv("ENTITLER_KEY")) { cache = null }
