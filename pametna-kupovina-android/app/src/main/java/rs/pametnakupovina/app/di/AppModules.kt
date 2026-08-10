package rs.pametnakupovina.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import rs.pametnakupovina.app.BuildConfig
import rs.pametnakupovina.app.data.local.DraftItemDao
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.network.ShoppingApiService
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        clientIdentityStore: ClientIdentityStore
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = runBlocking(Dispatchers.IO) {
                clientIdentityStore.getOrCreateToken()
            }
            val request = chain.request().newBuilder()
                .header("X-Client-Token", token)
                .build()
            chain.proceed(request)
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        json: Json,
        okHttpClient: OkHttpClient
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BACKEND_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(
            json.asConverterFactory("application/json".toMediaType())
        )
        .build()

    @Provides
    @Singleton
    fun provideShoppingApiService(
        retrofit: Retrofit
    ): ShoppingApiService = retrofit.create(ShoppingApiService::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): PametnaKupovinaDatabase = Room.databaseBuilder(
        context,
        PametnaKupovinaDatabase::class.java,
        "pametna-kupovina.db"
    ).build()

    @Provides
    fun provideDraftItemDao(
        database: PametnaKupovinaDatabase
    ): DraftItemDao = database.draftItemDao()
}
