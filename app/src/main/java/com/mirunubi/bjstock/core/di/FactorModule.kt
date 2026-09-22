package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FactorModule {
    @Provides
    @Singleton
    fun provideFactorRegistry(): FactorRegistry = SystemFactorRegistryFactory.create()

    @Provides
    @Singleton
    fun provideFactorValueRepository(factorDao: FactorDao): FactorValueRepository =
        FactorValueRepository(factorDao)

    @Provides
    @Singleton
    fun provideFactorCalculationService(
        registry: FactorRegistry,
        instrumentDao: InstrumentDao,
        marketDailyBarDao: MarketDailyBarDao,
        factorValues: FactorValueRepository,
    ): FactorCalculationService = FactorCalculationService(
        registry = registry,
        instrumentDao = instrumentDao,
        marketDailyBarDao = marketDailyBarDao,
        factorValues = factorValues,
    )
}
