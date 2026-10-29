package com.eloykim.lyra.amp.protocol.store

/**
 * Process/entity cache (port of `src/store/processStore.ts`).
 *
 * `src/database/entity/process.ts` is not ported yet; [ProcessEntity] is a
 * narrow projection carrying only the `pid` that the cache uses as its key. A
 * database-layer row type implements it (or adapts to it) when that layer lands.
 */
interface ProcessEntity {
    val pid: Int
}

/** The only process-service operation the cache needs. */
fun interface ProcessService {
    fun byId(pid: Int): ProcessEntity?
}

object ProcessStore {

    private val cacheFor: () -> EntityCache<ProcessEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<ProcessEntity>(
                load = { key -> key.toIntOrNull()?.let(db.processService::byId) },
                keyOf = { value -> value.pid.toString() },
                capacity = 256,
                ttlMs = 2000,
            ),
        )
    }

    fun get(pid: Int): ProcessEntity? = cacheFor().get(pid)

    fun put(value: ProcessEntity) {
        cacheFor().put(value)
    }

    fun invalidate(pid: Int? = null) {
        if (pid == null) cacheFor().invalidate() else cacheFor().invalidate(pid)
    }

    fun stats(): CacheStats = cacheFor().stats()

    /** Snapshot of resident entries; the reference exposes a live Proxy view. */
    fun entities(): Map<String, ProcessEntity> = cacheFor().entities()
}
