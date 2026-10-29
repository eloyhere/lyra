package com.eloykim.lyra.amp.protocol.window

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * Context window: the per-thread address space (port of `src/window/window.ts`
 * and `src/window/page.ts`).
 *
 * The model never sees the whole ledger. It sees a resident set of pages
 * bounded by tokens; a miss is a `fault` that loads an immutable object, evicts
 * the least-recently-used unpinned page, and records the cost. The page table is
 * inspectable, and an eviction is a decision rather than a silent truncation.
 *
 * ## Binding seams
 *
 * `src/database/repository/objectRepository.ts` is not ported in this package
 * yet. [ObjectRepository] is the narrow slice the window actually uses: look one
 * object up by `(kind, id)` and return its stored body. A host backed by the
 * database layer adapts its `ObjectRepository.find` to [ObjectRow].
 *
 * ## Iteration order
 *
 * The reference keeps resident pages in an `LRUUnorderedMap`, whose iteration
 * order is unspecified bucket order. Kotlin uses insertion/recency order, which
 * is deterministic; [ContextWindow.entries] therefore returns least-recently
 * used first. Eviction itself only depends on `lastUsed`, not on iteration
 * order, except when two pages tie, where the reference's order is already
 * unspecified.
 */

/** One addressable segment of context: a frame, a claim, an evidence item, a tool result. */
data class Page(
    val id: String,
    val objectKind: String,
    val objectId: String,
    val tokens: Int,
    var pinned: Int = 0,
    var lastUsed: Long,
    var content: JsonValue? = null,
)

/** `kind:id`, the key used by the page table. */
fun pageKey(kind: String, id: String): String = "$kind:$id"

/** `pg.<kind>.<id>`, the stable page identity. */
fun pageIdFor(kind: String, id: String): String = "pg.$kind.$id"

/**
 * Persisted shape of one resident page (`WindowPageInput` in
 * `src/database/repository/windowRepository.ts`).
 */
data class WindowPageInput(
    val pageId: String,
    val objectKind: String,
    val objectId: String,
    val tokens: Int,
    val pinned: Boolean,
    val lastUsed: Long,
)

data class WindowStats(
    val hits: Int,
    val faults: Int,
    val evictions: Int,
    val usedTokens: Int,
    val capacityTokens: Int,
    val pages: Int,
)

/** A stored object body, as the window needs to see it. */
data class ObjectRow(val body: String)

/**
 * Narrow object-repository port. The reference reads
 * `ObjectRepository.find(kind, id)?.body`; nothing else is required here.
 */
fun interface ObjectRepository {
    fun find(kind: String, id: String): ObjectRow?
}

/** Machine-readable window failures (`WindowCode` in `src/error/windowError.ts`). */
enum class WindowCode(val wire: String) {
    WINDOW_FULL("window_full"),
    UNKNOWN_PAGE("unknown_page");

    companion object {
        fun fromWire(value: String): WindowCode =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 window code: $value")
    }
}

/**
 * The context window cannot hold a page even after eviction.
 *
 * The message mirrors `AmpError`: `<code>: <problems joined by "; ">`.
 */
class WindowError(
    val code: WindowCode,
    val problems: List<String>,
) : IllegalStateException("${code.wire}: ${problems.joinToString("; ")}")

data class ContextWindowOptions(
    val capacityTokens: Int,
    /**
     * Hard page ceiling; the reference defaults to 128.
     */
    val maxPages: Int = 128,
    val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Optional loader for page content; a miss without it yields a token-only page.
     */
    val objects: ObjectRepository? = null,
)

/**
 * The page table and its resident set.
 *
 * A page is loaded once (`fault`), pinned while in use, and evicted only when
 * unpinned. `used` counts tokens, not pages; a page larger than the whole window
 * is rejected before anything is evicted.
 */
class ContextWindow(options: ContextWindowOptions) {

    val capacityTokens: Int = options.capacityTokens
    val maxPages: Int = options.maxPages

    private val resident: RecencyMap<String, Page>
    private val table = LinkedHashMap<String, String>()
    private val now: () -> Long = options.now
    private val objects: ObjectRepository? = options.objects
    private var used: Int = 0
    private var hits: Int = 0
    private var faults: Int = 0
    private var evictions: Int = 0

    init {
        require(options.capacityTokens > 0) { "capacityTokens must be a positive safe integer" }
        // The reference delegates the page ceiling to LRUUnorderedMap, which
        // rejects a non-positive capacity with this exact message.
        require(options.maxPages > 0) { "capacity must be a positive safe integer" }
        resident = RecencyMap(options.maxPages)
    }

    fun has(kind: String, id: String): Boolean = table.containsKey(pageKey(kind, id))

    /**
     * Load a page into the resident set, evicting unpinned pages if needed.
     *
     * A repeated fault for a resident page is a hit and returns the same [Page]
     * instance; the token count is not charged twice.
     */
    fun fault(kind: String, id: String, tokens: Int, content: JsonValue? = null): Page {
        require(tokens > 0) { "tokens must be a positive safe integer" }
        val key = pageKey(kind, id)
        val existingId = table[key]
        if (existingId != null) {
            val existing = resident[existingId]
            if (existing != null) {
                hits += 1
                return existing
            }
            table.remove(key)
        }

        if (tokens > capacityTokens) {
            throw WindowError(
                WindowCode.WINDOW_FULL,
                listOf("$key needs $tokens tokens, window holds $capacityTokens"),
            )
        }
        evictUntil(tokens)

        val page = Page(
            id = pageIdFor(kind, id),
            objectKind = kind,
            objectId = id,
            tokens = tokens,
            pinned = 0,
            lastUsed = now(),
        )
        val loaded = content ?: load(kind, id)
        if (loaded != null) {
            page.content = loaded
        }
        resident.set(page.id, page)
        table[key] = page.id
        used += tokens
        faults += 1
        return page
    }

    /**
     * Pin a page so eviction cannot remove it; returns the new pin count.
     */
    fun pin(kind: String, id: String): Int {
        val page = pageOf(kind, id)
            ?: throw WindowError(WindowCode.UNKNOWN_PAGE, listOf(pageKey(kind, id)))
        page.pinned += 1
        return page.pinned
    }

    fun unpin(kind: String, id: String): Int {
        val page = pageOf(kind, id)
            ?: throw WindowError(WindowCode.UNKNOWN_PAGE, listOf(pageKey(kind, id)))
        page.pinned = maxOf(0, page.pinned - 1)
        return page.pinned
    }

    /**
     * Remove a page regardless of pin count (explicit release, not eviction).
     */
    fun release(kind: String, id: String): Boolean {
        val key = pageKey(kind, id)
        val pageId = table[key] ?: return false
        resident[pageId]?.let { page -> used -= page.tokens }
        resident.delete(pageId)
        table.remove(key)
        return true
    }

    fun pageOf(kind: String, id: String): Page? {
        val pageId = table[pageKey(kind, id)] ?: return null
        return resident[pageId]
    }

    fun entries(): List<Page> = resident.values()

    fun snapshot(): List<WindowPageInput> = entries().map { page ->
        WindowPageInput(
            pageId = page.id,
            objectKind = page.objectKind,
            objectId = page.objectId,
            tokens = page.tokens,
            pinned = page.pinned > 0,
            lastUsed = page.lastUsed,
        )
    }

    fun restore(pages: List<WindowPageInput>) {
        resident.clear()
        table.clear()
        used = 0
        pages.forEach { input ->
            val page = Page(
                id = input.pageId,
                objectKind = input.objectKind,
                objectId = input.objectId,
                tokens = input.tokens,
                pinned = if (input.pinned) 1 else 0,
                lastUsed = input.lastUsed,
            )
            // The reference trusts the caller here: the LRU may evict a restored
            // page when the input exceeds maxPages, while `used` still counts it.
            resident.set(page.id, page)
            table[pageKey(input.objectKind, input.objectId)] = page.id
            used += page.tokens
        }
    }

    val usedTokens: Int get() = used

    fun stats(): WindowStats = WindowStats(
        hits = hits,
        faults = faults,
        evictions = evictions,
        usedTokens = used,
        capacityTokens = capacityTokens,
        pages = resident.size,
    )

    private fun evictUntil(incoming: Int) {
        while (used + incoming > capacityTokens || resident.size >= maxPages) {
            val victim = leastRecentUnpinned()
                ?: throw WindowError(
                    WindowCode.WINDOW_FULL,
                    listOf("no unpinned page can make room for $incoming tokens"),
                )
            used -= victim.tokens
            resident.delete(victim.id)
            table.remove(pageKey(victim.objectKind, victim.objectId))
            evictions += 1
        }
    }

    private fun leastRecentUnpinned(): Page? {
        var victim: Page? = null
        for (page in resident.values()) {
            if (page.pinned > 0) {
                continue
            }
            if (victim == null || page.lastUsed < victim.lastUsed) {
                victim = page
            }
        }
        return victim
    }

    private fun load(kind: String, id: String): JsonValue? {
        val row = objects?.find(kind, id) ?: return null
        // The reference parses the body as JSON and falls back to the raw string.
        return runCatching { JsonParser.parse(row.body) }.getOrElse { JsonValue.Str(row.body) }
    }
}

/**
 * A tiny access-ordered LRU map.
 *
 * `get` and `set` mark the entry most recently used; inserting past `capacity`
 * evicts the least recently used entry. This is the subset of
 * `src/structure/lru.ts` the window needs, kept private so the port does not
 * claim to provide the shared structure library.
 */
private class RecencyMap<K : Any, V : Any>(private val capacity: Int) {

    private val map = LinkedHashMap<K, V>(16, 0.75f, true)

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    fun set(key: K, value: V) {
        map[key] = value
        while (map.size > capacity) {
            val oldest = map.keys.first()
            map.remove(oldest)
        }
    }

    fun delete(key: K): Boolean = map.remove(key) != null

    fun clear() {
        map.clear()
    }

    fun values(): List<V> = map.values.toList()
}
