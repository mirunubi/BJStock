package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorCategory
import com.mirunubi.bjstock.core.kis.KisAuthErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.model.ApiErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorTaxonomyConsistencyTest {
    @Test
    fun taxonomy_keepsExistingValuesAndAddsExactlyThree() {
        assertEquals(
            listOf(
                "NETWORK_TIMEOUT", "HTTP_ERROR", "AUTH_ERROR", "KIS_BUSINESS_ERROR",
                "MALFORMED_RESPONSE", "MASTER_DOWNLOAD_ERROR",
                "RATE_LIMIT", "LOCAL_INVARIANT", "UNEXPECTED",
            ),
            ApiErrorType.entries.map { it.name },
        )
        listOf(
            "NETWORK_TIMEOUT", "HTTP_ERROR", "AUTH_ERROR", "KIS_BUSINESS_ERROR",
            "MALFORMED_RESPONSE", "MASTER_DOWNLOAD_ERROR",
        ).forEach { assertEquals(it, ApiErrorType.valueOf(it).name) }
    }

    @Test
    fun nonTransientCodes_neverPersistNetworkTimeout() {
        AppErrorCode.entries
            .filter { it.category != ErrorCategory.TRANSIENT }
            .forEach { assertNotEquals(it.name, ApiErrorType.NETWORK_TIMEOUT, KisApiErrorMapper.fromAppErrorCode(it)) }
        listOf(
            AppErrorCode.INTERNAL_INVARIANT_VIOLATION,
            AppErrorCode.UNEXPECTED_EXCEPTION,
            AppErrorCode.KIS_MALFORMED_RESPONSE,
            AppErrorCode.KIS_BUSINESS_ERROR,
        ).forEach { assertNotEquals(ApiErrorType.NETWORK_TIMEOUT, KisApiErrorMapper.fromAppErrorCode(it)) }
    }

    @Test
    fun canonicalCodes_mapToExpectedApiErrorType() {
        mapOf(
            AppErrorCode.KIS_RATE_LIMIT to ApiErrorType.RATE_LIMIT,
            AppErrorCode.KIS_BUSINESS_ERROR to ApiErrorType.KIS_BUSINESS_ERROR,
            AppErrorCode.KIS_MALFORMED_RESPONSE to ApiErrorType.MALFORMED_RESPONSE,
            AppErrorCode.INTERNAL_INVARIANT_VIOLATION to ApiErrorType.LOCAL_INVARIANT,
            AppErrorCode.DATA_INTEGRITY_ERROR to ApiErrorType.LOCAL_INVARIANT,
            AppErrorCode.UNEXPECTED_EXCEPTION to ApiErrorType.UNEXPECTED,
            AppErrorCode.KIS_SERVER_ERROR to ApiErrorType.HTTP_ERROR,
            AppErrorCode.NETWORK_TIMEOUT to ApiErrorType.NETWORK_TIMEOUT,
            AppErrorCode.NETWORK_UNAVAILABLE to ApiErrorType.NETWORK_TIMEOUT,
            AppErrorCode.AUTH_REQUIRED to ApiErrorType.AUTH_ERROR,
            AppErrorCode.CREDENTIAL_REJECTED to ApiErrorType.AUTH_ERROR,
            AppErrorCode.CREDENTIAL_MISSING to ApiErrorType.AUTH_ERROR,
        ).forEach { (code, type) -> assertEquals(code.name, type, KisApiErrorMapper.fromAppErrorCode(code)) }
        AppErrorCode.entries
            .filter { it.category == ErrorCategory.DOMAIN }
            .forEach { assertNull(it.name, KisApiErrorMapper.fromAppErrorCode(it)) }
    }

    @Test
    fun everyKisKind_repositoryTypeAndRetryMatchCanonicalCode() {
        KisMarketErrorKind.entries.forEach { kind ->
            val code = AppErrorMapper.fromKisMarketErrorKind(kind)
            assertEquals(kind.name, KisApiErrorMapper.fromAppErrorCode(code), KisApiErrorMapper.fromMarketKind(kind))
            assertEquals(kind.name, code.isRetryableAutomatically, KisApiErrorMapper.isRetryable(kind))
        }
    }

    @Test
    fun kisKinds_mapToGateFourSemantics() {
        mapOf(
            KisMarketErrorKind.BUSINESS to (AppErrorCode.KIS_BUSINESS_ERROR to ApiErrorType.KIS_BUSINESS_ERROR),
            KisMarketErrorKind.MALFORMED_RESPONSE to (AppErrorCode.KIS_MALFORMED_RESPONSE to ApiErrorType.MALFORMED_RESPONSE),
            KisMarketErrorKind.MAPPING_FAILURE to (AppErrorCode.KIS_MALFORMED_RESPONSE to ApiErrorType.MALFORMED_RESPONSE),
            KisMarketErrorKind.INVALID_SYMBOL to (AppErrorCode.INTERNAL_INVARIANT_VIOLATION to ApiErrorType.LOCAL_INVARIANT),
            KisMarketErrorKind.INVALID_DATE_RANGE to (AppErrorCode.INTERNAL_INVARIANT_VIOLATION to ApiErrorType.LOCAL_INVARIANT),
            KisMarketErrorKind.RATE_LIMITED to (AppErrorCode.KIS_RATE_LIMIT to ApiErrorType.RATE_LIMIT),
            KisMarketErrorKind.NETWORK_TIMEOUT to (AppErrorCode.NETWORK_TIMEOUT to ApiErrorType.NETWORK_TIMEOUT),
            KisMarketErrorKind.HTTP to (AppErrorCode.KIS_SERVER_ERROR to ApiErrorType.HTTP_ERROR),
            KisMarketErrorKind.AUTHENTICATION to (AppErrorCode.AUTH_REQUIRED to ApiErrorType.AUTH_ERROR),
            KisMarketErrorKind.UNEXPECTED to (AppErrorCode.UNEXPECTED_EXCEPTION to ApiErrorType.UNEXPECTED),
        ).forEach { (kind, expected) ->
            assertEquals(kind.name, expected.first, AppErrorMapper.fromKisMarketErrorKind(kind))
            assertEquals(kind.name, expected.second, KisApiErrorMapper.fromMarketKind(kind))
        }
        assertFalse(KisApiErrorMapper.isRetryable(KisMarketErrorKind.UNEXPECTED))
    }

    @Test
    fun everyAuthKind_repositoryTypeAndRetryMatchCanonicalCode() {
        KisAuthErrorKind.entries.forEach { kind ->
            val code = AppErrorMapper.fromKisAuthErrorKind(kind)
            assertEquals(kind.name, KisApiErrorMapper.fromAppErrorCode(code), KisApiErrorMapper.fromAuthKind(kind))
            assertEquals(kind.name, code.isRetryableAutomatically, KisApiErrorMapper.isRetryable(kind))
        }
    }

    @Test
    fun authKinds_mapToGateEightSemantics() {
        data class Expected(val code: AppErrorCode, val type: ApiErrorType, val retryable: Boolean)
        mapOf(
            KisAuthErrorKind.CREDENTIAL_MISSING to
                Expected(AppErrorCode.CREDENTIAL_MISSING, ApiErrorType.AUTH_ERROR, false),
            KisAuthErrorKind.CREDENTIAL_REJECTED to
                Expected(AppErrorCode.CREDENTIAL_REJECTED, ApiErrorType.AUTH_ERROR, false),
            KisAuthErrorKind.AUTH_REQUIRED to
                Expected(AppErrorCode.AUTH_REQUIRED, ApiErrorType.AUTH_ERROR, false),
            KisAuthErrorKind.SERVER_ERROR to
                Expected(AppErrorCode.KIS_SERVER_ERROR, ApiErrorType.HTTP_ERROR, true),
            KisAuthErrorKind.NETWORK_TIMEOUT to
                Expected(AppErrorCode.NETWORK_TIMEOUT, ApiErrorType.NETWORK_TIMEOUT, true),
            KisAuthErrorKind.NETWORK_UNAVAILABLE to
                Expected(AppErrorCode.NETWORK_UNAVAILABLE, ApiErrorType.NETWORK_TIMEOUT, true),
            KisAuthErrorKind.MALFORMED_RESPONSE to
                Expected(AppErrorCode.KIS_MALFORMED_RESPONSE, ApiErrorType.MALFORMED_RESPONSE, false),
            KisAuthErrorKind.UNEXPECTED to
                Expected(AppErrorCode.UNEXPECTED_EXCEPTION, ApiErrorType.UNEXPECTED, false),
        ).also { assertEquals(KisAuthErrorKind.entries.toSet(), it.keys) }
            .forEach { (kind, expected) ->
                assertEquals(kind.name, expected.code, AppErrorMapper.fromKisAuthErrorKind(kind))
                assertEquals(kind.name, expected.type, KisApiErrorMapper.fromAuthKind(kind))
                assertEquals(kind.name, expected.retryable, KisApiErrorMapper.isRetryable(kind))
            }
    }

    @Test
    fun tokenHttpStatus_classification() {
        assertEquals(KisAuthErrorKind.CREDENTIAL_REJECTED, KisAuthErrorKind.fromTokenHttpStatus(401))
        assertEquals(KisAuthErrorKind.CREDENTIAL_REJECTED, KisAuthErrorKind.fromTokenHttpStatus(403))
        listOf(500, 502, 503, 599).forEach {
            assertEquals(it.toString(), KisAuthErrorKind.SERVER_ERROR, KisAuthErrorKind.fromTokenHttpStatus(it))
        }
        listOf(400, 404, 429, 600).forEach {
            assertEquals(it.toString(), KisAuthErrorKind.AUTH_REQUIRED, KisAuthErrorKind.fromTokenHttpStatus(it))
        }
    }
}
