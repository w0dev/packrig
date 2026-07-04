package net.ft8vc.app.controllers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ft8vc.core.CallsignMatcher
import net.ft8vc.core.WorkedBefore
import net.ft8vc.data.Logbook

/**
 * In-memory cache of `Logbook.workedBands(call)` results, keyed by
 * [CallsignMatcher.canonical] callsign so ft8_lib's hashed transport form
 * (`<PJ4/K1ABC>`) resolves to the same entry as the logged full call.
 *
 * Entries are populated lazily on the first [classify] call for a callsign and
 * stay until [invalidate] (after a new QSO with that call is logged) or
 * [clear] (ViewModel teardown).
 */
class WorkedBeforeCache(private val logbook: Logbook) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Set<String>>()

    suspend fun classify(call: String, currentBand: String?): WorkedBefore {
        val key = CallsignMatcher.canonical(call)
        val bands = mutex.withLock { cache[key] } ?: run {
            val fetched = logbook.workedBands(key)
            mutex.withLock { cache[key] = fetched }
            fetched
        }
        return WorkedBefore.classify(currentBand, bands)
    }

    suspend fun invalidate(call: String) {
        mutex.withLock { cache.remove(CallsignMatcher.canonical(call)) }
    }

    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }
}
