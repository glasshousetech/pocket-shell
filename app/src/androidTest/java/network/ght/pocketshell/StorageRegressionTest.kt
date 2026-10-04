package network.ght.pocketshell

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Uses only synthetic keys/history in the isolated instrumentation app. */
class StorageRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun requireDisposableEmulator() {
        assumeTrue("Synthetic storage fixtures must never overwrite a real handset's settings",
            android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.MODEL.contains("sdk_gphone") ||
                android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun snapshotSaveThenClearCannotResurrectOldHistory() {
        val entry = SessionStore.Saved(SessionMode.LINUX, "fixture", null, "retained history")
        instrumentation.runOnMainSync {
            SessionStore.save(context, listOf(entry))
            SessionStore.clear(context)
        }
        assertTrue(SessionStore.load(context).isEmpty())
        instrumentation.runOnMainSync { SessionStore.save(context, listOf(entry.copy(title = "newer"))) }
        assertEquals("newer", SessionStore.load(context).single().title)
        SessionStore.clear(context)
        assertTrue(SessionStore.load(context).isEmpty())
    }

    private fun resetSecureCache() {
        listOf("cachedPrefs", "cachedTranscriptLogging", "storageError", "migrationNotice").forEach { name ->
            Secrets::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
    }

    @Test fun legacyPlaintextKeyMigratesOnlyIntoEncryptedStorage() {
        resetSecureCache()
        val secure = androidx.security.crypto.EncryptedSharedPreferences.create(
            "pocketshell_secrets",
            androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC),
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        // An existing but blank secure value must not erase a usable legacy key.
        assertTrue(secure.edit().putString("anthropic_api_key", "").commit())
        val legacy = context.getSharedPreferences("pocketshell_secrets_plain", Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString("anthropic_api_key", "synthetic-migration-key").commit())
        assertEquals("synthetic-migration-key", Secrets.apiKey(context))
        assertFalse(legacy.contains("anthropic_api_key"))
        resetSecureCache()
        assertEquals("synthetic-migration-key", Secrets.apiKey(context))
        assertTrue(Secrets.saveConfiguration(context, "", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun conflictingLegacyKeyIsPreservedUntilExplicitSave() {
        assertTrue(Secrets.saveConfiguration(context, "synthetic-secure-key", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        val legacy = context.getSharedPreferences("pocketshell_secrets_plain", Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString("anthropic_api_key", "synthetic-conflicting-key").commit())
        resetSecureCache()
        assertEquals("synthetic-secure-key", Secrets.apiKey(context))
        assertEquals("synthetic-conflicting-key", legacy.getString("anthropic_api_key", null))
        assertNotNull(Secrets.migrationNotice)
        assertTrue(Secrets.saveConfiguration(context, "synthetic-reviewed-key", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        assertFalse(legacy.contains("anthropic_api_key"))
        assertNull(Secrets.migrationNotice)
        assertEquals("synthetic-reviewed-key", Secrets.apiKey(context))
        assertTrue(Secrets.saveConfiguration(context, "", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
    }

    @Test fun unavailableSecureStorageDoesNotFallBackToPlaintext() {
        resetSecureCache()
        val blocked = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("Synthetic secure-storage failure")
        }
        assertEquals("", Secrets.apiKey(blocked))
        assertNotNull(Secrets.storageError)
        assertTrue(Secrets.saveConfiguration(blocked, "synthetic-unsaved-key", "test", Secrets.DEFAULT_BASE).isFailure)
        assertFalse(context.getSharedPreferences("pocketshell_secrets_plain", Context.MODE_PRIVATE)
            .contains("anthropic_api_key"))
        resetSecureCache()
    }

    @Test fun recoveryDoesNotEraseAnUnreadKeyWithTheBlankFormDefault() {
        assertTrue(Secrets.saveConfiguration(context, "synthetic-unread-key", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        resetSecureCache()
        val blocked = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("Synthetic locked keystore")
        }
        assertEquals("", Secrets.apiKey(blocked))
        assertTrue(Secrets.saveConfiguration(context, "", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        assertEquals("synthetic-unread-key", Secrets.apiKey(context))
        // Once settings are readable, an intentional blank still clears the key normally.
        assertTrue(Secrets.saveConfiguration(context, "", Secrets.DEFAULT_MODEL, Secrets.DEFAULT_BASE).isSuccess)
        assertEquals("", Secrets.apiKey(context))
    }
}
