package com.eloykim.lyra.amp.protocol.store

/**
 * Thread/task cache (port of `src/store/threadStore.ts`).
 *
 * `src/database/entity/thread.ts` is not ported yet; [ThreadEntity] carries only
 * the `tid` used as the cache key.
 */
interface ThreadEntity {
    val tid: Int
}

fun interface ThreadService {
    fun byId(tid: Int): ThreadEntity?
}

object ThreadStore {

    private val cacheFor: () -> EntityCache<ThreadEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<ThreadEntity>(
                load = { key -> key.toIntOrNull()?.let(db.threadService::byId) },
                keyOf = { value -> value.tid.toString() },
                capacity = 256,
                ttlMs = 2000,
            ),
        )
    }

    fun get(tid: Int): ThreadEntity? = cacheFor().get(tid)

    fun put(value: ThreadEntity) {
        cacheFor().put(value)
    }

    fun invalidate(tid: Int? = null) {
        if (tid == null) cacheFor().invalidate() else cacheFor().invalidate(tid)
    }

    fun stats(): CacheStats = cacheFor().stats()

    fun entities(): Map<String, ThreadEntity> = cacheFor().entities()
}
