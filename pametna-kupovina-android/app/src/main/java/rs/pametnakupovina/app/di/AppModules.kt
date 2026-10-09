package rs.pametnakupovina.app.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import rs.pametnakupovina.app.BuildConfig
import rs.pametnakupovina.app.data.local.DraftItemDao
import rs.pametnakupovina.app.data.local.MIGRATION_1_2
import rs.pametnakupovina.app.data.local.MIGRATION_2_3
import rs.pametnakupovina.app.data.local.MIGRATION_3_4
import rs.pametnakupovina.app.data.local.MIGRATION_4_5
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.purchase.MissingProductReporter
import rs.pametnakupovina.app.data.network.SessionApiService
import rs.pametnakupovina.app.data.network.SessionAuthenticator
import rs.pametnakupovina.app.data.network.SessionInterceptor
import rs.pametnakupovina.app.data.network.SessionManager
import rs.pametnakupovina.app.data.network.ShoppingApiService

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

    /** Klijent bez sesije: samo za otvaranje i obnovu sesije. */
    @Provides
    @Singleton
    fun provideSessionApiService(json: Json): SessionApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.BACKEND_BASE_URL)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(
            json.asConverterFactory("application/json".toMediaType())
        )
        .build()
        .create(SessionApiService::class.java)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        sessionManager: SessionManager
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(SessionInterceptor(sessionManager))
        .authenticator(SessionAuthenticator(sessionManager))
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
object PurchaseModule {

    @Provides
    fun provideMissingProductReporter(repository: ShoppingRepository): MissingProductReporter =
        MissingProductReporter(repository::reportNotInStore)
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
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()

    @Provides
    fun provideDraftItemDao(
        database: PametnaKupovinaDatabase
    ): DraftItemDao = database.draftItemDao()
}

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideFusedLocationClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

}
