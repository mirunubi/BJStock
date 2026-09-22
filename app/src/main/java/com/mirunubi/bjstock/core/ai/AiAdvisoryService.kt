package com.mirunubi.bjstock.core.ai

import com.mirunubi.bjstock.core.database.dao.AiAdviceDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.entity.AiAdviceRequestEntity
import com.mirunubi.bjstock.core.database.entity.AiAdviceResultEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull

/**
 * Read/write AI advisory requests & results. Never mutates evaluations or paper trading.
 * Never calls network.
 */
class AiAdvisoryService(
    private val modeStore: AiAdvisoryModeStore,
    private val promptBuilder: AiAdvisoryPromptBuilder,
    private val parser: AiAdviceResponseParser,
    private val aiAdviceDao: AiAdviceDao,
    private val evaluationDao: StockEvaluationDao,
    private val orderDao: OrderDao,
    private val now: () -> Instant = { Instant.now() },
    private val json: Json = Json { prettyPrint = false },
) {
    fun getMode(): AiAdvisoryMode = modeStore.getMode()

    fun setMode(mode: AiAdvisoryMode) {
        // OPENAI_API_FUTURE may be selected as architecture placeholder but cannot create requests.
        modeStore.setMode(mode)
    }

    suspend fun createManualRequest(
        evaluationId: Long,
        promptVersion: String = AiAdvisoryCodes.PROMPT_V1,
    ): AiAdviceOutcome {
        return when (val mode = getMode()) {
            AiAdvisoryMode.OFF -> AiAdviceOutcome.Failure(
                AiAdviceErrorKind.AI_DISABLED,
                "AI Advisor is OFF",
            )
            AiAdvisoryMode.OPENAI_API_FUTURE -> AiAdviceOutcome.Failure(
                AiAdviceErrorKind.FUTURE_API_NOT_IMPLEMENTED,
                "OPENAI_API_FUTURE is a placeholder; no network calls",
            )
            AiAdvisoryMode.CHATGPT_MANUAL -> {
                val evaluation = evaluationDao.findEvaluationById(evaluationId)
                    ?: return AiAdviceOutcome.Failure(
                        AiAdviceErrorKind.EVALUATION_NOT_FOUND,
                        "evaluation not found",
                    )
                val prompt = promptBuilder.build(evaluationId, promptVersion)
                val payloadObject = buildJsonObject {
                    put("schema_version", AiAdvisoryCodes.RESPONSE_SCHEMA_VERSION)
                    put("prompt_version", prompt.promptVersion)
                    put("request_fingerprint", prompt.requestFingerprint)
                    put("evaluation_id", evaluation.id)
                    put("prompt_text", prompt.promptText)
                }
                val payload = payloadObject.toString()
                val requestId = aiAdviceDao.insertRequest(
                    AiAdviceRequestEntity(
                        evaluationId = evaluationId,
                        provider = AiAdvisoryCodes.PROVIDER_OPENAI_CHATGPT_MANUAL,
                        model = AiAdvisoryCodes.MODEL_CHATGPT_MANUAL,
                        promptVersion = prompt.promptVersion,
                        requestPayload = payload,
                        requestedAt = now(),
                        createdAt = now(),
                    ),
                )
                AiAdviceOutcome.PromptReady(requestId = requestId, prompt = prompt)
            }
        }
    }

    suspend fun saveManualResponse(
        requestId: Long,
        rawResponse: String,
    ): AiAdviceOutcome {
        val request = aiAdviceDao.findRequestById(requestId)
            ?: return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.REQUEST_NOT_FOUND,
                "request not found",
            )
        if (aiAdviceDao.countResultsByRequest(requestId) > 0) {
            return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.RESULT_ALREADY_EXISTS,
                "RESULT_ALREADY_EXISTS",
            )
        }
        val expectedFingerprint = extractFingerprint(request.requestPayload)
            ?: return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
                "request fingerprint missing",
            )
        val parsed = when (val outcome = parser.parse(rawResponse, expectedFingerprint)) {
            is AiAdviceOutcome.Success -> outcome.advice
            is AiAdviceOutcome.Failure -> return outcome
            is AiAdviceOutcome.PromptReady -> return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.INVALID_RESPONSE_FORMAT,
                "unexpected parser outcome",
            )
        }
        val evaluation = evaluationDao.findEvaluationById(request.evaluationId)
            ?: return AiAdviceOutcome.Failure(
                AiAdviceErrorKind.EVALUATION_NOT_FOUND,
                "evaluation not found",
            )
        // Snapshot quant fields before insert for isolation guarantees.
        val before = EvaluationSnapshot.from(evaluation)
        val orderCountBefore = orderDao.countByRun(evaluation.strategyRunId)

        aiAdviceDao.insertResult(
            AiAdviceResultEntity(
                requestId = requestId,
                recommendation = parsed.stance.toRecommendation(),
                confidence = parsed.confidence?.let { confidenceToStored(it) },
                summary = parsed.summary,
                reasoningSummary = json.encodeToString(
                    ListSerializer(String.serializer()),
                    parsed.supportingReasons,
                ),
                riskNotes = json.encodeToString(
                    ListSerializer(String.serializer()),
                    parsed.riskFactors,
                ),
                rawResponse = rawResponse,
                usedInDecision = false,
                createdAt = now(),
            ),
        )

        val afterEval = evaluationDao.findEvaluationById(request.evaluationId)!!
        require(EvaluationSnapshot.from(afterEval) == before) {
            "AI must not mutate evaluation scores/decisions"
        }
        require(orderDao.countByRun(evaluation.strategyRunId) == orderCountBefore) {
            "AI must not mutate orders"
        }

        return AiAdviceOutcome.Success(
            AiAdvice(
                requestId = requestId,
                evaluationId = request.evaluationId,
                provider = request.provider,
                promptVersion = request.promptVersion,
                stance = parsed.stance,
                confidence = parsed.confidence,
                summary = parsed.summary,
                supportingReasons = parsed.supportingReasons,
                riskFactors = parsed.riskFactors,
                agreement = agreementOf(evaluation.quantDecision, parsed.stance),
            ),
        )
    }

    suspend fun listAdviceHistory(evaluationId: Long): List<AiAdviceHistoryRow> {
        return aiAdviceDao.findRequestsByEvaluation(evaluationId).map { request ->
            val result = aiAdviceDao.findResultByRequest(request.id)
            AiAdviceHistoryRow(
                requestId = request.id,
                provider = request.provider,
                promptVersion = request.promptVersion,
                requestedAt = request.requestedAt,
                stance = result?.let { AiStance.fromRecommendation(it.recommendation) },
                confidence = confidenceFromStored(result?.confidence),
                summary = result?.summary,
                hasResult = result != null,
            )
        }
    }

    suspend fun loadRequestPrompt(requestId: Long): AiPromptPackage? {
        val request = aiAdviceDao.findRequestById(requestId) ?: return null
        val payload = request.requestPayload ?: return null
        val obj = json.parseToJsonElement(payload).jsonObject
        val fingerprint = obj["request_fingerprint"]?.jsonPrimitive?.contentOrNull ?: return null
        val promptText = obj["prompt_text"]?.jsonPrimitive?.contentOrNull ?: return null
        return AiPromptPackage(
            promptVersion = request.promptVersion,
            requestFingerprint = fingerprint,
            promptText = promptText,
            evaluationId = request.evaluationId,
            canonicalPayload = promptText,
        )
    }

    private fun extractFingerprint(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(payload).jsonObject["request_fingerprint"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private data class EvaluationSnapshot(
        val quantScore: Long,
        val aiScore: Long?,
        val finalScore: Long,
        val quantDecision: String,
        val finalDecision: String,
    ) {
        companion object {
            fun from(entity: StockEvaluationEntity) = EvaluationSnapshot(
                quantScore = entity.quantScore,
                aiScore = entity.aiScore,
                finalScore = entity.finalScore,
                quantDecision = entity.quantDecision.name,
                finalDecision = entity.finalDecision.name,
            )
        }
    }
}

data class AiAdviceHistoryRow(
    val requestId: Long,
    val provider: String,
    val promptVersion: String,
    val requestedAt: Instant,
    val stance: AiStance?,
    val confidence: Int?,
    val summary: String?,
    val hasResult: Boolean,
)
