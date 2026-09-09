package rs.pametnakupovina.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "purchase_sessions")
data class PurchaseSessionEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val listName: String,
    val itemCount: Int,
    val purchasedCount: Int = 0,
    val archivedAt: Long? = null,
    val snapshotJson: String,
    val progressJson: String = "{}"
)

data class PurchaseSessionSummary(
    val id: String, val createdAt: Long, val archivedAt: Long?,
    val listName: String, val itemCount: Int, val purchasedCount: Int
)

@Dao
interface PurchaseSessionDao {
    @Insert suspend fun insert(session: PurchaseSessionEntity)
    @Query("SELECT id,createdAt,archivedAt,listName,itemCount,purchasedCount FROM purchase_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PurchaseSessionSummary>>
    @Query("SELECT * FROM purchase_sessions WHERE id=:id")
    fun observe(id: String): Flow<PurchaseSessionEntity?>
    @Query("SELECT * FROM purchase_sessions WHERE id=:id")
    suspend fun get(id: String): PurchaseSessionEntity?
    // The snapshot is immutable. Only progress and archive status may change.
    @Query("UPDATE purchase_sessions SET progressJson=:progress,purchasedCount=:purchasedCount WHERE id=:id")
    suspend fun updateProgress(id: String, progress: String, purchasedCount: Int)
    @Query("UPDATE purchase_sessions SET archivedAt=:time WHERE id=:id")
    suspend fun archive(id: String, time: Long?)
}
