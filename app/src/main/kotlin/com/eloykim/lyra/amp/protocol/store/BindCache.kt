package com.eloykim.lyra.amp.protocol.store

/**
 * Bind one cache to the current [StoreDatabase] (port of
 * `src/store/bindCache.ts`).
 *
 * When [configureStore] replaces the database, the next access rebuilds the
 * cache instead of serving stale rows. The factory is expected to close over the
 * database it receives; [EntityCache] itself is database-agnostic.
 */
fun <T : Any> bindCache(factory: (StoreDatabase) -> EntityCache<T>): () -> EntityCache<T> {
    var cache: EntityCache<T>? = null
    var bound: StoreDatabase? = null
    return {
        val db = database()
        if (cache == null || bound !== db) {
            cache = factory(db)
            bound = db
        }
        cache ?: error("cache factory returned null")
    }
}
