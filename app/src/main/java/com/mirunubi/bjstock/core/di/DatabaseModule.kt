package com.mirunubi.bjstock.core.di

import android.content.Context
import androidx.room.Room
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.BJStockMigrations
import com.mirunubi.bjstock.core.database.dao.AiAdviceDao
import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PaperTradingPolicyDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BJStockDatabase {
        return Room.databaseBuilder(
            context,
            BJStockDatabase::class.java,
            BJStockDatabase.NAME,
        )
            .addMigrations(
                BJStockMigrations.MIGRATION_1_2,
                BJStockMigrations.MIGRATION_2_3,
                BJStockMigrations.MIGRATION_3_4,
                BJStockMigrations.MIGRATION_4_5,
            )
            .build()
    }

    @Provides
    fun provideInstrumentDao(database: BJStockDatabase): InstrumentDao = database.instrumentDao()

    @Provides
    fun provideMarketDailyBarDao(database: BJStockDatabase): MarketDailyBarDao =
        database.marketDailyBarDao()

    @Provides
    fun provideFactorDao(database: BJStockDatabase): FactorDao = database.factorDao()

    @Provides
    fun provideStrategyDao(database: BJStockDatabase): StrategyDao = database.strategyDao()

    @Provides
    fun provideStrategyRunDao(database: BJStockDatabase): StrategyRunDao = database.strategyRunDao()

    @Provides
    fun provideStockEvaluationDao(database: BJStockDatabase): StockEvaluationDao =
        database.stockEvaluationDao()

    @Provides
    fun provideCashLedgerDao(database: BJStockDatabase): CashLedgerDao = database.cashLedgerDao()

    @Provides
    fun provideOrderDao(database: BJStockDatabase): OrderDao = database.orderDao()

    @Provides
    fun provideExecutionDao(database: BJStockDatabase): ExecutionDao = database.executionDao()

    @Provides
    fun providePositionDao(database: BJStockDatabase): PositionDao = database.positionDao()

    @Provides
    fun providePortfolioDailySnapshotDao(database: BJStockDatabase): PortfolioDailySnapshotDao =
        database.portfolioDailySnapshotDao()

    @Provides
    fun providePaperTradingPolicyDao(database: BJStockDatabase): PaperTradingPolicyDao =
        database.paperTradingPolicyDao()

    @Provides
    fun provideAiAdviceDao(database: BJStockDatabase): AiAdviceDao = database.aiAdviceDao()
}
