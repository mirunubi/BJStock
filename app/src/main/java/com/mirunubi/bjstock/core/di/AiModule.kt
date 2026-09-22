package com.mirunubi.bjstock.core.di

import android.content.Context
import com.mirunubi.bjstock.core.ai.AiAdviceResponseParser
import com.mirunubi.bjstock.core.ai.AiAdvisoryModeStore
import com.mirunubi.bjstock.core.ai.AiAdvisoryPromptBuilder
import com.mirunubi.bjstock.core.ai.AiAdvisoryService
import com.mirunubi.bjstock.core.ai.FutureOpenAiGatewayProvider
import com.mirunubi.bjstock.core.ai.ManualChatGptAdvisoryProvider
import com.mirunubi.bjstock.core.database.dao.AiAdviceDao
import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PaperTradingPolicyDao
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
object AiModule {
    @Provides
    @Singleton
    fun provideAiAdvisoryModeStore(
        @ApplicationContext context: Context,
    ): AiAdvisoryModeStore = AiAdvisoryModeStore(context)

    @Provides
    @Singleton
    fun provideAiAdvisoryPromptBuilder(
        evaluationDao: StockEvaluationDao,
        instrumentDao: InstrumentDao,
        factorDao: FactorDao,
        strategyRunDao: StrategyRunDao,
        strategyDao: StrategyDao,
        marketDailyBarDao: MarketDailyBarDao,
        policyDao: PaperTradingPolicyDao,
    ): AiAdvisoryPromptBuilder = AiAdvisoryPromptBuilder(
        evaluationDao = evaluationDao,
        instrumentDao = instrumentDao,
        factorDao = factorDao,
        strategyRunDao = strategyRunDao,
        strategyDao = strategyDao,
        marketDailyBarDao = marketDailyBarDao,
        policyDao = policyDao,
    )

    @Provides
    @Singleton
    fun provideAiAdviceResponseParser(): AiAdviceResponseParser = AiAdviceResponseParser()

    @Provides
    @Singleton
    fun provideManualChatGptAdvisoryProvider(): ManualChatGptAdvisoryProvider =
        ManualChatGptAdvisoryProvider()

    @Provides
    @Singleton
    fun provideFutureOpenAiGatewayProvider(): FutureOpenAiGatewayProvider =
        FutureOpenAiGatewayProvider()

    @Provides
    @Singleton
    fun provideAiAdvisoryService(
        modeStore: AiAdvisoryModeStore,
        promptBuilder: AiAdvisoryPromptBuilder,
        parser: AiAdviceResponseParser,
        aiAdviceDao: AiAdviceDao,
        evaluationDao: StockEvaluationDao,
        orderDao: OrderDao,
    ): AiAdvisoryService = AiAdvisoryService(
        modeStore = modeStore,
        promptBuilder = promptBuilder,
        parser = parser,
        aiAdviceDao = aiAdviceDao,
        evaluationDao = evaluationDao,
        orderDao = orderDao,
    )
}
