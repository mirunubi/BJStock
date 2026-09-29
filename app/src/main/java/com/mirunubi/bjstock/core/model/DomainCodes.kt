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

enum class ForwardOperationTrigger {
    MANUAL,
    WORKER,
}

enum class ForwardOperationKind {
    FORWARD_RUN,
    RETRY_FAILED_CYCLE,
}

enum class ForwardOperationStatus {
    RUNNING,
    SUCCEEDED,
    NO_OP,
    PARTIAL,
    BLOCKED,
    FAILED,
}

/** `operational_events.result` for RUN_RESULT. */
enum class ForwardRunResult {
    PROCESSED,
    SKIPPED,
    BLOCKED,
    FAILED,
    NO_OP,
}

/**
 * Durable reason tokens for operation / run outcomes that have no [com.mirunubi.bjstock.core.error.AppErrorCode].
 * Failures with a canonical error code persist that code instead.
 */
enum class ForwardOutcomeReason {
    PROCESSED,
    NO_ELIGIBLE_RUNS,
    THROUGH_DATE_BEFORE_START,
    EMPTY_UNIVERSE,
    MISSING_POLICY,
    AUTH_REQUIRED,
    PREVIOUS_FAILED_CYCLE,
    WAITING_FOR_MARKET_DATA,
    INVALID_RUN_STATE,
    RUN_NOT_FOUND,
    PRIOR_RUN_BLOCKED,
    ALREADY_RUNNING,
    CANCELLED,
    TARGET_CYCLE_NOT_FOUND,
    TARGET_CYCLE_NOT_FAILED,
    TARGET_CYCLE_RUN_MISMATCH,
}

enum class OperationalEventType {
    OPERATION_STARTED,
    OPERATION_FINISHED,
    MARKET_SYNC_RESULT,
    RUN_RESULT,
    CYCLE_STARTED,
    CYCLE_FINISHED,
    WORKER_SCHEDULE_CHANGED,
}

enum class ApiErrorType {
    NETWORK_TIMEOUT,
    HTTP_ERROR,
    AUTH_ERROR,
    KIS_BUSINESS_ERROR,
    MALFORMED_RESPONSE,
    MASTER_DOWNLOAD_ERROR,
    RATE_LIMIT,
    LOCAL_INVARIANT,
    UNEXPECTED,
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
