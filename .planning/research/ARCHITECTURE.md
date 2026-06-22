# Architecture Research — Controller Split + Coroutine Migration

**Domain:** Android amateur-radio transceiver — brownfield refactor of a Compose ViewModel orchestrator (FT8VC v1.x)
**Researched:** 2026-06-21
**Confidence:** HIGH for the controller boundary shape, the `combine + distinctUntilChanged` flow assembly, and the dedicated-single-thread-dispatcher choice for JNI/USB-serial (all anchored to PITFALLS.md and the in-code roadmap at `OperateViewModel.kt:66-81`). MEDIUM on the exact test-seam interface sketches (these are starting shapes that will need ~1 commit each to fit; the names and granularity are right). HIGH on the build order — it falls naturally out of the current import graph.

This document answers "what does the post-refactor architecture look like, and in what order do we get there without breaking the rig?" It assumes the reader has read `.planning/codebase/ARCHITECTURE.md` (current shape) and `.planning/research/PITFALLS.md` (the prescriptions this document operationalizes).

---

## 1. Standard Architecture (Post-Refactor Target)

### System Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       Compose UI (unchanged surface)                      │
│   OperateScreen · SpectrumScreen · LogScreen · SettingsScreen             │
│   collects ONE StateFlow<OperateUiState> via collectAsStateWithLifecycle  │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │ state
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  OperateViewModel  (thin orchestrator, ~250 LOC target, was 1135)        │
│  • Constructs 5 controllers, supplies SupervisorJob + dispatchers        │
│  • Assembles UI state:                                                   │
│      combine(s.flow, r.flow, d.flow, t.flow, q.flow) { … OperateUiState }│
│        .distinctUntilChanged()                                           │
│        .stateIn(viewModelScope, WhileSubscribed(5_000), Initial)         │
│  • Routes UI intents (startCq, abandonQso, setRigFrequency …) to the     │
│    owning controller — no business logic.                                │
│  • Owns SharedFlow<SnackbarEvent> (controllers emit; VM forwards to UI). │
└────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┘
     │             │             │             │             │
     ▼             ▼             ▼             ▼             ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────────────┐
│Settings  │ │ RigSess  │ │  DecodeCtrl  │ │  TxOrch  │ │ QsoSessionCtrl   │
│Bridge    │ │          │ │              │ │          │ │                  │
│          │ │ CAT poll │ │ slot collect │ │ encode + │ │ owns QsoMachine, │
│Settings  │ │ +dial/   │ │ + JNI decode │ │ playback │ │ auto-seq, parity,│
│Repo →    │ │ mode/PTT │ │ + decode-row │ │ + PTT    │ │ abandon counter, │
│ui slice  │ │ via Rig  │ │ list (cap)   │ │ try/fin  │ │ resume opps      │
│          │ │ Backend  │ │              │ │ watchdog │ │                  │
│Flow<Slc> │ │ Flow<Slc>│ │ Flow<Slice>  │ │ Flow<Slc>│ │ Flow<Slice>      │
└────┬─────┘ └────┬─────┘ └──────┬───────┘ └────┬─────┘ └────────┬─────────┘
     │            │              │              │                │
     │            │              │              │                │
     │     ┌──────┴────────┐     │       ┌──────┴──────────┐     │
     │     ▼               │     │       ▼                 │     │
     │  catDispatcher   pttCmd   │   txDispatcher      pttCmd    │
     │  (newSingleThread)  │     │   (newSingleThread)   │       │
     │     │               │     │       │               │       │
     │     ▼               ▼     │       ▼               ▼       │
     │  ┌──────────────────────┐ │   ┌──────────────────────┐    │
     │  │  RigBackend (iface)  │ │   │ AudioOutput (iface)  │    │
     │  │  • DigirigRigBackend │ │   │ • UsbAudioPlayback   │    │
     │  │  • NoOpRigBackend    │ │   │ • FakeAudioOutput    │    │
     │  │  • FakeRigBackend ←──┼─┘   │   (PITFALLS Pitfall 10)
     │  └──────────────────────┘ │   └──────────────────────┘    │
     │                           │                               │
     │                           ▼                               │
     │                  ┌────────────────────┐                   │
     │                  │ decodeDispatcher   │                   │
     │                  │ (newSingleThread)  │                   │
     │                  │  + SlotCollector   │                   │
     │                  │  + Ft8Native (JNI) │                   │
     │                  └─────────┬──────────┘                   │
     │                            │ decodes                      │
     │                            └───────► QsoSessionCtrl ◄─────┘
     │                            (DecodeCtrl pushes decodes into QSO via
     │                             a SharedFlow<List<QsoDecode>> — pull,
     │                             not push-and-couple. See §5.)
     ▼
┌──────────────────────────────────┐
│ AudioInput (iface)               │
│ • UsbAudioCapture                │
│ • FakeAudioInput                 │
└──────────────────────────────────┘
```

### Component Responsibilities

| Component | Owns (state) | Owns (I/O) | Drives |
|-----------|--------------|------------|--------|
| **SettingsBridge** | Settings UI slice (`myCall`, `myGrid`, `txEnabled`, `txSlotParity`, `pttPreference`, all toggles) | Reads `SettingsRepository.settings` Flow; writes via suspend setters | Other controllers' configuration changes (one-way; settings is a source, not a sink) |
| **RigSession** | `catBusy`, `catStatus`, `rigFreqHz`, `rigMode`, `pttKeyed` (mirror), `digirigPresent` | `RigBackend` (CAT serial reads/writes, PTT key/release), USB device discovery, `AudioDeviceCallback` for hot-plug | CAT polling loop; serves PTT key/release commands for `TxOrchestrator` |
| **DecodeController** | `decodes: ImmutableList<DecodeRow>`, `lastSlotDecodeCount`, `levelDbfs`, `clip`, `waterfallVersion`, `decodeFailureCount` | `UsbAudioCapture` (RX frames), `SlotCollector`, `SpectrumProcessor`, `Waterfall`, `Ft8Native.decode` (JNI) | Emits `Flow<List<QsoDecode>>` for `QsoSessionController` to consume |
| **TxOrchestrator** | `isTransmitting`, `txStatus`, `txGuard` (watchdog state) | `Ft8Native.encode` (JNI), `UsbAudioPlayback`, PTT (via `RigSession`) | One TX per slot; defends against PTT-stuck (4-layer per PITFALLS Pitfall 3) |
| **QsoSessionController** | `qsoActive`, `qsoState` label, `qsoDx`, `operateTxText/Form/Step`, `qsoTxParity`, `abandonedPartners`, `isOperating`, `slotIndex`, `secondsToNextSlot`, `utcClock` | None directly — composes `QsoMachine` + slot clock; sends TX requests to `TxOrchestrator`; consumes decodes from `DecodeController` | Auto-seq, answer-when-called, auto-answer-CQ, abandon-on-no-reply |
| **OperateViewModel** (residual) | Combined `OperateUiState` (assembled from slices); `SharedFlow<SnackbarEvent>` | None | Lifecycle: starts/stops controllers in `init`/`onCleared`; routes UI intents to owners |

**Sizing target:** `OperateViewModel` shrinks from 1,135 LOC to ~250 LOC (constructor + lifecycle + intent dispatch + `combine` block + snackbar fanout). Each controller should land in the 200–400 LOC band; if any exceeds 500 it's hiding a sub-concern.

---

## 2. Recommended Project Structure

```
app/src/main/java/net/ft8vc/app/
├── OperateViewModel.kt              # thin orchestrator (~250 LOC)
├── OperateUiState.kt                # unchanged: single screen state DTO
├── controllers/                     # NEW package
│   ├── SettingsBridge.kt
│   ├── RigSession.kt
│   ├── DecodeController.kt
│   ├── TxOrchestrator.kt
│   ├── QsoSessionController.kt
│   ├── ControllerScope.kt           # SupervisorJob + dispatcher provider
│   └── slices/                      # per-controller flow slice types
│       ├── SettingsSlice.kt
│       ├── RigSlice.kt
│       ├── DecodeSlice.kt
│       ├── TxSlice.kt
│       └── QsoSlice.kt
└── ui/                              # unchanged

app/src/test/java/net/ft8vc/app/
└── controllers/                     # NEW
    ├── SettingsBridgeTest.kt
    ├── RigSessionTest.kt
    ├── DecodeControllerTest.kt
    ├── TxOrchestratorTest.kt
    ├── QsoSessionControllerTest.kt
    └── golden/
        ├── GoldenTraceTest.kt       # cross-controller integration
        └── traces/
            └── cq-answer-73.json    # canonical QSO trace

audio/src/main/java/net/ft8vc/audio/
├── AudioEngine.kt                   # existing interface, untouched
├── AudioInput.kt                    # NEW: narrow capture interface (test seam)
├── AudioOutput.kt                   # NEW: narrow playback interface (test seam)
├── UsbAudioCapture.kt               # now implements AudioInput
├── UsbAudioPlayback.kt              # now implements AudioOutput
└── fake/
    ├── FakeAudioInput.kt            # NEW: drives synthetic slot samples
    └── FakeAudioOutput.kt           # NEW: records "playBlocking" calls

rig/src/main/java/net/ft8vc/rig/
├── RigBackend.kt                    # existing interface, untouched
├── RigController.kt                 # narrowed; only USB plumbing
├── DigirigRigBackend.kt             # unchanged
└── fake/
    └── FakeRigBackend.kt            # NEW: simulates CAT timeout, USB detach

ft8-native/src/main/java/net/ft8vc/ft8native/
├── Ft8Native.kt                     # unchanged JNI surface
└── Ft8Decoder.kt                    # NEW: thin Kotlin facade with cancellation
                                     # check + version probe (PITFALLS Sec.6)
```

### Structure Rationale

- **`controllers/` is a peer of `ui/`, not nested in it.** Controllers are not UI; they are domain orchestrators. Keeping them flat with `OperateViewModel.kt` mirrors the in-code roadmap (lines 66–81) verbatim and minimizes import churn.
- **`slices/` co-located with controllers.** Each controller exposes one slice type; keeping slices next to their producers keeps "who emits what" obvious. The slices are pure data classes (`@Immutable`) — required for Compose stability (PITFALLS Pitfall 6).
- **`fake/` packages alongside production implementations.** Test fakes are first-class artifacts (PITFALLS Pitfall 10: "Establish the `FakeRigBackend` early — every controller needs it"). Putting them in production source-sets under a `fake/` subpackage lets JVM tests, instrumented tests, and manual harnesses all consume them without a `testFixtures` plugin.
- **`AudioInput` / `AudioOutput` are NEW narrow interfaces, not refactors of `AudioEngine`.** The existing `AudioEngine` interface in `audio/AudioEngine.kt` is bound to a specific capture+playback contract; the new interfaces are the test seams the controllers depend on. Adapters wrap `UsbAudioCapture`/`UsbAudioPlayback`.

---

## 3. Architectural Patterns

### Pattern 1: Controller-with-State-Slice (the controller boundary shape)

**What:** Each extracted controller is a regular class (not an Android `ViewModel`) that:
1. Takes a `ControllerScope` (a `CoroutineScope` backed by `SupervisorJob` + the controller's dedicated dispatcher) in its constructor — never `viewModelScope` directly.
2. Owns one or more `MutableStateFlow` private fields; exposes them as `StateFlow` (read-only) on its API surface.
3. Exposes intent-style suspend functions or non-suspending `submit*` methods for UI actions — never raw `update` access to its state.
4. Emits cross-controller events through `SharedFlow`/`Channel`, not direct method calls on siblings. The ViewModel wires the channels.

**When to use:** Any time the orchestrator's surface area exceeds ~5 distinct concerns. (FT8VC has 5 → exactly this pattern.)

**Trade-offs:**
- **Pro:** Each controller is unit-testable with mocked platform boundaries only (PITFALLS Pitfall 5 mandate satisfied).
- **Pro:** Concurrency is per-controller — a stuck CAT call cannot starve the decode loop.
- **Con:** Slightly more boilerplate at the seams (the `combine` block, the channel wiring). One-time cost.
- **Con:** A naïve implementation gets the Compose recomposition storm (PITFALLS Pitfall 6); the `distinctUntilChanged` pattern (Pattern 2) is non-optional.

**Example shape (DecodeController):**
```kotlin
class DecodeController(
    private val scope: CoroutineScope,                     // SupervisorJob + Main
    private val decodeDispatcher: CoroutineDispatcher,     // dedicated single-thread
    private val audioInput: AudioInput,                    // test seam
    private val decoder: Ft8Decoder,                       // test seam (JNI wrapper)
    private val spectrum: SpectrumProcessor,
    private val waterfall: Waterfall,
) {
    private val _slice = MutableStateFlow(DecodeSlice.Initial)
    val slice: StateFlow<DecodeSlice> = _slice.asStateFlow()

    private val _decodesOut = MutableSharedFlow<DecodeBatch>(extraBufferCapacity = 8)
    val decodesOut: SharedFlow<DecodeBatch> = _decodesOut.asSharedFlow()

    fun start(deviceId: Int?) { /* launch on scope, route frames via onFrames */ }
    fun stop()                  { /* cancel children, release AudioRecord */ }
    fun clearDecodes()          { /* atomic update on _slice */ }
    // No direct method called by siblings; all comms via flows.
}
```

This shape — `(scope, dispatcher, seam, seam, …) -> StateFlow slice + outbound SharedFlow + suspend/intent fns` — is the **Now in Android (NIA) / Cash App Molecule** convention for non-ViewModel domain controllers. See *Now in Android architecture guide* (developer.android.com/topic/architecture, 2024+) for the "data layer / domain layer / UI layer" pattern this generalizes; FT8VC has no separate domain layer today because everything is in the ViewModel — extracting controllers *is* the introduction of one.

### Pattern 2: `combine` + `distinctUntilChanged` flow assembly (state composition)

**What:** The orchestrator (residual `OperateViewModel`) is the *only* place that combines slices into the screen-shaped `OperateUiState`. It does so with one `combine` operator, gated by `distinctUntilChanged`, materialized with `stateIn(WhileSubscribed(5_000))`.

**When to use:** Always, when multiple `StateFlow`s feed a single Compose screen.

**Trade-offs:**
- **Pro:** Compose recomposes on screen-state change, not on slice change.
- **Pro:** `WhileSubscribed(5_000)` keeps state warm across config changes without burning battery when the screen is gone.
- **Con:** A 6-arity `combine` (5 slices + a tick flow) needs `combine(a, b, c, d, e) { ... }` with the 5-arg overload; for >5 use `combine(listOf(a, b, c, d, e, f)) { arr -> ... }`. Both exist; the typed 5-arg form is preferred.

**Example (residual OperateViewModel):**
```kotlin
val state: StateFlow<OperateUiState> = combine(
    settingsBridge.slice,
    rigSession.slice,
    decodeController.slice,
    txOrchestrator.slice,
    qsoSession.slice,
) { s, r, d, t, q ->
    OperateUiState(
        // settings slice
        myCall = s.myCall, myGrid = s.myGrid, txEnabled = s.txEnabled,
        autoSeqEnabled = s.autoSeqEnabled, pttPreference = s.pttPreference,
        // rig slice
        rigFreqHz = r.rigFreqHz, rigMode = r.rigMode,
        catBusy = r.catBusy, catStatus = r.catStatus,
        // decode slice
        decodes = d.decodes, levelDbfs = d.levelDbfs,
        clip = d.clip, waterfallVersion = d.waterfallVersion,
        // tx slice
        isTransmitting = t.isTransmitting, txStatus = t.txStatus,
        // qso slice
        isOperating = q.isOperating, qsoActive = q.qsoActive,
        operateTxText = q.operateTxText, slotIndex = q.slotIndex,
        secondsToNextSlot = q.secondsToNextSlot, utcClock = q.utcClock,
        isTxSlot = q.isTxSlot, activeTxSlotParity = q.activeTxSlotParity,
        // … unchanged: contactCount fed by a separate logbook flow
    )
}.distinctUntilChanged()
 .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperateUiState())
```

**Stability requirement (PITFALLS Pitfall 6):** every slice must be a `@Immutable` data class, and `decodes` must be an `ImmutableList<DecodeRow>` (kotlinx.collections.immutable). Without this, `distinctUntilChanged` works at the `OperateUiState` level but Compose still recomposes children unnecessarily.

### Pattern 3: Dedicated single-thread dispatcher per blocking-I/O concern (threading model)

**What:** Each concern that owns a blocking platform call (JNI decode, JNI encode, USB-serial CAT) gets its own `CoroutineDispatcher` backed by a single `Executors.newSingleThreadExecutor()`. The controller wraps every blocking call in `withContext(thatDispatcher) { … }`. `Dispatchers.IO` is reserved for non-JNI, non-USB-serial blocking work (Room writes, file I/O for ADIF export).

**When to use:** Any time you have a blocking call that (a) is not cancellable via `Thread.interrupt()` or (b) requires thread affinity. JNI calls satisfy both; USB-serial reads satisfy (a).

**Trade-offs:**
- **Pro:** Total ordering is preserved within each concern — eliminates the race PITFALLS Pitfall 2 describes.
- **Pro:** Dispatcher-level serialization replaces the old single-thread executor + lock pattern with no behavior change.
- **Con:** Cooperative cancellation does not interrupt the *current* in-flight blocking call. Mitigation: `join()` the previous job before launching the next; add a Kotlin-side `isCanceled` gate before the JNI invocation drops queued-but-not-started work fast (PITFALLS Pitfall 2 mitigations).
- **Con:** Each dispatcher costs one thread for the app's lifetime. Three dispatchers × one thread = 3 threads. Acceptable.

**Dispatcher inventory:**

| Dispatcher | Concern | Why dedicated |
|------------|---------|---------------|
| `decodeDispatcher` | `Ft8Native.decode` calls inside `DecodeController` | JNI must be serialized; PITFALLS Pitfall 2 |
| `encodeDispatcher` | `Ft8Native.encode` calls inside `TxOrchestrator` | Same as above; separate from decode so a long decode cannot delay TX encode |
| `catDispatcher` | All `RigBackend` calls (CAT reads/writes, PTT key/release) inside `RigSession` | USB-serial `read()` ignores `Thread.interrupt()`; PITFALLS Pitfall 9 |
| `Dispatchers.IO` | Room writes (logbook), ADIF file I/O, `DataStore` writes | Standard Android idiom; no thread affinity required |
| `Dispatchers.Main` | StateFlow emission, snackbar fanout | Compose collection point |

**Construction pattern:**
```kotlin
internal class ControllerDispatchers : AutoCloseable {
    private val decodeExec = Executors.newSingleThreadExecutor { Thread(it, "ft8vc-decode") }
    private val encodeExec = Executors.newSingleThreadExecutor { Thread(it, "ft8vc-encode") }
    private val catExec    = Executors.newSingleThreadExecutor { Thread(it, "ft8vc-cat") }
    val decode: CoroutineDispatcher = decodeExec.asCoroutineDispatcher()
    val encode: CoroutineDispatcher = encodeExec.asCoroutineDispatcher()
    val cat:    CoroutineDispatcher = catExec.asCoroutineDispatcher()
    override fun close() {
        decodeExec.shutdownNow(); encodeExec.shutdownNow(); catExec.shutdownNow()
    }
}
```
Owned by `OperateViewModel`, closed in `onCleared`. The `.asCoroutineDispatcher()` from `kotlinx-coroutines` keeps the executor alive while the dispatcher is referenced.

**On `newSingleThreadContext` vs `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`:** Both achieve thread affinity. We prefer the executor-backed form because (a) `newSingleThreadContext` is `@DelicateCoroutinesApi` and explicitly warns about resource management, (b) the executor form gives us `shutdownNow()` semantics that match the existing `onCleared` pattern (line 1122–1123), (c) we can name the thread explicitly for stack-trace clarity.

### Pattern 4: QSO loop as a coroutine on a dedicated dispatcher (replaces `Thread.sleep` loop)

**What:** The current `qsoThread` (`Thread.sleep`-based loop, lines 749–798) becomes a coroutine launched on a fourth dedicated dispatcher (`qsoDispatcher`) owned by `QsoSessionController`. `Thread.sleep` → `delay`. `Thread.interrupt` → coroutine `cancel`. The `qsoLock` disappears because the dispatcher serializes access to `QsoMachine`.

**When to use:** This is the prescribed replacement for the only `Thread.sleep`+`@Volatile`+`synchronized` cluster in the codebase.

**Trade-offs:**
- **Pro:** Removes the `qsoLock` / `@Volatile qsoRunning` / `@Volatile inputGain` mixed pattern (CONCERNS.md "Volatile + Synchronized Mixed Pattern").
- **Pro:** Rapid start/stop is safe: `previousJob?.cancelAndJoin()` before launching the next, satisfying PITFALLS Pitfall 2.
- **Con:** Must preserve the ordering invariant the lock accidentally enforced (PITFALLS Pitfall 7). Document it in the PR.

**Sketch:**
```kotlin
class QsoSessionController(
    private val scope: CoroutineScope,
    private val qsoDispatcher: CoroutineDispatcher,  // dedicated single-thread
    private val txOrchestrator: TxOrchestrator,
    private val decodesIn: SharedFlow<DecodeBatch>,  // from DecodeController
    // …
) {
    private var loopJob: Job? = null
    private var machine: QsoMachine? = null  // mutated only on qsoDispatcher

    fun startCq(/* … */) {
        scope.launch {
            loopJob?.cancelAndJoin()           // PITFALLS Pitfall 2
            loopJob = scope.launch(qsoDispatcher) {
                val m = newMachine().also { machine = it; it.startCq() }
                runQsoLoop(m)
            }
        }
    }

    private suspend fun runQsoLoop(m: QsoMachine) {
        while (currentCoroutineContext().isActive) {
            val wait = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
            if (wait > 0) delay(wait)                 // ← Thread.sleep → delay
            if (!currentCoroutineContext().isActive) break
            val slotStart = SlotTiming.slotStart(System.currentTimeMillis())
            val parity = currentTxParity()
            val ourTx = SlotTiming.slotIndexInMinute(slotStart) % 2 == parity
            if (!ourTx) continue
            delay(QSO_TX_GRACE_MS)
            val message = m.txMessage() ?: break
            txOrchestrator.transmit(message)          // suspend; await TX end
            m.recordTransmitted()
            if (m.noReplyLimitExceeded(maxUnansweredCycles)) {
                abandonForNoReply(); break
            }
            if (m.state == QsoState.Complete) { handleComplete(m); break }
        }
    }

    init {
        // Decode-driven advance, on the SAME dispatcher → no lock needed
        scope.launch(qsoDispatcher) {
            decodesIn.collect { batch -> advanceFromDecode(batch) }
        }
    }
}
```

`actor` vs single-thread dispatcher: PITFALLS Pitfall 7 specifically warns against the `actor` pattern here because `actor` serializes message processing but does not guarantee ordering between *posting* and *processing* across different posters. The single-thread dispatcher gives us total ordering of all access (TX, decode-advance, settings change) for free.

### Pattern 5: PTT defense-in-depth (RF safety contract)

**What:** Four independent layers protecting against a stuck PTT, owned entirely by `TxOrchestrator`:

1. **`try-finally`** around every `keyPtt()` → `releasePtt()` pair (already in code, line 836–840).
2. **`AutoCloseable` TX session** — `TxOrchestrator.transmit` opens a `TxSession` (`use { … }`) whose `close()` calls `rig.releasePtt()`. Structured concurrency cancellation propagates through `use`.
3. **`withTimeoutOrNull(SLOT_DURATION_MS + 500)`** wrapping the whole TX block — if still keyed past slot end, force release.
4. **Watchdog coroutine** in `TxOrchestrator`, ticking every 250 ms: if `pttKeyed` AND not in active TX slot → force release + emit `TXSAFETY` status.

Plus: `onCleared()` on `TxOrchestrator` calls `rig.releasePtt()` unconditionally — flag value cannot be trusted (PITFALLS Pitfall 3).

**When to use:** This pattern is non-optional for any RF-keying code path. It is the single most important architectural invariant in the milestone.

**Trade-offs:** Layered redundancy looks like over-engineering until the day a callback throws on a non-orchestrator thread and a 50 W carrier sits on a band for 30 s. Then it looks like the minimum.

---

## 4. Data Flow

### Flow A: Incoming Decode (the RX path)

**Before refactor:** `UsbAudioCapture` → `onFrames` (in ViewModel) → `slotCollector.add { samples → decodeExecutor.execute { decodeSlot } }` → `Ft8Native.decode` → `_state.update { decodes = combined }` AND `qso?.onDecodes(...)` under `synchronized(qsoLock)` (lines 972–1068).

**After refactor:**
1. `UsbAudioCapture` (in `AudioInput`) delivers frames to `DecodeController.onFrames(frames)` (controller scope, audio callback thread).
2. `DecodeController` performs DSP (`spectrum.process`, level RMS) inline (light work) and hands raw PCM to `slotCollector`.
3. On slot boundary, `slotCollector` callback launches `scope.launch(decodeDispatcher) { decodeSlot(samples, slotStart) }`.
4. `decodeSlot` calls `Ft8Decoder.decode(samples)` (Kotlin facade wrapping `Ft8Native`, with cancellation gate + version probe).
5. `DecodeController` updates `_slice` (appends `DecodeRow`s, caps at `MAX_DECODE_ROWS`, increments `waterfallVersion`).
6. `DecodeController` also emits `DecodeBatch(slotParity, slotStart, decodes)` on `_decodesOut` (SharedFlow).
7. `QsoSessionController` collects `decodesIn` on `qsoDispatcher` → `machine.onDecodes(...)` → publish updated QSO slice. **No lock; the dispatcher provides ordering.**
8. The `combine` block in `OperateViewModel` re-runs (because `decodeSlice` AND `qsoSlice` changed); `distinctUntilChanged` lets the recomposition through; Compose updates `DecodeListPanel` and the QSO status row in one frame.

**Cross-cutting question — who updates state for "decode arrives during a TX slot"?**
- The decode row itself is owned by `DecodeController` — it adds the row to its slice regardless of TX state. Decode rendering and TX state are independent.
- The QSO-advance decision (does this decode advance us to `SendingRReport`?) is owned by `QsoSessionController` — it consumes the `DecodeBatch` and applies it to the `QsoMachine`.
- The `isTransmitting` flag is owned by `TxOrchestrator` — `QsoSessionController` does **not** read it before processing decodes; the `QsoMachine` is purely message-driven, and per-state-machine semantics it's fine to receive decodes during TX (the state advance just queues for next slot).

This is the seam that the monolith blurred: decode-row append (UI concern), QSO advance (domain concern), and TX state (orchestration concern) were all wired through `_state.update` in `decodeSlot`. Split, they become three independent emissions that the `combine` reassembles.

### Flow B: Outgoing TX Cycle (QSO mode)

**Before refactor:** `qsoThread` (lines 749–798) wakes on slot boundary, calls `transmitMessageNow` (lines 827–848) which stops capture, keys PTT, blocks on `playback.playBlocking`, releases PTT in finally, restarts capture.

**After refactor:**
1. `QsoSessionController`'s loop coroutine (on `qsoDispatcher`) calls `delay(millisUntilNextSlot)`.
2. If our TX slot, `delay(QSO_TX_GRACE_MS)`.
3. Calls `txOrchestrator.transmit(message)` — a **suspend function** that returns when TX is complete (or cancelled).
4. `TxOrchestrator.transmit(message)`:
   a. `_slice.update { copy(isTransmitting = true, txStatus = "TX: $message") }`.
   b. Opens a `TxSession(rigSession).use { session →`
   c. `withContext(encodeDispatcher) { Ft8Native.encode(message, …) }` → pcm.
   d. `decodeController.suspendCapture()` (suspend on `decodeDispatcher`; ensures AudioRecord is fully stopped).
   e. `withTimeoutOrNull(SLOT_DURATION_MS + 500) { session.keyAndPlay(pcm) }`.
   f. `session.close()` → `rigSession.releasePtt()` (idempotent — see Pattern 5).
   g. `_slice.update { copy(isTransmitting = false, txStatus = sentLabel) }`.
   h. `decodeController.resumeCapture()`.
5. Watchdog coroutine in `TxOrchestrator` was running the whole time; if PTT is still keyed 500 ms past slot end without an active session, it forces release.
6. `QsoSessionController` resumes its loop coroutine; calls `machine.recordTransmitted()` (still on `qsoDispatcher`, no lock); publishes the new QSO slice.
7. `combine` re-runs with new TX slice + new QSO slice; UI updates.

**`stopCapture`/`resumeCapture` coordination:** `DecodeController` exposes `suspendCapture()`/`resumeCapture()` as suspend functions that internally serialize on `decodeDispatcher`. `TxOrchestrator` awaits them — no race between TX start and an in-flight decode.

### Flow C: CAT Frequency Change (user taps band preset)

**Before refactor:** `setRigFrequency(hz)` → `runCat("Tuning…") { … }` → `catExecutor.execute { … }`, callback updates state, persists to settings (lines 451–462, 480–491).

**After refactor:**
1. UI calls `viewModel.setRigFrequency(hz)`.
2. ViewModel routes to `rigSession.setFrequency(hz)` (suspend).
3. `RigSession.setFrequency(hz)`:
   a. `_slice.update { copy(catBusy = true, catStatus = "Tuning…") }`.
   b. `val ok = withTimeoutOrNull(5_000) { withContext(catDispatcher) { backend.setFrequencyHz(hz) } }`.
   c. Inner driver-level timeout: the `serialPort.read(buf, 4_000)` driver-level timeout is set < 5_000 (PITFALLS Pitfall 9).
   d. On timeout: close + reopen the serial port; increment `consecutiveTimeouts`; if ≥3, emit `SnackbarEvent("CAT unreachable — check Digirig")` and back off.
   e. On success: read back via `backend.frequencyHz()`, update slice, write to `SettingsRepository.setLastDialFreqHz`.
   f. `finally`: `_slice.update { copy(catBusy = false) }`.
4. The `combine` block sees the rig slice change; UI updates the freq display and busy chip.

The flow that used to require an Executor + try/catch + nested coroutine launch is now a single suspend chain inside `RigSession`, with all three required guards (driver timeout, coroutine timeout, port-reopen-on-failure) explicit.

### Flow D: USB Disconnect Mid-TX (the safety scenario, PITFALLS Pitfall 4)

1. `ACTION_USB_DEVICE_DETACHED` broadcast arrives at `RigSession`'s `AudioDeviceCallback` and USB receiver (registered in `init`).
2. `RigSession.onDeviceDetached()` → calls `txOrchestrator.emergencyHalt()` and own teardown.
3. `TxOrchestrator.emergencyHalt()`:
   a. Cancels active TX coroutine (its `use { }` block fires `releasePtt`).
   b. Calls `rig.releasePtt()` again, unconditionally (idempotent — Pattern 5).
   c. Updates slice: `isTransmitting = false`, `txStatus = "EMERGENCY HALT — USB"`.
4. `QsoSessionController.emergencyHalt()`: cancels loop, clears `qsoActive`, snackbar "Digirig disconnected — RX only".
5. `RigSession` updates slice: `digirigPresent = false`, `catStatus = "Disconnected"`.
6. Reconnect handler does **not** auto-resume — requires explicit user action (re-open Operate or tap "Reconnect"); license-ack gate is re-evaluated.

This flow is impossible to express atomically in the current monolith because there's no single owner for "is the rig actually ready right now." With `RigSession` owning that question and `TxOrchestrator` honoring `emergencyHalt`, the cross-controller contract is one suspend call.

---

## 5. Suggested Build Order

Extraction sequence aligns with PITFALLS Pitfall 1 (one controller per commit, lowest coupling first). Each phase ends with a real-rig verification gate before the next begins.

### Phase 0 — Foundations (pre-extraction setup)

**Goal:** No controller extracted yet, but the rails are laid.

**Work:**
1. Add `controllers/` and `controllers/slices/` packages (empty).
2. Add `audio/AudioInput.kt`, `audio/AudioOutput.kt` interfaces; have `UsbAudioCapture`/`UsbAudioPlayback` implement them (no behavior change).
3. Add `rig/fake/FakeRigBackend.kt`, `audio/fake/FakeAudioInput.kt`, `audio/fake/FakeAudioOutput.kt` (synthetic implementations).
4. Add `ft8native/Ft8Decoder.kt` — thin Kotlin facade around `Ft8Native` with version probe (refuses to operate if `Ft8Native.loaded == false`) and cancellation gate.
5. Add `ControllerDispatchers` class (idle — no one uses it yet).
6. Add golden-trace fixture: record a 5-minute decode/TX session on the reference rig; commit as `.planning/field-sessions/baseline-trace.json`.
7. Author `GoldenTraceTest` skeleton that replays the trace through a future `combine`-assembled state (currently asserting on monolith output as the baseline).

**Verification gate (real rig):** App still boots, RX still arrives, TX still keys. No behavior change expected — this commit only introduces unreferenced interfaces and fakes.

**Why first:** Nothing in this phase changes runtime behavior. It de-risks every subsequent extraction by making the test seams available before the first controller needs them. PITFALLS Pitfall 10 mandates `FakeRigBackend` exist "by end of Phase 1" — we push it to Phase 0 to be safe.

### Phase 1 — Extract `SettingsBridge` (lowest coupling)

**Goal:** Settings reading moves out of `OperateViewModel.init` into a standalone controller.

**Work:**
1. Create `SettingsBridge(scope, settingsRepo)`; emits `Flow<SettingsSlice>`.
2. `OperateViewModel.init` no longer collects `settingsRepo.settings` directly; instead constructs `SettingsBridge` and merges its slice into `_state` (still using the old `_state.update` pattern — `combine` arrives in Phase 5).
3. Move `stationIdentityChanged`, `refreshOperateTxFromStation` triggers to `SettingsBridge` (it emits a `stationIdentityChanged` event on a `SharedFlow`; ViewModel reacts).
4. Tests: `SettingsBridgeTest` — mocked `SettingsRepository`, asserts slice emissions and event-flow firing.

**Verification gate (real rig):** Settings round-trip works — change call sign in Settings, see it reflected in Operate. CAT/RX/TX behavior unchanged.

**Why second:** `SettingsBridge` has exactly one dependency (`SettingsRepository`) and emits one slice. It's the cheapest demonstration of the controller pattern and proves the slice-merging shape before more complex controllers depend on it.

### Phase 2 — Extract `RigSession` (CAT + PTT)

**Goal:** `runCat`, `setRigFrequency`, `setRigDataUsb`, all rig polling, and PTT plumbing leave `OperateViewModel`.

**Work:**
1. Create `RigSession(scope, catDispatcher, rigController)`; emits `Flow<RigSlice>`.
2. Move `runCat`, `setRigFrequency`, `setRigDataUsb`, `restoreLastBandIfNeeded`, `refreshDevices`, the CAT polling loop to `RigSession`.
3. Replace `catExecutor.execute { … }` with `scope.launch(catDispatcher) { withTimeoutOrNull(5_000) { … } }` (PITFALLS Pitfall 9 layered guards arrive in Phase 6; Phase 2 swaps mechanism only).
4. PTT key/release becomes `RigSession.keyPtt()` / `releasePtt()` (suspend); `OperateViewModel.transmitMessageNow` still calls them directly (`TxOrchestrator` doesn't exist yet).
5. Tests: `RigSessionTest` with `FakeRigBackend` — verify CAT poll cadence, timeout handling, PTT idempotence, USB-detach event handling.

**Verification gate (real rig):** Open Operate, see freq within 2 s; tap band preset, freq tunes; PTT keys on TX; unplug Digirig, snackbar appears. Per the promotion checklist in PITFALLS Pitfall 12.

**Why third:** CAT and PTT are owned by `RigController` already (decent abstraction). Extracting the orchestration without touching the protocol layer is a low-risk move that pays dividends — `DecodeController` and `TxOrchestrator` both need to talk to a `RigSession` rather than the ViewModel.

### Phase 3 — Extract `DecodeController` (RX path)

**Goal:** `onFrames`, `decodeSlot`, `slotCollector`, `spectrum`, `waterfall`, and the `decodes` list leave `OperateViewModel`.

**Work:**
1. Create `DecodeController(scope, decodeDispatcher, audioInput, decoder, spectrum, waterfall)`; emits `Flow<DecodeSlice>` AND `SharedFlow<DecodeBatch> decodesOut`.
2. Move `onFrames`, `decodeSlot`, `scaledFrames`, the decode-list capping logic.
3. `decodeExecutor` retires; replaced by `decodeDispatcher`.
4. `OperateViewModel.startOperating`/`stopOperating` now delegates to `DecodeController.start/stop`.
5. `qso?.onDecodes(...)` call in `decodeSlot` becomes a `decodesOut.emit(batch)`; the OLD `synchronized(qsoLock)` block in the ViewModel collects this flow (temporarily) and forwards into `qso` under the lock — the lock survives until Phase 4.
6. Adopt `ImmutableList<DecodeRow>` and stable `id` field on `DecodeRow` (PITFALLS Pitfall 11).
7. Tests: `DecodeControllerTest` with `FakeAudioInput` (drives canned PCM) + `FakeFt8Decoder` (canned decode results); verify slot boundary detection, list capping, stable keys, batch emission.

**Verification gate (real rig):** Decodes arrive every slot; waterfall renders; decode list caps at 500 (Phase 8 polish wires the Clear button, but capping behavior is testable now). Rapid start/stop x10 leaves no orphan decoder threads (PITFALLS Pitfall 2 smoke test).

**Why fourth:** `DecodeController` is the most user-visible controller and the one that benefits most from the dedicated dispatcher migration. It depends on `RigSession` only weakly (audio device discovery may consult rig presence; otherwise independent).

### Phase 4 — Extract `QsoSessionController` + remove `qsoLock`

**Goal:** The `qsoThread`, `qso` field, `qsoLock`, `qsoRunning`, `qsoTxParity`, and all QSO sequencing leave `OperateViewModel`. The `@Volatile + synchronized` race window closes.

**Work:**
1. Create `QsoSessionController(scope, qsoDispatcher, txOrchestrator=null/* temp */, decodesIn, settingsBridge)`. `txOrchestrator` is `null` because we haven't extracted it yet — for this phase, `QsoSessionController` calls back into `OperateViewModel.transmitMessageNow` via a function handle.
2. Move `startCq`, `answerCq`, `resumeFromDecode`, `abandonQso`, `tryAnswerWhenCalled`, `tryAutoAnswerCq`, `publishQsoState`, the QSO loop (now a coroutine on `qsoDispatcher`).
3. `Thread.sleep` → `delay`. `synchronized(qsoLock)` → naturally serialized by `qsoDispatcher`. `qsoLock` and `@Volatile qsoRunning` deleted.
4. Before deletion: write the lock-invariants comment block PITFALLS Pitfall 7 mandates. Reviewer signs off.
5. Decode-driven advance: `init { scope.launch(qsoDispatcher) { decodesIn.collect { advanceFromDecode(it) } } }`.
6. Tests: `QsoSessionControllerTest` using `FakeQsoMachine` is forbidden (PITFALLS Pitfall 5 — never mock domain types); use real `QsoMachine` with mocked TX function. Include rapid start/stop x10 test. Include golden-trace pass-through.

**Verification gate (real rig):** Full CQ → answer → 73 cycle completes; auto-seq engages; abandon-on-no-reply fires; rapid Stop/Start during a QSO leaves no stuck thread. The golden-trace test from Phase 0 should pass against the new controller stack.

**Why fifth:** `QsoSessionController` has the highest coupling (settings, decodes, TX, rig parity); extracting it last among the domain controllers gives every dependency a stable interface to bind against. Removing `qsoLock` in the same phase as extraction matches PITFALLS Pitfall 7's recommendation (lock invariants documented + replacement is a single-thread dispatcher).

### Phase 5 — Extract `TxOrchestrator` + the `combine` block

**Goal:** `transmitMessageNow`, `txThread`, `cancelTx`, `transmitNextSlot`, `transmitOperateTxOnce` leave `OperateViewModel`. The residual VM becomes the `combine`-only orchestrator.

**Work:**
1. Create `TxOrchestrator(scope, encodeDispatcher, rigSession, decodeController, audioOutput, encoder)`; emits `Flow<TxSlice>`; exposes suspend `transmit(message): Boolean` and `emergencyHalt()`.
2. Move TX logic. Implement the 4-layer PTT defense (Pattern 5). Add watchdog coroutine.
3. Rewire `QsoSessionController` to call `txOrchestrator.transmit(message)` (replacing the function-handle from Phase 4).
4. **Now** introduce the `combine` block in `OperateViewModel`. Delete the residual `_state.update` calls. State becomes `combine(s, r, d, t, q).distinctUntilChanged().stateIn(...)`.
5. `@Immutable` annotations on every slice. `ImmutableList` for the decodes list (Phase 3 already prepared the row).
6. `collectAsState` → `collectAsStateWithLifecycle` audit across all Compose call sites.
7. Tests: `TxOrchestratorTest` with `FakeAudioOutput` + `FakeRigBackend`; PTT-stuck scenarios (throw inside playback, USB detach mid-TX, slot overrun); watchdog firing.

**Verification gate (real rig):** Full operating session — CQ, auto-answer, manual TX, abandon, rapid restart. Measure recomposition count with `recomposeHighlighter` debug tool before and after the `combine` switch; must not exceed pre-refactor baseline. USB-detach mid-TX → PTT confirmed released within 250 ms (watchdog tick).

**Why last:** TX touches RF safety; do it after every supporting controller is stable and tested. The `combine` switch lands at the same time because it's only meaningful once all 5 slices exist.

### Phases 6–8 — Reliability + Polish (depends-on-refactor work)

These were originally separate items in CONCERNS.md / PROJECT.md; the controller split unlocks them cleanly:

- **Phase 6:** CAT timeout layered guards (PITFALLS Pitfall 9) — driver-level timeout, port-reopen-on-failure, consecutive-failure threshold. Lives entirely in `RigSession`. AudioRecord hot-plug recovery (PITFALLS Pitfall 8) — `AudioDeviceCallback` in `DecodeController` + zero-samples watchdog.
- **Phase 7:** Decode list UX (Clear button wire-up, "showing last 500" indicator), TxSlotParity enum cleanup, USB filter tightening, INTERNET permission removal, native lib version display.
- **Phase 8:** ADIF auto-export (Room → `ApplicationScope` + `Dispatchers.IO` write to `getExternalFilesDir()`).

Each of these is a one-controller change, which is the validation that the split was correct: a Phase 6+ change should never need to edit more than one controller.

---

## 6. Test Seam Interfaces (Kotlin sketches)

The point of a test seam is to let the controller run on the JVM with mocked platform boundaries. **Mock only platform boundaries — never domain types** (PITFALLS Pitfall 5).

```kotlin
// audio/src/main/java/net/ft8vc/audio/AudioInput.kt
interface AudioInput {
    /** start streaming frames; returns false if device unavailable. */
    fun start(deviceId: Int?, onFrames: (ShortArray) -> Unit): Boolean
    fun stop()
    val isActive: Boolean
    fun availableDevices(): List<AudioDeviceSummary>
}

// audio/src/main/java/net/ft8vc/audio/AudioOutput.kt
interface AudioOutput {
    /** Suspending; returns true if full payload was transmitted, false if halted. */
    suspend fun playBlocking(pcm: ShortArray, deviceId: Int?): Boolean
    /** Hard stop — used by emergencyHalt; must be safe to call from any thread. */
    fun stop()
}

// rig/src/main/java/net/ft8vc/rig/RigBackend.kt  (already exists; ensure shape)
interface RigBackend {
    val isReady: Boolean
    suspend fun keyPtt(): Boolean
    suspend fun releasePtt(): Boolean       // idempotent
    suspend fun frequencyHz(): Long?         // null on timeout
    suspend fun setFrequencyHz(hz: Long): Boolean
    suspend fun mode(): Ft891Cat.Mode?
    suspend fun setMode(mode: Ft891Cat.Mode): Boolean
    fun close()
}

// ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Decoder.kt
interface Ft8Decoder {
    /** True if native library loaded successfully — gate TX on this. */
    val isLoaded: Boolean
    /** Reported by native; format "x.y.z+gitsha"; null if load failed. */
    val nativeVersion: String?
    suspend fun decode(samples: ShortArray, sampleRateHz: Int): List<DecodeRaw>
    suspend fun encode(message: String, toneHz: Float, sampleRateHz: Int): ShortArray
}

// Time injection — already a pattern in core/SlotTiming;
// every controller takes a Clock to make slot timing testable.
fun interface Clock { fun nowMs(): Long }
```

**Per-controller dependency contracts:**

| Controller | Required test seams | Real types it uses directly |
|------------|---------------------|------------------------------|
| `SettingsBridge` | `SettingsRepository` (already DataStore-backed; test impl is an in-memory `StateFlow<StationSettings>`) | none |
| `RigSession` | `RigBackend` (`FakeRigBackend`), `Clock`, `UsbDeviceProvider` (narrow `AudioManager` wrapper) | none |
| `DecodeController` | `AudioInput` (`FakeAudioInput`), `Ft8Decoder` (`FakeFt8Decoder`), `Clock` | `SpectrumProcessor`, `Waterfall`, `SlotCollector` (pure logic) |
| `TxOrchestrator` | `AudioOutput` (`FakeAudioOutput`), `Ft8Decoder` (for encode), `RigBackend` (via `RigSession` or direct), `Clock` | none |
| `QsoSessionController` | None at the platform boundary — consumes `decodesIn: Flow`, calls `txOrchestrator: TxOrchestrator` (the real one or a test double that records calls); takes `Clock` | `QsoMachine`, `QsoMessages`, `QsoResume`, `AnswerSelector`, `AbandonedPartners`, `AnswerPolicy`, `TxSlotSelection` — ALL real; never mocked |

**Golden-trace test (cross-controller):**

```kotlin
// app/src/test/java/net/ft8vc/app/controllers/golden/GoldenTraceTest.kt
class GoldenTraceTest {
    @Test fun `cq answer 73 with auto-seq`() = runTest {
        val trace = GoldenTrace.load("traces/cq-answer-73.json")
        val rig = FakeRigBackend()
        val audioIn = FakeAudioInput(trace.audioFrames)
        val audioOut = FakeAudioOutput()
        val decoder = FakeFt8Decoder(trace.decodeResults)
        val clock = AdvancingClock(start = trace.startMs)

        val vm = harness(rig, audioIn, audioOut, decoder, clock)
        vm.startCq()
        clock.advanceUntil(trace.endMs)

        val finalState = vm.state.value
        assertEquals(QsoState.Complete, /* extracted from state */)
        assertEquals(trace.expectedTxMessages, audioOut.recordedMessages)
        assertEquals(trace.expectedPttEdges, rig.pttEdges)
    }
}
```

This test is the single most important deliverable in the milestone (PITFALLS Pitfall 5 + Pitfall 10): it exercises the real controller stack with mocked platform boundaries, and it's the test that would have caught every cross-controller regression the refactor risks.

---

## 7. Alignment with PITFALLS.md

| PITFALLS prescription | How this architecture honors it |
|----------------------|--------------------------------|
| Extraction order: `SettingsBridge` → `RigSession` → `DecodeController` → `TxOrchestrator` → `QsoSessionController` (Pitfall 1) | §5: Phase 1 → 2 → 3 → 5 → 4. **One deviation:** we extract `QsoSessionController` (Phase 4) before `TxOrchestrator` (Phase 5) — and only because `QsoSessionController`'s extraction is what lets us delete `qsoLock`, and we don't want the lock-removal commit to also contain the `TxOrchestrator` move. `QsoSessionController` temporarily calls back into the residual ViewModel's `transmitMessageNow` (Phase 4) and then rewires to `TxOrchestrator.transmit` (Phase 5). This is a finer-grained split, not a contradiction. **If we strictly follow PITFALLS Pitfall 1's order**, swap Phases 4 and 5 — but then the `qsoLock` survives through Phase 5, which means Phase 5 has to touch both lock removal *and* TX extraction. The trade-off is split-the-lock-from-TX (recommended above) vs strict-extraction-order (Pitfall 1 literal reading). Either way: one controller per commit, lock removal in its own commit. |
| One controller per commit; coroutine migration as separate commits within the same phase (Pitfall 1) | §5: every phase says "Phase N: extract X" — coroutine swap inside that extraction is a follow-up commit in the same phase. Hard gate on commit size (<600 LOC moved). |
| Dedicated single-thread dispatchers for JNI (decode, CAT, encode) (Pitfall 2, Pitfall 9) | §3 Pattern 3 + dispatcher inventory: 3 dedicated dispatchers (`decodeDispatcher`, `encodeDispatcher`, `catDispatcher`) plus `qsoDispatcher` for the QSO loop. `Dispatchers.IO` reserved for Room/file. |
| `combine + distinctUntilChanged + @Immutable` flow assembly (Pitfall 6) | §3 Pattern 2: explicit `combine` block with `distinctUntilChanged().stateIn(WhileSubscribed(5_000))`. `@Immutable` on every slice. `ImmutableList<DecodeRow>` with stable `id` keys. `collectAsStateWithLifecycle` everywhere. |
| `FakeRigBackend` + golden-trace test harness in Phase 1 (Pitfall 10) | §5 Phase 0 (one phase earlier than Pitfall 10 requires): fakes and golden-trace fixture exist before any controller is extracted. |
| PTT 4-layer defense (Pitfall 3) | §3 Pattern 5 + §5 Phase 5: try-finally + AutoCloseable + withTimeoutOrNull + watchdog coroutine + onCleared unconditional release. All owned by `TxOrchestrator`. |
| USB detach → emergency halt → no auto-resume (Pitfall 4) | §4 Flow D: `RigSession` calls `TxOrchestrator.emergencyHalt()` + cancels `QsoSessionController`; reconnect requires explicit user action and re-runs license-ack. |
| `qsoLock` removal preserves ordering invariants (Pitfall 7) | §5 Phase 4: invariants documented in PR before deletion; single-thread `qsoDispatcher` (not `actor`) gives total ordering. Golden-trace test catches violations. |
| `AudioDeviceCallback` + zero-samples watchdog (Pitfall 8) | §5 Phase 6: lives entirely in `DecodeController`. |
| CAT driver-level timeout < coroutine timeout; close+reopen on failure (Pitfall 9) | §4 Flow C + §5 Phase 6: layered guards in `RigSession.setFrequency`. |
| Decode list stable keys + `ImmutableList` (Pitfall 11) | §5 Phase 3: `DecodeRow` gets stable `id`; controller emits `ImmutableList`. |
| Promotion checklist signed off per phase (Pitfall 12) | §5: every phase has a "Verification gate (real rig)" line; bundled with `.planning/promotion-checklist.md` introduction in Phase 0. |

---

## 8. Sources

- `.planning/codebase/ARCHITECTURE.md` (2026-06-21) — current monolithic shape that this refactor targets.
- `.planning/codebase/CONCERNS.md` (2026-06-21) — issues this architecture closes: monolithic VM, manual thread mgmt, volatile/synchronized race, QSO state machine thread safety.
- `.planning/research/PITFALLS.md` (2026-06-21, sibling researcher) — dispatcher choices, extraction order, golden-trace test seams, PTT defense layers, USB detach handling. This document operationalizes those prescriptions.
- `.planning/PROJECT.md` (2026-06-21) — Constraints (behavior parity on reference Yaesu FT-891 + Digirig); Key Decisions (refactor + coroutines migration in one phase; unit tests for every extracted controller).
- `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` lines 60–130 (in-code controller roadmap, executor + lock declarations), lines 440–500 (CAT execution), lines 700–900 (QSO loop + TX path), lines 970–1130 (decode path + `onCleared`).
- Now in Android architecture guide (developer.android.com/topic/architecture, 2024+) — domain-layer controller pattern with `StateFlow` slices + `combine` assembly. Confidence MEDIUM — pattern is well-established but the exact API surface in NIA evolves.
- Kotlin coroutines guide (kotlinlang.org/docs/coroutines-guide.html) — single-thread dispatcher via `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`; cooperative cancellation semantics; `withTimeoutOrNull` behavior with blocking calls. HIGH.
- `kotlinx-collections-immutable` (github.com/Kotlin/kotlinx.collections.immutable) — `ImmutableList` for Compose stability. HIGH.
