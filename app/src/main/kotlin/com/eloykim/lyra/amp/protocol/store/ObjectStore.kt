package com.eloykim.lyra.amp.protocol.store

/**
 * Append-only object cache (port of `src/store/objectStore.ts`).
 *
 * `src/database/entity/object.ts` is not ported yet; [ObjectEntity] carries only
 * the `seq` used as the cache key. Bodies stay canonical JSON strings in the
 * reference table and are read through `database/store/database.ts`, which this
 * cache does not depend on.
 */
interface ObjectEntity {
    val seq: Int
}

fun interface ObjectService {
    fun byId(seq: Int): ObjectEntity?
}

object ObjectStore {

    private val cacheFor: () -> EntityCache<ObjectEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<ObjectEntity>(
                load = { key -> key.toIntOrNull()?.let(db.objectService::byId) },
                keyOf = { value -> value.seq.toString() },
                capacity = 512,
                ttlMs = 5000,
            ),
        )
    }

    fun get(seq: Int): ObjectEntity? = cacheFor().get(seq)

    fun put(value: ObjectEntity) {
        cacheFor().put(value)
    }

    fun invalidate(seq: Int? = null) {
        if (seq == null) cacheFor().invalidate() else cacheFor().invalidate(seq)
    }

    fun stats(): CacheStats = cacheFor().stats()

    fun entities(): Map<String, ObjectEntity> = cacheFor().entities()
}
