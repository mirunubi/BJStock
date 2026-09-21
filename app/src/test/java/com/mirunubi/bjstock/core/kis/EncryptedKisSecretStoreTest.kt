package com.mirunubi.bjstock.core.kis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.security.FakeSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedKisSecretStoreTest {
    private lateinit var store: EncryptedKisSecretStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val preferences = context.getSharedPreferences(
            EncryptedKisSecretStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        store = EncryptedKisSecretStore(preferences, FakeSecretCipher())
    }

    @Test
    fun saveLoadDelete_credentialsAreEncryptedAndEnvironmentScoped() = runBlocking {
        store.saveCredentials(
            KisEnvironment.PRODUCTION,
            "TEST_APP_KEY",
            "TEST_APP_SECRET",
        )
        store.saveCredentials(
            KisEnvironment.VIRTUAL,
            "TEST_VIRTUAL_APP_KEY",
            "TEST_VIRTUAL_APP_SECRET",
        )

        val prefs = context.getSharedPreferences(EncryptedKisSecretStore.PREFS_NAME, Context.MODE_PRIVATE)
        val storedValues = prefs.all.values.map { it.toString() }
        assertTrue(storedValues.none { it.contains("TEST_APP_SECRET") })
        assertTrue(storedValues.none { it.contains("TEST_VIRTUAL_APP_SECRET") })
        assertTrue(prefs.all.keys.any { it.contains("ciphertext") })
        assertTrue(prefs.all.keys.any { it.contains("iv") })
        assertTrue(prefs.all.keys.any { it.contains("version") })

        val production = store.loadCredentials(KisEnvironment.PRODUCTION)
        assertEquals("TEST_APP_KEY", production?.appKey)
        assertEquals("TEST_APP_SECRET", production?.appSecret)
        assertEquals("****_KEY", store.loadDisplay(KisEnvironment.PRODUCTION).appKeyMask)

        store.deleteCredentials(KisEnvironment.PRODUCTION)
        assertFalse(store.hasCredentials(KisEnvironment.PRODUCTION))
        assertNull(store.loadCredentials(KisEnvironment.PRODUCTION))
        assertNotNull(store.loadCredentials(KisEnvironment.VIRTUAL))
    }

    @Test
    fun tokenIsStoredEncryptedAndRemovedWithCredentials() = runBlocking {
        store.saveCredentials(KisEnvironment.PRODUCTION, "TEST_APP_KEY", "TEST_APP_SECRET")
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN",
                tokenType = "Bearer",
                expiresAtEpochMillis = 1_700_000_000_000L,
            ),
        )

        val prefs = context.getSharedPreferences(EncryptedKisSecretStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.all.values.none { it.toString() == "TEST_ACCESS_TOKEN" })

        val loaded = store.loadToken(KisEnvironment.PRODUCTION)
        assertEquals("TEST_ACCESS_TOKEN", loaded?.accessToken)

        store.deleteCredentials(KisEnvironment.PRODUCTION)
        assertNull(store.loadToken(KisEnvironment.PRODUCTION))
    }
}
