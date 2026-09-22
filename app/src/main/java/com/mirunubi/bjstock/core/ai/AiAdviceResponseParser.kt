package com.mirunubi.bjstock.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class AiAdviceResponseParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(
        rawResponse: String,
        expectedFingerprint: String,
    ): AiAdviceOutcome {
        val trimmed = rawResponse.trim()
        if (trimmed.isEmpty()) {
            return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
                "empty response",
            )
        }
        val element = try {
            json.parseToJsonElement(extractJsonObject(trimmed))
        } catch (_: Exception) {
            return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
                "malformed JSON",
            )
        }
        if (element !is JsonObject) {
            return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
                "root must be object",
            )
        }
        val schema = element.string("schema_version")
            ?: return formatFail("schema_version required")
        if (schema != AiAdvisoryCodes.RESPONSE_SCHEMA_VERSION) {
            return formatFail("unsupported schema_version=$schema")
        }
        val fingerprint = element.string("request_fingerprint")
            ?: return formatFail("request_fingerprint required")
        if (fingerprint != expectedFingerprint) {
            return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.REQUEST_MISMATCH,
                "REQUEST_MISMATCH",
            )
        }
        val stanceRaw = element.string("stance") ?: return formatFail("stance required")
        val stance = AiStance.parse(stanceRaw)
            ?: return formatFail("invalid stance=$stanceRaw")
        val confidence = element["confidence"]?.jsonPrimitive?.intOrNull
            ?: return formatFail("confidence required integer")
        if (confidence !in 0..100) {
            return formatFail("confidence out of range: $confidence")
        }
        val summary = element.string("summary")?.takeIf { it.isNotBlank() }
            ?: return formatFail("summary required")
        val reasons = element.stringArray("supporting_reasons")
            ?: return formatFail("supporting_reasons required array")
        val risks = element.stringArray("risk_factors")
            ?: return formatFail("risk_factors required array")

        return AiAdviceOutcome.Success(
            AiAdvice(
                requestId = 0,
                evaluationId = 0,
                provider = "",
                promptVersion = "",
                stance = stance,
                confidence = confidence,
                summary = summary,
                supportingReasons = reasons,
                riskFactors = risks,
                agreement = null,
            ),
        )
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) return raw.substring(start, end + 1)
        return raw
    }

    private fun formatFail(message: String) = AiAdviceOutcome.Failure(
        AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
        message,
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.stringArray(key: String): List<String>? {
        val value = this[key] ?: return null
        if (value !is JsonArray) return null
        return value.map { element ->
            if (element is JsonPrimitive) {
                element.content
            } else {
                return null
            }
        }
    }
}
