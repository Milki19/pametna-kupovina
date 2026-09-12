package rs.pametnakupovina.app.data.purchase

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.local.PurchaseSessionEntity
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto

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
        require(scenario.available && scenario.items.isNotEmpty()) { "Plan još nije dostupan." }
        require(scenario in listOf(result.singleStore, result.recommendedBalance, result.lowestPrice)) {
            "Plan ne pripada ovom računanju."
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
            val session = dao.get(id)?.let(::decode) ?: error("Kupovina nije pronađena.")
            require(session.archivedAt == null) { "Prvo ponovo otvori arhiviranu kupovinu." }
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
            require(dao.get(id) != null) { "Kupovina nije pronađena." }
            dao.archive(id, if (archived) System.currentTimeMillis() else null)
        }
    }

    private fun decode(entity: PurchaseSessionEntity): PurchaseSession {
        val snapshot = json.decodeFromString<PurchaseSnapshot>(entity.snapshotJson)
        require(snapshot.version == 1) { "Za ovaj plan je potrebna novija verzija aplikacije." }
        return PurchaseSession(entity.id, entity.createdAt, entity.archivedAt, snapshot,
            json.decodeFromString(entity.progressJson))
    }
}
