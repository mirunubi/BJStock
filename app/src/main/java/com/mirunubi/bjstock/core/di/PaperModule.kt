package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
import com.mirunubi.bjstock.core.paper.PaperTradingEngine
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import com.mirunubi.bjstock.core.paper.ProcessPendingOrdersUseCase
import com.mirunubi.bjstock.core.paper.VirtualFillService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PaperModule {
    @Provides
    @Singleton
    fun providePaperTradingPolicy(): PaperTradingPolicy = PaperTradingPolicy.DEFAULT

    @Provides
    @Singleton
    fun provideCashLedgerService(cashLedgerDao: CashLedgerDao): CashLedgerService =
        CashLedgerService(cashLedgerDao)

    @Provides
    @Singleton
    fun provideVirtualFillService(
        database: BJStockDatabase,
        orderDao: OrderDao,
        executionDao: ExecutionDao,
        positionDao: PositionDao,
        cashLedger: CashLedgerService,
    ): VirtualFillService = VirtualFillService(
        database = database,
        orderDao = orderDao,
        executionDao = executionDao,
        positionDao = positionDao,
        cashLedger = cashLedger,
    )

    @Provides
    @Singleton
    fun provideProcessEvaluationUseCase(
        evaluationDao: StockEvaluationDao,
        strategyRunDao: StrategyRunDao,
        orderDao: OrderDao,
        positionDao: PositionDao,
    ): ProcessEvaluationUseCase = ProcessEvaluationUseCase(
        evaluationDao = evaluationDao,
        strategyRunDao = strategyRunDao,
        orderDao = orderDao,
        positionDao = positionDao,
    )

    @Provides
    @Singleton
    fun provideProcessPendingOrdersUseCase(
        strategyRunDao: StrategyRunDao,
        orderDao: OrderDao,
        evaluationDao: StockEvaluationDao,
        marketDailyBarDao: MarketDailyBarDao,
        positionDao: PositionDao,
        cashLedger: CashLedgerService,
        fills: VirtualFillService,
        policy: PaperTradingPolicy,
    ): ProcessPendingOrdersUseCase = ProcessPendingOrdersUseCase(
        strategyRunDao = strategyRunDao,
        orderDao = orderDao,
        evaluationDao = evaluationDao,
        marketDailyBarDao = marketDailyBarDao,
        positionDao = positionDao,
        cashLedger = cashLedger,
        fills = fills,
        policy = policy,
    )

    @Provides
    @Singleton
    fun provideCreateDailySnapshotUseCase(
        strategyRunDao: StrategyRunDao,
        positionDao: PositionDao,
        marketDailyBarDao: MarketDailyBarDao,
        snapshotDao: PortfolioDailySnapshotDao,
        cashLedger: CashLedgerService,
    ): CreateDailySnapshotUseCase = CreateDailySnapshotUseCase(
        strategyRunDao = strategyRunDao,
        positionDao = positionDao,
        marketDailyBarDao = marketDailyBarDao,
        snapshotDao = snapshotDao,
        cashLedger = cashLedger,
    )

    @Provides
    @Singleton
    fun providePaperTradingEngine(
        strategyRunDao: StrategyRunDao,
        evaluationDao: StockEvaluationDao,
        processEvaluation: ProcessEvaluationUseCase,
        processPending: ProcessPendingOrdersUseCase,
        createSnapshot: CreateDailySnapshotUseCase,
        cashLedger: CashLedgerService,
    ): PaperTradingEngine = PaperTradingEngine(
        strategyRunDao = strategyRunDao,
        evaluationDao = evaluationDao,
        processEvaluation = processEvaluation,
        processPending = processPending,
        createSnapshot = createSnapshot,
        cashLedger = cashLedger,
    )
}
