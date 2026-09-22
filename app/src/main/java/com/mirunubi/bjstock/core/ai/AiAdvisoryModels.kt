package com.mirunubi.bjstock.core.ai

import com.mirunubi.bjstock.core.model.AiRecommendation
import com.mirunubi.bjstock.core.model.TradeDecision
import java.math.BigDecimal

enum class AiAdvisoryMode {
    OFF,
    CHATGPT_MANUAL,
    OPENAI_API_FUTURE,
}

enum class AiStance {
    BUY,
    HOLD,
    SELL,
    UNCERTAIN,
    ;

    fun toRecommendation(): AiRecommendation = when (this) {
        BUY -> AiRecommendation.BUY
        HOLD -> AiRecommendation.HOLD
        SELL -> AiRecommendation.SELL
        UNCERTAIN -> AiRecommendation.NO_OPINION
    }

    companion object {
        fun fromRecommendation(value: AiRecommendation): AiStance = when (value) {
            AiRecommendation.BUY -> BUY
            AiRecommendation.HOLD -> HOLD
            AiRecommendation.SELL -> SELL
            AiRecommendation.NO_OPINION -> UNCERTAIN
        }

        fun parse(raw: String): AiStance? = entries.find { it.name == raw.trim().uppercase() }
    }
}

enum class AiAgreement {
    AGREE,
    DISAGREE,
    UNCERTAIN,
}

object AiAdvisoryCodes {
    const val PROVIDER_OPENAI_CHATGPT_MANUAL = "OPENAI_CHATGPT_MANUAL"
    const val MODEL_CHATGPT_MANUAL = "CHATGPT_MANUAL"
    const val PROMPT_V1 = "BJSTOCK_AI_ADVISORY_V1"
    const val RESPONSE_SCHEMA_VERSION = "1"
    const val MAX_DAILY_BARS = 60
}

data class AiPromptPackage(
    val promptVersion: String,
    val requestFingerprint: String,
    val promptText: String,
    val evaluationId: Long,
    val canonicalPayload: String,
)

data class AiAdviceParsed(
    val schemaVersion: String,
    val requestFingerprint: String,
    val stance: AiStance,
    val confidence: Int,
    val summary: String,
    val supportingReasons: List<String>,
    val riskFactors: List<String>,
)

data class AiAdvice(
    val requestId: Long,
    val evaluationId: Long,
    val provider: String,
    val promptVersion: String,
    val stance: AiStance,
    val confidence: Int?,
    val summary: String?,
    val supportingReasons: List<String>,
    val riskFactors: List<String>,
    val agreement: AiAgreement?,
)

enum class AiAdviceErrorKind {
    AI_DISABLED,
    FUTURE_API_NOT_IMPLEMENTED,
    EVALUATION_NOT_FOUND,
    INVALID_RESPONSE_FORMAT,
    REQUEST_MISMATCH,
    RESULT_ALREADY_EXISTS,
    REQUEST_NOT_FOUND,
}

sealed class AiAdviceOutcome {
    data class Success(val advice: AiAdvice) : AiAdviceOutcome()
    data class PromptReady(
        val requestId: Long,
        val prompt: AiPromptPackage,
    ) : AiAdviceOutcome()
    data class Failure(
        val kind: AiAdviceErrorKind,
        val message: String,
    ) : AiAdviceOutcome()
}

fun agreementOf(quant: TradeDecision, stance: AiStance): AiAgreement = when {
    stance == AiStance.UNCERTAIN -> AiAgreement.UNCERTAIN
    quant.name == stance.name -> AiAgreement.AGREE
    else -> AiAgreement.DISAGREE
}

fun confidenceToStored(confidence0to100: Int): Long =
    BigDecimal(confidence0to100)
        .divide(BigDecimal(100), 4, java.math.RoundingMode.HALF_UP)
        .multiply(BigDecimal(com.mirunubi.bjstock.core.database.mapping.NumericMapping.CONFIDENCE_FACTOR))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .longValueExact()

fun confidenceFromStored(stored: Long?): Int? =
    stored?.let {
        BigDecimal(it)
            .divide(
                BigDecimal(com.mirunubi.bjstock.core.database.mapping.NumericMapping.CONFIDENCE_FACTOR),
                4,
                java.math.RoundingMode.HALF_UP,
            )
            .multiply(BigDecimal(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .intValueExact()
    }
