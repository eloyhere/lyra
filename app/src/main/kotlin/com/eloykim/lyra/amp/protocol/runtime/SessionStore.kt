package com.eloykim.lyra.amp.protocol.runtime

/**
 * `src/runtime/sessionStore.ts` 的移植：会话持久化端口。
 *
 * 运行时拥有 [SessionSnapshot]；适配器可以把它存进 SQLite、文件或内存。
 */
interface SessionStore {

    fun load(pid: Int): SessionSnapshot?

    fun save(pid: Int, snapshot: SessionSnapshot, at: String)

    /** 保留快照但标记为已归档。 */
    fun archive(pid: Int, at: String): Boolean

    /** 智能体被删除/归档时删除快照。 */
    fun delete(pid: Int): Boolean
}

/** 内存实现：与参考实现一样保存快照的引用，不做深拷贝。 */
class MemorySessionStore : SessionStore {

    private val snapshots: MutableMap<Int, SessionSnapshot> = LinkedHashMap()

    override fun load(pid: Int): SessionSnapshot? = snapshots[pid]

    override fun save(pid: Int, snapshot: SessionSnapshot, at: String) {
        snapshots[pid] = snapshot
    }

    override fun archive(pid: Int, at: String): Boolean {
        val snapshot = snapshots[pid] ?: return false
        snapshots[pid] = snapshot.copy(archivedAt = at)
        return true
    }

    override fun delete(pid: Int): Boolean = snapshots.remove(pid) != null
}
