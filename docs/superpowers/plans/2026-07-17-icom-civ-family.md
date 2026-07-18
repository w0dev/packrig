# Icom CI-V Family Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement multi-rig Phase 4 per `docs/superpowers/specs/2026-07-17-icom-civ-family-design.md`: a frame-stream refactor of `SerialRigBackend`, the `IcomCiV` binary protocol (Icom + Xiegu), five flagship presets, the "Icom CI-V" protocol dropdown entry with contextual hex address field, and the new Echo-only Test CAT outcome.

**Architecture:** `CatProtocol` grows framing knowledge (`splitFrames`/`classifyFrame`/`wantsInputFlush`/`setCommandsAcked`, defaults preserving today's Yaesu semantics byte-for-byte); `SerialRigBackend` exchanges become frame-classified loops; `IcomCiV` is a pure parser parameterized by `IcomModelSpec` (CI-V bus address + data-mode strategy); profiles gain a flat `civAddress: Int?` knob resolved through the existing override-wins scheme.

**Tech Stack:** Kotlin, JUnit4 (no new dependencies). Work on a branch off `unstable` (suggested name: `icom-civ`), in a worktree per `superpowers:using-git-worktrees`. Fresh worktrees need `local.properties` copied from the main checkout.

## Global Constraints

- **Yaesu parity is the bar:** every pre-existing test in `rig/` and `app/` must pass **unchanged** after the frame refactor (Task 2). Byte-identical wire behavior for `YaesuCat` paths.
- **Hardware merge gate (not automatable):** FT-891 + FTX-1 bench re-verification (CAT sync, TX key, Test CAT spot-checks) before this branch merges. Record as unchecked item in the final task.
- Persisted ids are frozen: preset ids, `CatProtocols` ids, JSON field names — never rename.
- Display copy is plain language (no "bus", no "controller", no hex jargon beyond the address itself).
- Kotlin official style, 4-space indent, one public type per file, KDoc on public APIs, no console logging outside existing `Log` use in `rig`.
- Unit test commands: single class `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.IcomCiVTest"`; module `./gradlew :rig:testDebugUnitTest` / `:app:testDebugUnitTest`; full sweep `./gradlew testDebugUnitTest`. If Gradle reports UP-TO-DATE, the XML under `build/test-results/` is stale — rerun with `--rerun-tasks` before trusting it.
- Authoring references (data, not dependencies): hamlib `rigs/icom/*.c`, FT8CN `rigs/IcomRigConstant.java` + `XieGuRig.java`, wfview. Per-model values below were cross-checked 2026-07-17: addresses IC-7300 `0x94` (hamlib+FT8CN), IC-705 `0xA4` (hamlib+wfview), IC-7100 `0x88` (hamlib+manual), G90 `0x70` (Xiegu manual+Radioddity+FT8CN; hamlib row ambiguous), X6100 `0xA4` (hamlib+wfview "identifies as IC-705").

---

### Task 1: Frame vocabulary in the protocol seam

**Files:**
- Create: `rig/src/main/java/net/packrig/rig/CatFraming.kt`
- Modify: `rig/src/main/java/net/packrig/rig/CatProtocol.kt`
- Modify: `rig/src/main/java/net/packrig/rig/YaesuCat.kt`
- Test: `rig/src/test/java/net/packrig/rig/YaesuCatTest.kt`

**Interfaces:**
- Consumes: existing `CatProtocol`, `YaesuCat.TERMINATOR`.
- Produces: `enum class FrameClass { Reply, Echo, Ack, Nak, Broadcast, Junk }`; `data class FrameSplit(val frames: List<ByteArray>, val remainder: ByteArray)`; new `CatProtocol` members `fun splitFrames(bytes: ByteArray): FrameSplit` (abstract), `fun classifyFrame(frame: ByteArray): FrameClass` (default `Reply`), `val wantsInputFlush: Boolean` (default `false`), `val setCommandsAcked: Boolean` (default `false`). `replyTerminator` stays for now (removed in Task 2).

- [ ] **Step 1: Write the failing tests** — append to `YaesuCatTest.kt`:

```kotlin
@Test
fun splitFrames_splitsCompleteFramesAndKeepsRemainder() {
    val cat = YaesuCat(YaesuCat.FT891)
    val split = cat.splitFrames("FA014074000;MD02;FA0".toByteArray(Charsets.US_ASCII))
    assertEquals(listOf("FA014074000;", "MD02;"), split.frames.map { it.toString(Charsets.US_ASCII) })
    assertEquals("FA0", split.remainder.toString(Charsets.US_ASCII))
}

@Test
fun splitFrames_noTerminatorIsAllRemainder() {
    val cat = YaesuCat(YaesuCat.FT891)
    val split = cat.splitFrames("FA0140".toByteArray(Charsets.US_ASCII))
    assertTrue(split.frames.isEmpty())
    assertEquals("FA0140", split.remainder.toString(Charsets.US_ASCII))
}

@Test
fun framingDefaults_matchLegacyYaesuBehavior() {
    val cat = YaesuCat(YaesuCat.FT891)
    assertEquals(FrameClass.Reply, cat.classifyFrame("FA014074000;".toByteArray(Charsets.US_ASCII)))
    assertFalse(cat.wantsInputFlush)
    assertFalse(cat.setCommandsAcked)
}
```

(`assertFalse` may need adding to the imports.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.YaesuCatTest"`
Expected: compile error — `splitFrames` / `FrameClass` unresolved.

- [ ] **Step 3: Implement** — create `CatFraming.kt`:

```kotlin
package net.packrig.rig

/**
 * What one complete CAT frame is, relative to the last command this
 * controller sent. ASCII families see everything as [Reply] (their default);
 * CI-V distinguishes echoes, acks, and transceive broadcasts.
 */
enum class FrameClass {
    /** A frame addressed to us that answers a query. */
    Reply,

    /** Our own transmitted command reflected back (echoing wiring). */
    Echo,

    /** Set-command acknowledged (CI-V 0xFB). */
    Ack,

    /** Set-command rejected (CI-V 0xFA). */
    Nak,

    /** Unsolicited rig announcement (CI-V transceive frequency/mode). */
    Broadcast,

    /** Malformed, or traffic for another station on the bus. */
    Junk,
}

/** Result of chopping accumulated bytes into complete frames. */
data class FrameSplit(val frames: List<ByteArray>, val remainder: ByteArray)
```

Add to `CatProtocol` (below `pttCommand`):

```kotlin
    /** Chop accumulated reply bytes into complete frames + unconsumed rest. */
    fun splitFrames(bytes: ByteArray): FrameSplit

    /** Classify a complete frame. Default: everything is a reply (ASCII CAT). */
    fun classifyFrame(frame: ByteArray): FrameClass = FrameClass.Reply

    /** Drain stale input (unclaimed acks/echoes) before each exchange. */
    val wantsInputFlush: Boolean get() = false

    /** Set commands are acknowledged — await Ack/Nak instead of fire-and-forget. */
    val setCommandsAcked: Boolean get() = false
```

Add to `YaesuCat` (below `pttCommand`):

```kotlin
    override fun splitFrames(bytes: ByteArray): FrameSplit {
        val frames = mutableListOf<ByteArray>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == TERMINATOR.code.toByte()) {
                frames += bytes.copyOfRange(start, i + 1)
                start = i + 1
            }
        }
        return FrameSplit(frames, bytes.copyOfRange(start, bytes.size))
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.YaesuCatTest"`
Expected: PASS (all, including pre-existing).

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/packrig/rig/CatFraming.kt rig/src/main/java/net/packrig/rig/CatProtocol.kt rig/src/main/java/net/packrig/rig/YaesuCat.kt rig/src/test/java/net/packrig/rig/YaesuCatTest.kt
git commit -m "feat(rig): frame vocabulary in the CatProtocol seam"
```

---

### Task 2: Frame-stream exchange loop in SerialRigBackend + Echo-only probe

**Files:**
- Modify: `rig/src/main/java/net/packrig/rig/SerialRigBackend.kt` (full exchange rework)
- Modify: `rig/src/main/java/net/packrig/rig/CatProtocol.kt` (delete `replyTerminator`)
- Modify: `rig/src/main/java/net/packrig/rig/YaesuCat.kt` (delete `replyTerminator` override)
- Modify: `rig/src/main/java/net/packrig/rig/ProbeResult.kt` (add `EchoOnly`)
- Modify: `app/src/main/java/net/packrig/app/OperateViewModel.kt:1185` (`probeResultText` — new branch)
- Test: `rig/src/test/java/net/packrig/rig/SerialRigBackendTest.kt`, `app/src/test/java/net/packrig/app/ProbeResultTextTest.kt`

**Interfaces:**
- Consumes: Task 1's `FrameClass`, `FrameSplit`, `splitFrames`, `classifyFrame`, `wantsInputFlush`, `setCommandsAcked`.
- Produces: `SerialRigBackend` public surface unchanged (`open/close/keyPtt/releasePtt/frequencyHz/setFrequencyHz/modeLabel/setDataMode/dataModeLabel/catPtt/probeFrequency`); `ProbeResult.EchoOnly : ProbeResult`. Behavior additions relied on later: acked set commands return real success; `frequencyHz()` falls back to a transceive broadcast heard during the exchange when the direct reply never comes; `probeFrequency()` can return `EchoOnly`, and a parseable `Broadcast` during a probe counts as `Sync`.

- [ ] **Step 1: Write the failing tests.** Existing `SerialRigBackendTest` is the parity suite — do not touch existing cases. Append (uses a tiny CI-V-shaped stub protocol so acked/flush paths are testable before `IcomCiV` exists):

```kotlin
    /** Minimal acked/flushing protocol: frames are `<class-byte>!`, ack = 'K', nak = 'N'. */
    private open class AckedStubProtocol : CatProtocol {
        override val dataModeLabel = "STUB"
        override val wantsInputFlush = true
        override val setCommandsAcked = true
        override fun readFrequencyCommand() = byteArrayOf('Q'.code.toByte())
        override fun setFrequencyCommand(hz: Long): ByteArray = byteArrayOf('S'.code.toByte())
        override fun parseFrequency(reply: ByteArray): Long? =
            if (reply.firstOrNull() == 'R'.code.toByte()) 7_074_000L else null
        override fun readModeCommand() = byteArrayOf('M'.code.toByte())
        override fun parseModeLabel(reply: ByteArray): String? = null
        override fun setDataModeCommand() = byteArrayOf('D'.code.toByte())
        override fun pttCommand(on: Boolean) = byteArrayOf('P'.code.toByte())
        override fun splitFrames(bytes: ByteArray): FrameSplit {
            val frames = mutableListOf<ByteArray>()
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == '!'.code.toByte()) {
                    frames += bytes.copyOfRange(start, i + 1)
                    start = i + 1
                }
            }
            return FrameSplit(frames, bytes.copyOfRange(start, bytes.size))
        }
        override fun classifyFrame(frame: ByteArray): FrameClass = when (frame.firstOrNull()) {
            'R'.code.toByte() -> FrameClass.Reply
            'E'.code.toByte() -> FrameClass.Echo
            'K'.code.toByte() -> FrameClass.Ack
            'N'.code.toByte() -> FrameClass.Nak
            'B'.code.toByte() -> FrameClass.Broadcast
            else -> FrameClass.Junk
        }
    }

    @Test
    fun exchange_skipsEchoAndJunkFramesBeforeReply() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueOnWrite("E!x!R!")
        assertEquals(7_074_000L, b.frequencyHz())
    }

    @Test
    fun ackedSetCommand_trueOnAck_falseOnNak() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueOnWrite("K!")
        assertTrue(b.setDataMode())
        t.enqueueOnWrite("N!")
        assertFalse(b.setDataMode())
    }

    @Test
    fun ackedSetCommand_falseOnTimeout() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, AckedStubProtocol()) { clock += 600; clock }
        assertFalse(b.setDataMode())
    }

    @Test
    fun flush_drainsStaleBytesBeforeExchange() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueReply("K!")          // stale ack from a previous set
        t.enqueueOnWrite("R!")        // the actual reply, delivered post-write
        assertEquals(7_074_000L, b.frequencyHz())
    }

    @Test
    fun frequency_fallsBackToBroadcastWhenReplyNeverComes() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, object : AckedStubProtocol() {
            override fun parseFrequency(reply: ByteArray): Long? = when (reply.firstOrNull()) {
                'B'.code.toByte() -> 14_074_000L
                'R'.code.toByte() -> 7_074_000L
                else -> null
            }
        }, { clock += 300; clock })
        t.enqueueOnWrite("B!")
        assertEquals(14_074_000L, b.frequencyHz())
    }

    @Test
    fun probe_echoOnlyWhenOnlyOurEchoComesBack() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, AckedStubProtocol()) { clock += 300; clock }
        t.enqueueOnWrite("E!")
        assertEquals(ProbeResult.EchoOnly, b.probeFrequency())
    }

    @Test
    fun probe_broadcastFrequencyCountsAsSync() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, object : AckedStubProtocol() {
            override fun parseFrequency(reply: ByteArray): Long? =
                if (reply.firstOrNull() == 'B'.code.toByte()) 14_074_000L else null
        })
        t.enqueueOnWrite("B!")
        assertEquals(ProbeResult.Sync(14_074_000L), b.probeFrequency())
    }
```

(`open` on the class and on `parseFrequency` lets the two anonymous-object overrides in the tests compile — add `open` to `parseFrequency` above.) Add to `FakeSerialTransport.kt`:

```kotlin
    /** Bytes delivered only after the next write() — for flush-enabled protocols
     *  whose pre-write drain would otherwise eat a pre-enqueued reply. */
    private val onWrite = ArrayDeque<Byte>()

    fun enqueueOnWrite(ascii: String) {
        ascii.toByteArray(Charsets.US_ASCII).forEach { onWrite.addLast(it) }
    }
```

and in its `write(...)` implementation, after recording the write: `while (onWrite.isNotEmpty()) pending.addLast(onWrite.removeFirst())`. Add a `FakeSerialTransportSelfTest` case: bytes from `enqueueOnWrite` are not readable before a write and are readable after.

Append to `ProbeResultTextTest.kt`:

```kotlin
@Test
fun echoOnly_pointsAtAddressAndPower() {
    val text = probeResultText(ProbeResult.EchoOnly)
    assertTrue(text.contains("CI-V address"))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.SerialRigBackendTest"`
Expected: compile error — `enqueueOnWrite` / `EchoOnly` unresolved.

- [ ] **Step 3: Implement.** In `ProbeResult.kt` add below `Silence`:

```kotlin
    /** Only our own echoed command came back — the rig itself never answered. */
    data object EchoOnly : ProbeResult
```

Delete `replyTerminator` from `CatProtocol` (and its KDoc line) and the `override val replyTerminator` from `YaesuCat`. Rewrite `SerialRigBackend`'s private exchange machinery (public API and `open/close/keyPtt/releasePtt` untouched):

```kotlin
    override fun frequencyHz(): Long? {
        val p = protocol ?: return null
        val outcome = catExchange(p, p.readFrequencyCommand())
        // A transceive broadcast heard while waiting is a valid answer when the
        // direct reply never came (echo-heavy or busy CI-V buses).
        return outcome.reply?.let(p::parseFrequency)
            ?: outcome.broadcasts.firstNotNullOfOrNull(p::parseFrequency)
    }

    fun probeFrequency(): ProbeResult = synchronized(catLock) {
        val p = protocol ?: return ProbeResult.NoCat
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(p.readFrequencyCommand(), CAT_TIMEOUT_MS)) return ProbeResult.Silence
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var pending = ByteArray(0)
        var sawBytes = false
        var sawEcho = false
        var sawOther = false
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) break
            if (n == 0) continue
            sawBytes = true
            pending += buffer.copyOfRange(0, n)
            val split = p.splitFrames(pending)
            pending = split.remainder
            for (frame in split.frames) {
                when (p.classifyFrame(frame)) {
                    FrameClass.Reply ->
                        return p.parseFrequency(frame)?.let { ProbeResult.Sync(it) } ?: ProbeResult.Garbage
                    FrameClass.Echo -> sawEcho = true
                    FrameClass.Broadcast -> {
                        p.parseFrequency(frame)?.let { return ProbeResult.Sync(it) }
                        sawOther = true
                    }
                    else -> sawOther = true
                }
            }
        }
        return when {
            sawEcho && !sawOther -> ProbeResult.EchoOnly
            sawBytes -> ProbeResult.Garbage
            else -> ProbeResult.Silence
        }
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        val p = protocol ?: return false
        val command = p.setFrequencyCommand(hz) ?: return false
        return catWrite(p, command)
    }

    override fun modeLabel(): String? {
        val p = protocol ?: return null
        return catExchange(p, p.readModeCommand()).reply?.let(p::parseModeLabel)
    }

    override fun setDataMode(): Boolean {
        val p = protocol ?: return false
        return catWrite(p, p.setDataModeCommand())
    }

    override fun catPtt(on: Boolean): Boolean {
        val p = protocol ?: return false
        val command = p.pttCommand(on) ?: return false
        val ok = catWrite(p, command)
        Log.i(TAG, "catPtt(on=$on) sent=$ok")
        return ok
    }

    /** Reply (or null) plus any transceive broadcasts heard while waiting. */
    private class Exchange(val reply: ByteArray?, val broadcasts: List<ByteArray>)

    /** Send a set command. Fire-and-forget unless the protocol acks sets. */
    private fun catWrite(p: CatProtocol, command: ByteArray): Boolean = synchronized(catLock) {
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return false
        }
        if (!p.setCommandsAcked) {
            Log.i(TAG, "CAT write \"${command.ascii()}\" ok=true")
            return true
        }
        val outcome = consumeFrames(p) { c -> c == FrameClass.Ack || c == FrameClass.Nak }
        val acked = outcome.reply?.let { p.classifyFrame(it) == FrameClass.Ack } ?: false
        Log.i(TAG, "CAT set \"${command.ascii()}\" acked=$acked")
        return acked
    }

    /** Send a query and collect frames until a Reply or the deadline. */
    private fun catExchange(p: CatProtocol, command: ByteArray): Exchange = synchronized(catLock) {
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return Exchange(null, emptyList())
        }
        val outcome = consumeFrames(p) { c -> c == FrameClass.Reply }
        Log.i(
            TAG,
            "CAT exchange \"${command.ascii()}\" -> " +
                (outcome.reply?.let { "\"${it.ascii()}\"" } ?: "<timeout>"),
        )
        return outcome
    }

    /** Accumulate reads, split into frames, and return the first frame matching
     *  [wanted]; echoes, junk, and unclaimed acks are dropped, broadcasts kept. */
    private fun consumeFrames(p: CatProtocol, wanted: (FrameClass) -> Boolean): Exchange {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var pending = ByteArray(0)
        val broadcasts = mutableListOf<ByteArray>()
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) {
                Log.w(TAG, "CAT read error — aborting frame wait")
                return Exchange(null, broadcasts)
            }
            if (n == 0) continue
            pending += buffer.copyOfRange(0, n)
            val split = p.splitFrames(pending)
            pending = split.remainder
            for (frame in split.frames) {
                val klass = p.classifyFrame(frame)
                if (wanted(klass)) return Exchange(frame, broadcasts)
                if (klass == FrameClass.Broadcast) broadcasts += frame
            }
        }
        Log.w(TAG, "CAT frame wait timed out")
        return Exchange(null, broadcasts)
    }

    /** Discard stale bytes (unclaimed acks, echoes) queued before an exchange. */
    private fun drainInput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        repeat(MAX_FLUSH_READS) {
            if (transport.read(buffer, FLUSH_READ_TIMEOUT_MS) <= 0) return
        }
    }
```

Companion additions: `private const val FLUSH_READ_TIMEOUT_MS = 10` and `private const val MAX_FLUSH_READS = 16`. Delete the old `readReply`. In `OperateViewModel.probeResultText`, add before the `NoDevice` branch:

```kotlin
    ProbeResult.EchoOnly ->
        "The cable echoes commands but the radio didn't answer — check the CI-V address matches " +
            "the radio's menu, and that the radio is on"
```

- [ ] **Step 4: Run to verify pass — parity suite must be untouched and green**

Run: `./gradlew :rig:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, zero modifications to pre-existing test cases (`git diff --stat` shows only appended tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src app/src
git commit -m "feat(rig): frame-stream CAT exchanges with echo/ack handling and Echo-only probe"
```

---

### Task 3: IcomCiV core — model spec, framing, BCD frequency

**Files:**
- Create: `rig/src/main/java/net/packrig/rig/IcomModelSpec.kt`
- Create: `rig/src/main/java/net/packrig/rig/IcomCiV.kt`
- Test: `rig/src/test/java/net/packrig/rig/IcomCiVTest.kt` (create)

**Interfaces:**
- Consumes: Task 1 `FrameClass`/`FrameSplit`; `CatProtocol`.
- Produces: `enum class DataModeStrategy { CMD_26, CMD_06_PLUS_1A, CMD_06_ONLY }`; `data class IcomModelSpec(val name: String, val civAddress: Int, val minFreqHz: Long, val maxFreqHz: Long, val dataModeStrategy: DataModeStrategy, val modeLabels: Map<Int, String> = IcomModelSpec.CIV_MODE_LABELS)`; `class IcomCiV(val model: IcomModelSpec, civAddressOverride: Int? = null) : CatProtocol` with `val civAddress: Int`. Constants `IcomCiV.CONTROLLER = 0xE0`, `IcomCiV.BROADCAST = 0x00`.

- [ ] **Step 1: Write the failing tests** — create `IcomCiVTest.kt`. Golden frames cross-checked against FT8CN `IcomRigConstant` and the CI-V reference (frame `FE FE <to> <from> <cmd> [data…] FD`; freq = 5 BCD bytes, 1-Hz digit first):

```kotlin
package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcomCiVTest {

    private val spec = IcomModelSpec(
        name = "Test rig",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 74_800_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )
    private val civ = IcomCiV(spec)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun readFrequencyCommand_isCmd03() {
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD).toList(),
            civ.readFrequencyCommand().toList(),
        )
    }

    @Test
    fun setFrequencyCommand_encodes14074AsLittleEndianBcd() {
        // FT8CN golden frame for 14.074 MHz: FE FE <addr> E0 05 00 40 07 14 00 FD
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD).toList(),
            civ.setFrequencyCommand(14_074_000L)!!.toList(),
        )
    }

    @Test
    fun setFrequencyCommand_outOfModelRangeIsNull() {
        assertNull(civ.setFrequencyCommand(144_174_000L))
    }

    @Test
    fun parseFrequency_readsPollReply() {
        val reply = bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        assertEquals(14_074_000L, civ.parseFrequency(reply))
    }

    @Test
    fun parseFrequency_readsTransceiveBroadcast() {
        val transceive = bytes(0xFE, 0xFE, 0x00, 0x94, 0x00, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        assertEquals(14_074_000L, civ.parseFrequency(transceive))
    }

    @Test
    fun parseFrequency_rejectsNonBcdAndWrongCommand() {
        assertNull(civ.parseFrequency(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x4A, 0x07, 0x14, 0x00, 0xFD)))
        assertNull(civ.parseFrequency(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x01, 0x01, 0xFD)))
        assertNull(civ.parseFrequency(bytes(0x03, 0xFD)))
    }

    @Test
    fun splitFrames_handlesNoiseAndPartials() {
        val stream = bytes(0x07, 0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD, 0xFE, 0xFE)
        val split = civ.splitFrames(stream)
        assertEquals(1, split.frames.size)
        assertEquals(
            bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD).toList(),
            split.frames[0].toList(),
        )
        assertEquals(bytes(0xFE, 0xFE).toList(), split.remainder.toList())
    }

    @Test
    fun classifyFrame_coversEchoAckNakBroadcastReplyJunk() {
        val echo = bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD)
        val ack = bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD)
        val nak = bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFA, 0xFD)
        val broadcast = bytes(0xFE, 0xFE, 0x00, 0x94, 0x00, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val reply = bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val otherStation = bytes(0xFE, 0xFE, 0xE0, 0xA4, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val truncated = bytes(0xFE, 0xFE, 0xE0, 0xFD)
        assertEquals(FrameClass.Echo, civ.classifyFrame(echo))
        assertEquals(FrameClass.Ack, civ.classifyFrame(ack))
        assertEquals(FrameClass.Nak, civ.classifyFrame(nak))
        assertEquals(FrameClass.Broadcast, civ.classifyFrame(broadcast))
        assertEquals(FrameClass.Reply, civ.classifyFrame(reply))
        assertEquals(FrameClass.Junk, civ.classifyFrame(otherStation))
        assertEquals(FrameClass.Junk, civ.classifyFrame(truncated))
    }

    @Test
    fun addressOverride_winsOverModelDefault() {
        val moved = IcomCiV(spec, civAddressOverride = 0x76)
        assertEquals(
            bytes(0xFE, 0xFE, 0x76, 0xE0, 0x03, 0xFD).toList(),
            moved.readFrequencyCommand().toList(),
        )
        assertEquals(FrameClass.Junk, moved.classifyFrame(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD)))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.IcomCiVTest"`
Expected: compile error — `IcomModelSpec` unresolved.

- [ ] **Step 3: Implement.** `IcomModelSpec.kt`:

```kotlin
package net.packrig.rig

/** How a CI-V model selects its FT8 data mode — varies by rig generation. */
enum class DataModeStrategy {
    /** One-shot 0x26: mode + data flag + filter (IC-7300 era and newer). */
    CMD_26,

    /** 0x06 mode set followed by the 0x1A 0x06 data-mode subcommand (IC-7100 era). */
    CMD_06_PLUS_1A,

    /** Plain USB via 0x06 — the rig has no data mode at all (Xiegu G90). */
    CMD_06_ONLY,
}

/**
 * Per-model CI-V parameters: bus address, tuning bounds, and the data-mode
 * command generation. Values authored from CAT manuals and cross-checked
 * against hamlib + FT8CN (see docs/RIG_MODELS.md).
 */
data class IcomModelSpec(
    val name: String,
    val civAddress: Int,
    val minFreqHz: Long,
    val maxFreqHz: Long,
    val dataModeStrategy: DataModeStrategy,
    val modeLabels: Map<Int, String> = CIV_MODE_LABELS,
) {
    companion object {
        /** Standard CI-V mode codes (command 0x04 reply / 0x01 broadcast). */
        val CIV_MODE_LABELS: Map<Int, String> = mapOf(
            0x00 to "LSB", 0x01 to "USB", 0x02 to "AM", 0x03 to "CW",
            0x04 to "RTTY", 0x05 to "FM", 0x06 to "WFM", 0x07 to "CW-R",
            0x08 to "RTTY-R", 0x17 to "DV",
        )
    }
}
```

`IcomCiV.kt`:

```kotlin
package net.packrig.rig

/**
 * Icom CI-V binary protocol (also spoken by Xiegu), parameterized by
 * [IcomModelSpec] and an optional bus-address override (operators can move a
 * rig off its factory address). Frames are `FE FE <to> <from> <cmd> [data] FD`;
 * frequencies are 5 BCD bytes, 1-Hz digit pair first. 0xFD never occurs inside
 * a frame body, so splitting on it is sound.
 */
class IcomCiV(
    val model: IcomModelSpec,
    civAddressOverride: Int? = null,
) : CatProtocol {

    val civAddress: Int = civAddressOverride ?: model.civAddress

    override val dataModeLabel: String =
        if (model.dataModeStrategy == DataModeStrategy.CMD_06_ONLY) "USB" else "USB-D"

    override val wantsInputFlush: Boolean = true

    override val setCommandsAcked: Boolean = true

    override fun readFrequencyCommand(): ByteArray = frame(CMD_READ_FREQ)

    override fun setFrequencyCommand(hz: Long): ByteArray? {
        if (hz !in model.minFreqHz..model.maxFreqHz) return null
        return frame(CMD_SET_FREQ, *bcdFrequency(hz))
    }

    override fun parseFrequency(reply: ByteArray): Long? {
        val body = commandBody(reply) ?: return null
        val cmd = body[0].toInt() and 0xFF
        // 0x03 = poll reply; 0x00 = transceive broadcast. Same payload layout.
        if (cmd != CMD_READ_FREQ && cmd != CMD_TRANSCEIVE_FREQ) return null
        val data = body.copyOfRange(1, body.size)
        if (data.size < 5) return null
        return bcdToHz(data)
    }

    override fun readModeCommand(): ByteArray = frame(CMD_READ_MODE)

    override fun parseModeLabel(reply: ByteArray): String? {
        val body = commandBody(reply) ?: return null
        val cmd = body[0].toInt() and 0xFF
        if (cmd != CMD_READ_MODE && cmd != CMD_TRANSCEIVE_MODE) return null
        if (body.size < 2) return null
        // Mode codes are matched as raw byte values: 0x00–0x08 read the same
        // raw or BCD, and DV arrives as byte 0x17 (BCD "17") — the label map
        // is keyed accordingly.
        return model.modeLabels[body[1].toInt() and 0xFF]
    }

    override fun setDataModeCommand(): ByteArray = when (model.dataModeStrategy) {
        // 0x26 0x00 = selected VFO: mode USB, data on, FIL1.
        DataModeStrategy.CMD_26 -> frame(0x26, 0x00, MODE_USB, 0x01, 0x01)
        // Two frames in one write: USB via 0x06, then data mode via 0x1A 0x06.
        // The backend awaits the first ack; the flush drains the second.
        DataModeStrategy.CMD_06_PLUS_1A ->
            frame(0x06, MODE_USB, 0x01) + frame(0x1A, 0x06, 0x01, 0x01)
        DataModeStrategy.CMD_06_ONLY -> frame(0x06, MODE_USB, 0x01)
    }

    override fun pttCommand(on: Boolean): ByteArray =
        frame(0x1C, 0x00, if (on) 0x01 else 0x00)

    override fun splitFrames(bytes: ByteArray): FrameSplit {
        val frames = mutableListOf<ByteArray>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == END.toByte()) {
                // Discard pre-preamble line noise inside the chunk.
                var head = start
                while (head < i - 1 &&
                    !(bytes[head] == PREAMBLE.toByte() && bytes[head + 1] == PREAMBLE.toByte())
                ) {
                    head++
                }
                frames += bytes.copyOfRange(head, i + 1)
                start = i + 1
            }
        }
        return FrameSplit(frames, bytes.copyOfRange(start, bytes.size))
    }

    override fun classifyFrame(frame: ByteArray): FrameClass {
        // Shortest legal frame is 6 bytes (FE FE to from cmd FD).
        if (frame.size < 6) return FrameClass.Junk
        if (frame[0] != PREAMBLE.toByte() || frame[1] != PREAMBLE.toByte()) return FrameClass.Junk
        if (frame.last() != END.toByte()) return FrameClass.Junk
        val to = frame[2].toInt() and 0xFF
        val from = frame[3].toInt() and 0xFF
        val cmd = frame[4].toInt() and 0xFF
        return when {
            from == CONTROLLER -> FrameClass.Echo
            from != civAddress -> FrameClass.Junk
            to != CONTROLLER && to != BROADCAST -> FrameClass.Junk
            cmd == RESULT_OK -> FrameClass.Ack
            cmd == RESULT_NG -> FrameClass.Nak
            cmd == CMD_TRANSCEIVE_FREQ || cmd == CMD_TRANSCEIVE_MODE -> FrameClass.Broadcast
            else -> FrameClass.Reply
        }
    }

    /** `[cmd, data…]` of a well-formed frame addressed to this controller. */
    private fun commandBody(frame: ByteArray): ByteArray? {
        if (frame.size < 6) return null
        if (frame[0] != PREAMBLE.toByte() || frame[1] != PREAMBLE.toByte()) return null
        if (frame.last() != END.toByte()) return null
        val to = frame[2].toInt() and 0xFF
        val from = frame[3].toInt() and 0xFF
        if (from != civAddress || (to != CONTROLLER && to != BROADCAST)) return null
        return frame.copyOfRange(4, frame.size - 1)
    }

    private fun frame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 5)
        out[0] = PREAMBLE.toByte()
        out[1] = PREAMBLE.toByte()
        out[2] = civAddress.toByte()
        out[3] = CONTROLLER.toByte()
        body.forEachIndexed { i, b -> out[4 + i] = b.toByte() }
        out[out.size - 1] = END.toByte()
        return out
    }

    /** 5 BCD bytes, little-endian digit pairs: byte k = digit(2k+1)<<4 | digit(2k). */
    private fun bcdFrequency(hz: Long): IntArray {
        val out = IntArray(5)
        var rest = hz
        for (k in 0 until 5) {
            val lo = (rest % 10).toInt()
            val hi = ((rest / 10) % 10).toInt()
            out[k] = (hi shl 4) or lo
            rest /= 100
        }
        return out
    }

    private fun bcdToHz(data: ByteArray): Long? {
        var hz = 0L
        var scale = 1L
        for (k in 0 until 5) {
            val lo = data[k].toInt() and 0x0F
            val hi = (data[k].toInt() shr 4) and 0x0F
            if (lo > 9 || hi > 9) return null
            hz += lo * scale + hi * scale * 10
            scale *= 100
        }
        return hz
    }

    companion object {
        const val CONTROLLER = 0xE0
        const val BROADCAST = 0x00
        private const val PREAMBLE = 0xFE
        private const val END = 0xFD
        private const val RESULT_OK = 0xFB
        private const val RESULT_NG = 0xFA
        private const val CMD_TRANSCEIVE_FREQ = 0x00
        private const val CMD_TRANSCEIVE_MODE = 0x01
        private const val CMD_READ_FREQ = 0x03
        private const val CMD_READ_MODE = 0x04
        private const val CMD_SET_FREQ = 0x05
        private const val MODE_USB = 0x01
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.IcomCiVTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/packrig/rig/IcomModelSpec.kt rig/src/main/java/net/packrig/rig/IcomCiV.kt rig/src/test/java/net/packrig/rig/IcomCiVTest.kt
git commit -m "feat(rig): IcomCiV protocol core — framing, classification, BCD frequency"
```

---

### Task 4: IcomCiV mode/data/PTT coverage + IcomModels table

**Files:**
- Create: `rig/src/main/java/net/packrig/rig/IcomModels.kt`
- Test: `rig/src/test/java/net/packrig/rig/IcomCiVTest.kt` (append), `rig/src/test/java/net/packrig/rig/IcomModelsTest.kt` (create)

**Interfaces:**
- Consumes: Task 3 `IcomModelSpec`, `DataModeStrategy`, `IcomCiV`.
- Produces: `object IcomModels` with `val IC7300`, `val IC705`, `val IC7100`, `val XIEGU_G90`, `val XIEGU_X6100`, `val GENERIC: IcomModelSpec`.

- [ ] **Step 1: Write the failing tests.** Append to `IcomCiVTest.kt`:

```kotlin
    @Test
    fun pttCommand_isCmd1c00() {
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x01, 0xFD).toList(),
            civ.pttCommand(true).toList(),
        )
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x00, 0xFD).toList(),
            civ.pttCommand(false).toList(),
        )
    }

    @Test
    fun parseModeLabel_readsCmd04Reply() {
        val reply = bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x01, 0x02, 0xFD) // USB, FIL2
        assertEquals("USB", civ.parseModeLabel(reply))
    }

    @Test
    fun dataMode_cmd26Strategy() {
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x26, 0x00, 0x01, 0x01, 0x01, 0xFD).toList(),
            civ.setDataModeCommand().toList(),
        )
        assertEquals("USB-D", civ.dataModeLabel)
    }

    @Test
    fun dataMode_cmd06Plus1aStrategyIsTwoFrames() {
        val old = IcomCiV(spec.copy(dataModeStrategy = DataModeStrategy.CMD_06_PLUS_1A))
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x06, 0x01, 0x01, 0xFD).toList() +
                bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x06, 0x01, 0x01, 0xFD).toList(),
            old.setDataModeCommand().toList(),
        )
    }

    @Test
    fun dataMode_cmd06OnlyStrategyIsPlainUsb() {
        val g90 = IcomCiV(spec.copy(dataModeStrategy = DataModeStrategy.CMD_06_ONLY))
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x06, 0x01, 0x01, 0xFD).toList(),
            g90.setDataModeCommand().toList(),
        )
        assertEquals("USB", g90.dataModeLabel)
    }
```

Create `IcomModelsTest.kt`:

```kotlin
package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IcomModelsTest {

    @Test
    fun addressesMatchCrossCheckedFactoryDefaults() {
        assertEquals(0x94, IcomModels.IC7300.civAddress)
        assertEquals(0xA4, IcomModels.IC705.civAddress)
        assertEquals(0x88, IcomModels.IC7100.civAddress)
        assertEquals(0x70, IcomModels.XIEGU_G90.civAddress)
        assertEquals(0xA4, IcomModels.XIEGU_X6100.civAddress)
    }

    @Test
    fun dataModeStrategiesMatchRigGenerations() {
        assertEquals(DataModeStrategy.CMD_26, IcomModels.IC7300.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_26, IcomModels.IC705.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_06_PLUS_1A, IcomModels.IC7100.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_06_ONLY, IcomModels.XIEGU_G90.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_26, IcomModels.XIEGU_X6100.dataModeStrategy)
    }

    @Test
    fun tuningBoundsGateFt8DialPresets() {
        // HF-only G90 rejects 6 m; VHF/UHF-capable IC-705 accepts 70 cm.
        assertNull(IcomCiV(IcomModels.XIEGU_G90).setFrequencyCommand(50_313_000L))
        assertNotNull(IcomCiV(IcomModels.IC705).setFrequencyCommand(432_174_000L))
        assertNotNull(IcomCiV(IcomModels.IC7300).setFrequencyCommand(50_313_000L))
        assertNull(IcomCiV(IcomModels.IC7300).setFrequencyCommand(144_174_000L))
        assertNotNull(IcomCiV(IcomModels.XIEGU_X6100).setFrequencyCommand(50_313_000L))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.IcomModelsTest"`
Expected: compile error — `IcomModels` unresolved.

- [ ] **Step 3: Implement** — `IcomModels.kt`:

```kotlin
package net.packrig.rig

/**
 * CI-V model table (Phase 4 flagship presets + the generic entry). Every value
 * cross-checked against ≥2 of hamlib / FT8CN / wfview on 2026-07-17; rows and
 * discrepancies tracked in docs/RIG_MODELS.md. All community-verification
 * tier — no CI-V reference hardware on the bench.
 */
object IcomModels {

    val IC7300 = IcomModelSpec(
        name = "Icom IC-7300",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 74_800_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    val IC705 = IcomModelSpec(
        name = "Icom IC-705",
        civAddress = 0xA4,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    // Pre-0x26 generation: data mode via the 0x1A 0x06 subcommand (IC-7100
    // manual; hamlib's capability flags are ambiguous here — community
    // verification adjudicates).
    val IC7100 = IcomModelSpec(
        name = "Icom IC-7100",
        civAddress = 0x88,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_06_PLUS_1A,
    )

    // No data mode at all — FT8 runs in plain USB (FT8CN does the same).
    // Address 0x70 per the Xiegu manual + Radioddity guide + FT8CN.
    val XIEGU_G90 = IcomModelSpec(
        name = "Xiegu G90",
        civAddress = 0x70,
        minFreqHz = 500_000L,
        maxFreqHz = 30_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_06_ONLY,
    )

    // Emulates the IC-705 command set (wfview: "identifies as IC-705").
    val XIEGU_X6100 = IcomModelSpec(
        name = "Xiegu X6100",
        civAddress = 0xA4,
        minFreqHz = 500_000L,
        maxFreqHz = 54_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    /** Backs the generic presets' "Icom CI-V" protocol choice: wide bounds,
     *  modern data command; the profile's address field supplies the address. */
    val GENERIC = IcomModelSpec(
        name = "Icom CI-V (generic)",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.IcomModelsTest" --tests "net.packrig.rig.IcomCiVTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/packrig/rig/IcomModels.kt rig/src/test/java/net/packrig/rig
git commit -m "feat(rig): IcomCiV mode/data/PTT commands and the CI-V model table"
```

---

### Task 5: Descriptor, registry, and profile plumbing for CI-V

**Files:**
- Modify: `rig/src/main/java/net/packrig/rig/RigDescriptor.kt` (factory signature + `civAddress`)
- Modify: `rig/src/main/java/net/packrig/rig/RigRegistry.kt` (5 presets; adapt factories)
- Modify: `rig/src/main/java/net/packrig/rig/CatProtocols.kt` (`ICOM_CIV` entry; factory signature)
- Modify: `rig/src/main/java/net/packrig/rig/RigProfile.kt` (`civAddress: Int?`)
- Modify: `rig/src/main/java/net/packrig/rig/RigProfiles.kt` (resolve address)
- Modify: `rig/src/main/java/net/packrig/rig/RigController.kt:171,205,329` (invoke with address)
- Modify: `app/src/main/java/net/packrig/app/ui/Ft8Bands.kt:47`
- Modify: `app/src/main/java/net/packrig/app/settings/RigProfileJson.kt` (`civAddr` field)
- Test: `rig/src/test/java/net/packrig/rig/RigProfilesTest.kt`, `rig/src/test/java/net/packrig/rig/RigRegistryTest.kt`, plus the app-module JSON test file that covers `RigProfileJson` (locate with `grep -rl RigProfileJson app/src/test`)

**Interfaces:**
- Consumes: Tasks 3–4 `IcomCiV`, `IcomModels`.
- Produces: `RigDescriptor.protocolFactory: ((civAddress: Int?) -> CatProtocol)?` and `RigDescriptor.civAddress: Int? = null`; `CatProtocols.ICOM_CIV = "icom-civ"` with `Entry.factory: (Int?) -> CatProtocol`; `RigProfile.civAddress: Int? = null`; preset ids `"ic7300"`, `"ic705"`, `"ic7100"`, `"xiegu-g90"`, `"xiegu-x6100"`; JSON field `"civAddr"`. Consumers invoke `d.protocolFactory?.invoke(d.civAddress)`.

- [ ] **Step 1: Write the failing tests.** Append to `RigProfilesTest.kt`:

```kotlin
    @Test
    fun civAddress_overrideWinsPresetDefaultFallsThrough() {
        val preset = RigRegistry.byId("ic7300")!!
        assertEquals(0x94, preset.civAddress)
        val defaulted = RigProfiles.resolve(
            RigProfile(id = "p1", name = "My 7300", presetId = "ic7300"),
        )!!
        assertEquals(0x94, defaulted.civAddress)
        val moved = RigProfiles.resolve(
            RigProfile(id = "p2", name = "Moved 7300", presetId = "ic7300", civAddress = 0x76),
        )!!
        assertEquals(0x76, moved.civAddress)
        val protocol = moved.protocolFactory!!.invoke(moved.civAddress) as IcomCiV
        assertEquals(0x76, protocol.civAddress)
    }

    @Test
    fun catGeneric_icomCivProtocolUsesProfileAddress() {
        val resolved = RigProfiles.resolve(
            RigProfile(
                id = "p3", name = "Bench CI-V", presetId = RigRegistry.GENERIC_CAT,
                catProtocolId = CatProtocols.ICOM_CIV, civAddress = 0xA2,
            ),
        )!!
        val protocol = resolved.protocolFactory!!.invoke(resolved.civAddress) as IcomCiV
        assertEquals(0xA2, protocol.civAddress)
    }
```

Append to `RigRegistryTest.kt`:

```kotlin
    @Test
    fun civPresets_shipUnverifiedWithExpectedTransportDefaults() {
        val ids = listOf("ic7300", "ic705", "ic7100", "xiegu-g90", "xiegu-x6100")
        ids.forEach { id ->
            val d = RigRegistry.byId(id) ?: error("missing preset $id")
            assertFalse(d.transportVerified)
            assertNotNull(d.civAddress)
            assertNotNull(d.protocolFactory)
        }
        assertEquals(115_200, RigRegistry.byId("ic7300")!!.defaultBaud)
        assertEquals(19_200, RigRegistry.byId("xiegu-g90")!!.defaultBaud)
        assertEquals(PttMethod.RTS, RigRegistry.byId("xiegu-g90")!!.defaultPtt)
        assertEquals(PttMethod.CAT, RigRegistry.byId("ic7300")!!.defaultPtt)
    }
```

In the `RigProfileJson` test file, add a round-trip case:

```kotlin
    @Test
    fun civAddress_roundTripsAndOmitsWhenNull() {
        val with = RigProfile(id = "a", name = "n", presetId = "ic7300", civAddress = 0x76)
        val without = RigProfile(id = "b", name = "m", presetId = "ft891")
        val decoded = RigProfileJson.decode(RigProfileJson.encode(listOf(with, without)))
        assertEquals(0x76, decoded[0].civAddress)
        assertNull(decoded[1].civAddress)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.packrig.rig.RigProfilesTest"`
Expected: compile error — `civAddress` unresolved on `RigProfile`.

- [ ] **Step 3: Implement.**

`RigDescriptor.kt` — change the factory type and add the address (KDoc the param):

```kotlin
    /** Builds the CAT protocol (param: CI-V bus address, or null for the
     *  model default / non-CI-V families), or null for CAT-less presets
     *  (generic-rts): PTT keys via RTS, every CAT read/write is a fast no-op. */
    val protocolFactory: ((civAddress: Int?) -> CatProtocol)?,
    ...
    /** CI-V bus address this descriptor resolves to; null for non-CI-V rigs. */
    val civAddress: Int? = null,
```

`CatProtocols.kt` — factory takes the address; add the CI-V entry:

```kotlin
    const val YAESU_NEWCAT = "yaesu-newcat"
    const val ICOM_CIV = "icom-civ"

    data class Entry(
        val id: String,
        val displayName: String,
        val factory: (civAddress: Int?) -> CatProtocol,
    )

    val all: List<Entry> = listOf(
        Entry(
            id = YAESU_NEWCAT,
            displayName = "Yaesu CAT",
            factory = { _ -> YaesuCat(YaesuModels.GENERIC) },
        ),
        Entry(
            id = ICOM_CIV,
            displayName = "Icom CI-V (Icom, Xiegu)",
            factory = { addr -> IcomCiV(IcomModels.GENERIC, addr) },
        ),
    )
```

`RigRegistry.kt` — every existing Yaesu factory becomes `{ _ -> YaesuCat(...) }`; the two CAT generics keep delegating to `CatProtocols.byId(...)!!.factory` (types now line up). Insert after the `ftx1` entry, before the generics:

```kotlin
        RigDescriptor(
            id = "ic7300",
            displayName = "Icom IC-7300",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC7300, addr) },
            defaultBaud = 115_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC7300.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ic705",
            displayName = "Icom IC-705",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC705, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC705.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ic7100",
            displayName = "Icom IC-7100",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC7100, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC7100.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "xiegu-g90",
            displayName = "Xiegu G90 (via Digirig)",
            protocolFactory = { addr -> IcomCiV(IcomModels.XIEGU_G90, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            civAddress = IcomModels.XIEGU_G90.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "xiegu-x6100",
            displayName = "Xiegu X6100",
            protocolFactory = { addr -> IcomCiV(IcomModels.XIEGU_X6100, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.XIEGU_X6100.civAddress,
            transportVerified = false,
        ),
```

`RigProfile.kt` — add below `pttMethod` (KDoc: "CI-V bus address override; honored for CI-V presets and CAT generics speaking CI-V"):

```kotlin
    val civAddress: Int? = null,
```

`RigProfiles.resolve` — add to the `copy(...)`:

```kotlin
            civAddress = profile.civAddress ?: preset.civAddress,
```

`RigController.kt` — lines 171, 329: `d.protocolFactory?.invoke(d.civAddress)` / `descriptor?.let { it.protocolFactory?.invoke(it.civAddress) }?.dataModeLabel`; line 205's probe path invokes `factory(d.civAddress)`.

`Ft8Bands.kt:47`:

```kotlin
    val protocol = modelId
        ?.let { RigRegistry.byId(it) }
        ?.let { it.protocolFactory?.invoke(it.civAddress) }
        ?: return Ft8DialPresets
```

`RigProfileJson.kt` — encode: `p.civAddress?.let { o.put("civAddr", it) }`; decode: `civAddress = if (o.has("civAddr")) o.getInt("civAddr") else null`; extend the KDoc's frozen-field list with `"civAddr"`.

Fix any other compile fallout mechanically (`grep -rn "protocolFactory" rig/ app/` — null-checks stay as-is; only `invoke()` call sites change).

No `SettingsBridge` change is needed for the new field: its `SettingsSlice` carries whole `RigProfile` objects (`rigProfiles: List<RigProfile>` in `app/src/main/java/net/packrig/app/controllers/SettingsBridge.kt`), so `civAddress` flows through automatically — verified 2026-07-17, satisfying the Phase 2.5 "mirror every new settings field" lesson.

- [ ] **Step 4: Run both modules**

Run: `./gradlew :rig:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS — including the pre-existing FT-891 byte-equality regression in `RigProfilesTest`.

- [ ] **Step 5: Commit**

```bash
git add rig/src app/src
git commit -m "feat(rig): CI-V presets, icom-civ protocol entry, and the civAddress profile knob"
```

---

### Task 6: Settings UI — protocol dropdown, CI-V address field, baud options

**Files:**
- Modify: `app/src/main/java/net/packrig/app/settings/SettingsRepository.kt:371` (`CAT_BAUD_OPTIONS`)
- Modify: `app/src/main/java/net/packrig/app/settings/RigProfileForm.kt` (visibility + validation rules)
- Modify: `app/src/main/java/net/packrig/app/settings/RigProfileEditor.kt` (dropdown, address field, info row)
- Test: `app/src/test/java/net/packrig/app/settings/RigProfileFormTest.kt`

**Interfaces:**
- Consumes: Task 5 `CatProtocols.ICOM_CIV`, `RigDescriptor.civAddress`, `RigProfile.civAddress`.
- Produces: `RigProfileForm.showsCivAddressField(presetId: String, catProtocolId: String?): Boolean`; `RigProfileForm.civAddressError(text: String, presetId: String, catProtocolId: String?): String?`; `RigProfileForm.parseCivAddress(text: String): Int?`; `RigProfileForm.protocolLabel(presetId: String): String?`. `SettingsRepository.CAT_BAUD_OPTIONS` grows `57600, 115200`.

- [ ] **Step 1: Write the failing tests** — append to `RigProfileFormTest.kt`:

```kotlin
    @Test
    fun civAddressField_showsOnCivPresetsAndCivGenericsOnly() {
        assertTrue(RigProfileForm.showsCivAddressField("ic7300", null))
        assertTrue(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertTrue(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_DIGIRIG, CatProtocols.ICOM_CIV))
        assertFalse(RigProfileForm.showsCivAddressField("ft891", null))
        assertFalse(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_CAT, CatProtocols.YAESU_NEWCAT))
        assertFalse(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_RTS, null))
    }

    @Test
    fun civAddress_parsesTwoHexDigitsInControllerSafeRange() {
        assertEquals(0x94, RigProfileForm.parseCivAddress("94"))
        assertEquals(0xA4, RigProfileForm.parseCivAddress(" a4 "))
        assertNull(RigProfileForm.parseCivAddress("E0")) // reserved for this app
        assertNull(RigProfileForm.parseCivAddress("00"))
        assertNull(RigProfileForm.parseCivAddress("zz"))
        assertNull(RigProfileForm.parseCivAddress("123"))
    }

    @Test
    fun civAddressError_requiredOnGenericsOptionalOnPresets() {
        assertNotNull(RigProfileForm.civAddressError("", RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertNull(RigProfileForm.civAddressError("", "ic7300", null)) // blank = preset default
        assertNotNull(RigProfileForm.civAddressError("xx", "ic7300", null))
        assertNull(RigProfileForm.civAddressError("94", RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertNull(RigProfileForm.civAddressError("anything", "ft891", null)) // field hidden → no error
    }

    @Test
    fun protocolLabel_namesFamilyForNamedCatPresets() {
        assertEquals("Icom CI-V", RigProfileForm.protocolLabel("ic7300"))
        assertEquals("Yaesu CAT", RigProfileForm.protocolLabel("ft891"))
        assertNull(RigProfileForm.protocolLabel(RigRegistry.GENERIC_CAT))   // generics pick their own
        assertNull(RigProfileForm.protocolLabel(RigRegistry.GENERIC_RTS))   // no CAT at all
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.settings.RigProfileFormTest"`
Expected: FAIL — unresolved functions.

- [ ] **Step 3: Implement.** `SettingsRepository.kt:371`:

```kotlin
        val CAT_BAUD_OPTIONS = listOf(4800, 9600, 19200, 38400, 57600, 115200)
```

`RigProfileForm.kt` — add below `showsCatPortPicker` (imports: `CatProtocols`):

```kotlin
    /** The CI-V address field: always on CI-V presets (prefilled, editable —
     *  operators can move the rig's address, and a mismatch is otherwise
     *  undebuggable); on CAT generics only once Icom CI-V is the chosen
     *  protocol, where it is required. */
    fun showsCivAddressField(presetId: String, catProtocolId: String?): Boolean {
        val preset = RigRegistry.byId(presetId) ?: return false
        if (preset.civAddress != null) return true
        return RigRegistry.isCatGeneric(presetId) && catProtocolId == CatProtocols.ICOM_CIV
    }

    /** Two hex digits, 01–DF (E0 is this app's own address on the bus). */
    fun parseCivAddress(text: String): Int? =
        text.trim().takeIf { it.length in 1..2 }?.toIntOrNull(16)?.takeIf { it in 0x01..0xDF }

    fun civAddressError(text: String, presetId: String, catProtocolId: String?): String? {
        if (!showsCivAddressField(presetId, catProtocolId)) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return if (RigRegistry.isCatGeneric(presetId)) {
                "Enter the radio's CI-V address — two hex digits from its menu, e.g. 94"
            } else {
                null // blank keeps the preset's factory default
            }
        }
        return if (parseCivAddress(trimmed) == null) {
            "CI-V address is two hex digits between 01 and DF"
        } else {
            null
        }
    }

    /** Fixed protocol family of a named CAT preset, for the read-only info row.
     *  Null for generics (they pick their own) and CAT-less presets. */
    fun protocolLabel(presetId: String): String? {
        val preset = RigRegistry.byId(presetId) ?: return null
        if (RigRegistry.isGeneric(presetId) || preset.protocolFactory == null) return null
        return if (preset.civAddress != null) "Icom CI-V" else "Yaesu CAT"
    }
```

`RigProfileEditor.kt` — wire the state and fields:

1. New state vars beside `pttMethod`:

```kotlin
    var catProtocolId by remember {
        mutableStateOf(existing?.catProtocolId ?: CatProtocols.YAESU_NEWCAT)
    }
    var civAddressText by remember {
        mutableStateOf(existing?.civAddress?.let { "%02X".format(it) } ?: "")
    }
```

2. `draft()` uses them:

```kotlin
        catProtocolId = if (RigRegistry.isCatGeneric(presetId)) catProtocolId else null,
        civAddress = RigProfileForm.parseCivAddress(civAddressText),
```

3. Beside the existing `nameError` local, compute the address error once and fold it into `canSave`:

```kotlin
    val shownProtocolId = if (RigRegistry.isCatGeneric(presetId)) catProtocolId else null
    val civError = RigProfileForm.civAddressError(civAddressText, presetId, shownProtocolId)
    val canSave = preset != null && nameError == null && civError == null
```

4. On preset change (inside `PresetPicker`'s `onSelect`), reset the new knobs and prefill the address from the preset:

```kotlin
                        catProtocolId = CatProtocols.YAESU_NEWCAT
                        civAddressText = chosen?.civAddress?.let { "%02X".format(it) } ?: ""
```

5. Replace the read-only `CatProtocolPicker()` composable (and its call site) with a real dropdown; delete the "Only Yaesu-protocol radios…" caption:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatProtocolPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = CatProtocols.byId(selectedId)?.displayName ?: selectedId
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("CAT protocol") },
            supportingText = { Text("The command language your radio speaks — check its manual if unsure") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CatProtocols.all.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.displayName) },
                    onClick = { expanded = false; onSelect(entry.id) },
                )
            }
        }
    }
}
```

Call site (replaces the old no-arg call inside the `isCatGeneric` branch):

```kotlin
                    if (RigRegistry.isCatGeneric(presetId)) {
                        CatProtocolPicker(
                            selectedId = catProtocolId,
                            onSelect = { id ->
                                catProtocolId = id
                                civAddressText = ""
                                testResult = null
                            },
                        )
                    }
```

6. Below the protocol picker (generics) / below the name field (named presets), render conditionally:

```kotlin
                    RigProfileForm.protocolLabel(presetId)?.let {
                        Text("Protocol: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (RigProfileForm.showsCivAddressField(presetId, shownProtocolId)) {
                        OutlinedTextField(
                            value = civAddressText,
                            onValueChange = { civAddressText = it.uppercase().take(2) },
                            label = { Text("CI-V address") },
                            isError = civAddressText.isNotEmpty() && civError != null,
                            supportingText = {
                                Text(civError ?: "Two hex digits from the radio's CI-V menu, e.g. 94")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
```

(Local dialog state, saved on confirm — the `SettingsTextField` echo-race pattern applies only to fields bound directly to DataStore, which this is not.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (including any pre-existing baud validation tests — if one pins the old options list, extend it to the new list, don't weaken it).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(app): CI-V address field, real protocol dropdown, and CI-V baud options"
```

---

### Task 7: Docs + community verification template

**Files:**
- Modify: `docs/RIG_MODELS.md`
- Create: `.github/ISSUE_TEMPLATE/civ-rig-report.yml`

**Interfaces:** none (docs only). No test cycle; review is the gate.

- [ ] **Step 1: Extend `docs/RIG_MODELS.md`.** Add the five rows to the model table:

```markdown
| IC-7300  | ic7300  | built-in USB (CP210x) | CAT from manual (CI-V `0x94`, 0x26 data; hamlib+FT8CN cross-check) |
| IC-705   | ic705   | built-in USB          | CAT from manual (CI-V `0xA4`, 0x26 data; hamlib+wfview cross-check) |
| IC-7100  | ic7100  | built-in USB          | CAT from manual (CI-V `0x88`, 0x06+0x1A data — pre-0x26 generation; hamlib flags ambiguous, manual-based) |
| Xiegu G90  | xiegu-g90   | Digirig (CI-V jack)  | CAT from manual (CI-V `0x70` per Xiegu manual/Radioddity/FT8CN — hamlib row differs; no data mode, plain USB) |
| Xiegu X6100 | xiegu-x6100 | built-in USB (CDC-ACM) | CAT from manual (CI-V `0xA4`, emulates IC-705; hamlib+wfview cross-check) |
```

Replace the Phase 4 roadmap bullet's "Neither is implemented yet" framing (Phase 4 is now implemented; Phase 3 remains) and add a section:

```markdown
## Verifying a CI-V rig (community reports)

No CI-V hardware is on the maintainer's bench — these presets are
desk-authored and ship unverified. To flip a row to Verified, open a
"CI-V rig report" issue (template in the tracker) with: rig model +
firmware, connection path (built-in USB / Digirig), the Test CAT result
from the profile editor (Sync / Echo only / Garbage / Silence, with the
baud + address used), whether TX keys, and whether a full FT8 QSO
completed. Echo only usually means the CI-V address doesn't match the
rig's menu. `transportVerified = true` lands with the row flip.
```

- [ ] **Step 2: Create `.github/ISSUE_TEMPLATE/civ-rig-report.yml`:**

```yaml
name: CI-V rig report
description: Field-verification report for an Icom / Xiegu radio
title: "[CI-V] <rig model>: <works / partial / broken>"
labels: ["civ-verification"]
body:
  - type: input
    id: model
    attributes:
      label: Rig model and firmware
      placeholder: IC-7300, firmware 1.42
    validations:
      required: true
  - type: dropdown
    id: connection
    attributes:
      label: Connection path
      options:
        - Built-in USB
        - Digirig / external interface
    validations:
      required: true
  - type: input
    id: settings
    attributes:
      label: Profile settings used
      placeholder: "preset ic7300, baud 115200, CI-V address 94, PTT CAT"
    validations:
      required: true
  - type: dropdown
    id: testcat
    attributes:
      label: Test CAT result
      options:
        - Sync OK
        - Echo only
        - Garbage
        - Silence
    validations:
      required: true
  - type: dropdown
    id: qso
    attributes:
      label: How far did it get?
      options:
        - Full FT8 QSO completed (decode + TX + log)
        - TX keys and decodes arrive, no QSO yet
        - CAT syncs only
        - Nothing works
    validations:
      required: true
  - type: textarea
    id: notes
    attributes:
      label: Notes (echo settings, rig menu values, anything odd)
```

- [ ] **Step 3: Commit**

```bash
git add docs/RIG_MODELS.md .github/ISSUE_TEMPLATE/civ-rig-report.yml
git commit -m "docs: CI-V preset rows and community verification path"
```

---

### Task 8: Full sweep + gates

**Files:** none new — verification only.

- [ ] **Step 1: Full unit sweep**

Run: `./gradlew testDebugUnitTest`
Expected: all modules green. If any task left a pre-existing test modified (other than sanctioned appends), that is a parity violation — fix the production code, not the test.

- [ ] **Step 2: Assemble both variants**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify with the project verify skill** (drive the app if a device/emulator is available: add a CI-V profile, watch Test CAT against no hardware report Silence with the new copy paths intact; confirm a Yaesu profile still Syncs if the bench rig is attached).

- [ ] **Step 4: Record the release gates in the PR/branch notes (do NOT claim them done):**
  - [ ] **HARD MERGE GATE:** FT-891 + FTX-1 bench re-verification on the frame layer — CAT sync, TX key, Test CAT spot-checks (owner hardware).
  - [ ] Community gate (non-blocking): CI-V presets remain "CAT from manual" until tester reports arrive.

- [ ] **Step 5: Finish the branch** per `superpowers:finishing-a-development-branch` (unstable is the integration target; direct push to `unstable` is allowed, but the hardware gate above precedes any merge).
