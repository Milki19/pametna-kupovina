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

    // A new list on the server: everything is sent again, except a pasted
    // line still waiting to be read and a row already deleted here.
    @Query(
        "UPDATE draft_items SET remoteId = NULL, matchingStatus = 'PENDING', " +
            "syncState = CASE WHEN syncState IN ('PENDING_PASTE', 'PENDING_DELETE') " +
            "THEN syncState ELSE 'PENDING_CREATE' END"
    )
    suspend fun resetRemoteState()

    @Transaction
    suspend fun replaceWithRemote(items: List<DraftItemEntity>) {
        deleteAll()
        insertAll(items)
    }

    @Query("DELETE FROM draft_items WHERE syncState = 'SYNCED'")
    suspend fun deleteSynced()

    @Query("SELECT remoteId FROM draft_items WHERE remoteId IS NOT NULL")
    suspend fun remoteIdsKeptLocally(): List<Long>

    /**
     * What the server holds, next to the rows it has not accepted yet. Rows
     * keep their place: a new local id would move every accepted row below
     * the refused one.
     */
    @Transaction
    suspend fun replaceSyncedWithRemote(items: List<DraftItemEntity>) {
        val localIdByRemoteId = getAllItems()
            .filter { it.remoteId != null }
            .associate { it.remoteId to it.localId }
        deleteSynced()
        val kept = remoteIdsKeptLocally().toSet()
        insertAll(
            items.filter { it.remoteId !in kept }.map { remote ->
                localIdByRemoteId[remote.remoteId]?.let { remote.copy(localId = it) } ?: remote
            }
        )
    }
}
