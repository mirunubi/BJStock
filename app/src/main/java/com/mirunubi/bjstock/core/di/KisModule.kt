package com.mirunubi.bjstock.core.di

import android.content.Context
import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.kis.EncryptedKisSecretStore
import com.mirunubi.bjstock.core.kis.KisAuthLogger
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisEnvironmentConfig
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.kis.KisTokenStore
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisMarketRepositoryImpl
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisReadOnlyInterceptor
import com.mirunubi.bjstock.core.security.AesGcmSecretCipher
import com.mirunubi.bjstock.core.security.SecretCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object KisModule {
    @Provides
    @Singleton
    fun provideSecretCipher(): SecretCipher = AesGcmSecretCipher()

    @Provides
    @Singleton
    fun provideKisSecretStore(
        @ApplicationContext context: Context,
        cipher: SecretCipher,
    ): EncryptedKisSecretStore {
        val preferences = context.getSharedPreferences(
            EncryptedKisSecretStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        return EncryptedKisSecretStore(preferences, cipher)
    }

    @Provides
    fun provideCredentialStore(store: EncryptedKisSecretStore): KisCredentialStore = store

    @Provides
    fun provideTokenStore(store: EncryptedKisSecretStore): KisTokenStore = store

    @Provides
    fun provideSettingsStore(store: EncryptedKisSecretStore): KisSettingsStore = store

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(KisReadOnlyInterceptor())
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(KisEnvironmentConfig.baseUrl(KisEnvironment.PRODUCTION) + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideKisAuthApi(retrofit: Retrofit): KisAuthApi = retrofit.create(KisAuthApi::class.java)

    @Provides
    @Singleton
    fun provideKisMarketApi(retrofit: Retrofit): KisMarketApi = retrofit.create(KisMarketApi::class.java)

    @Provides
    @Singleton
    fun provideKisAuthLogger(): KisAuthLogger = KisAuthLogger { message ->
        Log.i("BJStockKisAuth", message)
    }

    @Provides
    @Singleton
    fun provideKisAuthRepository(
        api: KisAuthApi,
        credentialStore: KisCredentialStore,
        tokenStore: KisTokenStore,
        settingsStore: KisSettingsStore,
        logger: KisAuthLogger,
    ): KisAuthRepository = KisAuthRepository(
        api = api,
        credentialStore = credentialStore,
        tokenStore = tokenStore,
        settingsStore = settingsStore,
        logger = logger,
    )

    @Provides
    @Singleton
    fun provideKisMarketRepository(
        api: KisMarketApi,
        authRepository: KisAuthRepository,
        credentialStore: KisCredentialStore,
        logger: KisAuthLogger,
    ): KisMarketRepository = KisMarketRepositoryImpl(
        api = api,
        authRepository = authRepository,
        credentialStore = credentialStore,
        logger = logger,
    )
}
