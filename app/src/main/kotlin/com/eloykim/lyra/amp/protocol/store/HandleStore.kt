package com.eloykim.lyra.amp.protocol.store

/**
 * Capability/handle cache (port of `src/store/handleStore.ts`).
 *
 * `src/database/entity/handle.ts` is not ported yet; [HandleEntity] carries only
 * the `hid` used as the cache key.
 */
interface HandleEntity {
    val hid: Int
}

fun interface HandleService {
    fun byId(hid: Int): HandleEntity?
}

object HandleStore {

    private val cacheFor: () -> EntityCache<HandleEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<HandleEntity>(
                load = { key -> key.toIntOrNull()?.let(db.handleService::byId) },
                keyOf = { value -> value.hid.toString() },
                capacity = 256,
                ttlMs = 2000,
            ),
        )
    }

    fun get(hid: Int): HandleEntity? = cacheFor().get(hid)

    fun put(value: HandleEntity) {
        cacheFor().put(value)
    }

    fun invalidate(hid: Int? = null) {
        if (hid == null) cacheFor().invalidate() else cacheFor().invalidate(hid)
    }

    fun stats(): CacheStats = cacheFor().stats()

    fun entities(): Map<String, HandleEntity> = cacheFor().entities()
}
