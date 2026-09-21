package com.mirunubi.bjstock.core.kis

enum class KisEnvironment {
    PRODUCTION,
    VIRTUAL,
}

enum class KisAuthState {
    NOT_CONFIGURED,
    READY,
    AUTHENTICATING,
    AUTHENTICATED,
    ERROR,
}

data class KisCredentials(
    val appKey: String,
    val appSecret: String,
)

data class KisToken(
    val accessToken: String,
    val tokenType: String,
    val expiresAtEpochMillis: Long,
)

data class KisCredentialDisplay(
    val saved: Boolean,
    val appKeyMask: String?,
)
