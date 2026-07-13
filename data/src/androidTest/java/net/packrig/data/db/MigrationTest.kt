package net.packrig.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PackRigDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesRowsAndAddsNullParkColumn() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO qso_contacts " +
                    "(utcMillis, myCall, myGrid, dxCall, dxGrid, rstSent, rstRcvd, freqHz, mode, band, notes) " +
                    "VALUES (1700000000000, 'W0DEV', 'EM26', 'K1ABC', 'FN42', -8, -15, 14074000, 'FT8', '20m', '')",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 2, true, PackRigDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT dxCall, potaParkRefs FROM qso_contacts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("K1ABC", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate2To3_existingRowsBecomeNotQueued() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO qso_contacts " +
                    "(utcMillis, myCall, myGrid, dxCall, dxGrid, rstSent, rstRcvd, freqHz, mode, band, notes, potaParkRefs) " +
                    "VALUES (1700000000000, 'W0DEV', 'EM26', 'K1ABC', 'FN42', -8, -15, 14074000, 'FT8', '20m', '', NULL)",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 3, true, PackRigDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT dxCall, qrzUploadState FROM qso_contacts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("K1ABC", cursor.getString(0))
                assertEquals("NOT_QUEUED", cursor.getString(1))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
