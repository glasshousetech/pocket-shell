package network.ght.pocketshell

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted storage for the user's own Anthropic API key (BYO-key model — zero
 * GHT cost) and AI model choice. A keystore failure disables secure saving;
 * credentials are never silently written to plaintext preferences.
 */
object Secrets {
    private const val FILE = "pocketshell_secrets"
    private const val KEY_API = "anthropic_api_key"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_BASE = "ai_base_url"
    private const val KEY_TRANSCRIPTS = "transcript_logging_enabled"

    /** Default per the Claude API guidance: highest-quality model unless changed. */
    const val DEFAULT_MODEL = "claude-opus-4-8"

    /** Anthropic by default; GHT staff can point this at a Halo proxy instead. */
    const val DEFAULT_BASE = "https://api.anthropic.com"

    val MODELS: List<Pair<String, String>> = listOf(
        "claude-opus-4-8" to "Opus 4.8",
        "claude-sonnet-5" to "Sonnet 5",
        "claude-haiku-4-5" to "Haiku 4.5",
    )

    // Creating EncryptedSharedPreferences opens the keystore and decrypts its keysets.
    // It is a process-level resource, not something to rebuild for every terminal redraw.
    @Volatile private var cachedPrefs: SharedPreferences? = null
    @Volatile private var cachedTranscriptLogging: Boolean? = null
    @Volatile var storageError: String? = null
        private set

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs ?: createPrefs(context).also { cachedPrefs = it }
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val secure = EncryptedSharedPreferences.create(
                FILE,
                alias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val legacy = context.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
            val fields = listOf(KEY_API, KEY_MODEL, KEY_BASE)
            val editor = secure.edit()
            fields.forEach { field ->
                if (!secure.contains(field) && legacy.contains(field))
                    editor.putString(field, legacy.getString(field, ""))
            }
            check(editor.commit()) { "Secure settings could not be saved." }
            val regular = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            if (!regular.contains(KEY_TRANSCRIPTS)) {
                val enabled = secure.getBoolean(KEY_TRANSCRIPTS, legacy.getBoolean(KEY_TRANSCRIPTS, false))
                regular.edit().putBoolean(KEY_TRANSCRIPTS, enabled).apply()
                cachedTranscriptLogging = enabled
            }
            // Retire the old plaintext values only after the encrypted write succeeds.
            val cleanup = legacy.edit()
            fields.forEach { cleanup.remove(it) }
            cleanup.remove(KEY_TRANSCRIPTS).apply()
            return secure
        }

    private fun readablePrefs(context: Context): SharedPreferences? {
        if (storageError != null) return null
        return runCatching { prefs(context) }.getOrElse {
            storageError = "Android secure storage is unavailable. No key was saved in plaintext. Unlock the device and retry Save."
            null
        }
    }

    fun apiKey(context: Context): String = readablePrefs(context)?.getString(KEY_API, "").orEmpty()
    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()
    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API, value.trim()).apply()
    }

    fun model(context: Context): String =
        readablePrefs(context)?.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODEL, value).apply()
    }

    fun baseUrl(context: Context): String =
        readablePrefs(context)?.getString(KEY_BASE, DEFAULT_BASE)?.ifBlank { DEFAULT_BASE } ?: DEFAULT_BASE
    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE, value.trim().ifBlank { DEFAULT_BASE }).apply()
    }

    /** Off by default — transcripts can contain passwords/keys typed at the prompt. */
    fun transcriptLoggingEnabled(context: Context): Boolean {
        cachedTranscriptLogging?.let { return it }
        return synchronized(this) {
            val regular = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            if (!regular.contains(KEY_TRANSCRIPTS)) readablePrefs(context)
            cachedTranscriptLogging ?: regular.getBoolean(KEY_TRANSCRIPTS, false)
                .also { cachedTranscriptLogging = it }
        }
    }
    fun setTranscriptLoggingEnabled(context: Context, value: Boolean) {
        synchronized(this) {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TRANSCRIPTS, value).apply()
            cachedTranscriptLogging = value
        }
    }

    /** Call on IO. Failure keeps the editor open and the entered key recoverable. */
    fun saveConfiguration(context: Context, key: String, model: String, base: String): Result<Unit> = synchronized(this) {
        runCatching {
            check(prefs(context).edit().putString(KEY_API, key.trim()).putString(KEY_MODEL, model)
                .putString(KEY_BASE, base.trim().ifBlank { DEFAULT_BASE }).commit()) {
                "Secure settings could not be saved."
            }
            storageError = null
        }.onFailure {
            storageError = "Could not securely save settings. Unlock the device and retry. Your entered key remains in this dialog."
        }
    }
}
