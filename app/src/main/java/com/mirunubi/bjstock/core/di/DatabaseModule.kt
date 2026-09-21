package com.mirunubi.bjstock.core.di

import android.content.Context
import androidx.room.Room
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
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
        ).build()
    }

    @Provides
    fun provideInstrumentDao(database: BJStockDatabase): InstrumentDao = database.instrumentDao()

    @Provides
    fun provideMarketDailyBarDao(database: BJStockDatabase): MarketDailyBarDao =
        database.marketDailyBarDao()

    @Provides
    fun provideStrategyDao(database: BJStockDatabase): StrategyDao = database.strategyDao()

    @Provides
    fun provideStrategyRunDao(database: BJStockDatabase): StrategyRunDao = database.strategyRunDao()
}
