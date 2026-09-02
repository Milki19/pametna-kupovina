package rs.pametnakupovina.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rs.pametnakupovina.app.data.local.MIGRATION_1_2
import rs.pametnakupovina.app.data.local.MIGRATION_2_3

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class RoomMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SCHEMA_FOLDER,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationFrom1To2PreservesItemsAndAddsCanonicalProduct() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO draft_items (
                    remoteId, name, rawInput, barcode, quantity,
                    matchingRule, matchingStatus, category, requiredBrand,
                    minPackageQuantity, maxPackageQuantity, requiredBaseUnit,
                    syncState, updatedAtEpochMillis
                ) VALUES (
                    NULL, 'Donat', 'DONAT', NULL, 1.0,
                    'EXACT_PRODUCT', 'PENDING', NULL, NULL,
                    NULL, NULL, NULL, 'PENDING_CREATE', 1
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            MIGRATION_1_2
        ).use { database ->
            database.query(
                "SELECT name, canonicalProductId FROM draft_items"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("Donat", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrationFrom2To3PreservesItemsAndAddsProductFamily() {
        helper.createDatabase(DATABASE_NAME_V3, 2).apply {
            execSQL(
                """
                INSERT INTO draft_items (
                    remoteId, name, rawInput, barcode, canonicalProductId,
                    quantity, matchingRule, matchingStatus, category,
                    requiredBrand, minPackageQuantity, maxPackageQuantity,
                    requiredBaseUnit, syncState, updatedAtEpochMillis
                ) VALUES (
                    NULL, 'Donat', 'DONAT', '3838600041300', 42,
                    1.0, 'EXACT_PRODUCT', 'CONFIRMED', NULL,
                    NULL, NULL, NULL, NULL, 'SYNCED', 1
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V3,
            3,
            true,
            MIGRATION_2_3
        ).use { database ->
            database.query(
                "SELECT canonicalProductId, productFamilyId " +
                    "FROM draft_items"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(42L, cursor.getLong(0))
                assertEquals(true, cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
        const val DATABASE_NAME_V3 = "migration-test-v3"
        const val SCHEMA_FOLDER =
            "rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase"
    }
}
