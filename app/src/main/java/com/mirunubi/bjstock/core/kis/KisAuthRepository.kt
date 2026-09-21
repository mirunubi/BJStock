package com.mirunubi.bjstock.core.kis

import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisTokenRequestDto
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

class KisAuthRepository(
    private val api: KisAuthApi,
    private val credentialStore: KisCredentialStore,
    private val tokenStore: KisTokenStore,
    private val settingsStore: KisSettingsStore,
    private val logger: KisAuthLogger,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val safetyMarginMillis: Long = KisEnvironmentConfig.TOKEN_SAFETY_MARGIN_MILLIS,
    private val tokenUrl: (KisEnvironment) -> String = KisEnvironmentConfig::tokenUrl,
) {
    private val mutex = Mutex()
    private val states = MutableStateFlow<Map<KisEnvironment, KisAuthState>>(emptyMap())
    private val selectedEnvironmentState = MutableStateFlow(KisEnvironment.PRODUCTION)

    val authStates: StateFlow<Map<KisEnvironment, KisAuthState>> = states.asStateFlow()

    fun observeAuthState(): Flow<KisAuthState> = combine(states, selectedEnvironmentState) { map, env ->
        map[env] ?: KisAuthState.NOT_CONFIGURED
    }

    suspend fun selectedEnvironment(): KisEnvironment {
        val environment = settingsStore.selectedEnvironment()
        selectedEnvironmentState.value = environment
        return environment
    }

    suspend fun setSelectedEnvironment(environment: KisEnvironment) {
        settingsStore.setSelectedEnvironment(environment)
        selectedEnvironmentState.value = environment
        refreshState(environment)
    }

    suspend fun refreshState(environment: KisEnvironment): KisAuthState {
        val next = computeState(environment)
        publish(environment, next)
        return next
    }

    suspend fun saveCredentials(
        environment: KisEnvironment,
        appKey: String,
        appSecret: String,
    ) {
        credentialStore.saveCredentials(environment, appKey, appSecret)
        publish(environment, KisAuthState.READY)
    }

    suspend fun deleteCredentials(environment: KisEnvironment) {
        credentialStore.deleteCredentials(environment)
        publish(environment, KisAuthState.NOT_CONFIGURED)
        logger.info("KIS credentials deleted")
    }

    suspend fun credentialDisplay(environment: KisEnvironment): KisCredentialDisplay =
        credentialStore.loadDisplay(environment)

    suspend fun testConnection(environment: KisEnvironment): KisAuthState {
        return try {
            getValidToken(environment)
            KisAuthState.AUTHENTICATED
        } catch (_: KisAuthException) {
            if (!credentialStore.hasCredentials(environment)) {
                publish(environment, KisAuthState.NOT_CONFIGURED)
                KisAuthState.NOT_CONFIGURED
            } else {
                publish(environment, KisAuthState.ERROR)
                KisAuthState.ERROR
            }
        }
    }

    suspend fun getValidToken(environment: KisEnvironment): KisToken {
        mutex.withLock {
            val cached = tokenStore.loadToken(environment)
            if (cached != null && isUsable(cached)) {
                publish(environment, KisAuthState.AUTHENTICATED)
                return cached
            }
            publish(environment, KisAuthState.AUTHENTICATING)
            logger.info("KIS token request started")
            val credentials = credentialStore.loadCredentials(environment)
                ?: run {
                    publish(environment, KisAuthState.NOT_CONFIGURED)
                    logger.info("KIS token request failed: credentials missing")
                    throw KisAuthException("KIS credentials are not configured")
                }
            return try {
                val response = api.issueToken(
                    url = tokenUrl(environment),
                    request = KisTokenRequestDto(
                        grantType = KisEnvironmentConfig.GRANT_TYPE,
                        appKey = credentials.appKey,
                        appSecret = credentials.appSecret,
                    ),
                )
                val now = currentTimeMillis()
                val expiresIn = response.expiresIn ?: TimeUnit.HOURS.toSeconds(24)
                val token = KisToken(
                    accessToken = response.accessToken,
                    tokenType = response.tokenType ?: "Bearer",
                    expiresAtEpochMillis = now + TimeUnit.SECONDS.toMillis(expiresIn),
                )
                tokenStore.saveToken(environment, token)
                publish(environment, KisAuthState.AUTHENTICATED)
                logger.info("KIS token request success")
                token
            } catch (error: HttpException) {
                publish(environment, KisAuthState.ERROR)
                logger.info("KIS token request failed: HTTP ${error.code()}")
                throw KisAuthException("KIS token request failed: HTTP ${error.code()}", error.code())
            } catch (error: SerializationException) {
                publish(environment, KisAuthState.ERROR)
                logger.info("KIS token request failed: malformed response")
                throw KisAuthException("KIS token request failed: malformed response")
            } catch (error: IOException) {
                publish(environment, KisAuthState.ERROR)
                logger.info("KIS token request failed: network error")
                throw KisAuthException("KIS token request failed: network error")
            } catch (error: KisAuthException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publish(environment, KisAuthState.ERROR)
                logger.info("KIS token request failed: unexpected error")
                throw KisAuthException("KIS token request failed: unexpected error")
            } finally {
                // Do not retain decrypted credentials beyond this request.
            }
        }
    }

    private suspend fun computeState(environment: KisEnvironment): KisAuthState {
        if (!credentialStore.hasCredentials(environment)) {
            return KisAuthState.NOT_CONFIGURED
        }
        val token = tokenStore.loadToken(environment)
        return if (token != null && isUsable(token)) {
            KisAuthState.AUTHENTICATED
        } else {
            KisAuthState.READY
        }
    }

    private fun isUsable(token: KisToken): Boolean {
        return token.expiresAtEpochMillis > currentTimeMillis() + safetyMarginMillis
    }

    private fun publish(environment: KisEnvironment, state: KisAuthState) {
        states.value = states.value + (environment to state)
    }
}
