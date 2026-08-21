package rs.pametnakupovina.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DraftItemEntity::class],
    version = 2,
    exportSchema = true
)
abstract class PametnaKupovinaDatabase : RoomDatabase() {
    abstract fun draftItemDao(): DraftItemDao
}

val MIGRATION_1_2 = Migration(1, 2) { database ->
    database.execSQL(
        "ALTER TABLE draft_items ADD COLUMN canonicalProductId INTEGER"
    )
}
