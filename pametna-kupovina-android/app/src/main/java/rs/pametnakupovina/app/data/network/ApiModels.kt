package rs.pametnakupovina.app.data.network

import kotlinx.serialization.Serializable

@Serializable
enum class ShoppingItemRuleDto {
    EXACT_PRODUCT,
    PRODUCT_FAMILY,
    FLEXIBLE_CATEGORY
}

@Serializable
enum class ShoppingItemMatchingStatusDto {
    PENDING,
    AUTO_MATCHED,
    NEEDS_CONFIRMATION,
    CONFIRMED,
    UNMATCHED
}

@Serializable
enum class ShoppingItemMatchActionDto {
    CONFIRM,
    REJECT
}

@Serializable
enum class RecommendationItemStatusDto {
    AVAILABLE,
    NEEDS_CONFIRMATION,
    UNMATCHED,
    NO_VALID_PRICE
}

@Serializable
enum class RecommendationScenarioTypeDto {
    SINGLE_STORE,
    RECOMMENDED_BALANCE,
    LOWEST_PRICE
}

@Serializable
data class FlexibleItemConstraintsDto(
    val category: String,
    val requiredBrand: String? = null,
    val minPackageQuantity: Double? = null,
    val maxPackageQuantity: Double? = null,
    val requiredBaseUnit: String? = null,
    val targetQuantity: Double? = null
)

@Serializable
data class ShoppingListSummaryDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val itemCount: Int
)

@Serializable
data class ShoppingListItemDto(
    val id: Long,
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val quantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val matchingStatus: ShoppingItemMatchingStatusDto,
    val matchedCanonicalProductId: Long? = null,
    val matchedProductFamilyId: Long? = null,
    val matchingDecisionId: Long? = null,
    val matchingScore: Double? = null,
    val matchingAlgorithmVersion: String? = null,
    val flexibleConstraints: FlexibleItemConstraintsDto? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ShoppingListDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ShoppingListItemDto>
)

@Serializable
data class CreateShoppingListRequestDto(val name: String)

@Serializable
data class AddShoppingListItemRequestDto(
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val quantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val flexibleConstraints: FlexibleItemConstraintsDto? = null
)

@Serializable
data class UpdateShoppingListItemRequestDto(
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val quantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val flexibleConstraints: FlexibleItemConstraintsDto? = null
)

@Serializable
data class PasteShoppingListItemsRequestDto(val text: String)

@Serializable
data class PasteShoppingListItemsResponseDto(
    val createdCount: Int,
    val ignoredBlankLineCount: Int,
    val items: List<ShoppingListItemDto>
)

@Serializable
data class CanonicalProductSearchItemDto(
    val productFamilyId: Long? = null,
    val canonicalProductId: Long? = null,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val quantityValue: Double? = null,
    val baseUnit: String? = null,
    val categoryCode: String? = null,
    val categoryName: String? = null,
    val variantCount: Int = 1,
    val availability: List<ProductRetailerAvailabilityDto> = emptyList(),
    val score: Double,
    val hasUsablePrice: Boolean = false,
    val knownRetailers: List<String> = emptyList(),
    val packageCount: Int = 1
)

@Serializable
data class ProductRetailerAvailabilityDto(
    val retailerCode: String,
    val retailerName: String,
    val latestPriceDate: String,
    val storeCount: Int = 0,
    val formatCount: Int = 0,
    val minimumEffectivePrice: Double? = null,
    val priceNeedsCheck: Boolean = false
)

@Serializable
data class CanonicalProductSearchPageDto(
    val query: String,
    val page: Int,
    val limit: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val items: List<CanonicalProductSearchItemDto> = emptyList()
)

@Serializable
data class CanonicalProductOfferDto(
    val retailerProductId: Long,
    val retailerCode: String,
    val retailerName: String,
    val storeId: Long? = null,
    val storeName: String? = null,
    val storeFormatCode: String? = null,
    val storeFormatName: String? = null,
    val priceDate: String,
    val regularPrice: Double? = null,
    val discountedPrice: Double? = null,
    val effectivePrice: Double,
    val unitPrice: Double? = null,
    val priceScope: String,
    val priceNeedsCheck: Boolean = false
)

@Serializable
data class CanonicalProductPricePointDto(
    val retailerProductId: Long,
    val retailerCode: String,
    val retailerName: String,
    val storeId: Long? = null,
    val storeName: String? = null,
    val storeFormatName: String? = null,
    val priceDate: String,
    val regularPrice: Double? = null,
    val discountedPrice: Double? = null,
    val effectivePrice: Double,
    val priceScope: String
)

@Serializable
data class CanonicalProductDetailsDto(
    val canonicalProductId: Long,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val quantityValue: Double? = null,
    val baseUnit: String? = null,
    val requestedDate: String,
    val latestPriceDate: String? = null,
    val offers: List<CanonicalProductOfferDto> = emptyList(),
    val priceHistory: List<CanonicalProductPricePointDto> = emptyList(),
    val packageCount: Int = 1
)

@Serializable
data class ProductMatchScoreDto(
    val totalScore: Double,
    val nameContribution: Double,
    val brandContribution: Double,
    val packageContribution: Double,
    val reasons: List<String> = emptyList()
)

@Serializable
data class ProductCandidateDto(
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val quantityValue: Double? = null,
    val baseUnit: String? = null,
    val nameSimilarity: Double,
    val score: ProductMatchScoreDto,
    val packageCount: Int = 1
)

@Serializable
data class ShoppingItemMatchResultDto(
    val itemId: Long,
    val requestedName: String,
    val matchingRule: ShoppingItemRuleDto,
    val matchingStatus: ShoppingItemMatchingStatusDto,
    val matchedCanonicalProductId: Long? = null,
    val decisionId: Long? = null,
    val score: Double? = null,
    val blocksOptimization: Boolean,
    val explanation: String,
    val candidates: List<ProductCandidateDto> = emptyList()
)

@Serializable
data class ShoppingListMatchingDto(
    val listId: Long,
    val totalItems: Int,
    val automaticallyMatchedItems: Int,
    val confirmedItems: Int = 0,
    val itemsNeedingConfirmation: Int,
    val unmatchedItems: Int,
    val flexibleItems: Int,
    val readyForOptimization: Boolean,
    val blockingItemIds: List<Long> = emptyList(),
    val items: List<ShoppingItemMatchResultDto> = emptyList()
)

@Serializable
data class ResolveShoppingItemMatchRequestDto(
    val action: ShoppingItemMatchActionDto,
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val note: String? = null
)

@Serializable
data class OptimizationAssumptionsDto(
    val candidateRadiusMeters: Int,
    val maxCandidateStores: Int,
    val maxPriceAgeDays: Int = 30,
    val costPerKm: Double,
    val valuePerHour: Double,
    val costPerStop: Double,
    val straightLineAverageSpeedKmh: Double,
    val currency: String
)

@Serializable
data class RecommendationStoreDto(
    val stopOrder: Int,
    val storeId: Long,
    val retailerCode: String,
    val retailerName: String,
    val storeFormatCode: String? = null,
    val storeFormatName: String? = null,
    val storeName: String,
    val address: String? = null,
    val city: String? = null,
    val latitude: Double,
    val longitude: Double,
    val distanceFromPreviousKm: Double,
    val durationFromPreviousSeconds: Long
)

@Serializable
data class PurchaseQuantityDto(
    val packages: Double? = null,
    val packageSize: Double? = null,
    val baseUnit: String? = null,
    val targetAmount: Double? = null,
    val suppliedAmount: Double? = null,
    val extraAmount: Double? = null,
    val unitPrice: Double? = null
)

@Serializable
data class RecommendationItemDto(
    val itemId: Long,
    val requestedName: String,
    val requestedQuantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val matchingStatus: ShoppingItemMatchingStatusDto,
    val resultStatus: RecommendationItemStatusDto,
    val storeId: Long? = null,
    val retailerCode: String? = null,
    val retailerName: String? = null,
    val canonicalProductId: Long? = null,
    val retailerProductId: Long? = null,
    val productName: String? = null,
    val productBrand: String? = null,
    val productBarcode: String? = null,
    val priceDate: String? = null,
    val effectivePrice: Double? = null,
    val lineTotal: Double? = null,
    val priceScope: String? = null,
    val explanation: String,
    val purchaseQuantity: PurchaseQuantityDto? = null
)

@Serializable
data class OptimizationScenarioDto(
    val type: RecommendationScenarioTypeDto,
    val available: Boolean,
    val complete: Boolean,
    val explanation: String,
    val coveredItems: Int,
    val unmatchedItems: Int,
    val unavailableItems: Int,
    val stopCount: Int,
    val basketCost: Double,
    val routeDistanceKm: Double,
    val routeDurationSeconds: Long,
    val travelCost: Double,
    val timeCost: Double,
    val stopCost: Double,
    val totalCost: Double? = null,
    val savingsComparedWithSingleStore: Double? = null,
    val routeProvider: String,
    val distanceMethod: String,
    val approximateRoute: Boolean,
    val priceSources: List<String> = emptyList(),
    val dataAsOf: String? = null,
    val stores: List<RecommendationStoreDto> = emptyList(),
    val items: List<RecommendationItemDto> = emptyList(),
    val disclaimer: String
)

/**
 * A chain that publishes prices but not where its shops are. It never enters
 * a plan; it only answers whether somewhere else would have been cheaper.
 */
@Serializable
data class UnlocatedPriceOptionDto(
    val retailerCode: String,
    val retailerName: String,
    val coveredItems: Int,
    val totalItems: Int,
    val lowestBasketCost: Double,
    val highestBasketCost: Double,
    val priceListCount: Int,
    val caveat: String
)

@Serializable
data class ShoppingRecommendationDto(
    val listId: Long,
    val listName: String,
    val requestedDate: String,
    val candidateStoreCount: Int,
    val evaluatedSingleStoreScenarios: Int,
    val evaluatedTwoStoreCombinations: Int,
    val assumptions: OptimizationAssumptionsDto,
    val singleStore: OptimizationScenarioDto,
    val recommendedBalance: OptimizationScenarioDto,
    val lowestPrice: OptimizationScenarioDto,
    val disclaimer: String,
    val unlocatedPriceOptions: List<UnlocatedPriceOptionDto> = emptyList()
)
