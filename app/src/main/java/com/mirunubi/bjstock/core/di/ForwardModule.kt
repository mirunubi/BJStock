package com.mirunubi.bjstock.core.di

import android.content.Context
import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.ForwardTestCycleDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.forward.ForwardMarketDataGateway
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestOrchestrator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.ForwardTestSchedulerSettings
import com.mirunubi.bjstock.core.forward.KisForwardMarketDataGateway
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import com.mirunubi.bjstock.core.paper.ProcessPendingOrdersUseCase
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ForwardModule {
    @Provides
    @Singleton
    fun provideForwardTestClock(): ForwardTestClock = ForwardTestClock()

    @Provides
    @Singleton
    fun provideForwardTestSchedulerSettings(
        @ApplicationContext context: Context,
    ): ForwardTestSchedulerSettings = ForwardTestSchedulerSettings(context)

    @Provides
    @Singleton
    fun provideForwardTestScheduler(
        @ApplicationContext context: Context,
        settings: ForwardTestSchedulerSettings,
    ): ForwardTestScheduler = ForwardTestScheduler(context, settings)

    @Provides
    @Singleton
    fun provideKisForwardMarketDataGateway(
        credentials: KisCredentialStore,
        settings: KisSettingsStore,
        localRepository: MarketDataLocalRepository,
        syncFromLatest: SyncDailyBarsFromLatestUseCase,
        historicalSync: SyncHistoricalDailyBarsUseCase,
    ): KisForwardMarketDataGateway = KisForwardMarketDataGateway(
        credentials = credentials,
        settings = settings,
        localRepository = localRepository,
        syncFromLatest = syncFromLatest,
        historicalSync = historicalSync,
    )

    @Provides
    @Singleton
    fun provideForwardMarketDataGateway(
        gateway: KisForwardMarketDataGateway,
    ): ForwardMarketDataGateway = gateway

    @Provides
    @Singleton
    fun provideForwardTestOrchestrator(
        strategyRunDao: StrategyRunDao,
        strategyDao: StrategyDao,
        universeDao: StrategyRunInstrumentDao,
        cycleDao: ForwardTestCycleDao,
        marketDailyBarDao: MarketDailyBarDao,
        orderDao: OrderDao,
        evaluationDao: StockEvaluationDao,
        snapshotDao: PortfolioDailySnapshotDao,
        factorDao: FactorDao,
        policyService: PaperTradingPolicyService,
        processPending: ProcessPendingOrdersUseCase,
        factorCalculation: FactorCalculationService,
        evaluateRun: EvaluateStrategyRunUseCase,
        processEvaluation: ProcessEvaluationUseCase,
        createSnapshot: CreateDailySnapshotUseCase,
        marketData: ForwardMarketDataGateway,
        clock: ForwardTestClock,
    ): ForwardTestOrchestrator = ForwardTestOrchestrator(
        strategyRunDao = strategyRunDao,
        strategyDao = strategyDao,
        universeDao = universeDao,
        cycleDao = cycleDao,
        marketDailyBarDao = marketDailyBarDao,
        orderDao = orderDao,
        evaluationDao = evaluationDao,
        snapshotDao = snapshotDao,
        factorDao = factorDao,
        policyService = policyService,
        processPending = processPending,
        factorCalculation = factorCalculation,
        evaluateRun = evaluateRun,
        processEvaluation = processEvaluation,
        createSnapshot = createSnapshot,
        marketData = marketData,
        clock = clock,
    )
}
