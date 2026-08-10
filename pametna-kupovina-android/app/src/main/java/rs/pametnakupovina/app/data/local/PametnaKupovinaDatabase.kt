package rs.pametnakupovina.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DraftItemEntity::class],
    version = 1,
    exportSchema = true
)
abstract class PametnaKupovinaDatabase : RoomDatabase() {
    abstract fun draftItemDao(): DraftItemDao
}
