package net.ft8vc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [QsoEntity::class], version = 1, exportSchema = false)
abstract class Ft8vcDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile private var instance: Ft8vcDatabase? = null

        fun get(context: Context): Ft8vcDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Ft8vcDatabase::class.java,
                    "ft8vc_logbook.db",
                ).build().also { instance = it }
            }
    }
}
