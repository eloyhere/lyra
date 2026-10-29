package com.eloykim.lyra.amp.protocol.store

/**
 * Read-through entity cache with TTL and LRU eviction (port of
 * `src/store/cache.ts`).
 *
 * `store` is the read-through cache between callers and the database services.
 * A miss calls [EntityCacheOptions.load]; repeated reads hit the LRU until the
 * TTL expires. The cache is bounded, so a long session cannot grow it without
 * limit.
 *
 * The reference exposes the cache through a JavaScript `Proxy`, so
 * `cache.entities["42"]` reads through to the service. Kotlin has no Proxy;
 * [EntityCache.entities] returns a snapshot of the resident entries instead,
 * while [EntityCache.get] keeps the read-through behaviour. This is a binding
 * difference, not a semantic one: the reference proxy's `ownKeys` also only
 * reports resident entries.
 */
data class EntityCacheOptions<T : Any>(
    val load: (String) -> T?,
    val keyOf: (T) -> String,
    val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Time-to-live in milliseconds; a value of `0` means "current clock tick
     * only", mirroring the reference's inclusive comparison.
     */
    val ttlMs: Long = DEFAULT_TTL_MS,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_CAPACITY: Int = 256
        const val DEFAULT_TTL_MS: Long = 2000
    }
}

data class CacheStats(
    val loads: Int,
    val hits: Int,
    val misses: Int,
    val evictions: Int,
    val size: Int,
    val capacity: Int,
)

/**
 * The cache itself.
 *
 * A successful `get` is a hit while `now - loadedAt <= ttlMs`; an expired entry
 * is removed and reloaded. `loads` counts successful loads, `misses` counts
 * every load attempt (including a `null` result), matching the reference
 * counters exactly.
 *
 * Not synchronized: the reference is a single-threaded Node store, and callers
 * that share one cache across threads must serialize access.
 */
class EntityCache<T : Any>(options: EntityCacheOptions<T>) {

    val capacity: Int = options.capacity
    val ttlMs: Long = options.ttlMs

    private val load: (String) -> T? = options.load
    private val keyOf: (T) -> String = options.keyOf
    private val now: () -> Long = options.now

    /** Access-ordered: iteration is least-recently-used first. */
    private val lru = LinkedHashMap<String, T>(16, 0.75f, true)
    private val loadedAt = LinkedHashMap<String, Long>()

    private var loads: Int = 0
    private var hits: Int = 0
    private var misses: Int = 0
    private var evictions: Int = 0

    init {
        // The reference delegates this check to LRUUnorderedMap.
        require(options.capacity > 0) { "capacity must be a positive safe integer" }
    }

    /** Read through the cache; a miss calls the service loader. */
    fun get(id: String): T? {
        val key = id
        if (lru.containsKey(key)) {
            val cached = lru[key] ?: error("cache entry disappeared")
            val at = loadedAt[key] ?: 0L
            if (now() - at <= ttlMs) {
                hits += 1
                return cached
            }
            lru.remove(key)
            loadedAt.remove(key)
        }

        val loaded = load(key)
        misses += 1
        if (loaded == null) {
            return null
        }
        evictIfNeeded()
        lru[key] = loaded
        loadedAt[key] = now()
        loads += 1
        return loaded
    }

    fun get(id: Int): T? = get(id.toString())

    /**
     * Look at a cached value without loading or extending its TTL.
     *
     * Matching the reference, the lookup still marks the entry most recently
     * used; only `loadedAt` (and therefore the TTL) is left alone.
     */
    fun peek(id: String): T? = if (lru.containsKey(id)) lru[id] else null

    fun peek(id: Int): T? = peek(id.toString())

    fun put(value: T) {
        val key = keyOf(value)
        evictIfNeeded()
        lru[key] = value
        loadedAt[key] = now()
    }

    /** Clear every entry. */
    fun invalidate() {
        lru.clear()
        loadedAt.clear()
    }

    fun invalidate(id: String) {
        lru.remove(id)
        loadedAt.remove(id)
    }

    fun invalidate(id: Int) = invalidate(id.toString())

    /** Resident values, least recently used first. */
    fun entries(): List<T> = lru.values.toList()

    val size: Int get() = lru.size

    fun stats(): CacheStats = CacheStats(
        loads = loads,
        hits = hits,
        misses = misses,
        evictions = evictions,
        size = lru.size,
        capacity = capacity,
    )

    /** Snapshot of the resident entries; see the class KDoc for the Proxy difference. */
    fun entities(): Map<String, T> = LinkedHashMap(lru)

    private fun evictIfNeeded() {
        while (lru.size >= capacity) {
            val victim = lru.keys.firstOrNull() ?: return
            lru.remove(victim)
            loadedAt.remove(victim)
            evictions += 1
        }
    }
}
