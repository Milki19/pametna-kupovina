package rs.pametnakupovina.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DraftItemEntity::class, PurchaseSessionEntity::class],
    version = 4,
    exportSchema = true
)
abstract class PametnaKupovinaDatabase : RoomDatabase() {
    abstract fun draftItemDao(): DraftItemDao
    abstract fun purchaseSessionDao(): PurchaseSessionDao
}

val MIGRATION_3_4 = Migration(3, 4) { database ->
    database.execSQL("ALTER TABLE draft_items ADD COLUMN targetQuantity REAL")
    database.execSQL("""CREATE TABLE IF NOT EXISTS purchase_sessions (
        id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL,
        listName TEXT NOT NULL, itemCount INTEGER NOT NULL, purchasedCount INTEGER NOT NULL,
        archivedAt INTEGER, snapshotJson TEXT NOT NULL, progressJson TEXT NOT NULL
    )""")
}

val MIGRATION_1_2 = Migration(1, 2) { database ->
    database.execSQL(
        "ALTER TABLE draft_items ADD COLUMN canonicalProductId INTEGER"
    )
}

val MIGRATION_2_3 = Migration(2, 3) { database ->
    database.execSQL(
        "ALTER TABLE draft_items ADD COLUMN productFamilyId INTEGER"
    )
}
