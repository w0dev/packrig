package net.ft8vc.app.controllers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.WorkedBefore
import net.ft8vc.data.ImportResult
import net.ft8vc.data.Logbook
import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class FakeLogbook(
    private val bandsByCall: MutableMap<String, Set<String>> = mutableMapOf(),
) : Logbook {
    val calls = AtomicInteger(0)
    override suspend fun log(contact: QsoContact): Long = 0L
    override fun contacts(): Flow<List<QsoContact>> = emptyFlow()
    override suspend fun exportAdif(context: AdifExportContext): String = ""
    override fun contactCount(): Flow<Int> = emptyFlow()
    override suspend fun clearAll() {}
    override suspend fun delete(ids: List<Long>) {}
    override suspend fun workedBands(call: String): Set<String> {
        calls.incrementAndGet()
        return bandsByCall[call] ?: emptySet()
    }
    override suspend fun setParkRefs(ids: List<Long>, potaParkRefs: String?) {}
    override suspend fun importContacts(incoming: List<QsoContact>): ImportResult =
        ImportResult(imported = 0, duplicates = 0)
    fun set(call: String, bands: Set<String>) { bandsByCall[call] = bands }
}

class WorkedBeforeCacheTest {
    @Test fun classifies_never_other_this() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("40m", "15m"))
        log.set("W9XYZ", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        assertEquals(WorkedBefore.Never, cache.classify("N0CALL", "20m"))
        assertEquals(WorkedBefore.OtherBand, cache.classify("K1ABC", "20m"))
        assertEquals(WorkedBefore.ThisBand, cache.classify("W9XYZ", "20m"))
    }

    @Test fun caches_lookup_per_call() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.classify("K1ABC", "20m")
        cache.classify("K1ABC", "40m")
        assertEquals(1, log.calls.get())
    }

    @Test fun invalidate_forces_refetch_for_one_call() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        log.set("W9XYZ", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.classify("W9XYZ", "20m")
        assertEquals(2, log.calls.get())

        cache.invalidate("K1ABC")
        cache.classify("K1ABC", "20m")
        cache.classify("W9XYZ", "20m")  // still cached
        assertEquals(3, log.calls.get())
    }

    @Test fun clear_drops_everything() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.clear()
        cache.classify("K1ABC", "20m")
        assertEquals(2, log.calls.get())
    }

    // Field bug 2026-07-04: ft8_lib shows nonstandard calls hashed (<PJ4/K1ABC>)
    // in directed messages, but the QSO was logged from the full-call CQ form
    // (PJ4/K1ABC). The exact-match lookup then classified the hashed form Never.

    @Test fun classify_matches_hashed_form_against_logged_full_call() = runTest {
        val log = FakeLogbook()
        log.set("PJ4/K1ABC", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        assertEquals(WorkedBefore.ThisBand, cache.classify("<PJ4/K1ABC>", "20m"))
    }

    @Test fun invalidate_with_logged_call_drops_entry_cached_under_hashed_form() = runTest {
        val log = FakeLogbook()
        val cache = WorkedBeforeCache(log)

        assertEquals(WorkedBefore.Never, cache.classify("<PJ4/K1ABC>", "20m"))

        // QSO completes and logs under the full call; the VM invalidates with it.
        log.set("PJ4/K1ABC", setOf("20m"))
        cache.invalidate("PJ4/K1ABC")

        assertEquals(WorkedBefore.ThisBand, cache.classify("<PJ4/K1ABC>", "20m"))
    }

    @Test fun invalidate_then_reclassify_picks_up_new_band() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("40m"))
        val cache = WorkedBeforeCache(log)

        // Initial classification: K1ABC worked on 40m, current band 20m → OtherBand.
        assertEquals(WorkedBefore.OtherBand, cache.classify("K1ABC", "20m"))

        // Simulate a fresh QSO on 20m being logged.
        log.set("K1ABC", setOf("40m", "20m"))
        cache.invalidate("K1ABC")

        // Next classification should re-fetch and now report ThisBand.
        assertEquals(WorkedBefore.ThisBand, cache.classify("K1ABC", "20m"))
        assertEquals(2, log.calls.get())  // initial fetch + re-fetch after invalidate
    }
}
