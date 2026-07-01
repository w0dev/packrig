package net.ft8vc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [QsoEntity::class], version = 2, exportSchema = true)
abstract class Ft8vcDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile private var instance: Ft8vcDatabase? = null

        /** v1 → v2: per-QSO POTA park refs (normalized CSV; null = home QSO). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qso_contacts ADD COLUMN potaParkRefs TEXT")
            }
        }

        fun get(context: Context): Ft8vcDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Ft8vcDatabase::class.java,
                    "ft8vc_logbook.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
