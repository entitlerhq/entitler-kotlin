package com.entitlerhq.entitler

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration

/**
 * A kept answer in the answer cache, serialisable with kotlinx.serialization so a custom store can
 * keep it anywhere.
 *
 * The [body] holds the answer as Entitler sent it: keep it as private as the answers your app shows.
 * Its string form never shows the body.
 *
 * @property v The entry format's version, `1`.
 * @property body The answer body, as raw JSON text.
 * @property etag The answer's `ETag`, or null.
 * @property cacheControl The answer's `Cache-Control` directives, or null.
 * @property age The answer's `Age` header in seconds, or null.
 * @property receivedAt When the answer was received.
 */
@Serializable
public data class CacheEntry(
    @EncodeDefault val v: Int = 1,
    val body: String,
    val etag: String? = null,
    val cacheControl: String? = null,
    val age: Long? = null,
    @Serializable(with = InstantSerializer::class) val receivedAt: Instant,
) {
    /** A string form that never shows the body. */
    override fun toString(): String = "CacheEntry(v=$v, etag=$etag, cacheControl=$cacheControl, age=$age, receivedAt=$receivedAt)"
}

/**
 * Where the answer cache keeps answers. Implementations must be safe for concurrent use.
 *
 * Keys are lowercase hex SHA-256 digests and never hold a credential. Implement it to share answers
 * through Redis, or to keep them across app launches. A `get` that throws counts as a miss and a
 * `set` that throws is skipped; both go to `onError` and neither fails the call.
 *
 * ```kotlin
 * class RedisCacheStore(private val redis: Redis) : CacheStore {
 *     override suspend fun get(key: String): CacheEntry? = redis.get(key)?.let(::decode)
 *     override suspend fun set(key: String, entry: CacheEntry, ttl: Duration) = redis.set(key, encode(entry), ttl)
 * }
 * ```
 */
public interface CacheStore {
    /** The entry kept under [key], or null. */
    public suspend fun get(key: String): CacheEntry?

    /**
     * Keeps [entry] under [key], replacing any entry there. A store that expires entries keeps it for
     * [ttl]: `staleFor` plus the answer's `max-age`.
     */
    public suspend fun set(
        key: String,
        entry: CacheEntry,
        ttl: Duration,
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
        ttl: Duration,
    ) {
        synchronized(entries) { entries[key] = entry }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
