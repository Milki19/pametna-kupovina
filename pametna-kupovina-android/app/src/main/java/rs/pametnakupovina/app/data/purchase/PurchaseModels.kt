package rs.pametnakupovina.app.data.purchase

import kotlinx.serialization.Serializable
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto
import rs.pametnakupovina.app.text.requireUser
import rs.pametnakupovina.app.text.uiText

@Serializable
data class PurchaseSnapshot(
    val version: Int = 1,
    val listId: Long,
    val listName: String,
    val calculationDate: String,
    val scenario: OptimizationScenarioDto,
    /** Plan je računat za put peške; Google mape onda vode peške. */
    val walking: Boolean = false
)

@Serializable
enum class PurchaseStatus { TO_BUY, PURCHASED, NOT_FOUND, SKIPPED }

@Serializable
data class PurchaseItemProgress(
    val status: PurchaseStatus = PurchaseStatus.TO_BUY,
    val boughtPackages: Double = 0.0,
    val note: String = "",
    // Decimal text prevents binary floating-point errors in user-entered money.
    val actualLineTotal: String? = null,
    /** Kupac je javio serveru da ovoga nema u prodavnici. */
    val reportedMissing: Boolean = false
)

/**
 * Šalje serveru „nema u prodavnici"; posebna klasa da kupovina po planu
 * (i njen test) ne zavisi od celog ShoppingRepository-ja.
 */
class MissingProductReporter(
    private val send: suspend (canonicalProductId: Long, retailerProductId: Long, storeId: Long) -> Unit
) {
    suspend fun report(canonicalProductId: Long, retailerProductId: Long, storeId: Long) =
        send(canonicalProductId, retailerProductId, storeId)
}

data class PurchaseSession(
    val id: String,
    val createdAt: Long,
    val archivedAt: Long?,
    val snapshot: PurchaseSnapshot,
    val progress: Map<Long, PurchaseItemProgress>
) {
    val purchasedCount get() = snapshot.scenario.items.count {
        progress[it.itemId]?.status == PurchaseStatus.PURCHASED
    }
    val resolvedCount get() = snapshot.scenario.items.count {
        (progress[it.itemId]?.status ?: PurchaseStatus.TO_BUY) != PurchaseStatus.TO_BUY
    }
}

fun validatePurchaseProgress(progress: PurchaseItemProgress, plannedPackages: Double): PurchaseItemProgress {
    requireUser(plannedPackages.isFinite() && plannedPackages > 0) { uiText(R.string.purchase_error_invalid_planned) }
    requireUser(progress.boughtPackages.isFinite() && progress.boughtPackages in 0.0..plannedPackages) {
        uiText(R.string.purchase_error_bought_range)
    }
    requireUser(progress.note.length <= 1000) { uiText(R.string.purchase_error_note_length, 1000) }
    val price = progress.actualLineTotal?.trim()?.takeIf { it.isNotEmpty() }?.replace(',', '.')
    val decimal = price?.toBigDecimalOrNull()
    requireUser(price == null || (decimal != null && decimal.signum() >= 0 &&
        decimal.scale() <= 2 && decimal <= "99999999.99".toBigDecimal())) {
        uiText(R.string.purchase_error_price)
    }
    val bought = if (progress.status == PurchaseStatus.PURCHASED) plannedPackages else progress.boughtPackages
    return progress.copy(
        boughtPackages = bought,
        status = if (bought == plannedPackages) PurchaseStatus.PURCHASED else progress.status,
        note = progress.note.trim(),
        actualLineTotal = decimal?.toPlainString()
    )
}
