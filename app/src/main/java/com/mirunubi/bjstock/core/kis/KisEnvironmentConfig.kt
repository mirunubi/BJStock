package com.mirunubi.bjstock.core.kis

object KisEnvironmentConfig {
    const val TOKEN_PATH = "/oauth2/tokenP"
    const val GRANT_TYPE = "client_credentials"
    const val TOKEN_SAFETY_MARGIN_MILLIS = 5 * 60 * 1000L

    fun baseUrl(environment: KisEnvironment): String = when (environment) {
        KisEnvironment.PRODUCTION -> "https://openapi.koreainvestment.com:9443"
        KisEnvironment.VIRTUAL -> "https://openapivts.koreainvestment.com:29443"
    }

    fun tokenUrl(environment: KisEnvironment): String = baseUrl(environment) + TOKEN_PATH
}
