package rs.pametnakupovina.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "draft_items")
data class DraftItemEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val remoteId: Long? = null,
    val name: String,
    val rawInput: String? = null,
    val barcode: String? = null,
    val canonicalProductId: Long? = null,
    val productFamilyId: Long? = null,
    val quantity: Double,
    val matchingRule: String,
    val matchingStatus: String = "PENDING",
    val category: String? = null,
    val requiredBrand: String? = null,
    val minPackageQuantity: Double? = null,
    val maxPackageQuantity: Double? = null,
    val requiredBaseUnit: String? = null,
    val syncState: String = SyncState.PENDING_CREATE.name,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

enum class SyncState {
    PENDING_CREATE,
    PENDING_UPDATE,
    PENDING_DELETE,
    SYNCED
}
