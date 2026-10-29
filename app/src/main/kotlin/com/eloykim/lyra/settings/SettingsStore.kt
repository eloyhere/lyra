package com.eloykim.lyra.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.eloykim.lyra.designsystem.LyraColorTheme
import com.eloykim.lyra.network.ThinkingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ThemeMode { System, Light, Dark }

data class Provider(
    val id: String,
    val label: String,
    val models: List<String>,
)

const val DEFAULT_LOCAL_URL = "http://192.168.1.100:11434/v1"

data class LyraSettings(
    val brandId: String = Providers.default.id,
    val model: String = Providers.default.models.first(),
    val themeMode: ThemeMode = ThemeMode.System,
    val colorTheme: LyraColorTheme = LyraColorTheme.Default,
    val dynamicColor: Boolean = false,
    val apiKeyHint: String = "",
    val cloudUsageUrl: String = "",
    val cloudUsageKeyHint: String = "",
    val localBaseUrl: String = DEFAULT_LOCAL_URL,
    val languageId: String = "zh-Hans-CN",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.Medium,
) {
    val brand: Provider get() = Providers.byId(brandId)
    val hasApiKey: Boolean get() = apiKeyHint.isNotBlank()
}

object Providers {

    val all = listOf(
        Provider("openai", "OpenAI", listOf("gpt-4o", "gpt-4o-mini", "o4-mini")),
        Provider("anthropic", "Anthropic", listOf("claude-sonnet-4-5", "claude-haiku-4-5")),
        Provider("gemini", "Google Gemini", listOf("gemini-2.5-pro", "gemini-2.5-flash")),
        Provider("deepseek", "DeepSeek", listOf("deepseek-chat", "deepseek-reasoner")),
        Provider("qwen", "通义千问", listOf("qwen-max", "qwen-plus", "qwen-turbo")),
        Provider("moonshot", "Kimi", listOf("kimi-k2", "moonshot-v1-8k")),
        Provider("zhipu", "智谱 GLM", listOf("glm-4.6", "glm-4-flash")),
        Provider("openrouter", "OpenRouter", listOf("openrouter/auto")),
        Provider("local", "本地 / 自建", listOf("llama3.1", "qwen2.5", "自定义")),
    )

    val default: Provider = all.first()

    fun byId(id: String): Provider = all.firstOrNull { it.id == id } ?: default
}

/**
 * 设置存储。
 *
 * - 普通项放 SharedPreferences；
 * - API Key 用 Android Keystore 的 AES-256-GCM 加密后再落盘（不写日志、不进备份）；
 * - 对外只暴露 `StateFlow`，UI 订阅它。
 */
object SettingsStore {

    private const val PREFS = "lyra_settings"
    private const val KEY_ALIAS = "lyra.settings.aes"
    private const val K_BRAND = "brand"
    private const val K_MODEL = "model"
    private const val K_THEME = "theme"
    private const val K_COLOR_THEME = "color_theme"
    private const val K_DYNAMIC = "dynamic_color"
    private const val K_API_KEY = "api_key_cipher"
    private const val K_API_KEY_IV = "api_key_iv"
    private const val K_CLOUD_USAGE_URL = "cloud_usage_url"
    private const val K_CLOUD_USAGE_KEY = "cloud_usage_key_cipher"
    private const val K_CLOUD_USAGE_KEY_IV = "cloud_usage_key_iv"
    private const val K_LOCAL_URL = "local_base_url"
    private const val K_LANGUAGE = "language_id"
    private const val K_THINKING = "thinking_level"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(LyraSettings())
    val state: StateFlow<LyraSettings> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val brandId = p.getString(K_BRAND, Providers.default.id) ?: Providers.default.id
        val brand = Providers.byId(brandId)
        val model = p.getString(K_MODEL, brand.models.first()) ?: brand.models.first()
        val theme = runCatching {
            ThemeMode.valueOf(p.getString(K_THEME, ThemeMode.System.name)!!)
        }.getOrDefault(ThemeMode.System)
        val thinking = runCatching {
            ThinkingLevel.valueOf(p.getString(K_THINKING, ThinkingLevel.Medium.name)!!)
        }.getOrDefault(ThinkingLevel.Medium)
        _state.value = LyraSettings(
            brandId = brand.id,
            // Keep catalog-discovered models (for example DeepSeek Flash) across
            // process restarts; the built-in list is only a fallback for new setup.
            model = model.ifBlank { brand.models.first() },
            themeMode = theme,
            colorTheme = LyraColorTheme.byId(p.getString(K_COLOR_THEME, null)),
            // 系统没有壁纸取色入口时（< Android 12）即使存过 true 也要退回固定配色
            dynamicColor = p.getBoolean(K_DYNAMIC, false) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            apiKeyHint = readApiKey(p)?.let { mask(it) } ?: "",
            cloudUsageUrl = p.getString(K_CLOUD_USAGE_URL, "") ?: "",
            cloudUsageKeyHint = readCloudKey(p)?.let { mask(it) } ?: "",
            localBaseUrl = p.getString(K_LOCAL_URL, DEFAULT_LOCAL_URL) ?: DEFAULT_LOCAL_URL,
            languageId = p.getString(K_LANGUAGE, "zh-Hans-CN") ?: "zh-Hans-CN",
            thinkingLevel = thinking,
        )
    }

    fun setBrand(brandId: String) {
        val brand = Providers.byId(brandId)
        prefs?.edit()?.putString(K_BRAND, brand.id)?.apply()
        // 换品牌时模型重置为该品牌第一个，避免出现不匹配的组合
        prefs?.edit()?.putString(K_MODEL, brand.models.first())?.apply()
        _state.value = _state.value.copy(brandId = brand.id, model = brand.models.first())
    }

    fun setBrandAndModel(brandId: String, model: String) {
        prefs?.edit()?.putString(K_BRAND, brandId)?.apply()
        prefs?.edit()?.putString(K_MODEL, model)?.apply()
        _state.value = _state.value.copy(brandId = brandId, model = model)
    }

    fun setModel(model: String) {
        prefs?.edit()?.putString(K_MODEL, model)?.apply()
        _state.value = _state.value.copy(model = model)
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        prefs?.edit()?.putString(K_THINKING, level.name)?.apply()
        _state.value = _state.value.copy(thinkingLevel = level)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs?.edit()?.putString(K_THEME, mode.name)?.apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    /** 选择固定配色。动态取色会盖住固定配色，所以一并关闭，保证点选后立刻可见。 */
    fun setColorTheme(theme: LyraColorTheme) {
        prefs?.edit()
            ?.putString(K_COLOR_THEME, theme.id)
            ?.putBoolean(K_DYNAMIC, false)
            ?.apply()
        _state.value = _state.value.copy(colorTheme = theme, dynamicColor = false)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs?.edit()?.putBoolean(K_DYNAMIC, enabled)?.apply()
        _state.value = _state.value.copy(dynamicColor = enabled)
    }

    fun setLanguage(id: String) {
        prefs?.edit()?.putString(K_LANGUAGE, id)?.apply()
        _state.value = _state.value.copy(languageId = id)
    }

    /** 供 Activity 在 attachBaseContext 阶段读取（此时还没 init）。 */
    fun languageIdOf(context: android.content.Context): String =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(K_LANGUAGE, "zh-Hans-CN") ?: "zh-Hans-CN"

    fun setLocalBaseUrl(url: String) {
        val normalized = url.trim()
        prefs?.edit()?.putString(K_LOCAL_URL, normalized)?.apply()
        _state.value = _state.value.copy(localBaseUrl = normalized)
    }

    fun setApiKey(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            prefs?.edit()?.remove(K_API_KEY)?.remove(K_API_KEY_IV)?.apply()
            _state.value = _state.value.copy(apiKeyHint = "")
            return
        }
        val (cipherText, iv) = encrypt(trimmed)
        prefs?.edit()?.putString(K_API_KEY, cipherText)?.putString(K_API_KEY_IV, iv)?.apply()
        _state.value = _state.value.copy(apiKeyHint = mask(trimmed))
    }

    /** 只给模型网关用；不要写进日志或导出。 */
    fun apiKey(): String? = prefs?.let { readApiKey(it) }

    fun setCloudUsageUrl(url: String) {
        val normalized = url.trim()
        prefs?.edit()?.putString(K_CLOUD_USAGE_URL, normalized)?.apply()
        _state.value = _state.value.copy(cloudUsageUrl = normalized)
    }

    fun setCloudUsageKey(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            prefs?.edit()?.remove(K_CLOUD_USAGE_KEY)?.remove(K_CLOUD_USAGE_KEY_IV)?.apply()
            _state.value = _state.value.copy(cloudUsageKeyHint = "")
            return
        }
        val (cipherText, iv) = encrypt(trimmed)
        prefs?.edit()
            ?.putString(K_CLOUD_USAGE_KEY, cipherText)
            ?.putString(K_CLOUD_USAGE_KEY_IV, iv)
            ?.apply()
        _state.value = _state.value.copy(cloudUsageKeyHint = mask(trimmed))
    }

    fun cloudUsageKey(): String? = prefs?.let { readCloudKey(it) }

    private fun readCloudKey(p: SharedPreferences): String? {
        val cipherText = p.getString(K_CLOUD_USAGE_KEY, null) ?: return null
        val iv = p.getString(K_CLOUD_USAGE_KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun readApiKey(p: SharedPreferences): String? {
        val cipherText = p.getString(K_API_KEY, null) ?: return null
        val iv = p.getString(K_API_KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)))
        }.getOrNull()
    }

    private fun encrypt(raw: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(raw.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun mask(key: String): String =
        if (key.length <= 8) "••••" else "${key.take(4)}••••${key.takeLast(4)}"
}
