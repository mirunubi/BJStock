package com.mirunubi.bjstock.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeLogTextTest {
    @Test
    fun safeMessages_passUnchangedAfterWhitespaceNormalization() {
        assertEquals("Run 3 processed 2 market dates", SafeLogText.message("Run 3  processed\n2 market dates"))
        assertEquals("KIS HTTP 500 (EGW00201)", SafeLogText.message("KIS HTTP 500 (EGW00201)"))
        assertEquals("거래일 데이터 대기 중", SafeLogText.message("거래일 데이터 대기 중"))
        assertNull(SafeLogText.message(null))
        assertNull(SafeLogText.message("   "))
    }

    @Test
    fun secretLikeMessages_areWithheld() {
        listOf(
            "appkey PS12345",
            "App Secret leaked",
            "access token expired",
            "Authorization Bearer abc",
            "password reset",
            "refresh_token rotated",
            "eyJhbGciOiJIUzI1NiJ9 header",
            "value abcdefghijklmnopqrstuvwxyz0123456789",
            "account 12345678-01",
            "acct 1234567890123",
        ).forEach { assertEquals(it, SafeLogText.WITHHELD, SafeLogText.message(it)) }
    }

    @Test
    fun structuredOrRawPayloadCharacters_areWithheld() {
        listOf(
            "{\"rt_cd\":\"1\"}",
            "key=value",
            "<html>error</html>",
            "user@example.com",
            "path\\to",
            "a & b",
        ).forEach { assertEquals(it, SafeLogText.WITHHELD, SafeLogText.message(it)) }
    }

    @Test
    fun overlongMessages_areWithheld() {
        assertEquals(SafeLogText.WITHHELD, SafeLogText.message("a ".repeat(150)))
    }

    @Test
    fun existingSanitizerKeywords_remainBlocked() {
        listOf(
            "appkey", "appsecret", "app_key", "app_secret", "authorization",
            "bearer", "access_token", "access token", "account", "acctno",
        ).forEach { keyword ->
            val message = "failed $keyword x"
            assertEquals(keyword, SafeLogText.WITHHELD, SafeLogText.message(message))
            assertEquals(
                "secure error details omitted",
                com.mirunubi.bjstock.core.audit.ApiErrorLogService.sanitize(message),
            )
        }
    }

    @Test
    fun codesAndKeys_areShapeValidated() {
        assertEquals("NETWORK_TIMEOUT", SafeLogText.code("NETWORK_TIMEOUT"))
        assertThrows(IllegalArgumentException::class.java) { SafeLogText.code("network timeout") }
        assertThrows(IllegalArgumentException::class.java) { SafeLogText.code("Bearer abc") }
        assertEquals("op:1:run:3:result", SafeLogText.eventKey("op:1:run:3:result"))
        assertThrows(IllegalArgumentException::class.java) { SafeLogText.eventKey("op:1:token=abc") }
        assertEquals("worker:2b1f-9c:2026-09-30:0", SafeLogText.operationKey("worker:2b1f-9c:2026-09-30:0"))
        assertEquals("manual:req-1", SafeLogText.operationKey("manual:req-1"))
        assertEquals("manual-retry:req-1", SafeLogText.operationKey("manual-retry:req-1"))
        assertThrows(IllegalArgumentException::class.java) { SafeLogText.operationKey("worker:2b1f-9c:0") }
        assertThrows(IllegalArgumentException::class.java) { SafeLogText.operationKey("cron:1") }
    }
}
