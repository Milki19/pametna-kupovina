package rs.pametnakupovina.app.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ShoppingApiService {

    @POST("api/v1/shopping-lists")
    suspend fun createShoppingList(
        @Body request: CreateShoppingListRequestDto
    ): ShoppingListSummaryDto

    @GET("api/v1/shopping-lists")
    suspend fun getShoppingLists(): List<ShoppingListSummaryDto>

    @GET("api/v1/shopping-lists/{listId}")
    suspend fun getShoppingList(
        @Path("listId") listId: Long
    ): ShoppingListDto

    @POST("api/v1/shopping-lists/{listId}/items")
    suspend fun addShoppingListItem(
        @Path("listId") listId: Long,
        @Body request: AddShoppingListItemRequestDto
    ): ShoppingListItemDto

    @POST("api/v1/shopping-lists/{listId}/items/paste")
    suspend fun pasteShoppingListItems(
        @Path("listId") listId: Long,
        @Body request: PasteShoppingListItemsRequestDto
    ): PasteShoppingListItemsResponseDto

    @PUT("api/v1/shopping-lists/{listId}/items/{itemId}")
    suspend fun updateShoppingListItem(
        @Path("listId") listId: Long,
        @Path("itemId") itemId: Long,
        @Body request: UpdateShoppingListItemRequestDto
    ): ShoppingListItemDto

    @DELETE("api/v1/shopping-lists/{listId}/items/{itemId}")
    suspend fun deleteShoppingListItem(
        @Path("listId") listId: Long,
        @Path("itemId") itemId: Long
    ): Response<Unit>

    @POST("api/v1/shopping-lists/{listId}/matching")
    suspend fun matchShoppingList(
        @Path("listId") listId: Long
    ): ShoppingListMatchingDto

    @PUT("api/v1/shopping-lists/{listId}/items/{itemId}/match")
    suspend fun resolveShoppingItemMatch(
        @Path("listId") listId: Long,
        @Path("itemId") itemId: Long,
        @Body request: ResolveShoppingItemMatchRequestDto
    ): ShoppingListItemDto

    @GET("api/v1/shopping-lists/{listId}/recommendations")
    suspend fun getRecommendations(
        @Path("listId") listId: Long,
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("date") date: String? = null
    ): ShoppingRecommendationDto
}
