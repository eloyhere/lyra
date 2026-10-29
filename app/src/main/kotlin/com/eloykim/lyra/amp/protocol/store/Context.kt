package com.eloykim.lyra.amp.protocol.store

/**
 * Store context: one database (and therefore one set of services) per process
 * (port of `src/store/context.ts`).
 *
 * Caches are bound to the current database instance; [configureStore] closes the
 * previous one, and every store factory notices the change and rebuilds.
 *
 * ## Binding seam
 *
 * `src/database/service/database.ts` is not ported in this package yet. The
 * reference constructs `new Database(path)` and exposes six *Service fields the
 * stores use. [StoreDatabase] is that narrow seam: only `close()` and the six
 * lookup services are required. A host installs its implementation once via
 * [StoreDatabaseFactories.install] before calling [configureStore]; the stores,
 * [bindCache] and the replacement-on-reconfigure behaviour are otherwise
 * unchanged.
 *
 * Not thread-safe, matching the reference. A host that configures the store
 * while other threads read it must coordinate shutdown itself.
 */

/** The services the entity stores read through. Each is a narrow `byId`/`get` port. */
interface StoreDatabase {

    val processService: ProcessService
    val threadService: ThreadService
    val handleService: HandleService
    val messageService: MessageService
    val windowService: WindowService
    val objectService: ObjectService

    /** Close the underlying connection; called by [configureStore] and [shutdownStore]. */
    fun close()
}

/** Opens a database for a path (`":memory:"` in tests and demos). */
fun interface StoreDatabaseFactory {
    fun open(path: String): StoreDatabase
}

/**
 * Process-wide registry for the host's [StoreDatabase] implementation.
 *
 * Installing a factory is the Kotlin equivalent of importing the concrete
 * `Database` class: [configureStore] must not guess how to open SQLite.
 */
object StoreDatabaseFactories {

    @Volatile
    private var factory: StoreDatabaseFactory? = null

    fun install(factory: StoreDatabaseFactory) {
        this.factory = factory
    }

    /** Remove the installed factory; mostly useful for test isolation. */
    fun clear() {
        factory = null
    }

    internal fun create(path: String): StoreDatabase {
        val installed = factory ?: throw IllegalStateException(
            "no StoreDatabase factory is installed: call StoreDatabaseFactories.install(...) before configureStore(path)",
        )
        return installed.open(path)
    }
}

@Volatile
private var currentDatabase: StoreDatabase? = null

/**
 * Close the previous database (if any) and open a new one for `path`.
 *
 * If opening throws, the previous database has already been closed and remains
 * the current value, matching the reference's assignment order.
 */
@Synchronized
fun configureStore(path: String): StoreDatabase {
    currentDatabase?.close()
    val opened = StoreDatabaseFactories.create(path)
    currentDatabase = opened
    return opened
}

/** The configured database, or a fail-closed error before [configureStore]. */
fun database(): StoreDatabase = currentDatabase
    ?: throw IllegalStateException("configureStore(path) must be called before using a store")

/** Close the current database, if any, and forget it. */
@Synchronized
fun shutdownStore() {
    currentDatabase?.close()
    currentDatabase = null
}
