package com.mirunubi.bjstock.core.kis

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mirunubi.bjstock.core.security.EncryptedPayload
import com.mirunubi.bjstock.core.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EncryptedKisSecretStore(
    private val preferences: SharedPreferences,
    private val cipher: SecretCipher,
) : KisCredentialStore, KisTokenStore, KisSettingsStore {
    override suspend fun saveCredentials(
        environment: KisEnvironment,
        appKey: String,
        appSecret: String,
    ) = withContext(Dispatchers.IO) {
        val keyPayload = cipher.encrypt(appKey)
        val secretPayload = cipher.encrypt(appSecret)
        preferences.edit {
            putPayload(environment, KEY_APP_KEY, keyPayload)
            putPayload(environment, KEY_APP_SECRET, secretPayload)
            putString(maskKey(environment), maskAppKey(appKey))
        }
    }

    override suspend fun hasCredentials(environment: KisEnvironment): Boolean =
        withContext(Dispatchers.IO) {
            preferences.contains(payloadKey(environment, KEY_APP_KEY, FIELD_CIPHERTEXT)) &&
                preferences.contains(payloadKey(environment, KEY_APP_SECRET, FIELD_CIPHERTEXT))
        }

    override suspend fun loadCredentials(environment: KisEnvironment): KisCredentials? =
        withContext(Dispatchers.IO) {
            val keyPayload = readPayload(environment, KEY_APP_KEY) ?: return@withContext null
            val secretPayload = readPayload(environment, KEY_APP_SECRET) ?: return@withContext null
            KisCredentials(
                appKey = cipher.decrypt(keyPayload),
                appSecret = cipher.decrypt(secretPayload),
            )
        }

    override suspend fun deleteCredentials(environment: KisEnvironment) = withContext(Dispatchers.IO) {
        preferences.edit {
            removePayload(environment, KEY_APP_KEY)
            removePayload(environment, KEY_APP_SECRET)
            remove(maskKey(environment))
        }
        deleteToken(environment)
    }

    override suspend fun loadDisplay(environment: KisEnvironment): KisCredentialDisplay =
        withContext(Dispatchers.IO) {
            KisCredentialDisplay(
                saved = preferences.contains(payloadKey(environment, KEY_APP_KEY, FIELD_CIPHERTEXT)) &&
                    preferences.contains(payloadKey(environment, KEY_APP_SECRET, FIELD_CIPHERTEXT)),
                appKeyMask = preferences.getString(maskKey(environment), null),
            )
        }

    override suspend fun saveToken(environment: KisEnvironment, token: KisToken) =
        withContext(Dispatchers.IO) {
            val payload = cipher.encrypt(token.accessToken)
            preferences.edit {
                putPayload(environment, KEY_ACCESS_TOKEN, payload)
                putString(pref(environment, KEY_TOKEN_TYPE), token.tokenType)
                putLong(pref(environment, KEY_EXPIRES_AT), token.expiresAtEpochMillis)
            }
        }

    override suspend fun loadToken(environment: KisEnvironment): KisToken? =
        withContext(Dispatchers.IO) {
            val payload = readPayload(environment, KEY_ACCESS_TOKEN) ?: return@withContext null
            val expiresAt = preferences.getLong(pref(environment, KEY_EXPIRES_AT), -1L)
            if (expiresAt <= 0L) return@withContext null
            KisToken(
                accessToken = cipher.decrypt(payload),
                tokenType = preferences.getString(pref(environment, KEY_TOKEN_TYPE), "Bearer") ?: "Bearer",
                expiresAtEpochMillis = expiresAt,
            )
        }

    override suspend fun deleteToken(environment: KisEnvironment) = withContext(Dispatchers.IO) {
        preferences.edit {
            removePayload(environment, KEY_ACCESS_TOKEN)
            remove(pref(environment, KEY_TOKEN_TYPE))
            remove(pref(environment, KEY_EXPIRES_AT))
        }
    }

    override suspend fun selectedEnvironment(): KisEnvironment = withContext(Dispatchers.IO) {
        val raw = preferences.getString(KEY_SELECTED_ENVIRONMENT, KisEnvironment.PRODUCTION.name)
        runCatching { KisEnvironment.valueOf(raw ?: KisEnvironment.PRODUCTION.name) }
            .getOrDefault(KisEnvironment.PRODUCTION)
    }

    override suspend fun setSelectedEnvironment(environment: KisEnvironment) =
        withContext(Dispatchers.IO) {
            preferences.edit { putString(KEY_SELECTED_ENVIRONMENT, environment.name) }
        }

    private fun readPayload(environment: KisEnvironment, name: String): EncryptedPayload? {
        val ciphertext = preferences.getString(payloadKey(environment, name, FIELD_CIPHERTEXT), null)
            ?: return null
        val iv = preferences.getString(payloadKey(environment, name, FIELD_IV), null) ?: return null
        val version = preferences.getInt(payloadKey(environment, name, FIELD_VERSION), 1)
        return EncryptedPayload(ciphertext = ciphertext, iv = iv, version = version)
    }

    private fun SharedPreferences.Editor.putPayload(
        environment: KisEnvironment,
        name: String,
        payload: EncryptedPayload,
    ) {
        putString(payloadKey(environment, name, FIELD_CIPHERTEXT), payload.ciphertext)
        putString(payloadKey(environment, name, FIELD_IV), payload.iv)
        putInt(payloadKey(environment, name, FIELD_VERSION), payload.version)
    }

    private fun SharedPreferences.Editor.removePayload(environment: KisEnvironment, name: String) {
        remove(payloadKey(environment, name, FIELD_CIPHERTEXT))
        remove(payloadKey(environment, name, FIELD_IV))
        remove(payloadKey(environment, name, FIELD_VERSION))
    }

    private fun pref(environment: KisEnvironment, name: String): String =
        "kis.${environment.name}.$name"

    private fun payloadKey(environment: KisEnvironment, name: String, field: String): String =
        "${pref(environment, name)}.$field"

    private fun maskKey(environment: KisEnvironment): String = pref(environment, KEY_APP_KEY_MASK)

    private fun maskAppKey(appKey: String): String {
        val suffix = appKey.takeLast(4)
        return "****$suffix"
    }

    companion object {
        const val PREFS_NAME = "kis_secrets"
        private const val KEY_APP_KEY = "appKey"
        private const val KEY_APP_SECRET = "appSecret"
        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_TOKEN_TYPE = "tokenType"
        private const val KEY_EXPIRES_AT = "expiresAtEpochMillis"
        private const val KEY_APP_KEY_MASK = "appKeyMask"
        private const val KEY_SELECTED_ENVIRONMENT = "selectedEnvironment"
        private const val FIELD_CIPHERTEXT = "ciphertext"
        private const val FIELD_IV = "iv"
        private const val FIELD_VERSION = "version"
    }
}
