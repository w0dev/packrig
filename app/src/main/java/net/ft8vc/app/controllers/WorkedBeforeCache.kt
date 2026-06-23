package net.ft8vc.app.controllers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ft8vc.core.WorkedBefore
import net.ft8vc.data.Logbook

/**
 * In-memory cache of `Logbook.workedBands(call)` results, keyed by callsign.
 *
 * Entries are populated lazily on the first [classify] call for a callsign and
 * stay until [invalidate] (after a new QSO with that call is logged) or
 * [clear] (ViewModel teardown).
 */
class WorkedBeforeCache(private val logbook: Logbook) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Set<String>>()

    suspend fun classify(call: String, currentBand: String?): WorkedBefore {
        val bands = mutex.withLock { cache[call] } ?: run {
            val fetched = logbook.workedBands(call)
            mutex.withLock { cache[call] = fetched }
            fetched
        }
        return WorkedBefore.classify(currentBand, bands)
    }

    suspend fun invalidate(call: String) {
        mutex.withLock { cache.remove(call) }
    }

    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }
}
