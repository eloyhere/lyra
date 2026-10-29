package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.storage.AppendLog
import com.eloykim.lyra.amp.protocol.storage.AppendSink
import com.eloykim.lyra.amp.protocol.storage.MemoryAppendSink
import com.eloykim.lyra.amp.protocol.types.Digest

/**
 * Durable store for the governance objects themselves, a faithful port of
 * `src/governance/store.ts`.
 *
 * The audit chain records that an act happened; this store keeps the object a
 * later process needs to resume: the latest manifest per id, credentials and
 * board receipts. It is an append-only JSONL event log, so replaying the file
 * reconstructs the latest state without trusting the writer's memory.
 *
 * 落盘与重放复用 `storage/AppendLog.kt`：存储层只决定“存什么”，链的校验交给
 * 同一份 AppendLog，因此 store 与 audit 共享同一种崩溃语义（截断的尾行是崩溃，
 * 不是可跳过的内容）。`AttentionManifest` / `SwitchCredential` / `BoardReceipt`
 * 在重放时经各自的 `fromJson` 还原，摘要与签名字段逐字节保留。
 * Kotlin 侧的数据类不可变，所以读取返回同一实例而不是 `JSON.parse(JSON.stringify())`
 * 拷贝。
 */

/** The three kinds of object the governance store keeps. */
enum class StoredGovernanceKind(val wire: String) {
    MANIFEST("manifest"),
    CREDENTIAL("credential"),
    RECEIPT("receipt");

    companion object {
        fun fromWireOrNull(value: String): StoredGovernanceKind? =
            entries.firstOrNull { it.wire == value }
    }
}

/** One stored record: the kind plus the object's wire body. */
data class StoredGovernanceRecord(
    val type: StoredGovernanceKind,
    val value: JsonValue.Obj,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "type" to jsonText(type.wire),
        "value" to value,
    )

    companion object {
        fun fromJsonOrNull(value: JsonValue): StoredGovernanceRecord? {
            if (value !is JsonValue.Obj) {
                return null
            }
            val type = value.string("type")?.let { StoredGovernanceKind.fromWireOrNull(it) } ?: return null
            val body = value.obj("value") ?: return null
            return StoredGovernanceRecord(type = type, value = body)
        }
    }
}

/** The latest state of the store, as values rather than live maps. */
data class GovernanceStoreSnapshot(
    val manifests: List<AttentionManifest>,
    val credentials: List<SwitchCredential>,
    val receipts: List<BoardReceipt>,
    val count: Int,
)

/**
 * The append-only governance store.
 */
class GovernanceStore private constructor(private val log: AppendLog) {

    private val manifests = LinkedHashMap<String, AttentionManifest>()
    private val credentials = LinkedHashMap<String, SwitchCredential>()
    private val receipts = LinkedHashMap<String, BoardReceipt>()

    companion object {

        /**
         * Open a store over a sink. Replay verifies the chain first, so a
         * half-written tail or an edited record fails here instead of surfacing
         * as a wrong "latest" state later.
         */
        fun open(sink: AppendSink): GovernanceStore {
            val store = GovernanceStore(AppendLog.open(sink))
            store.replay()
            return store
        }

        /** Convenience for tests and demos: an in-memory, non-durable store. */
        fun openMemory(lines: List<String> = emptyList()): GovernanceStore =
            open(MemoryAppendSink(lines))
    }

    private fun replay() {
        log.entries().forEachIndexed { index, entry ->
            val record = StoredGovernanceRecord.fromJsonOrNull(entry.payload)
                ?: throw GovernanceError(
                    GovernanceCode.REGISTRY_INVALID,
                    "entry $index is not a governance record",
                )
            when (record.type) {
                StoredGovernanceKind.MANIFEST -> applyManifest(
                    decode("entry $index is not a manifest") { AttentionManifest.fromJson(record.value) },
                )

                StoredGovernanceKind.CREDENTIAL -> {
                    val credential = decode("entry $index is not a credential") {
                        SwitchCredential.fromJson(record.value)
                    }
                    credentials[credential.id] = credential
                }

                StoredGovernanceKind.RECEIPT -> {
                    val receipt = decode("entry $index is not a receipt") {
                        BoardReceipt.fromJson(record.value)
                    }
                    receipts[receipt.id] = receipt
                }
            }
        }
    }

    private fun <T> decode(message: String, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "$message: ${error.message}")
    }

    /**
     * The reference keeps the highest version per id (`existing.version <=
     * value.version`, so an equal version replaces).
     */
    private fun applyManifest(manifest: AttentionManifest) {
        val existing = manifests[manifest.id]
        if (existing == null || existing.version <= manifest.version) {
            manifests[manifest.id] = manifest
        }
    }

    fun saveManifest(manifest: AttentionManifest) {
        log.append(
            EntryKind.EVENT,
            StoredGovernanceRecord(StoredGovernanceKind.MANIFEST, manifest.toJson()).toJson(),
        )
        applyManifest(manifest)
    }

    fun saveCredential(credential: SwitchCredential) {
        log.append(
            EntryKind.EVENT,
            StoredGovernanceRecord(StoredGovernanceKind.CREDENTIAL, credential.toJson()).toJson(),
        )
        credentials[credential.id] = credential
    }

    fun saveReceipt(receipt: BoardReceipt) {
        log.append(
            EntryKind.EVENT,
            StoredGovernanceRecord(StoredGovernanceKind.RECEIPT, receipt.toJson()).toJson(),
        )
        receipts[receipt.id] = receipt
    }

    fun getManifest(id: String): AttentionManifest? = manifests[id]

    fun getCredential(id: String): SwitchCredential? = credentials[id]

    fun getReceipt(id: String): BoardReceipt? = receipts[id]

    fun snapshot(): GovernanceStoreSnapshot = GovernanceStoreSnapshot(
        manifests = manifests.values.toList(),
        credentials = credentials.values.toList(),
        receipts = receipts.values.toList(),
        count = log.count,
    )

    fun verify(): AuditVerdict = log.verify().let { AuditVerdict(it.ok, it.reasons) }

    val count: Int get() = log.count

    /** The current chain tip, for an audit checkpoint or a store receipt. */
    fun tip(): Digest = log.tip
}
