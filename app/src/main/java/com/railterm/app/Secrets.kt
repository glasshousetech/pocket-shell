package com.railterm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted storage for the user's own Anthropic API key (BYO-key model — zero
 * GHT cost) and AI model choice. Falls back to plain prefs if the Android
 * keystore is unavailable so the app never crashes on exotic devices.
 */
object Secrets {
    private const val FILE = "railterm_secrets"
    private const val KEY_API = "anthropic_api_key"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_BASE = "ai_base_url"

    /** Default per the Claude API guidance: highest-quality model unless changed. */
    const val DEFAULT_MODEL = "claude-opus-4-8"

    /** Anthropic by default; GHT staff can point this at a Halo proxy instead. */
    const val DEFAULT_BASE = "https://api.anthropic.com"

    val MODELS: List<Pair<String, String>> = listOf(
        "claude-opus-4-8" to "Opus 4.8",
        "claude-sonnet-5" to "Sonnet 5",
        "claude-haiku-4-5" to "Haiku 4.5",
    )

    private fun prefs(context: Context): SharedPreferences =
        runCatching {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE,
                alias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            context.applicationContext.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
        }

    fun apiKey(context: Context): String = prefs(context).getString(KEY_API, "").orEmpty()
    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()
    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API, value.trim()).apply()
    }

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODEL, value).apply()
    }

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE, DEFAULT_BASE)?.ifBlank { DEFAULT_BASE } ?: DEFAULT_BASE
    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE, value.trim().ifBlank { DEFAULT_BASE }).apply()
    }
}
