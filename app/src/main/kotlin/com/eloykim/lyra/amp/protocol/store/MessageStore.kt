package com.eloykim.lyra.amp.protocol.store

/**
 * Mailbox message cache (port of `src/store/messageStore.ts`).
 *
 * `src/database/entity/message.ts` is not ported yet; [MessageEntity] carries
 * only the `msg_id` used as the cache key. The snake_case property matches the
 * reference column name.
 */
interface MessageEntity {
    val msg_id: Int
}

fun interface MessageService {
    fun byId(msgId: Int): MessageEntity?
}

object MessageStore {

    private val cacheFor: () -> EntityCache<MessageEntity> = bindCache { db ->
        EntityCache(
            EntityCacheOptions<MessageEntity>(
                load = { key -> key.toIntOrNull()?.let(db.messageService::byId) },
                keyOf = { value -> value.msg_id.toString() },
                capacity = 256,
                ttlMs = 1000,
            ),
        )
    }

    fun get(msgId: Int): MessageEntity? = cacheFor().get(msgId)

    fun put(value: MessageEntity) {
        cacheFor().put(value)
    }

    fun invalidate(msgId: Int? = null) {
        if (msgId == null) cacheFor().invalidate() else cacheFor().invalidate(msgId)
    }

    fun stats(): CacheStats = cacheFor().stats()

    fun entities(): Map<String, MessageEntity> = cacheFor().entities()
}
