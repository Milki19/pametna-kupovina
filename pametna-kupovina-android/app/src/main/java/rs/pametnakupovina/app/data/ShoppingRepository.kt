package rs.pametnakupovina.app.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import rs.pametnakupovina.app.BuildConfig
import rs.pametnakupovina.app.data.local.DraftItemDao
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.local.SyncState
import rs.pametnakupovina.app.data.market.MarketStore
import rs.pametnakupovina.app.data.network.AccountDeviceDto
import rs.pametnakupovina.app.data.network.AccountStateDto
import rs.pametnakupovina.app.data.network.AddLoyaltyCardRequestDto
import rs.pametnakupovina.app.data.network.HabitDto
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.data.network.JoinRequestDto
import rs.pametnakupovina.app.data.network.LoyaltyCardDto
import rs.pametnakupovina.app.data.network.ReceiptDto
import rs.pametnakupovina.app.data.network.ScanReceiptRequestDto
import rs.pametnakupovina.app.data.network.SpendingDto
import rs.pametnakupovina.app.data.network.GoogleSignInRequestDto
import rs.pametnakupovina.app.data.network.ProductCandidateDto
import rs.pametnakupovina.app.data.network.AddShoppingListItemRequestDto
import rs.pametnakupovina.app.data.network.CanonicalProductSearchPageDto
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CreateShoppingListRequestDto
import rs.pametnakupovina.app.data.network.FlexibleItemConstraintsDto
import rs.pametnakupovina.app.data.network.PasteShoppingListItemsRequestDto
import rs.pametnakupovina.app.data.network.serverMessage
import rs.pametnakupovina.app.data.network.ResolveShoppingItemMatchRequestDto
import rs.pametnakupovina.app.data.network.ShoppingApiService
import rs.pametnakupovina.app.data.network.ShoppingItemMatchActionDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListItemDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.UpdateShoppingListItemRequestDto
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.requireUser
import rs.pametnakupovina.app.text.requireUserNotNull
import rs.pametnakupovina.app.text.uiText

data class DraftItemInput(
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val quantity: Double,
    val matchingRule: ShoppingItemRuleDto,
    val category: String? = null,
    val requiredBrand: String? = null,
    val minPackageQuantity: Double? = null,
    val maxPackageQuantity: Double? = null,
    val requiredBaseUnit: String? = null,
    val targetQuantity: Double? = null
) {
    fun validated(): DraftItemInput {
        requireUser(name.isNotBlank()) { uiText(R.string.list_error_name_required) }
        requireUser(quantity.isFinite() && quantity > 0) {
            uiText(R.string.list_error_quantity_positive)
        }
        requireUser(targetQuantity == null || (targetQuantity.isFinite() && targetQuantity > 0
            && matchingRule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY
            && requiredBaseUnit in setOf("g", "ml", "piece", "kom", "komad"))) {
            uiText(R.string.list_error_target_quantity)
        }
        when (matchingRule) {
            ShoppingItemRuleDto.EXACT_PRODUCT -> {
                requireUser(productFamilyId == null) {
                    uiText(R.string.list_error_exact_no_family)
                }
            }

            ShoppingItemRuleDto.PRODUCT_FAMILY -> {
                requireUser(productFamilyId != null && productFamilyId > 0) {
                    uiText(R.string.list_error_family_required)
                }
                requireUser(canonicalProductId == null && barcode == null) {
                    uiText(R.string.list_error_family_no_barcode)
                }
            }

            ShoppingItemRuleDto.FLEXIBLE_CATEGORY -> {
                requireUser(canonicalProductId == null && productFamilyId == null) {
                    uiText(R.string.list_error_flexible_no_product)
                }
                requireUser(!category.isNullOrBlank()) {
                    uiText(R.string.list_error_flexible_category_required)
                }
                if (
                    minPackageQuantity != null &&
                    maxPackageQuantity != null
                ) {
                    requireUser(minPackageQuantity <= maxPackageQuantity) {
                        uiText(R.string.list_error_package_min_over_max)
                    }
                }
                requireUser(minPackageQuantity == null || minPackageQuantity > 0) {
                    uiText(R.string.list_error_package_min_positive)
                }
                requireUser(maxPackageQuantity == null || maxPackageQuantity > 0) {
                    uiText(R.string.list_error_package_max_positive)
                }
                // "Pakovanje od 6" alone was read as 6 ml or 6 g.
                requireUser(
                    (minPackageQuantity == null && maxPackageQuantity == null) ||
                        !requiredBaseUnit.isNullOrBlank()
                ) {
                    uiText(R.string.list_error_package_unit_required)
                }
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
                    requireUser(unit in setOf("g", "ml", "piece")) {
                        uiText(R.string.list_error_unit_invalid)
                    }
                }
        )
    }
}

@Singleton
class ShoppingRepository @Inject constructor(
    private val api: ShoppingApiService,
    private val dao: DraftItemDao,
    private val clientIdentityStore: ClientIdentityStore,
    private val marketStore: MarketStore
) {
    private val syncMutex = Mutex()

    val draftItems: Flow<List<DraftItemEntity>> = dao.observeVisibleItems()

    suspend fun loyaltyCards(): List<LoyaltyCardDto> = api.getLoyaltyCards()

    suspend fun addLoyaltyCard(
        name: String,
        cardNumber: String,
        barcodeFormat: String
    ): LoyaltyCardDto = api.addLoyaltyCard(
        AddLoyaltyCardRequestDto(name, cardNumber, barcodeFormat)
    )

    suspend fun deleteLoyaltyCard(cardId: Long) = api.deleteLoyaltyCard(cardId)

    suspend fun habits(limit: Int = 20): List<HabitDto> = api.getHabits(limit)

    suspend fun scanReceipt(verificationUrl: String): ReceiptDto =
        api.scanReceipt(ScanReceiptRequestDto(verificationUrl))

    suspend fun scanReceiptFile(file: ReceiptFile): ReceiptDto =
        api.scanReceiptFile(
            MultipartBody.Part.createFormData(
                "file",
                if (file.mimeType == "application/pdf") "racun.pdf" else "racun.jpg",
                file.bytes.toRequestBody(file.mimeType.toMediaType())
            )
        )

    suspend fun receipts(limit: Int = 50): List<ReceiptDto> =
        api.getReceipts(limit)

    suspend fun spending(month: String): SpendingDto = api.getSpending(month)

    suspend fun accountState(): AccountStateDto = api.getAccount().rememberingMarket()

    /** Every answer about the account says which market it shops in. */
    private suspend fun AccountStateDto.rememberingMarket(): AccountStateDto {
        market?.let { marketStore.remember(it) }
        return this
    }

    suspend fun deleteAccount() = api.deleteAccount()

    /** Telefoni na ovom nalogu; ovaj je označen sa `current`. */
    suspend fun accountDevices(): List<AccountDeviceDto> = api.getAccountDevices()

    /** Drugi telefon gubi pristup odmah; sledeći put počinje kao nov. */
    suspend fun removeAccountDevice(deviceId: Long) = api.removeAccountDevice(deviceId)

    /** Server gasi sesiju ovog telefona i odvaja ga od naloga. */
    suspend fun signOut() = api.signOut()

    /** Sadržaj QR koda za drugi telefon: jednokratni kod i spisak koji se deli. */
    suspend fun householdInvite(): String {
        val listId = synchronizePending(allowRejected = true)
        return householdCode(api.createInvite().code, listId)
    }

    /**
     * Ovaj telefon ulazi u nalog koji je pokazao kod i prelazi na njegov
     * spisak. Ono što je već bilo na ovom spisku dodaje se zajedničkom, pa
     * ništa što je kupac upisao ne nestaje sa ekrana.
     */
    suspend fun joinHousehold(scanned: String) {
        val (code, listId) = parseHouseholdCode(scanned) ?: throw NotAHouseholdCode()
        api.joinHousehold(JoinRequestDto(code)).rememberingMarket()
        syncMutex.withLock {
            clientIdentityStore.setActiveListId(listId)
            dao.resetRemoteState()
        }
        synchronizeAndRefresh()
    }

    suspend fun signInWithGoogle(idToken: String): AccountStateDto =
        api.signInWithGoogle(GoogleSignInRequestDto(idToken)).rememberingMarket()

    suspend fun searchProducts(
        query: String,
        page: Int = 0,
        limit: Int = 10,
        includeWithoutPrice: Boolean = false,
        near: Coordinates? = null,
        onSale: Boolean = false
    ): CanonicalProductSearchPageDto = api.searchProducts(
        query = query.trim(),
        page = page,
        limit = limit,
        includeWithoutPrice = includeWithoutPrice,
        latitude = near?.latitude,
        longitude = near?.longitude,
        // Bez parametra kad nije uključen, kao i ranije.
        onSale = onSale.takeIf { it }
    )

    suspend fun sales(
        category: String?,
        sort: rs.pametnakupovina.app.data.network.SaleSortDto,
        page: Int,
        limit: Int,
        near: Coordinates?
    ): rs.pametnakupovina.app.data.network.SalePageDto = api.getSales(
        category = category,
        sort = sort,
        page = page,
        limit = limit,
        latitude = near?.latitude,
        longitude = near?.longitude
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

    suspend fun reportProduct(
        canonicalProductId: Long,
        reason: rs.pametnakupovina.app.data.network.ProductReportReasonDto,
        note: String?
    ) {
        api.reportProduct(
            canonicalProductId,
            rs.pametnakupovina.app.data.network.ProductReportRequestDto(
                reason = reason,
                note = note?.trim()?.takeIf(String::isNotEmpty)
            )
        )
    }

    suspend fun addItem(input: DraftItemInput) {
        val value = input.validated()
        dao.insert(value.toEntity())
    }

    suspend fun alternativeQuery(itemId: Long): String {
        val item = requireUserNotNull(dao.findByRemoteId(itemId)) { uiText(R.string.list_error_item_not_in_list) }
        return item.rawInput?.takeIf { it.isNotBlank() } ?: item.name
    }

    /** Stavka koje nema u planu postaje „slično, bilo koji brend" (vidi [similarItem]). */
    suspend fun replaceWithSimilar(itemId: Long, kind: String) {
        val item = requireUserNotNull(dao.findByRemoteId(itemId)) { uiText(R.string.list_error_item_not_in_list) }
        requireUser(item.syncState != SyncState.PENDING_DELETE.name) { uiText(R.string.list_error_item_deleted) }
        updateItem(item, similarItem(item.name, item.quantity, item.rawInput, kind))
    }

    suspend fun replaceWithAlternative(itemId: Long, product: rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto, packages: Double) {
        val item = requireUserNotNull(dao.findByRemoteId(itemId)) { uiText(R.string.list_error_item_not_in_list) }
        requireUser(item.syncState != SyncState.PENDING_DELETE.name) { uiText(R.string.list_error_item_deleted) }
        requireUser(product.hasUsablePrice) { uiText(R.string.list_error_product_no_price) }
        updateItem(item, DraftItemInput(name=product.name,rawInput=item.rawInput,
            productFamilyId=requireNotNull(product.productFamilyId),quantity=packages,
            matchingRule=ShoppingItemRuleDto.PRODUCT_FAMILY))
    }

    /**
     * A pasted line waits on this device as written, and the server reads it
     * when it is sent, so "Pivo Zaječarsko 0.5" means the same here as
     * anywhere. Until then the row shows the phone's rough reading.
     */
    suspend fun pasteItems(text: String): Int {
        val parsed = PastedListParser.parse(text)
        requireUser(parsed.isNotEmpty()) { uiText(R.string.list_error_paste_empty) }

        parsed.forEach { line ->
            dao.insert(line.toFlexibleDraftInput().toEntity(syncState = SyncState.PENDING_PASTE))
        }
        return parsed.size
    }

    suspend fun convertToFlexible(
        itemId: Long,
        category: String
    ): ShoppingListMatchingDto {
        var local = dao.findByRemoteId(itemId)
        if (local == null) {
            synchronizeAndRefresh()
            local = dao.findByRemoteId(itemId)
        }
        val item = requireUserNotNull(local) {
            uiText(R.string.list_error_item_not_local)
        }
        val flexibleCategory = category.trim().ifBlank { item.name.trim() }

        updateItem(
            item = item,
            input = DraftItemInput(
                name = item.name,
                rawInput = item.rawInput,
                quantity = item.quantity,
                matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                category = flexibleCategory
            )
        )
        return matchItems()
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
        } catch (error: ItemSyncValidationException) {
            // Show what the server made of the rest; keep the refused rows.
            val remote = api.getShoppingList(error.listId)
            dao.replaceSyncedWithRemote(remote.items.map { it.toEntity() })
            throw error
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
            recreateListAndPush()
        }
    }

    /**
     * With [allowRejected] the rows the server refused stay on this device,
     * marked with the reason, and the rest of the list is used: the shopper
     * has already chosen to go on without them.
     */
    suspend fun synchronizePending(allowRejected: Boolean = false): Long = syncMutex.withLock {
        val listId = ensureActiveList()
        try {
            pushPending(listId)
            listId
        } catch (error: ItemSyncValidationException) {
            if (!allowRejected) throw error
            error.listId
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
            recreateListAndPush()
        }
    }

    suspend fun matchItems(): ShoppingListMatchingDto {
        val listId = synchronizePending(allowRejected = true)
        return api.matchShoppingList(listId)
    }

    suspend fun resolveMatch(
        listId: Long,
        itemId: Long,
        candidate: ProductCandidateDto?
    ): ShoppingListItemDto {
        val action = if (candidate == null) {
            ShoppingItemMatchActionDto.REJECT
        } else {
            ShoppingItemMatchActionDto.CONFIRM
        }
        val response = api.resolveShoppingItemMatch(
            listId = listId,
            itemId = itemId,
            request = ResolveShoppingItemMatchRequestDto(
                action = action,
                canonicalProductId = candidate?.canonicalProductId,
                // Sold without a barcode: confirmed as the merged product.
                productFamilyId = candidate
                    ?.takeIf { it.canonicalProductId == null }
                    ?.productFamilyId,
                note = if (candidate == null) {
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
    ): ShoppingRecommendationDto {
        // Pre računanja guramo sve lokalne izmene. Ako je server u međuvremenu
        // obrisao spisak, synchronizePending kreira novi i vraća njegov ID.
        val synchronizedListId = synchronizePending(allowRejected = true)
        val recommendationListId = if (synchronizedListId == listId) {
            listId
        } else {
            synchronizedListId
        }
        return api.getRecommendations(
            listId = recommendationListId,
            latitude = latitude,
            longitude = longitude,
            date = date
        )
    }

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

    private suspend fun pushPending(listId: Long) = pushPendingItems(api, dao, listId)
}

data class RejectedItem(val name: String, val reason: String?)

class NotAHouseholdCode : Exception()

private const val HOUSEHOLD_PREFIX = "pametnakupovina:domacinstvo:"

// Link do web verzije: iPhone ga otvori kamerom, a aplikacija ga čita kao kod.
private val HOUSEHOLD_LINK =
    Regex("^https?://[^/\\s]+/app/(?:index\\.html)?#/domacinstvo/([A-Za-z0-9_-]+)/(\\d+)$")

internal fun householdCode(
    code: String,
    listId: Long,
    baseUrl: String = BuildConfig.BACKEND_BASE_URL
) = "${baseUrl.trimEnd('/')}/app/#/domacinstvo/$code/$listId"

/** Kod i spisak iz skeniranog QR-a (link ili kod iz aplikacije 1.x), ili null za bilo koji drugi kod. */
internal fun parseHouseholdCode(scanned: String): Pair<String, Long>? {
    val text = scanned.trim()
    HOUSEHOLD_LINK.matchEntire(text)?.let { link ->
        return link.groupValues[1] to (link.groupValues[2].toLongOrNull() ?: return null)
    }
    val rest = text.takeIf { it.startsWith(HOUSEHOLD_PREFIX) }
        ?.removePrefix(HOUSEHOLD_PREFIX) ?: return null
    val listId = rest.substringAfter(':', "").toLongOrNull() ?: return null
    return rest.substringBefore(':').takeIf(String::isNotBlank)?.let { it to listId }
}

class ItemSyncValidationException(
    val rejected: List<RejectedItem>,
    val listId: Long
) : UserFacingException(
    uiText(
        R.string.list_error_server_rejected,
        rejected.joinToString("; ") { item ->
            item.reason?.let { "${item.name} (${it.trimEnd('.')})" } ?: item.name
        }
    )
) {
    val itemNames: List<String> get() = rejected.map { it.name }
}

// A refused row stays on this device with the server's reason, and does not
// stop the rows after it from being sent.
internal suspend fun pushPendingItems(api: ShoppingApiService, dao: DraftItemDao, listId: Long) {
    val rejected = mutableListOf<RejectedItem>()
    dao.getAllItems().forEach { item ->
        try {
            when (SyncState.valueOf(item.syncState)) {
                SyncState.PENDING_PASTE -> {
                    val response = api.pasteShoppingListItems(
                        listId,
                        PasteShoppingListItemsRequestDto(
                            text = item.rawInput?.takeIf(String::isNotBlank) ?: item.name
                        )
                    )
                    if (response.items.isEmpty()) {
                        dao.deleteByLocalId(item.localId)
                    }
                    response.items.forEachIndexed { index, saved ->
                        dao.insert(saved.toEntity(localId = if (index == 0) item.localId else 0))
                    }
                }

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
        } catch (error: HttpException) {
            if (error.code() !in setOf(400, 422)) throw error
            val reason = error.serverMessage()
            // Blank when the server gave no reason; the row then shows a
            // general one in the reader's language.
            dao.update(item.copy(syncError = reason ?: ""))
            rejected += RejectedItem(item.name, reason)
        }
    }
    // Calculation waits for the shopper to choose to go on without these rows.
    if (rejected.isNotEmpty()) throw ItemSyncValidationException(rejected, listId)
}

internal fun ParsedDraftLine.toFlexibleDraftInput(): DraftItemInput {
    val requested = parseShoppingAmount(name)
    val categoryName = requested?.name ?: name
    val amount = requested?.amount
    return DraftItemInput(
        name = categoryName, rawInput = rawInput, quantity = quantity,
        matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY, category = categoryName,
        targetQuantity = amount?.value, requiredBaseUnit = amount?.unit
    )
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
    productFamilyId = productFamilyId,
    quantity = quantity,
    matchingRule = matchingRule.name,
    matchingStatus = matchingStatus,
    category = category,
    requiredBrand = requiredBrand,
    minPackageQuantity = minPackageQuantity,
    maxPackageQuantity = maxPackageQuantity,
    requiredBaseUnit = requiredBaseUnit,
    targetQuantity = targetQuantity,
    syncState = syncState.name
)

private fun DraftItemEntity.constraints(): FlexibleItemConstraintsDto? {
    if (matchingRule != ShoppingItemRuleDto.FLEXIBLE_CATEGORY.name) return null
    return FlexibleItemConstraintsDto(
        category = requireNotNull(category),
        requiredBrand = requiredBrand,
        minPackageQuantity = minPackageQuantity,
        maxPackageQuantity = maxPackageQuantity,
        requiredBaseUnit = requiredBaseUnit,
        targetQuantity = targetQuantity
    )
}

private fun DraftItemEntity.toAddRequest() = AddShoppingListItemRequestDto(
    name = name,
    rawInput = rawInput,
    barcode = barcode,
    canonicalProductId = canonicalProductId,
    productFamilyId = productFamilyId,
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
        productFamilyId = productFamilyId,
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
    productFamilyId = matchedProductFamilyId,
    quantity = quantity,
    matchingRule = matchingRule.name,
    matchingStatus = matchingStatus.name,
    category = flexibleConstraints?.category,
    requiredBrand = flexibleConstraints?.requiredBrand,
    minPackageQuantity = flexibleConstraints?.minPackageQuantity,
    maxPackageQuantity = flexibleConstraints?.maxPackageQuantity,
    requiredBaseUnit = flexibleConstraints?.requiredBaseUnit,
    targetQuantity = flexibleConstraints?.targetQuantity,
    syncState = SyncState.SYNCED.name
)
