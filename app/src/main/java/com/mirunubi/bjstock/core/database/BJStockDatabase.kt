package com.mirunubi.bjstock.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mirunubi.bjstock.core.database.converter.BJStockConverters
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.AiAdviceRequestEntity
import com.mirunubi.bjstock.core.database.entity.AiAdviceResultEntity
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.FactorDefinitionEntity
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import com.mirunubi.bjstock.core.database.entity.PositionEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationDetailEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity

@Database(
    entities = [
        InstrumentEntity::class,
        MarketDailyBarEntity::class,
        FactorDefinitionEntity::class,
        FactorValueEntity::class,
        StrategyEntity::class,
        StrategyVersionEntity::class,
        StrategyFactorWeightEntity::class,
        StrategyRunEntity::class,
        StockEvaluationEntity::class,
        StockEvaluationDetailEntity::class,
        PositionEntity::class,
        OrderEntity::class,
        ExecutionEntity::class,
        PortfolioDailySnapshotEntity::class,
        AiAdviceRequestEntity::class,
        AiAdviceResultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(BJStockConverters::class)
abstract class BJStockDatabase : RoomDatabase() {
    abstract fun instrumentDao(): InstrumentDao
    abstract fun strategyDao(): StrategyDao
    abstract fun strategyRunDao(): StrategyRunDao

    companion object {
        const val NAME = "bjstock.db"
        const val VERSION = 1
        const val ENTITY_COUNT = 16
    }
}
