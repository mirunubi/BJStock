package com.mirunubi.bjstock.core.database.converter

import androidx.room.TypeConverter
import com.mirunubi.bjstock.core.model.AiRecommendation
import com.mirunubi.bjstock.core.model.FactorCategory
import com.mirunubi.bjstock.core.model.FactorValueType
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
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
    fun aiRecommendationToCode(value: AiRecommendation): String = value.name

    @TypeConverter
    fun codeToAiRecommendation(value: String): AiRecommendation = AiRecommendation.valueOf(value)
}
