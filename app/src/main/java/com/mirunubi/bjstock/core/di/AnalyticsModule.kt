package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
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
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {
    @Provides
    @Singleton
    fun providePerformanceAnalyticsRepository(
        strategyRunDao: StrategyRunDao,
        strategyDao: StrategyDao,
        snapshotDao: PortfolioDailySnapshotDao,
        orderDao: OrderDao,
        executionDao: ExecutionDao,
        positionDao: PositionDao,
        cashLedgerDao: CashLedgerDao,
        evaluationDao: StockEvaluationDao,
        policyDao: PaperTradingPolicyDao,
        instrumentDao: InstrumentDao,
        marketDailyBarDao: MarketDailyBarDao,
    ): PerformanceAnalyticsRepository = PerformanceAnalyticsRepository(
        strategyRunDao = strategyRunDao,
        strategyDao = strategyDao,
        snapshotDao = snapshotDao,
        orderDao = orderDao,
        executionDao = executionDao,
        positionDao = positionDao,
        cashLedgerDao = cashLedgerDao,
        evaluationDao = evaluationDao,
        policyDao = policyDao,
        instrumentDao = instrumentDao,
        marketDailyBarDao = marketDailyBarDao,
    )

    @Provides
    @Singleton
    fun providePerformanceAnalyticsService(
        repository: PerformanceAnalyticsRepository,
    ): PerformanceAnalyticsService = PerformanceAnalyticsService(repository)
}
