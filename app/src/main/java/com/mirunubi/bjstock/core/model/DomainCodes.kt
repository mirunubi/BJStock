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

enum class ForwardCycleStatus {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED,
}

enum class ForwardCycleStage {
    PENDING_FILLS,
    FACTORS,
    EVALUATIONS,
    ORDER_CREATION,
    SNAPSHOT,
    COMPLETE,
}

enum class SignalMetricCode {
    DAILY_CHANGE_PCT,
}

enum class SignalOperator {
    GTE,
    LTE,
}

enum class SignalAction {
    BUY,
    SELL,
}

enum class DecisionSource {
    SIGNAL_RULE,
    FACTOR_STRATEGY,
}

enum class TradeAuditEventType {
    RULE_TRIGGERED,
    EVALUATION_DECIDED,
    ORDER_CREATED,
    ORDER_SKIPPED,
    ORDER_REJECTED,
    ORDER_CANCELLED,
    EXECUTION_FILLED,
}

enum class ApiErrorProvider {
    KIS,
}

enum class ApiErrorType {
    NETWORK_TIMEOUT,
    HTTP_ERROR,
    AUTH_ERROR,
    KIS_BUSINESS_ERROR,
    MALFORMED_RESPONSE,
    MASTER_DOWNLOAD_ERROR,
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
