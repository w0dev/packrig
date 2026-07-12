package net.packset.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QsoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QsoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<QsoEntity>): List<Long>

    @Query("SELECT * FROM qso_contacts ORDER BY utcMillis DESC")
    fun observeAll(): Flow<List<QsoEntity>>

    @Query("SELECT COUNT(*) FROM qso_contacts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM qso_contacts ORDER BY utcMillis DESC")
    suspend fun getAll(): List<QsoEntity>

    @Query("DELETE FROM qso_contacts")
    suspend fun deleteAll()

    @Query("DELETE FROM qso_contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT DISTINCT band FROM qso_contacts WHERE dxCall = :call AND band IS NOT NULL")
    suspend fun workedBands(call: String): List<String>

    @Query("UPDATE qso_contacts SET potaParkRefs = :potaParkRefs WHERE id IN (:ids)")
    suspend fun updateParkRefs(ids: List<Long>, potaParkRefs: String?)

    @Query("SELECT * FROM qso_contacts WHERE qrzUploadState = 'PENDING' ORDER BY utcMillis ASC")
    suspend fun qrzPending(): List<QsoEntity>

    @Query("SELECT COUNT(*) FROM qso_contacts WHERE qrzUploadState = 'PENDING'")
    suspend fun qrzPendingCount(): Int

    @Query("UPDATE qso_contacts SET qrzUploadState = 'UPLOADED' WHERE id = :id")
    suspend fun markQrzUploaded(id: Long)
}
