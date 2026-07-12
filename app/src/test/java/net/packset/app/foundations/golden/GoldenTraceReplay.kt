package net.packset.app.foundations.golden

import net.packset.core.QsoDecode
import net.packset.core.QsoMachine
import net.packset.core.QsoState
import net.packset.ft8native.Ft8DecodeResult
import net.packset.ft8native.fakes.DecodeInvocation
import net.packset.ft8native.fakes.Ft8DecoderFake
import net.packset.rig.fakes.FakeRigBackend
import net.packset.rig.fakes.PttEdge
import net.packset.rig.fakes.PttEdgeKind

/**
 * Golden-trace replay driver (FOUND-06).
 *
 * Feeds [TraceEvent]s into the three Phase 0 fakes ([FakeRigBackend],
 * [Ft8DecoderFake]) and the real domain types ([QsoMachine] + the parser in
 * `QsoMessages`) — NEVER mocking domain types (PITFALLS.md Pitfall 5).
 *
 * `run()` is event-driven on virtual time: nothing in this replay sleeps or
 * advances a real clock. Events execute in their declared order at their
 * declared `ts_ms`, which is informational for the QSO state machine.
 */
class GoldenTraceReplay(
    val events: List<TraceEvent>,
    val machine: QsoMachine,
    val rig: FakeRigBackend = FakeRigBackend(),
    val decoder: Ft8DecoderFake = Ft8DecoderFake(),
) {

    /** Outcome of [run]. */
    data class ReplayResult(
        val finalState: QsoState,
        val pttKeyEdges: Int,
        val pttReleaseEdges: Int,
        val observedDecodeBatches: Int,
        val pttEdges: List<PttEdge>,
        val decodeInvocations: List<DecodeInvocation>,
        val transitions: List<QsoState>,
    )

    fun run(): ReplayResult {
        var decodeBatches = 0
        val transitions = ArrayList<QsoState>()
        transitions += machine.state
        for (event in events) {
            when (event.kind) {
                TraceEventKind.TRACE_START -> {
                    // Informational; identity in payload is documentary.
                }
                TraceEventKind.DECODE_BATCH -> {
                    decodeBatches++
                    val qsoDecodes = event.decodes.map { QsoDecode(it.message, it.snr) }
                    val nativeResults = event.decodes.map { d ->
                        Ft8DecodeResult(
                            message = d.message,
                            snr = d.snr,
                            dtSeconds = d.dt,
                            freqHz = d.freqHz,
                            score = d.score,
                        )
                    }
                    decoder.queueDecodeResults(nativeResults)
                    // Drain the queued batch so the invocation history reflects this slot.
                    decoder.decode(samples = ShortArray(0))
                    val beforeState = machine.state
                    machine.onDecodes(qsoDecodes)
                    if (machine.state != beforeState) transitions += machine.state
                }
                TraceEventKind.UI_ACTION -> {
                    when (val action = event.payload["action"]) {
                        "start_cq" -> {
                            val before = machine.state
                            machine.startCq()
                            if (machine.state != before) transitions += machine.state
                        }
                        "answer_cq" -> {
                            val dxCall = event.payload["dx_call"]
                                ?: error("answer_cq UI_ACTION requires payload.dx_call")
                            val dxGrid = event.payload["dx_grid"]
                            val snr = event.payload["snr"]?.toIntOrNull() ?: 0
                            val before = machine.state
                            machine.answerCq(dxCall, dxGrid, snr)
                            if (machine.state != before) transitions += machine.state
                        }
                        null -> error("UI_ACTION requires payload.action")
                        else -> error("Unknown UI_ACTION action: $action")
                    }
                }
                TraceEventKind.CAT_READ -> {
                    // Informational at schema v1; future versions may compare against rig state.
                }
                TraceEventKind.PTT_KEY_EXPECTED, TraceEventKind.PTT_RELEASE_EXPECTED -> {
                    // Informational — TX_SLOT is what drives the PTT edges in the fake.
                }
                TraceEventKind.TX_SLOT -> {
                    val message = machine.txMessage()
                    if (message != null) {
                        rig.keyPtt()
                        val beforeState = machine.state
                        machine.markTransmitted()
                        if (machine.state != beforeState) transitions += machine.state
                        rig.releasePtt()
                    }
                }
                TraceEventKind.TRACE_END -> {
                    return finalize(transitions, decodeBatches)
                }
            }
        }
        return finalize(transitions, decodeBatches)
    }

    private fun finalize(transitions: List<QsoState>, decodeBatches: Int): ReplayResult {
        val edges = rig.pttEdgesSnapshot()
        return ReplayResult(
            finalState = machine.state,
            pttKeyEdges = edges.count { it.kind == PttEdgeKind.KEY },
            pttReleaseEdges = edges.count { it.kind == PttEdgeKind.RELEASE },
            observedDecodeBatches = decodeBatches,
            pttEdges = edges,
            decodeInvocations = decoder.decodeInvocationsSnapshot(),
            transitions = transitions,
        )
    }
}
