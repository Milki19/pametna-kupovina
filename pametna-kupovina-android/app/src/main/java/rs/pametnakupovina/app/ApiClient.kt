package rs.pametnakupovina.app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class ShoppingListSummaryDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val itemCount: Long
)

data class ShoppingListItemDto(
    val id: Long,
    val name: String,
    val barcode: String?,
    val quantity: Double,
    val createdAt: String
)

data class ShoppingListDetailsDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ShoppingListItemDto>
)

data class CreateShoppingListItemRequest(
    val name: String,
    val barcode: String?,
    val quantity: Double
)

interface ShoppingApiService {

    @GET("api/v1/shopping-lists")
    suspend fun getShoppingLists(): List<ShoppingListSummaryDto>

    @GET("api/v1/shopping-lists/{id}")
    suspend fun getShoppingList(
        @Path("id") id: Long
    ): ShoppingListDetailsDto

    @POST("api/v1/shopping-lists/{id}/items")
    suspend fun addShoppingListItem(
        @Path("id") listId: Long,
        @Body request: CreateShoppingListItemRequest
    ): ShoppingListItemDto
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