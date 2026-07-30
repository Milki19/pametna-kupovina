package rs.pametnakupovina.app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class ShoppingListSummaryDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val itemCount: Long
)

interface ShoppingApiService {

    @GET("api/v1/shopping-lists")
    suspend fun getShoppingLists(): List<ShoppingListSummaryDto>
}

object ApiClient {

    private const val BASE_URL = "http://127.0.0.1:8080/"

    val shoppingApi: ShoppingApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ShoppingApiService::class.java)
    }
}