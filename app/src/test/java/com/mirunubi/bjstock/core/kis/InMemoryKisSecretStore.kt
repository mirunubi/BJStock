package com.mirunubi.bjstock.core.kis

class InMemoryKisSecretStore : KisCredentialStore, KisTokenStore, KisSettingsStore {
    private val credentials = mutableMapOf<KisEnvironment, KisCredentials>()
    private val tokens = mutableMapOf<KisEnvironment, KisToken>()
    private var selected = KisEnvironment.PRODUCTION

    override suspend fun saveCredentials(
        environment: KisEnvironment,
        appKey: String,
        appSecret: String,
    ) {
        credentials[environment] = KisCredentials(appKey = appKey, appSecret = appSecret)
    }

    override suspend fun hasCredentials(environment: KisEnvironment): Boolean =
        credentials.containsKey(environment)

    override suspend fun loadCredentials(environment: KisEnvironment): KisCredentials? =
        credentials[environment]

    override suspend fun deleteCredentials(environment: KisEnvironment) {
        credentials.remove(environment)
        tokens.remove(environment)
    }

    override suspend fun loadDisplay(environment: KisEnvironment): KisCredentialDisplay {
        val saved = credentials[environment]
        return KisCredentialDisplay(
            saved = saved != null,
            appKeyMask = saved?.appKey?.takeLast(4)?.let { "****$it" },
        )
    }

    override suspend fun saveToken(environment: KisEnvironment, token: KisToken) {
        tokens[environment] = token
    }

    override suspend fun loadToken(environment: KisEnvironment): KisToken? = tokens[environment]

    override suspend fun deleteToken(environment: KisEnvironment) {
        tokens.remove(environment)
    }

    override suspend fun selectedEnvironment(): KisEnvironment = selected

    override suspend fun setSelectedEnvironment(environment: KisEnvironment) {
        selected = environment
    }
}
