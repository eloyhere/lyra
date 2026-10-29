package com.eloykim.lyra.amp.protocol.store

/**
 * Context-window row cache (port of `src/store/windowStore.ts`).
 *
 * `src/database/entity/window.ts` is not ported yet; [WindowEntity] carries only
 * the thread id used as the cache key. The `WindowRepository` page rows are a
 * separate concern, handled by `window.ContextWindow`.
 */
interface WindowEntity {
    val tid: Int
}

fun interface WindowService {
    fun get(tid: Int): WindowEntity?
}

object WindowStore {

    private val cacheFor: () -> EntityCache<WindowEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<WindowEntity>(
                load = { key -> key.toIntOrNull()?.let(db.windowService::get) },
                keyOf = { value -> value.tid.toString() },
                capacity = 128,
                ttlMs = 1000,
            ),
        )
    }

    fun get(tid: Int): WindowEntity? = cacheFor().get(tid)

    fun put(value: WindowEntity) {
        cacheFor().put(value)
    }

    fun invalidate(tid: Int? = null) {
        if (tid == null) cacheFor().invalidate() else cacheFor().invalidate(tid)
    }

    fun stats(): CacheStats = cacheFor().stats()

    fun entities(): Map<String, WindowEntity> = cacheFor().entities()
}
