package com.mirunubi.bjstock.core.model

enum class FactorCategory {
    FINANCIAL,
    VALUATION,
    MOMENTUM,
    VOLUME,
    FLOW,
    MARKET,
    TECHNICAL,
    OTHER,
}

enum class FactorValueType {
    NUMBER,
    PERCENT,
    RATIO,
    CURRENCY,
    COUNT,
}

enum class StrategyVersionStatus {
    DRAFT,
    ACTIVE,
    RETIRED,
}

enum class RunType {
    PAPER,
}

enum class RunStatus {
    DRAFT,
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
}

enum class TradeDecision {
    BUY,
    HOLD,
    SELL,
    NO_ACTION,
}

enum class OrderSide {
    BUY,
    SELL,
}

enum class OrderType {
    MARKET,
    LIMIT,
}

enum class OrderStatus {
    CREATED,
    VIRTUAL_FILLED,
    CANCELLED,
    REJECTED,
}

enum class AiRecommendation {
    BUY,
    HOLD,
    SELL,
    NO_OPINION,
}

enum class Board {
    KOSPI,
    KOSDAQ,
    OTHER,
}

enum class InstrumentType {
    COMMON_STOCK,
    PREFERRED_STOCK,
    ETP,
    SPAC,
    OTHER,
}
