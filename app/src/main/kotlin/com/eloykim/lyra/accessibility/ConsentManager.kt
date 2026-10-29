package com.eloykim.lyra.accessibility

import com.eloykim.lyra.policy.RiskScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 授权闸门。
 *
 * 关键约束：授权只能由**人**完成，不能由 Agent 通过 AIDL 调用。
 * - 本对象不暴露任何 AIDL 方法，只有 UI 能调用 approve/deny；
 * - 闸门挂起期间，引擎拒绝所有动作（包括「先干别的再回来」）；
 * - 授权一次只对一个「动作指纹」有效，60 秒过期，用掉即焚。
 */
object ConsentManager {

    data class Challenge(
        val id: String,
        val scope: RiskScope,
        val summary: String,
        val fingerprint: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private const val TTL_MS = 60_000L

    private val _pending = MutableStateFlow<Challenge?>(null)
    val pending: StateFlow<Challenge?> = _pending.asStateFlow()

    /** fingerprint -> 过期时间戳 */
    private val grants = ConcurrentHashMap<String, Long>()

    val isPending: Boolean get() = _pending.value != null

    fun request(scope: RiskScope, summary: String, fingerprint: String): Challenge {
        val existing = _pending.value
        if (existing != null && existing.fingerprint == fingerprint) return existing
        val challenge = Challenge(
            id = UUID.randomUUID().toString(),
            scope = scope,
            summary = summary,
            fingerprint = fingerprint,
        )
        _pending.value = challenge
        return challenge
    }

    /** 仅由 UI 调用。 */
    fun approve(challengeId: String): Boolean {
        val challenge = _pending.value?.takeIf { it.id == challengeId } ?: return false
        grants[challenge.fingerprint] = System.currentTimeMillis() + TTL_MS
        _pending.value = null
        return true
    }

    /** 仅由 UI 调用。 */
    fun deny(challengeId: String) {
        if (_pending.value?.id == challengeId) {
            _pending.value = null
        }
    }

    /** 引擎在执行前消费一次授权；用掉即焚。 */
    fun consume(fingerprint: String): Boolean {
        val expiry = grants[fingerprint] ?: return false
        if (expiry < System.currentTimeMillis()) {
            grants.remove(fingerprint)
            return false
        }
        grants.remove(fingerprint)
        return true
    }

    fun clear() {
        grants.clear()
        _pending.value = null
    }
}
