package rs.pametnakupovina.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftItemDao {

    @Query(
        "SELECT * FROM draft_items " +
            "WHERE syncState != 'PENDING_DELETE' " +
            "ORDER BY localId"
    )
    fun observeVisibleItems(): Flow<List<DraftItemEntity>>

    @Query("SELECT * FROM draft_items ORDER BY localId")
    suspend fun getAllItems(): List<DraftItemEntity>

    @Query("SELECT * FROM draft_items WHERE remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: Long): DraftItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DraftItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DraftItemEntity>)

    @Update
    suspend fun update(item: DraftItemEntity)

    @Query("DELETE FROM draft_items WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: Long)

    @Query("DELETE FROM draft_items")
    suspend fun deleteAll()

    @Query(
        "UPDATE draft_items SET remoteId = NULL, " +
            "matchingStatus = 'PENDING', syncState = 'PENDING_CREATE'"
    )
    suspend fun resetRemoteState()

    @Transaction
    suspend fun replaceWithRemote(items: List<DraftItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}
