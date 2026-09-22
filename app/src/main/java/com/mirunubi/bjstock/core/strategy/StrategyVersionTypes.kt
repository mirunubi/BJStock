package com.mirunubi.bjstock.core.strategy

enum class StrategyActivationFailure {
    NOT_FOUND,
    NOT_DRAFT,
    NO_ENABLED_FACTOR,
    INVALID_WEIGHT_SUM,
    INVALID_THRESHOLDS,
    UNSUPPORTED_FACTOR_VERSION,
    INVALID_GATE,
}

sealed class StrategyActivationResult {
    data class Success(val strategyVersionId: Long) : StrategyActivationResult()
    data class Failed(
        val kind: StrategyActivationFailure,
        val message: String,
    ) : StrategyActivationResult()
}

class StrategyVersionException(
    val kind: StrategyErrorKind,
    override val message: String,
) : IllegalStateException(message)

enum class StrategyErrorKind {
    NOT_FOUND,
    NOT_DRAFT,
    IMMUTABLE,
    INVALID_THRESHOLDS,
    INVALID_WEIGHT,
    INVALID_GATE,
    INVALID_CALCULATION_VERSION,
    VERSION_NOT_ACTIVE,
}
