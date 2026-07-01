package net.ft8vc.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QsoDaoTest {

    @Test
    fun bulkUpdateAndClearParkRefs() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            Ft8vcDatabase::class.java,
        ).build()
        try {
            val dao = db.qsoDao()
            val id1 = dao.insert(entity("K1ABC", 1_000L))
            dao.insert(entity("K2DEF", 2_000L))

            dao.updateParkRefs(listOf(id1), "US-3315,US-0891")
            var byCall = dao.getAll().associateBy { it.dxCall }
            assertEquals("US-3315,US-0891", byCall.getValue("K1ABC").potaParkRefs)
            assertNull(byCall.getValue("K2DEF").potaParkRefs)

            dao.updateParkRefs(listOf(id1), null)
            byCall = dao.getAll().associateBy { it.dxCall }
            assertNull(byCall.getValue("K1ABC").potaParkRefs)
        } finally {
            db.close()
        }
    }

    private fun entity(dxCall: String, utcMillis: Long) = QsoEntity(
        utcMillis = utcMillis,
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = dxCall,
        dxGrid = null,
        rstSent = null,
        rstRcvd = null,
        freqHz = null,
        mode = "FT8",
        band = null,
        notes = "",
    )
}
