package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorCategory
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.model.ApiErrorType
import org.junit.Assert.assertEquals
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
        ).forEach { (kind, expected) ->
            assertEquals(kind.name, expected.first, AppErrorMapper.fromKisMarketErrorKind(kind))
            assertEquals(kind.name, expected.second, KisApiErrorMapper.fromMarketKind(kind))
        }
    }
}
