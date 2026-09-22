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

    @GET("api/v1/products/search")
    suspend fun searchProducts(
        @Query("query") query: String,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 10,
        @Query("includeWithoutPrice") includeWithoutPrice: Boolean = false
    ): CanonicalProductSearchPageDto

    @GET("api/v1/products/{canonicalProductId}")
    suspend fun getProductDetails(
        @Path("canonicalProductId") canonicalProductId: Long,
        @Query("date") date: String? = null,
        @Query("historyLimit") historyLimit: Int = 30
    ): CanonicalProductDetailsDto

    @GET("api/v1/loyalty-cards")
    suspend fun getLoyaltyCards(): List<LoyaltyCardDto>

    @POST("api/v1/loyalty-cards")
    suspend fun addLoyaltyCard(
        @Body request: AddLoyaltyCardRequestDto
    ): LoyaltyCardDto

    @DELETE("api/v1/loyalty-cards/{cardId}")
    suspend fun deleteLoyaltyCard(@Path("cardId") cardId: Long)

    @GET("api/v1/receipts/habits")
    suspend fun getHabits(
        @Query("limit") limit: Int = 20
    ): List<HabitDto>

    @POST("api/v1/receipts")
    suspend fun scanReceipt(
        @Body request: ScanReceiptRequestDto
    ): ReceiptDto

    @GET("api/v1/receipts")
    suspend fun getReceipts(
        @Query("limit") limit: Int = 50
    ): List<ReceiptDto>

    @GET("api/v1/receipts/spending")
    suspend fun getSpending(
        @Query("month") month: String? = null
    ): SpendingDto

    @GET("api/v1/accounts/me")
    suspend fun getAccount(): AccountStateDto

    @POST("api/v1/accounts/sign-in/google")
    suspend fun signInWithGoogle(
        @Body request: GoogleSignInRequestDto
    ): AccountStateDto

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
        @Path("listId") listId: Long,
        @Query("includeProductsWithoutBarcode") includeProductsWithoutBarcode: Boolean = true
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

    @POST("api/v1/products/{canonicalProductId}/reports")
    suspend fun reportProduct(
        @Path("canonicalProductId") canonicalProductId: Long,
        @Body request: ProductReportRequestDto
    ): ProductReportResponseDto
}
