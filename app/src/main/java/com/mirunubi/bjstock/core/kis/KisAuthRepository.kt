package com.mirunubi.bjstock.core.kis

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisTokenRequestDto
import java.io.IOException
import java.io.InterruptedIOException
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
    private val apiErrorLog: ApiErrorLogService? = null,
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
                    throw KisAuthException(
                        KisAuthErrorKind.CREDENTIAL_MISSING,
                        "KIS credentials are not configured",
                    )
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
                tokenFailure(
                    environment = environment,
                    kind = KisAuthErrorKind.fromTokenHttpStatus(error.code()),
                    safeMessage = "KIS token request failed: HTTP ${error.code()}",
                    httpCode = error.code(),
                )
            } catch (_: SerializationException) {
                tokenFailure(
                    environment,
                    KisAuthErrorKind.MALFORMED_RESPONSE,
                    "KIS token request failed: malformed response",
                )
            } catch (_: InterruptedIOException) {
                tokenFailure(
                    environment,
                    KisAuthErrorKind.NETWORK_TIMEOUT,
                    "KIS token request failed: network timeout",
                )
            } catch (_: IOException) {
                tokenFailure(
                    environment,
                    KisAuthErrorKind.NETWORK_UNAVAILABLE,
                    "KIS token request failed: network error",
                )
            } catch (error: KisAuthException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                tokenFailure(
                    environment,
                    KisAuthErrorKind.UNEXPECTED,
                    "KIS token request failed: unexpected error",
                )
            } finally {
                // Do not retain decrypted credentials beyond this request.
            }
        }
    }

    /** Records exactly one KIS_OAUTH row per failed token attempt; the message is always a fixed text. */
    private suspend fun tokenFailure(
        environment: KisEnvironment,
        kind: KisAuthErrorKind,
        safeMessage: String,
        httpCode: Int? = null,
    ): Nothing {
        publish(environment, KisAuthState.ERROR)
        logger.info(safeMessage)
        recordAuthError(kind, safeMessage, httpCode)
        throw KisAuthException(kind, safeMessage, httpCode)
    }

    private suspend fun recordAuthError(
        kind: KisAuthErrorKind,
        safeMessage: String,
        httpStatus: Int?,
    ) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_OAUTH",
                errorType = KisApiErrorMapper.fromAuthKind(kind),
                safeMessage = safeMessage,
                retryable = KisApiErrorMapper.isRetryable(kind),
                httpStatus = httpStatus,
            )
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
