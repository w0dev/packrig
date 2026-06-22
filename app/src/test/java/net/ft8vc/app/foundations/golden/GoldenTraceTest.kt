package net.ft8vc.app.foundations.golden

import net.ft8vc.core.QsoMachine
import net.ft8vc.core.QsoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenTraceTest {

    @Test
    fun loadsAndParsesCanonicalTraceFile() {
        val events = GoldenTrace.loadFromClasspath("traces/cq-answer-73.jsonl")
        assertTrue(
            "expected >= 5 events in canonical trace, got ${events.size}",
            events.size >= 5,
        )
        assertEquals(TraceEventKind.TRACE_START, events.first().kind)
        assertEquals(TraceEventKind.TRACE_END, events.last().kind)
    }

    @Test
    fun replaysCqAnswer73AndReachesComplete() {
        val events = GoldenTrace.loadFromClasspath("traces/cq-answer-73.jsonl")
        val replay = GoldenTraceReplay(
            events = events,
            machine = QsoMachine(myCall = "W0DEV", myGrid = "EM26"),
        )
        val result = replay.run()

        assertEquals(
            "QSO did not reach Complete; transitions: ${result.transitions}",
            QsoState.Complete,
            result.finalState,
        )
        assertTrue(
            "expected >= 2 KEY edges, got ${result.pttKeyEdges}",
            result.pttKeyEdges >= 2,
        )
        assertTrue(
            "expected >= 2 RELEASE edges, got ${result.pttReleaseEdges}",
            result.pttReleaseEdges >= 2,
        )
        assertTrue(
            "expected >= 3 decode batches, got ${result.observedDecodeBatches}",
            result.observedDecodeBatches >= 3,
        )
    }
}
