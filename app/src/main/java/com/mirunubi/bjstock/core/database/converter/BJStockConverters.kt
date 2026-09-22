package com.mirunubi.bjstock.core.database.converter

import androidx.room.TypeConverter
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.AiRecommendation
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.FactorCategory
import com.mirunubi.bjstock.core.model.FactorValueType
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.InstrumentType
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.SellPolicy
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant
import java.time.LocalDate

class BJStockConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun factorCategoryToCode(value: FactorCategory): String = value.name

    @TypeConverter
    fun codeToFactorCategory(value: String): FactorCategory = FactorCategory.valueOf(value)

    @TypeConverter
    fun factorValueTypeToCode(value: FactorValueType): String = value.name

    @TypeConverter
    fun codeToFactorValueType(value: String): FactorValueType = FactorValueType.valueOf(value)

    @TypeConverter
    fun strategyVersionStatusToCode(value: StrategyVersionStatus): String = value.name

    @TypeConverter
    fun codeToStrategyVersionStatus(value: String): StrategyVersionStatus =
        StrategyVersionStatus.valueOf(value)

    @TypeConverter
    fun runTypeToCode(value: RunType): String = value.name

    @TypeConverter
    fun codeToRunType(value: String): RunType = RunType.valueOf(value)

    @TypeConverter
    fun runStatusToCode(value: RunStatus): String = value.name

    @TypeConverter
    fun codeToRunStatus(value: String): RunStatus = RunStatus.valueOf(value)

    @TypeConverter
    fun tradeDecisionToCode(value: TradeDecision): String = value.name

    @TypeConverter
    fun codeToTradeDecision(value: String): TradeDecision = TradeDecision.valueOf(value)

    @TypeConverter
    fun orderSideToCode(value: OrderSide): String = value.name

    @TypeConverter
    fun codeToOrderSide(value: String): OrderSide = OrderSide.valueOf(value)

    @TypeConverter
    fun orderTypeToCode(value: OrderType): String = value.name

    @TypeConverter
    fun codeToOrderType(value: String): OrderType = OrderType.valueOf(value)

    @TypeConverter
    fun orderStatusToCode(value: OrderStatus): String = value.name

    @TypeConverter
    fun codeToOrderStatus(value: String): OrderStatus = OrderStatus.valueOf(value)

    @TypeConverter
    fun cashLedgerEventTypeToCode(value: CashLedgerEventType): String = value.name

    @TypeConverter
    fun codeToCashLedgerEventType(value: String): CashLedgerEventType =
        CashLedgerEventType.valueOf(value)

    @TypeConverter
    fun executionPricePolicyToCode(value: ExecutionPricePolicy): String = value.name

    @TypeConverter
    fun codeToExecutionPricePolicy(value: String): ExecutionPricePolicy =
        ExecutionPricePolicy.valueOf(value)

    @TypeConverter
    fun additionalBuyPolicyToCode(value: AdditionalBuyPolicy): String = value.name

    @TypeConverter
    fun codeToAdditionalBuyPolicy(value: String): AdditionalBuyPolicy =
        AdditionalBuyPolicy.valueOf(value)

    @TypeConverter
    fun sellPolicyToCode(value: SellPolicy): String = value.name

    @TypeConverter
    fun codeToSellPolicy(value: String): SellPolicy = SellPolicy.valueOf(value)

    @TypeConverter
    fun aiRecommendationToCode(value: AiRecommendation): String = value.name

    @TypeConverter
    fun codeToAiRecommendation(value: String): AiRecommendation = AiRecommendation.valueOf(value)

    @TypeConverter
    fun forwardCycleStatusToCode(value: ForwardCycleStatus): String = value.name

    @TypeConverter
    fun codeToForwardCycleStatus(value: String): ForwardCycleStatus =
        ForwardCycleStatus.valueOf(value)

    @TypeConverter
    fun forwardCycleStageToCode(value: ForwardCycleStage): String = value.name

    @TypeConverter
    fun codeToForwardCycleStage(value: String): ForwardCycleStage =
        ForwardCycleStage.valueOf(value)

    @TypeConverter
    fun signalMetricToCode(value: SignalMetricCode): String = value.name

    @TypeConverter
    fun codeToSignalMetric(value: String): SignalMetricCode = SignalMetricCode.valueOf(value)

    @TypeConverter
    fun signalOperatorToCode(value: SignalOperator): String = value.name

    @TypeConverter
    fun codeToSignalOperator(value: String): SignalOperator = SignalOperator.valueOf(value)

    @TypeConverter
    fun signalActionToCode(value: SignalAction): String = value.name

    @TypeConverter
    fun codeToSignalAction(value: String): SignalAction = SignalAction.valueOf(value)

    @TypeConverter
    fun decisionSourceToCode(value: DecisionSource): String = value.name

    @TypeConverter
    fun codeToDecisionSource(value: String): DecisionSource = DecisionSource.valueOf(value)

    @TypeConverter
    fun tradeAuditEventToCode(value: TradeAuditEventType): String = value.name

    @TypeConverter
    fun codeToTradeAuditEvent(value: String): TradeAuditEventType =
        TradeAuditEventType.valueOf(value)

    @TypeConverter
    fun apiErrorProviderToCode(value: ApiErrorProvider): String = value.name

    @TypeConverter
    fun codeToApiErrorProvider(value: String): ApiErrorProvider = ApiErrorProvider.valueOf(value)

    @TypeConverter
    fun apiErrorTypeToCode(value: ApiErrorType): String = value.name

    @TypeConverter
    fun codeToApiErrorType(value: String): ApiErrorType = ApiErrorType.valueOf(value)

    @TypeConverter
    fun boardToCode(value: Board): String = value.name

    @TypeConverter
    fun codeToBoard(value: String): Board = Board.valueOf(value)

    @TypeConverter
    fun instrumentTypeToCode(value: InstrumentType): String = value.name

    @TypeConverter
    fun codeToInstrumentType(value: String): InstrumentType = InstrumentType.valueOf(value)
}
