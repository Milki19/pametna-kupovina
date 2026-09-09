package rs.pametnakupovina.app.data.purchase

import kotlinx.serialization.Serializable
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto

@Serializable
data class PurchaseSnapshot(
    val version: Int = 1,
    val listId: Long,
    val listName: String,
    val calculationDate: String,
    val scenario: OptimizationScenarioDto
)

@Serializable
enum class PurchaseStatus { TO_BUY, PURCHASED, NOT_FOUND, SKIPPED }

@Serializable
data class PurchaseItemProgress(
    val status: PurchaseStatus = PurchaseStatus.TO_BUY,
    val boughtPackages: Double = 0.0,
    val note: String = "",
    // Decimal text prevents binary floating-point errors in user-entered money.
    val actualLineTotal: String? = null
)

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
    require(plannedPackages.isFinite() && plannedPackages > 0) { "Neispravna planirana količina." }
    require(progress.boughtPackages.isFinite() && progress.boughtPackages in 0.0..plannedPackages) {
        "Kupljena količina mora biti između 0 i planirane količine."
    }
    require(progress.note.length <= 1000) { "Napomena može imati najviše 1000 znakova." }
    val price = progress.actualLineTotal?.trim()?.takeIf { it.isNotEmpty() }?.replace(',', '.')
    val decimal = price?.toBigDecimalOrNull()
    require(price == null || (decimal != null && decimal.signum() >= 0 &&
        decimal.scale() <= 2 && decimal <= "99999999.99".toBigDecimal())) {
        "Unesi nenegativan ukupan iznos, sa najviše dve decimale."
    }
    val bought = if (progress.status == PurchaseStatus.PURCHASED) plannedPackages else progress.boughtPackages
    return progress.copy(
        boughtPackages = bought,
        status = if (bought == plannedPackages) PurchaseStatus.PURCHASED else progress.status,
        note = progress.note.trim(),
        actualLineTotal = decimal?.toPlainString()
    )
}
