package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.marketdata.FetchAndPersistDailyBarsUseCase
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketDataModule {
    @Provides
    @Singleton
    fun provideMarketDataLocalRepository(
        database: BJStockDatabase,
        instrumentDao: InstrumentDao,
        marketDailyBarDao: MarketDailyBarDao,
    ): MarketDataLocalRepository = MarketDataLocalRepository(
        database = database,
        instrumentDao = instrumentDao,
        marketDailyBarDao = marketDailyBarDao,
    )

    @Provides
    fun provideFetchAndPersistDailyBarsUseCase(
        marketRepository: KisMarketRepository,
        localRepository: MarketDataLocalRepository,
    ): FetchAndPersistDailyBarsUseCase = FetchAndPersistDailyBarsUseCase(
        marketRepository = marketRepository,
        localRepository = localRepository,
    )
}
