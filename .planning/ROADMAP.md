# Roadmap: FT8VC — v1.x Code Health Milestone

**Created:** 2026-06-21
**Mode:** standard (Horizontal Layers)
**Granularity:** standard (target 5-8 phases; landed at 8 — controller extractions cannot be merged per PITFALLS Pitfall 1)
**Core Value:** The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field — every change in this milestone must preserve that.
**Coverage:** 56/56 v1 requirements mapped (8 FOUND + 9 REFACTOR + 10 SAFETY + 7 RELY + 6 UX + 5 HYG + 8 TEST + 3 PARITY)

---

## Cross-Cutting Requirements (Apply to Every Phase)

These three requirements are **not** owned by a single phase. They are the non-negotiable bar that every phase must clear before promotion from `unstable` to `main`. The promotion checklist (FOUND-01) enforces them.

- **PARITY-01** — After every phase, the behavior-parity replay (FOUND-07) passes against the recorded baseline; RX/TX/CAT/QSO behavior is byte-equivalent to v1.0 on the reference rig (PITFALLS Pitfall 1).
- **PARITY-02** — Before any promotion from `unstable` to `main`, the full promotion checklist (FOUND-01) is signed off in the PR, with field-session evidence committed under `.planning/field-sessions/<date>/` (PITFALLS Pitfall 12).
- **PARITY-03** — No phase introduces user-visible behavior changes outside those explicitly enumerated in REQUIREMENTS.md (CAT timeout chip, USB disconnect snackbar/chip, TX-safety snackbar/chip, decode-counter chip, decode-list cap indicator + Clear button, Settings → About decoder row, Settings → Logbook backup row). Any new UX surfaces inline via snackbars, chips, or existing Settings rows — no new top-level screens or tabs.

---

## Phases

- [x] **Phase 0: Foundations** — Test seams, fakes, golden-trace harness, baselines, and promotion checklist — nothing else ships without these
- [x] **Phase 1: Extract SettingsBridge** — Lowest-coupling controller first; proves the slice/combine pattern
- [ ] **Phase 2: Extract RigSession** — CAT polling, dial preset, busy flag, PTT routing on `catDispatcher`
- [ ] **Phase 3: Extract DecodeController** — RX path, slot collector, JNI decode on `decodeDispatcher`; ImmutableList + stable keys prep
- [ ] **Phase 4: Extract QsoSessionController + Remove qsoLock** — Highest-coupling controller; eliminates `@Volatile + synchronized` mixed pattern; `Thread.sleep` → `delay`; JNI cancellation discipline
- [ ] **Phase 5: Extract TxOrchestrator + RF Safety + combine Assembly** — Highest-stakes phase; 4-layer PTT defense, watchdog, emergency-halt state machine, USB-disconnect routing, license re-check on reconnect, combine flow assembly, native lib load/version handshake
- [ ] **Phase 6: Reliability Hardening (CAT + Audio)** — Layered CAT timeout guards, port close+reopen, AudioRecord hot-swap recovery, decode-loop failure counter chip
- [ ] **Phase 7: UX Polish + Manifest Hygiene + ADIF Auto-Export** — Decode list Clear + cap indicator, Settings → About decoder row, TxSlotParity enum cleanup, USB filter tightening, INTERNET permission removal, ADIF auto-export

---

## Phase Details

### Phase 0: Foundations

**Goal**: Every test seam, fake, harness, baseline, and promotion gate exists before any controller is touched. Nothing in subsequent phases can ship without these artifacts in place.
**Depends on**: Nothing (first phase)
**Requirements**: FOUND-01, FOUND-02, FOUND-03, FOUND-04, FOUND-05, FOUND-06, FOUND-07, FOUND-08, TEST-06, TEST-07, TEST-08
**Success Criteria** (what must be TRUE):

  1. `.planning/promotion-checklist.md` exists, lists every required field check (cold boot, CAT read, 5 decodes in 3 slots, PTT key/release on both modes, dummy-load QSO cycle, mid-RX disconnect snackbar, relaunch with no PTT-stuck), and is referenced by the PR template's "Promotion checklist signed off" checkbox.
  2. `FakeRigBackend`, `FakeUsbAudio` (capture + playback), and `Ft8DecoderFake` test fakes exist at the same interface boundaries as their production siblings, support detach/timeout/zero-sample injection, and compile cleanly against the unchanged production code.
  3. A golden-trace harness replays a recorded decode sequence through real `QsoMachine` / `SlotCollector` / `QsoMessages` / `TxSlotParity` types, asserts on resulting QSO state transitions, and is wired into CI to run on every commit.
  4. A 5-minute behavior-parity baseline captured on the reference FT-891 + Digirig rig is committed under `.planning/field-sessions/baseline-<date>/` (UTC timestamps, slot indices, dial freq, PTT edges) and is replayable by the golden-trace harness.
  5. A pre-refactor Compose recomposition-count baseline for the Operate tab over one full slot cycle is captured and documented in the promotion checklist as the regression threshold for every subsequent phase.

**Plans**: 5 plans
**Wave 1**

  - [ ] 00-01-PLAN.md — Promotion checklist + PR template (FOUND-01, FOUND-02, TEST-07, TEST-08)
  - [ ] 00-02-PLAN.md — Test dependency wiring (Turbine, MockK, kotlinx-coroutines-test on rig/audio/ft8-native/app)
  - [ ] 00-05-PLAN.md — Compose recomposition baseline methodology + capture (FOUND-08)

**Wave 2** *(blocked on Wave 1 completion)*

  - [ ] 00-03-PLAN.md — Three test fakes: FakeRigBackend, FakeUsbAudio (capture + playback), Ft8DecoderFake (FOUND-03, FOUND-04, FOUND-05)

**Wave 3** *(blocked on Wave 2 completion)*

  - [ ] 00-04-PLAN.md — Golden-trace harness + recording format + CI wiring + real-rig baseline capture (FOUND-06, FOUND-07, TEST-06)

### Phase 1: Extract SettingsBridge

**Goal**: Settings reading moves out of `OperateViewModel.init` into a standalone `SettingsBridge` controller. The controller pattern (constructor + `StateFlow` slice + intent suspend fns + dedicated scope) is demonstrated end-to-end on the simplest seam before any complex controllers depend on it.
**Depends on**: Phase 0 (FakeRigBackend, golden-trace harness, baselines must exist)
**Requirements**: REFACTOR-01, TEST-01
**Success Criteria** (what must be TRUE):

  1. `controllers/SettingsBridge.kt` exists, exposes `StateFlow<SettingsSlice>` and station-identity change events via SharedFlow, and is constructed from `OperateViewModel` with `SettingsRepository` injected.
  2. `OperateViewModel` no longer collects `SettingsRepository.settings` directly; settings-related state in `OperateUiState` originates from `SettingsBridge.slice`. Behavior on the reference rig is unchanged (settings round-trip: change call sign → reflected in Operate within one frame).
  3. `SettingsBridgeTest` exists, mocks only `SettingsRepository` (platform boundary), instantiates at least one real sibling controller surface (not all-mocks), and asserts on slice emissions plus station-identity event-flow firing via Turbine.
  4. The golden-trace test (FOUND-06) and behavior-parity replay (FOUND-07) both pass against the post-extraction commit; pass is documented in the commit body.

**Plans**: TBD

### Phase 2: Extract RigSession

**Goal**: `runCat`, `setRigFrequency`, `setRigDataUsb`, `restoreLastBandIfNeeded`, `refreshDevices`, the CAT polling loop, and PTT key/release plumbing leave `OperateViewModel` and land in a `RigSession` controller running on a dedicated single-thread `catDispatcher`. The CAT timeout mechanism is swapped to coroutines (layered guards arrive in Phase 6 — this phase only changes the mechanism, not the policy).
**Depends on**: Phase 1 (SettingsBridge pattern established)
**Requirements**: REFACTOR-02, TEST-02
**Success Criteria** (what must be TRUE):

  1. `controllers/RigSession.kt` exists, owns the `catDispatcher` (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`), exposes `StateFlow<RigSlice>` with `catBusy`/`catStatus`/`rigFreqHz`/`rigMode`/`pttKeyed`/`digirigPresent`, and exposes suspend `keyPtt()` / `releasePtt()` / `setFrequency(hz)` / `setMode(mode)` functions.
  2. Every former `catExecutor.execute { … }` site is replaced with `scope.launch(catDispatcher) { withTimeoutOrNull(5_000) { … } }`; the underlying `ExecutorService` is `close()`d in `RigSession`'s teardown.
  3. On the reference rig: Operate opens with freq displayed within 2 s, band-preset tap re-tunes within 2 s, PTT keys/releases cleanly on both CAT and RTS modes — verified against pre-refactor baseline.
  4. `RigSessionTest` uses `FakeRigBackend` to cover: CAT poll cadence, single-call timeout returning null, PTT idempotence, and the consecutive-failure threshold scaffolding (full close+reopen logic in Phase 6).
  5. Golden-trace test and behavior-parity replay both pass; documented in the commit body.

**Plans**: TBD

### Phase 3: Extract DecodeController

**Goal**: `onFrames`, `decodeSlot`, `slotCollector`, `spectrum`, `waterfall`, and the `decodes` list leave `OperateViewModel` and land in a `DecodeController` running on a dedicated `decodeDispatcher`. DecodeRows gain stable `id` and `ImmutableList` shape so later Compose stability work has the foundation it needs. JNI cancellation discipline is established for the decode path.
**Depends on**: Phase 2 (RigSession provides device presence + audio device hints)
**Requirements**: REFACTOR-03, UX-03, TEST-03
**Success Criteria** (what must be TRUE):

  1. `controllers/DecodeController.kt` exists, owns `decodeDispatcher`, exposes `StateFlow<DecodeSlice>` (with `decodes: ImmutableList<DecodeRow>`, `lastSlotDecodeCount`, `levelDbfs`, `clip`, `waterfallVersion`, `decodeFailureCount`) and `SharedFlow<DecodeBatch> decodesOut` for sibling controllers to consume.
  2. `DecodeRow` carries a stable `id: Long` (`slotStart * 1000 + indexInSlot`); `DecodeListPanel`'s `LazyColumn` uses `key = { it.id }`; the decode list type is `ImmutableList<DecodeRow>` end-to-end inside the controller's slice.
  3. On the reference rig: decodes still arrive every slot, waterfall still renders, and a rapid-restart smoke test (start/stop x10 in 5 s) leaves no orphan decoder threads or duplicate decodes.
  4. `DecodeControllerTest` uses `FakeUsbAudio` (canned PCM frames) + `Ft8DecoderFake` (canned decode results) and covers slot-boundary detection, list capping at 500 (UI wire-up arrives in Phase 7), stable-key invariance under append, and decode-batch emission ordering.
  5. Golden-trace test and behavior-parity replay both pass; documented in the commit body.

**Plans**: TBD

### Phase 4: Extract QsoSessionController + Remove qsoLock

**Goal**: The `qsoThread`, `qso` field, `qsoLock`, `@Volatile qsoRunning`, `qsoTxParity`, and all QSO sequencing leave `OperateViewModel` and land in a `QsoSessionController` running on a dedicated `qsoDispatcher`. The `@Volatile + synchronized` race window closes. `Thread.sleep` becomes `delay`. JNI calls honor cancellation via `cancelAndJoin` discipline. TX is still called via a function handle into the residual ViewModel — `TxOrchestrator` extraction is Phase 5.
**Depends on**: Phase 3 (DecodeController emits the `decodesOut` flow this controller consumes)
**Requirements**: REFACTOR-04, REFACTOR-07, REFACTOR-08, TEST-04
**Success Criteria** (what must be TRUE):

  1. `controllers/QsoSessionController.kt` exists, owns `qsoDispatcher`, owns the QSO loop coroutine (collecting `decodesIn` and ticking on slot boundaries via `delay`), and exposes `StateFlow<QsoSlice>` with `qsoActive`, `qsoState`, `qsoDx`, `operateTxText/Form/Step`, `qsoTxParity`, `abandonedPartners`, `isOperating`, `slotIndex`, `secondsToNextSlot`, `utcClock`.
  2. `qsoLock`, `@Volatile qsoRunning`, and every `Thread.sleep()` in the decode/CAT/QSO loops are deleted. Every `Executors.newSingleThreadExecutor()` is wrapped via `.asCoroutineDispatcher()` and its underlying `ExecutorService` is `close()`d in the owning controller's teardown. Every JNI call (`Ft8Native.decode`, `Ft8Native.encode`) runs inside `withContext(decodeDispatcher) { … }` or `withContext(encodeDispatcher) { … }` and the launching code `cancelAndJoin()`s any in-flight job before starting a new one.
  3. A block-comment header on `QsoSessionController` enumerates every invariant the old `qsoLock` enforced (cross-access ordering of TX, decode-advance, parity update); reviewer sign-off recorded in the lock-removal commit body.
  4. On the reference rig: a full CQ → answer → 73 cycle completes with auto-seq on, abandon-on-no-reply fires correctly, rapid Stop/Start during a QSO leaves no stuck thread, and the golden-trace test passes against the new controller stack.
  5. `QsoSessionControllerTest` uses real `QsoMachine`/`QsoMessages`/`QsoResume`/`AnswerSelector`/`AbandonedPartners` (never mocked), runs on `qsoDispatcher`, and covers the documented lock invariants (especially decode-during-TX-slot ordering), rapid start/stop, and golden-trace pass-through.

**Plans**: TBD

### Phase 5: Extract TxOrchestrator + RF Safety + combine Assembly

**Goal**: `transmitMessageNow`, `txThread`, `cancelTx`, `transmitNextSlot`, `transmitOperateTxOnce` leave `OperateViewModel` and land in a `TxOrchestrator` with all four PTT-defense layers, a watchdog coroutine, the `AppRfState` emergency-halt state machine, native lib load/version handshake, USB-disconnect routing, and license re-check on reconnect. The residual `OperateViewModel` becomes the thin `combine`-only orchestrator. This is the highest-stakes phase in the milestone — partial defense is not deliverable.
**Depends on**: Phase 4 (QsoSessionController's `txOrchestrator` function handle gets rewired to the real controller)
**Requirements**: REFACTOR-05, REFACTOR-06, REFACTOR-09, SAFETY-01, SAFETY-02, SAFETY-03, SAFETY-04, SAFETY-05, SAFETY-06, SAFETY-07, SAFETY-08, SAFETY-09, SAFETY-10, RELY-06, UX-04, UX-05, TEST-05
**Success Criteria** (what must be TRUE):

  1. `controllers/TxOrchestrator.kt` exists, owns `encodeDispatcher`, exposes `StateFlow<TxSlice>` and suspend `transmit(message): Boolean` / `emergencyHalt()` functions. All four PTT-defense layers are present and exercised by tests: (a) `try-finally` unconditional `releasePtt()`, (b) `AutoCloseable`/`use { }` TX session, (c) `withTimeoutOrNull(SLOT_DURATION_MS + 500)`, (d) 250 ms watchdog coroutine that forces release + surfaces a "TX safety halt — PTT forced released" snackbar plus persistent status chip in the existing Operate header. `onCleared()` of every PTT-touching controller unconditionally calls `releasePtt()`.
  2. `AppRfState { READY, RX_ONLY, EMERGENCY_HALT }` enum is the single source of truth for RF readiness. `ACTION_USB_DEVICE_DETACHED` transitions the app into `EMERGENCY_HALT` which (a) releases PTT idempotently, (b) sets `qsoRunning=false`, (c) tears down `RigSession`, (d) blocks TX until explicit user action; reconnect routes through the same cold-init path as fresh start and re-checks license acknowledgment with no auto-resume of TX. A "Digirig disconnected — RX only" snackbar plus persistent status chip surfaces on detach.
  3. `Ft8Native` exposes a `version()` JNI export and Kotlin verifies it on load; mismatch records a load-failure state, TX is non-interactive with helper text when `Ft8Native.loaded == false` OR version check failed, and Settings → About displays the current native version and load status.
  4. `OperateViewModel` is reduced to constructor + lifecycle + intent dispatch + a `combine(settings, rig, decode, tx, qso) { … OperateUiState(…) }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` flow assembly; every `_state.update {}` call in the codebase is owned by exactly one controller. Every `OperateUiState` field type is stable (primitive, `@Immutable` data class, or `ImmutableList`); every flow collected by `OperateScreen` (and any tab consuming controller flows) uses `collectAsStateWithLifecycle()` — no plain `collectAsState()` survives.
  5. On the reference rig: full operating session passes (CQ, auto-answer, manual TX, abandon, rapid restart); USB-detach mid-TX confirms PTT released within 250 ms via the watchdog; recomposition count for the Operate tab over one full slot cycle does not exceed the Phase 0 baseline; golden-trace test and behavior-parity replay both pass; documented in the commit body.
  6. `TxOrchestratorTest` exercises all four PTT-safety layers, a "throw inside playback callback" scenario, USB-detach-mid-TX, and the watchdog directly; PTT confirmed released in every failure path.

**Plans**: TBD

### Phase 6: Reliability Hardening (CAT + Audio)

**Goal**: The CAT timeout mechanism swapped in Phase 2 gains layered guards (driver-level inner timeout, coroutine outer timeout, port close+reopen on timeout, consecutive-failure threshold with "CAT unreachable" persistent chip). The AudioRecord lifecycle gains two-signal hot-swap detection (`AudioDeviceCallback` + zero-samples watchdog). Audio capture stop path is hardened with interrupt-then-join-with-timeout. The decode-loop failure counter surfaces as an Operate-header status chip. Each item is a one-controller change — validating that the Phase 1-5 split was correct.
**Depends on**: Phase 5 (RigSession and DecodeController must be stable controllers before layered guards land)
**Requirements**: RELY-01, RELY-02, RELY-03, RELY-04, RELY-05, RELY-07
**Success Criteria** (what must be TRUE):

  1. Every CAT operation is guarded by coroutine `withTimeoutOrNull(5_000)` (outer) AND a driver-level read timeout (inner, < 5 s). On CAT timeout, the serial port is closed and reopened before the next CAT attempt. Three consecutive timeouts transition CAT into an "unreachable" state, stop retrying, and surface "CAT unreachable — tap to retry" as a persistent status chip in the existing CAT status row (never a dismissible toast).
  2. AudioRecord/AudioTrack hot-swap is detected by TWO independent signals: (a) `AudioDeviceCallback.onAudioDevicesRemoved()` registered via `AudioManager.registerAudioDeviceCallback()`, AND (b) zero-samples-returned for >2 consecutive slots cross-checked against `AudioManager.getDevices()` for USB presence. Either signal triggers capture-chain recreation.
  3. Audio capture stop calls `stop()` + `release()` inside a `finally` block, with `Thread.interrupt()` followed by `Thread.join(timeout)`; failure-to-stop emits a "Audio thread didn't stop cleanly — recovering" snackbar and forces capture-chain recreation.
  4. Decode-loop failures (caught `Throwable` inside `decodeSlot`) increment `decodeFailureCount`; counter ≥ 1 over the last ~5 slots surfaces a "Decodes dropped: N" status chip in the Operate header (auto-clears when stable).
  5. On the reference rig: deliberate cable-wiggle recovers within 1 slot of re-plug; CAT cable disconnect surfaces "CAT unreachable" chip after 3 consecutive timeouts and stops retrying until user tap; golden-trace test and behavior-parity replay still pass.

**Plans**: TBD
**UI hint**: yes

### Phase 7: UX Polish + Manifest Hygiene + ADIF Auto-Export

**Goal**: The remaining surfaced-but-not-yet-implemented operator deltas land: decode list Clear button wired with "showing last 500" indicator, Settings → About decoder row, `TxSlotParity` enum used end-to-end, `usb_device_filter.xml` tightened to Digirig parts only, INTERNET permission removed, ADIF auto-export with Settings → Logbook "Last backup" row. Each item is a single-file or two-file change touching a single controller — final validation that the split was correct.
**Depends on**: Phase 6 (decode failure counter chip and CAT status chip patterns established; controllers stable)
**Requirements**: UX-01, UX-02, UX-06, HYG-01, HYG-02, HYG-03, HYG-04, HYG-05
**Success Criteria** (what must be TRUE):

  1. The decode list caps at 500 rows (oldest dropped from the front); the existing `clearDecodes()` ViewModel function is wired to a visible Clear button in the existing `DecodeListPanel` toolbar; a small "showing last 500 — older cleared" indicator appears at the top of the panel only when capped.
  2. Settings → About gains a "Decoder library: loaded v<version>" row that flips to "Decoder library: FAILED — reinstall app" when load failed (matching the TX-gate behavior shipped in Phase 5).
  3. `usb_device_filter.xml` is tightened to only Digirig parts: CP2102 (VID `0x10C4` / PID `0xEA60`) and FT240X (VID `0x0403` / PID `0x6015`); USB device permission auto-grants via the intent-filter + filter path, and reconnect does not re-prompt for permission (verified and documented).
  4. `android.permission.INTERNET` is removed from `AndroidManifest.xml`; `TxSlotParity` enum is used end-to-end with raw `Int` `0`/`1` for parity appearing only at I/O boundaries (production code is enum-only).
  5. Logbook auto-exports to ADIF in `getExternalFilesDir()` after every QSO commit + on app pause + on a daily rolling timer (whichever first); writes are atomic (write-temp + rename); export runs on `ApplicationScope` (not `viewModelScope`); Settings → Logbook gains a "Last backup: X min ago" row and a "Backup now" button.
  6. On the reference rig: full promotion checklist (FOUND-01) passes end-to-end, decode list scroll position holds steady across slot boundaries (stable keys from Phase 3 still working), ADIF file appears in app-private storage after first QSO commit, and the milestone is ready for promotion from `unstable` to `main`.

**Plans**: TBD
**UI hint**: yes

---

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 0. Foundations | 5/5 | Complete | 2026-06-21 |
| 1. Extract SettingsBridge | 1/1 | Complete | 2026-06-22 |
| 2. Extract RigSession | 0/? | Not started | - |
| 3. Extract DecodeController | 0/? | Not started | - |
| 4. Extract QsoSessionController + Remove qsoLock | 0/? | Not started | - |
| 5. Extract TxOrchestrator + RF Safety + combine Assembly | 0/? | Not started | - |
| 6. Reliability Hardening (CAT + Audio) | 0/? | Not started | - |
| 7. UX Polish + Manifest Hygiene + ADIF Auto-Export | 0/? | Not started | - |

---

## Notes on Granularity & Phase Shape

- **8 phases instead of 5-8 typical for `standard` granularity** — PITFALLS Pitfall 1 mandates one-controller-per-commit, which makes the 5 controller-extraction phases (1-5) non-mergeable. Wave phases (0, 6, 7) are already compressed: `HYG-*`, `UX-01/02/06`, and `HYG-04` (ADIF auto-export) all merged into a single hygiene/polish wave (Phase 7); CAT reliability and AudioRecord recovery merged into a single reliability wave (Phase 6). Further compression is not safe under the milestone's RF-safety constraints.
- **Phase 5 is intentionally the largest phase (16 requirements)** — partial RF-safety defense is not deliverable per REQUIREMENTS PARITY-03 and PITFALLS Pitfall 4. The 4-layer PTT defense, watchdog, `AppRfState` enum, USB-disconnect routing, license re-check, native lib version handshake, and the `combine` block must all land together; any subset leaves a known field-safety gap open.
- **PARITY-01, PARITY-02, PARITY-03 are cross-cutting** — they apply to every phase and are tracked separately in the Cross-Cutting section at the top. They appear in no single phase's requirement list because they have no single owner; the promotion checklist enforces them at every phase boundary.
- **Phase ordering deviates intentionally from a strict reading of PITFALLS Pitfall 1** — `QsoSessionController` extraction (Phase 4) lands before `TxOrchestrator` extraction (Phase 5), even though `QsoSessionController` has the highest coupling. The reason: removing `qsoLock` in its own commit keeps the invariant-documentation diff clean. `QsoSessionController` temporarily calls back into the residual ViewModel's TX path; Phase 5 rewires it to the real `TxOrchestrator`. This is a finer-grained split, not a contradiction — one controller per commit is preserved throughout.

---

*Roadmap created: 2026-06-21*
*Last updated: 2026-06-21 after initial creation*
