package rs.pametnakupovina.app.data.purchase

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.local.PurchaseSessionEntity
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.requireUser
import rs.pametnakupovina.app.text.uiText

@Singleton
class PurchaseRepository @Inject constructor(
    private val database: PametnaKupovinaDatabase,
    private val json: Json
) {
    private val dao get() = database.purchaseSessionDao()
    val sessions get() = dao.observeAll()
    fun observe(id: String) = dao.observe(id).map { it?.let(::decode) }
    fun observeActive(listId: Long) = dao.observeActive().map { rows ->
        rows.asSequence().map(::decode).firstOrNull { it.snapshot.listId == listId }
    }

    suspend fun start(result: ShoppingRecommendationDto, scenario: OptimizationScenarioDto,
        createNew: Boolean = false): String = database.withTransaction {
        requireUser(scenario.available && scenario.items.isNotEmpty()) { uiText(R.string.purchase_error_plan_unavailable) }
        requireUser(scenario in listOf(result.singleStore, result.recommendedBalance, result.lowestPrice)) {
            uiText(R.string.purchase_error_plan_mismatch)
        }
        require(scenario.items.map { it.itemId }.distinct().size == scenario.items.size)
        // Repeated taps/reopening recommendations must not reset a shopping session.
        // Starting another session is an explicit UI choice; its old snapshot stays intact.
        if (!createNew) {
            dao.getActive().asSequence().map(::decode)
                .firstOrNull { it.snapshot.listId == result.listId }
                ?.let { return@withTransaction it.id }
        }
        val id = UUID.randomUUID().toString()
        // Store public shop coordinates, never the user's origin.
        val snapshot = PurchaseSnapshot(listId = result.listId, listName = result.listName,
            calculationDate = result.requestedDate, scenario = scenario)
        dao.insert(PurchaseSessionEntity(id, System.currentTimeMillis(),
            listName = result.listName, itemCount = scenario.items.size,
            snapshotJson = json.encodeToString(snapshot)))
        id
    }

    suspend fun update(id: String, itemId: Long, update: (PurchaseItemProgress) -> PurchaseItemProgress) {
        database.withTransaction {
            val session = dao.get(id)?.let(::decode) ?: throw UserFacingException(uiText(R.string.purchase_error_not_found))
            requireUser(session.archivedAt == null) { uiText(R.string.purchase_error_archived) }
            val item = session.snapshot.scenario.items.single { it.itemId == itemId }
            val current = session.progress[itemId] ?: PurchaseItemProgress()
            val next = validatePurchaseProgress(update(current), item.purchaseQuantity?.packages ?: item.requestedQuantity)
            val updated = session.progress + (itemId to next)
            dao.updateProgress(id, json.encodeToString(updated),
                updated.values.count { it.status == PurchaseStatus.PURCHASED })
        }
    }

    suspend fun archive(id: String, archived: Boolean) {
        database.withTransaction {
            requireUser(dao.get(id) != null) { uiText(R.string.purchase_error_not_found) }
            dao.archive(id, if (archived) System.currentTimeMillis() else null)
        }
    }

    private fun decode(entity: PurchaseSessionEntity): PurchaseSession {
        val snapshot = json.decodeFromString<PurchaseSnapshot>(entity.snapshotJson)
        requireUser(snapshot.version == 1) { uiText(R.string.purchase_error_newer_app) }
        return PurchaseSession(entity.id, entity.createdAt, entity.archivedAt, snapshot,
            json.decodeFromString(entity.progressJson))
    }
}
