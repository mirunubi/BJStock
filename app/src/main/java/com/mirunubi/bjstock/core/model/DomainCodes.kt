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
    PENDING_EXECUTION,
    VIRTUAL_FILLED,
    CANCELLED,
    REJECTED,
}

enum class CashLedgerEventType {
    INITIAL_DEPOSIT,
    BUY,
    SELL,
    COMMISSION,
    TAX,
    ADJUSTMENT,
}

object CashLedgerReferenceTypes {
    const val STRATEGY_RUN = "STRATEGY_RUN"
    const val ORDER = "ORDER"
    const val EXECUTION = "EXECUTION"
}

enum class ExecutionPricePolicy {
    NEXT_TRADING_DAY_OPEN,
}

enum class AdditionalBuyPolicy {
    DISALLOW,
}

enum class SellPolicy {
    FULL_POSITION,
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
