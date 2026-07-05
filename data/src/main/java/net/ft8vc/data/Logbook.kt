package net.ft8vc.data

import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.adif.AdifImportMerge
import net.ft8vc.data.adif.AdifWriter
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.db.QsoEntity
import net.ft8vc.data.model.QsoContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Outcome of an ADIF import merge. */
data class ImportResult(val imported: Int, val duplicates: Int)

interface Logbook {
    suspend fun log(contact: QsoContact): Long
    fun contacts(): Flow<List<QsoContact>>
    suspend fun exportAdif(context: AdifExportContext = AdifExportContext()): String
    fun contactCount(): Flow<Int>
    suspend fun clearAll()
    suspend fun delete(ids: List<Long>)
    suspend fun workedBands(call: String): Set<String>
    suspend fun setParkRefs(ids: List<Long>, potaParkRefs: String?)
    /** Merge [incoming] into the log, skipping duplicates (call+band+90 s window). */
    suspend fun importContacts(incoming: List<QsoContact>): ImportResult
}

class RoomLogbook(db: Ft8vcDatabase) : Logbook {
    private val dao = db.qsoDao()

    override suspend fun log(contact: QsoContact): Long =
        dao.insert(contact.toEntity())

    override fun contacts(): Flow<List<QsoContact>> =
        dao.observeAll().map { list -> list.map { it.toContact() } }

    override suspend fun exportAdif(context: AdifExportContext): String =
        AdifWriter.export(dao.getAll().map { it.toContact() }, context)
    override fun contactCount(): Flow<Int> = dao.observeCount()

    override suspend fun clearAll() {
        dao.deleteAll()
    }

    override suspend fun delete(ids: List<Long>) {
        dao.deleteByIds(ids)
    }

    override suspend fun workedBands(call: String): Set<String> =
        dao.workedBands(call).toSet()

    override suspend fun setParkRefs(ids: List<Long>, potaParkRefs: String?) =
        dao.updateParkRefs(ids, potaParkRefs)

    override suspend fun importContacts(incoming: List<QsoContact>): ImportResult {
        val existing = dao.getAll().map { it.toContact() }
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, incoming)
        // Single list @Insert = one Room transaction: all-or-nothing.
        // IGNORE returns -1 for rows the (dxCall, utcMillis) unique index
        // rejected — count those as duplicates, not imports.
        val ids = dao.insertAll(toInsert.map { it.copy(id = 0).toEntity() })
        val inserted = ids.count { it != -1L }
        return ImportResult(imported = inserted, duplicates = duplicates + (ids.size - inserted))
    }

    private fun QsoContact.toEntity() = QsoEntity(
        id = id,
        utcMillis = utcMillis,
        myCall = myCall,
        myGrid = myGrid,
        dxCall = dxCall,
        dxGrid = dxGrid,
        rstSent = rstSent,
        rstRcvd = rstRcvd,
        freqHz = freqHz,
        mode = mode,
        band = band,
        notes = notes,
        potaParkRefs = potaParkRefs,
    )

    private fun QsoEntity.toContact() = QsoContact(
        id = id,
        utcMillis = utcMillis,
        myCall = myCall,
        myGrid = myGrid,
        dxCall = dxCall,
        dxGrid = dxGrid,
        rstSent = rstSent,
        rstRcvd = rstRcvd,
        freqHz = freqHz,
        mode = mode,
        band = band,
        notes = notes,
        potaParkRefs = potaParkRefs,
    )
}
