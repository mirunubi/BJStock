package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import com.mirunubi.bjstock.core.strategy.PreviewStrategyEvaluationUseCase
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationLoader
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationRepository
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StrategyModule {
    @Provides
    @Singleton
    fun provideStrategyVersionService(
        strategyDao: StrategyDao,
        factorValues: FactorValueRepository,
        registry: FactorRegistry,
    ): StrategyVersionService = StrategyVersionService(
        strategyDao = strategyDao,
        factorValues = factorValues,
        registry = registry,
    )

    @Provides
    @Singleton
    fun provideStrategyRunService(
        database: BJStockDatabase,
        strategyDao: StrategyDao,
        strategyRunDao: StrategyRunDao,
        universeDao: com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao,
        instrumentDao: com.mirunubi.bjstock.core.database.dao.InstrumentDao,
        marketDailyBarDao: com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao,
        cashLedger: CashLedgerService,
        policyService: PaperTradingPolicyService,
        registry: FactorRegistry,
        credentials: com.mirunubi.bjstock.core.kis.KisCredentialStore,
        settings: com.mirunubi.bjstock.core.kis.KisSettingsStore,
        historyGateway: com.mirunubi.bjstock.core.forward.KisForwardMarketDataGateway,
        policyTemplate: PaperTradingPolicy,
    ): StrategyRunService = StrategyRunService(
        database = database,
        strategyDao = strategyDao,
        strategyRunDao = strategyRunDao,
        universeDao = universeDao,
        instrumentDao = instrumentDao,
        marketDailyBarDao = marketDailyBarDao,
        cashLedger = cashLedger,
        policyService = policyService,
        factorRegistry = registry,
        credentials = credentials,
        settings = settings,
        historyGateway = historyGateway,
        defaultPolicyTemplate = { policyTemplate },
    )

    @Provides
    @Singleton
    fun provideStrategyEvaluationRepository(
        database: BJStockDatabase,
        evaluationDao: StockEvaluationDao,
    ): StrategyEvaluationRepository = StrategyEvaluationRepository(database, evaluationDao)

    @Provides
    @Singleton
    fun provideStrategyEvaluationLoader(
        strategyService: StrategyVersionService,
        factorDao: FactorDao,
        factorValues: FactorValueRepository,
    ): StrategyEvaluationLoader = StrategyEvaluationLoader(
        strategyService = strategyService,
        factorDao = factorDao,
        factorValues = factorValues,
    )

    @Provides
    fun providePreviewStrategyEvaluationUseCase(
        strategyService: StrategyVersionService,
        loader: StrategyEvaluationLoader,
    ): PreviewStrategyEvaluationUseCase = PreviewStrategyEvaluationUseCase(strategyService, loader)

    @Provides
    fun provideEvaluateStrategyRunUseCase(
        strategyDao: StrategyDao,
        strategyRunDao: StrategyRunDao,
        evaluations: StrategyEvaluationRepository,
        loader: StrategyEvaluationLoader,
    ): EvaluateStrategyRunUseCase = EvaluateStrategyRunUseCase(
        strategyDao = strategyDao,
        strategyRunDao = strategyRunDao,
        evaluations = evaluations,
        loader = loader,
    )
}
