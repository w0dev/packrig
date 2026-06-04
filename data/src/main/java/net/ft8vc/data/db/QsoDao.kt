package net.ft8vc.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QsoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QsoEntity): Long

    @Query("SELECT * FROM qso_contacts ORDER BY utcMillis DESC")
    fun observeAll(): Flow<List<QsoEntity>>

    @Query("SELECT COUNT(*) FROM qso_contacts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM qso_contacts ORDER BY utcMillis DESC")
    suspend fun getAll(): List<QsoEntity>

    @Query("DELETE FROM qso_contacts")
    suspend fun deleteAll()
}
