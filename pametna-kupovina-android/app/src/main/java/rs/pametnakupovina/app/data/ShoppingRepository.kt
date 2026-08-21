package rs.pametnakupovina.app.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import rs.pametnakupovina.app.data.local.DraftItemDao
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.local.SyncState
import rs.pametnakupovina.app.data.network.AddShoppingListItemRequestDto
import rs.pametnakupovina.app.data.network.CanonicalProductSearchPageDto
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CreateShoppingListRequestDto
import rs.pametnakupovina.app.data.network.FlexibleItemConstraintsDto
import rs.pametnakupovina.app.data.network.ResolveShoppingItemMatchRequestDto
import rs.pametnakupovina.app.data.network.ShoppingApiService
import rs.pametnakupovina.app.data.network.ShoppingItemMatchActionDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListItemDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.UpdateShoppingListItemRequestDto
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore

data class DraftItemInput(
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val canonicalProductId: Long? = null,
    val quantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val category: String? = null,
    val requiredBrand: String? = null,
    val minPackageQuantity: Double? = null,
    val maxPackageQuantity: Double? = null,
    val requiredBaseUnit: String? = null
) {
    fun validated(): DraftItemInput {
        require(name.isNotBlank()) { "Naziv stavke je obavezan." }
        require(quantity.isFinite() && quantity > 0) {
            "Količina mora biti veća od nule."
        }
        if (matchingRule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY) {
            require(canonicalProductId == null) {
                "Fleksibilna stavka ne može imati canonical proizvod."
            }
            require(!category.isNullOrBlank()) {
                "Za fleksibilnu stavku izaberi kategoriju."
            }
            if (minPackageQuantity != null && maxPackageQuantity != null) {
                require(minPackageQuantity <= maxPackageQuantity) {
                    "Minimalno pakovanje ne može biti veće od maksimalnog."
                }
            }
            require(minPackageQuantity == null || minPackageQuantity > 0) {
                "Minimalno pakovanje mora biti veće od nule."
            }
            require(maxPackageQuantity == null || maxPackageQuantity > 0) {
                "Maksimalno pakovanje mora biti veće od nule."
            }
        }
        return copy(
            name = name.trim(),
            rawInput = rawInput?.trim()?.takeIf(String::isNotBlank),
            barcode = barcode?.trim()?.takeIf(String::isNotBlank),
            category = category?.trim()?.takeIf(String::isNotBlank),
            requiredBrand = requiredBrand?.trim()?.takeIf(String::isNotBlank),
            requiredBaseUnit = requiredBaseUnit
                ?.trim()
                ?.lowercase()
                ?.let { unit ->
                    when (unit) {
                        "kom", "komad" -> "piece"
                        else -> unit
                    }
                }
                ?.takeIf(String::isNotBlank)
                ?.also { unit ->
                    require(unit in setOf("g", "ml", "piece")) {
                        "Jedinica mora biti g, ml ili piece."
                    }
                }
        )
    }
}

@Singleton
class ShoppingRepository @Inject constructor(
    private val api: ShoppingApiService,
    private val dao: DraftItemDao,
    private val clientIdentityStore: ClientIdentityStore
) {
    private val syncMutex = Mutex()

    val draftItems: Flow<List<DraftItemEntity>> = dao.observeVisibleItems()

    suspend fun searchProducts(
        query: String,
        page: Int = 0,
        limit: Int = 10
    ): CanonicalProductSearchPageDto = api.searchProducts(
        query = query.trim(),
        page = page,
        limit = limit
    )

    suspend fun getProductDetails(
        canonicalProductId: Long,
        date: String? = null,
        historyLimit: Int = 30
    ): CanonicalProductDetailsDto = api.getProductDetails(
        canonicalProductId = canonicalProductId,
        date = date,
        historyLimit = historyLimit
    )

    suspend fun addItem(input: DraftItemInput) {
        val value = input.validated()
        dao.insert(value.toEntity())
    }

    suspend fun pasteItems(text: String): Int {
        val parsed = PastedListParser.parse(text)
        require(parsed.isNotEmpty()) { "Unesi bar jednu nepraznu stavku." }

        parsed.forEach { line ->
            dao.insert(
                DraftItemInput(
                    name = line.name,
                    rawInput = line.rawInput,
                    quantity = line.quantity,
                    matchingRule = ShoppingItemRuleDto.EXACT_PRODUCT
                ).toEntity()
            )
        }
        return parsed.size
    }

    suspend fun updateItem(
        item: DraftItemEntity,
        input: DraftItemInput
    ) {
        val value = input.validated()
        dao.update(
            value.toEntity(
                localId = item.localId,
                remoteId = item.remoteId,
                matchingStatus = if (item.remoteId == null) {
                    item.matchingStatus
                } else {
                    "PENDING"
                },
                syncState = if (item.remoteId == null) {
                    SyncState.PENDING_CREATE
                } else {
                    SyncState.PENDING_UPDATE
                }
            )
        )
    }

    suspend fun deleteItem(item: DraftItemEntity) {
        if (item.remoteId == null) {
            dao.deleteByLocalId(item.localId)
        } else {
            dao.update(
                item.copy(
                    syncState = SyncState.PENDING_DELETE.name,
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun synchronizeAndRefresh(): Long = syncMutex.withLock {
        val listId = ensureActiveList()
        try {
            pushPending(listId)
            val remote = api.getShoppingList(listId)
            dao.replaceWithRemote(remote.items.map { it.toEntity() })
            listId
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
            recreateListAndPush()
        }
    }

    suspend fun synchronizePending(): Long = syncMutex.withLock {
        val listId = ensureActiveList()
        try {
            pushPending(listId)
            listId
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
            recreateListAndPush()
        }
    }

    suspend fun matchItems(): ShoppingListMatchingDto {
        val listId = synchronizePending()
        return api.matchShoppingList(listId)
    }

    suspend fun resolveMatch(
        listId: Long,
        itemId: Long,
        candidateId: Long?
    ): ShoppingListItemDto {
        val action = if (candidateId == null) {
            ShoppingItemMatchActionDto.REJECT
        } else {
            ShoppingItemMatchActionDto.CONFIRM
        }
        val response = api.resolveShoppingItemMatch(
            listId = listId,
            itemId = itemId,
            request = ResolveShoppingItemMatchRequestDto(
                action = action,
                canonicalProductId = candidateId,
                note = if (candidateId == null) {
                    "Korisnik je izabrao opciju neupareno u Android aplikaciji."
                } else {
                    "Korisnik je potvrdio kandidata u Android aplikaciji."
                }
            )
        )
        val local = dao.findByRemoteId(itemId)
        dao.insert(response.toEntity(localId = local?.localId ?: 0))
        return response
    }

    suspend fun getRecommendations(
        listId: Long,
        latitude: Double,
        longitude: Double,
        date: String? = null
    ): ShoppingRecommendationDto = api.getRecommendations(
        listId = listId,
        latitude = latitude,
        longitude = longitude,
        date = date
    )

    private suspend fun ensureActiveList(): Long {
        val existing = clientIdentityStore.getActiveListId()
        if (existing != null) return existing

        val created = api.createShoppingList(
            CreateShoppingListRequestDto(name = "Moja kupovina")
        )
        clientIdentityStore.setActiveListId(created.id)
        return created.id
    }

    private suspend fun recreateListAndPush(): Long {
        clientIdentityStore.clearActiveListId()
        dao.resetRemoteState()
        val listId = ensureActiveList()
        pushPending(listId)
        return listId
    }

    private suspend fun pushPending(listId: Long) {
        dao.getAllItems().forEach { item ->
            when (SyncState.valueOf(item.syncState)) {
                SyncState.PENDING_CREATE -> {
                    val saved = api.addShoppingListItem(
                        listId,
                        item.toAddRequest()
                    )
                    dao.insert(saved.toEntity(localId = item.localId))
                }

                SyncState.PENDING_UPDATE -> {
                    val remoteId = item.remoteId
                        ?: error("Nedostaje remote ID za izmenu.")
                    val saved = api.updateShoppingListItem(
                        listId,
                        remoteId,
                        item.toUpdateRequest()
                    )
                    dao.insert(saved.toEntity(localId = item.localId))
                }

                SyncState.PENDING_DELETE -> {
                    val remoteId = item.remoteId
                    if (remoteId == null) {
                        dao.deleteByLocalId(item.localId)
                    } else {
                        val response = api.deleteShoppingListItem(
                            listId,
                            remoteId
                        )
                        if (response.isSuccessful || response.code() == 404) {
                            dao.deleteByLocalId(item.localId)
                        } else {
                            throw IllegalStateException(
                                "Brisanje nije uspelo (${response.code()})."
                            )
                        }
                    }
                }

                SyncState.SYNCED -> Unit
            }
        }
    }
}

private fun DraftItemInput.toEntity(
    localId: Long = 0,
    remoteId: Long? = null,
    matchingStatus: String = "PENDING",
    syncState: SyncState = SyncState.PENDING_CREATE
): DraftItemEntity = DraftItemEntity(
    localId = localId,
    remoteId = remoteId,
    name = name,
    rawInput = rawInput,
    barcode = barcode,
    canonicalProductId = canonicalProductId,
    quantity = quantity,
    matchingRule = matchingRule.name,
    matchingStatus = matchingStatus,
    category = category,
    requiredBrand = requiredBrand,
    minPackageQuantity = minPackageQuantity,
    maxPackageQuantity = maxPackageQuantity,
    requiredBaseUnit = requiredBaseUnit,
    syncState = syncState.name
)

private fun DraftItemEntity.constraints(): FlexibleItemConstraintsDto? {
    if (matchingRule != ShoppingItemRuleDto.FLEXIBLE_CATEGORY.name) return null
    return FlexibleItemConstraintsDto(
        category = requireNotNull(category),
        requiredBrand = requiredBrand,
        minPackageQuantity = minPackageQuantity,
        maxPackageQuantity = maxPackageQuantity,
        requiredBaseUnit = requiredBaseUnit
    )
}

private fun DraftItemEntity.toAddRequest() = AddShoppingListItemRequestDto(
    name = name,
    rawInput = rawInput,
    barcode = barcode,
    canonicalProductId = canonicalProductId,
    quantity = quantity,
    matchingRule = ShoppingItemRuleDto.valueOf(matchingRule),
    flexibleConstraints = constraints()
)

private fun DraftItemEntity.toUpdateRequest() =
    UpdateShoppingListItemRequestDto(
        name = name,
        rawInput = rawInput,
        barcode = barcode,
        canonicalProductId = canonicalProductId,
        quantity = quantity,
        matchingRule = ShoppingItemRuleDto.valueOf(matchingRule),
        flexibleConstraints = constraints()
    )

private fun ShoppingListItemDto.toEntity(
    localId: Long = 0
): DraftItemEntity = DraftItemEntity(
    localId = localId,
    remoteId = id,
    name = name,
    rawInput = rawInput,
    barcode = barcode,
    canonicalProductId = matchedCanonicalProductId,
    quantity = quantity,
    matchingRule = matchingRule.name,
    matchingStatus = matchingStatus.name,
    category = flexibleConstraints?.category,
    requiredBrand = flexibleConstraints?.requiredBrand,
    minPackageQuantity = flexibleConstraints?.minPackageQuantity,
    maxPackageQuantity = flexibleConstraints?.maxPackageQuantity,
    requiredBaseUnit = flexibleConstraints?.requiredBaseUnit,
    syncState = SyncState.SYNCED.name
)
