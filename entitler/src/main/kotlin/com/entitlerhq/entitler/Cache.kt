package com.entitlerhq.entitler

import java.time.Instant
import kotlin.time.Duration

/**
 * A kept answer in the answer cache.
 *
 * The [body] holds the answer as Entitler sent it: keep it as private as the answers your app shows.
 * Its string form never shows the body.
 *
 * @property body The answer body.
 * @property etag The answer's `ETag`, or null.
 * @property maxAge How long the answer stays fresh, from its `Cache-Control`, or null.
 * @property receivedAt When the answer was received.
 */
public data class CacheEntry(
    val body: String,
    val etag: String?,
    val maxAge: Duration?,
    val receivedAt: Instant,
) {
    override fun toString(): String = "CacheEntry(etag=$etag, maxAge=$maxAge, receivedAt=$receivedAt)"
}

/**
 * Where the answer cache keeps answers. Implementations must be safe for concurrent use.
 *
 * Keys are lowercase hex SHA-256 digests and never hold a credential. Implement it to share answers
 * through Redis, or to keep them across app launches:
 *
 * ```kotlin
 * class RedisCacheStore(private val redis: Redis) : CacheStore {
 *     override suspend fun get(key: String): CacheEntry? = redis.get(key)?.let(::decode)
 *     override suspend fun set(key: String, entry: CacheEntry) = redis.set(key, encode(entry))
 * }
 * ```
 */
public interface CacheStore {
    /** The entry kept under [key], or null. */
    public suspend fun get(key: String): CacheEntry?

    /** Keeps [entry] under [key], replacing any entry there. */
    public suspend fun set(
        key: String,
        entry: CacheEntry,
    )
}

/**
 * An in-memory [CacheStore] that keeps at most [maxEntries] answers, least recently used first out.
 * It is the clients' default, with 1,000 answers.
 */
public class MemoryCacheStore(
    private val maxEntries: Int = 1000,
) : CacheStore {
    init {
        require(maxEntries > 0) { "Pass maxEntries as 1 or more." }
    }

    private val entries =
        object : LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean = size > maxEntries
        }

    override suspend fun get(key: String): CacheEntry? = synchronized(entries) { entries[key] }

    override suspend fun set(
        key: String,
        entry: CacheEntry,
    ) {
        synchronized(entries) { entries[key] = entry }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
