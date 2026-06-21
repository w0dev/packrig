package net.ft8vc.data

import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.adif.AdifWriter
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.db.QsoEntity
import net.ft8vc.data.model.QsoContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface Logbook {
    suspend fun log(contact: QsoContact): Long
    fun contacts(): Flow<List<QsoContact>>
    suspend fun exportAdif(context: AdifExportContext = AdifExportContext()): String
    fun contactCount(): Flow<Int>
    suspend fun clearAll()
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
    )
}
