package com.mirunubi.bjstock.core.di

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.instrument.InstrumentMasterDownloader
import com.mirunubi.bjstock.core.instrument.InstrumentMasterSynchronizer
import com.mirunubi.bjstock.core.instrument.KisMstParser
import com.mirunubi.bjstock.core.instrument.OkHttpInstrumentMasterDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InstrumentMasterHttp

@Module
@InstallIn(SingletonComponent::class)
object InstrumentMasterModule {
    @Provides
    @Singleton
    @InstrumentMasterHttp
    fun provideInstrumentMasterHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideInstrumentMasterDownloader(
        @InstrumentMasterHttp client: OkHttpClient,
    ): InstrumentMasterDownloader = OkHttpInstrumentMasterDownloader(client)

    @Provides
    @Singleton
    fun provideKisMstParser(): KisMstParser = KisMstParser()

    @Provides
    @Singleton
    fun provideInstrumentMasterSynchronizer(
        downloader: InstrumentMasterDownloader,
        parser: KisMstParser,
        database: BJStockDatabase,
        instrumentDao: InstrumentDao,
        apiErrorLog: ApiErrorLogService,
    ): InstrumentMasterSynchronizer = InstrumentMasterSynchronizer(
        downloader = downloader,
        parser = parser,
        database = database,
        instrumentDao = instrumentDao,
        apiErrorLog = apiErrorLog,
    )
}
