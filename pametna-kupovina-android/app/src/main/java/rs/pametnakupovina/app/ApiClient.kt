package rs.pametnakupovina.app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.PUT

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

data class BestPricesDto(
    val listId: Long,
    val listName: String,
    val matchedItems: Int,
    val unmatchedItems: Int,
    val totalPrice: Double,
    val items: List<BestPriceItemDto>
)

data class BestPriceItemDto(
    val itemId: Long,
    val requestedName: String,
    val barcode: String?,
    val quantity: Double,
    val matched: Boolean,
    val productId: Long?,
    val productName: String?,
    val retailerCode: String?,
    val retailerName: String?,
    val priceDate: String?,
    val regularPrice: Double?,
    val discountedPrice: Double?,
    val effectivePrice: Double?,
    val lineTotal: Double?
)

data class ProductSearchResultDto(
    val productId: Long,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val unit: String?,
    val categoryName: String?,
    val retailerCode: String?,
    val retailerName: String?,
    val retailerFormatName: String?,
    val priceDate: String?,
    val regularPrice: Double?,
    val discountedPrice: Double?,
    val unitPrice: Double?,
    val effectivePrice: Double?
)

data class UpdateShoppingListItemRequest(
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

    @GET("api/v1/shopping-lists/{id}/best-prices")
    suspend fun getBestPrices(
        @Path("id") listId: Long
    ): BestPricesDto

    @GET("api/v1/products/search")
    suspend fun searchProducts(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20
    ): List<ProductSearchResultDto>

    @PUT("api/v1/shopping-lists/{listId}/items/{itemId}")
    suspend fun updateShoppingListItem(
        @Path("listId") listId: Long,
        @Path("itemId") itemId: Long,
        @Body request: UpdateShoppingListItemRequest
    ): ShoppingListItemDto

    @DELETE("api/v1/shopping-lists/{listId}/items/{itemId}")
    suspend fun deleteShoppingListItem(
        @Path("listId") listId: Long,
        @Path("itemId") itemId: Long
    ): Response<Unit>
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