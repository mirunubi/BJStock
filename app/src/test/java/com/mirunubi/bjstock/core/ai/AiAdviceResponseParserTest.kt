package com.mirunubi.bjstock.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAdviceResponseParserTest {
    private val parser = AiAdviceResponseParser()
    private val fingerprint = "abc123"

    @Test
    fun validJson_passes() {
        val outcome = parser.parse(validJson(fingerprint), fingerprint)
        assertTrue(outcome is AiAdviceOutcome.Success)
        val advice = (outcome as AiAdviceOutcome.Success).advice
        assertEquals(AiStance.HOLD, advice.stance)
        assertEquals(68, advice.confidence)
    }

    @Test
    fun invalidStance_rejects() {
        val raw = validJson(fingerprint).replace("\"HOLD\"", "\"STRONG_BUY\"")
        val outcome = parser.parse(raw, fingerprint)
        assertTrue(outcome is AiAdviceOutcome.Failure)
        assertEquals(
            AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
            (outcome as AiAdviceOutcome.Failure).kind,
        )
    }

    @Test
    fun confidenceBounds() {
        assertTrue(parser.parse(validJson(fingerprint, confidence = 0), fingerprint) is AiAdviceOutcome.Success)
        assertTrue(parser.parse(validJson(fingerprint, confidence = 100), fingerprint) is AiAdviceOutcome.Success)
        assertEquals(
            AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
            (parser.parse(validJson(fingerprint, confidence = -1), fingerprint) as AiAdviceOutcome.Failure).kind,
        )
        assertEquals(
            AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
            (parser.parse(validJson(fingerprint, confidence = 101), fingerprint) as AiAdviceOutcome.Failure).kind,
        )
    }

    @Test
    fun fingerprintMismatch_rejects() {
        val outcome = parser.parse(validJson("other"), fingerprint)
        assertEquals(
            AiAdviceErrorKind.REQUEST_MISMATCH,
            (outcome as AiAdviceOutcome.Failure).kind,
        )
    }

    @Test
    fun malformedJson_rejects() {
        val outcome = parser.parse("not-json", fingerprint)
        assertEquals(
            AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
            (outcome as AiAdviceOutcome.Failure).kind,
        )
    }

    @Test
    fun uncertainStance_maps() {
        val outcome = parser.parse(validJson(fingerprint, stance = "UNCERTAIN"), fingerprint)
        assertEquals(AiStance.UNCERTAIN, (outcome as AiAdviceOutcome.Success).advice.stance)
        assertEquals(
            com.mirunubi.bjstock.core.model.AiRecommendation.NO_OPINION,
            outcome.advice.stance.toRecommendation(),
        )
    }

    private fun validJson(
        fingerprint: String,
        stance: String = "HOLD",
        confidence: Int = 68,
    ): String = """
        {
          "schema_version": "1",
          "request_fingerprint": "$fingerprint",
          "stance": "$stance",
          "confidence": $confidence,
          "summary": "Neutral for now",
          "supporting_reasons": ["reason-a", "reason-b"],
          "risk_factors": ["risk-a"]
        }
    """.trimIndent()
}
