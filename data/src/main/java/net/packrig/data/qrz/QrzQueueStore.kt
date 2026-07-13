package net.packrig.data.qrz

import net.packrig.data.db.QsoDao
import net.packrig.data.model.QsoContact
import net.packrig.data.toContact

/** Narrow persistence seam for the QRZ upload queue (fake-able in JVM tests). */
interface QrzQueueStore {
    /** QSOs awaiting upload, oldest first. */
    suspend fun pending(): List<QsoContact>
    suspend fun pendingCount(): Int
    suspend fun markUploaded(id: Long)
}

class RoomQrzQueueStore(private val dao: QsoDao) : QrzQueueStore {
    override suspend fun pending(): List<QsoContact> = dao.qrzPending().map { it.toContact() }
    override suspend fun pendingCount(): Int = dao.qrzPendingCount()
    override suspend fun markUploaded(id: Long) = dao.markQrzUploaded(id)
}
