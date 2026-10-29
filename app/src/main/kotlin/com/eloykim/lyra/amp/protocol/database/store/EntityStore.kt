package com.eloykim.lyra.amp.protocol.database.store

import com.eloykim.lyra.amp.protocol.database.service.Service

/**
 * JPA-style first-level entity cache over one service (port of
 * `src/database/store/store.ts`).
 *
 * The store mirrors the identity map of a JPA persistence context: one managed
 * row per primary key, loaded through the service, and explicitly detached or
 * cleared when stale. It is intentionally read-through; writes still belong to
 * the service and repository so the cache cannot become a second source of
 * truth.
 *
 * ## Why this is not `store/EntityCache.kt`
 *
 * The reference has two caches with different contracts: `src/store/cache.ts`
 * (read-through LRU used by the entity stores, default TTL 2 s, `capacity`
 * entries) and this JPA identity map (TTL 0 by default, `maxEntries` bound with
 * "evict until size <= maxEntries", `requireById`, `contains`, `refresh`).
 * `store/Cache.kt` already ports the first one; this class ports the second and
 * deliberately does not reuse it, because merging them would change eviction and
 * expiry behaviour.
 *
 * @param TEntity cached row shape.
 * @param TKey primary-key type.
 */
data class StoreOptions(
    /** Maximum resident rows; the oldest entry is evicted first. */
    val maxEntries: Int = 256,
    /** Entry lifetime in milliseconds; zero disables expiry. */
    val ttlMs: Long = 0,
    /** Clock used for expiry, injectable for tests. */
    val now: () -> Long = { System.currentTimeMillis() },
)

/** Cache counters exposed for diagnostics. */
data class StoreStats(
    val hits: Int,
    val misses: Int,
    val evictions: Int,
    val size: Int,
)

/**
 * Reference name: `Store`.
 *
 * Not synchronized: the reference is a single-threaded Node store; callers that
 * share one cache across threads must serialize access.
 */
abstract class EntityStore<TEntity : Any, TKey : Any, TService : Service<TEntity, TKey, *>>(
    protected val service: TService,
    options: StoreOptions = StoreOptions(),
) {

    private class CacheEntry<TEntity>(val value: TEntity, val expiresAt: Long)

    private val entries: MutableMap<TKey, CacheEntry<TEntity>> = LinkedHashMap()
    private val maxEntries: Int = options.maxEntries
    private val ttlMs: Long = options.ttlMs
    private val now: () -> Long = options.now
    private var hits: Int = 0
    private var misses: Int = 0
    private var evictions: Int = 0

    init {
        require(maxEntries > 0) { "maxEntries must be a positive integer" }
        require(ttlMs >= 0) { "ttlMs must be a non-negative number" }
    }

    /** Find a row through the identity map; a miss loads it through the service. */
    fun findById(id: TKey): TEntity? {
        val cached = read(id)
        if (cached != null) {
            hits += 1
            touch(id)
            return cached
        }
        misses += 1
        val loaded = service.findById(id)
        if (loaded != null) {
            put(id, loaded)
        }
        return loaded
    }

    /**
     * Find a row or throw through the service.
     *
     * @throws com.eloykim.lyra.amp.protocol.database.service.EntityNotFoundError
     *   when the row is absent.
     */
    fun requireById(id: TKey, entityName: String? = null): TEntity {
        val cached = read(id)
        if (cached != null) {
            hits += 1
            touch(id)
            return cached
        }
        misses += 1
        val loaded = if (entityName == null) service.requireById(id) else service.requireById(id, entityName)
        put(id, loaded)
        return loaded
    }

    /** Put a row into the identity map. */
    fun put(id: TKey, entity: TEntity) {
        entries.remove(id)
        val expiresAt = if (ttlMs == 0L) 0L else now() + ttlMs
        entries[id] = CacheEntry(entity, expiresAt)
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
            evictions += 1
        }
    }

    /** Test whether a row is resident and unexpired. */
    fun contains(id: TKey): Boolean = read(id) != null

    /** Remove one row from the identity map. */
    fun detach(id: TKey): Boolean = entries.remove(id) != null

    /** Remove every resident row. */
    fun clear() {
        entries.clear()
    }

    /** Drop a row and load it again through the service. */
    fun refresh(id: TKey): TEntity? {
        detach(id)
        return findById(id)
    }

    /** Number of resident rows. */
    val size: Int
        get() {
            compact()
            return entries.size
        }

    /** Read cache counters. */
    fun stats(): StoreStats {
        compact()
        return StoreStats(hits = hits, misses = misses, evictions = evictions, size = entries.size)
    }

    private fun read(id: TKey): TEntity? {
        val entry = entries[id] ?: return null
        if (entry.expiresAt != 0L && entry.expiresAt <= now()) {
            entries.remove(id)
            evictions += 1
            return null
        }
        return entry.value
    }

    private fun touch(id: TKey) {
        val entry = entries[id] ?: return
        entries.remove(id)
        entries[id] = entry
    }

    private fun compact() {
        val keys = ArrayList(entries.keys)
        keys.forEach { id ->
            val entry = entries[id] ?: return@forEach
            if (entry.expiresAt != 0L && entry.expiresAt <= now()) {
                entries.remove(id)
                evictions += 1
            }
        }
    }
}
