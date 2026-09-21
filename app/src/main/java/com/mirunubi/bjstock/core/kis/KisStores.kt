package com.mirunubi.bjstock.core.kis

interface KisCredentialStore {
    suspend fun saveCredentials(environment: KisEnvironment, appKey: String, appSecret: String)
    suspend fun hasCredentials(environment: KisEnvironment): Boolean
    suspend fun loadCredentials(environment: KisEnvironment): KisCredentials?
    suspend fun deleteCredentials(environment: KisEnvironment)
    suspend fun loadDisplay(environment: KisEnvironment): KisCredentialDisplay
}

interface KisTokenStore {
    suspend fun saveToken(environment: KisEnvironment, token: KisToken)
    suspend fun loadToken(environment: KisEnvironment): KisToken?
    suspend fun deleteToken(environment: KisEnvironment)
}

interface KisSettingsStore {
    suspend fun selectedEnvironment(): KisEnvironment
    suspend fun setSelectedEnvironment(environment: KisEnvironment)
}

fun interface KisAuthLogger {
    fun info(message: String)
}
