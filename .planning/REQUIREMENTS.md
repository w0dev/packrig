# Requirements: FT8VC — v1.x Code Health Milestone

**Defined:** 2026-06-21
**Core Value:** The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field — every change in this milestone must preserve that.

All v1 requirements below derive from `.planning/codebase/CONCERNS.md`, the research in `.planning/research/`, and the questioning answers captured in `.planning/PROJECT.md`. Each requirement carries a back-reference to its source feature (`TS-*` / `D-*` in `research/FEATURES.md`) or codebase concern.

## v1 Requirements

### Foundation (Phase 0)

Pre-refactor scaffolding. These exist before any controller moves so that subsequent phases have parity tooling and a working test harness.

- [ ] **FOUND-01**: Project commits a `.planning/promotion-checklist.md` listing the field-session checks every `unstable → main` promotion must satisfy (cold boot, CAT read, 5 decodes in 3 slots, PTT key/release on both modes, dummy-load QSO cycle, mid-RX disconnect snackbar, relaunch with no PTT-stuck) (D-6, PITFALLS Pitfall 12)
- [ ] **FOUND-02**: PR template adds a "Promotion checklist signed off" checkbox referencing the above file, and an "RF-irrelevant skip" justification line for the rare phase that genuinely cannot affect RF (D-6, PITFALLS Pitfall 12)
- [ ] **FOUND-03**: Project ships a `FakeRigBackend` test fake at the same interface boundary as `DigirigRigBackend`, supporting `simulateDetach()` mid-call, configurable CAT response delay, configurable timeout-then-error, and configurable PTT-state observation (D-3, PITFALLS Pitfalls 5, 10)
- [ ] **FOUND-04**: Project ships a `FakeUsbAudio` test fake at the same boundary as `UsbAudioCapture`/`UsbAudioPlayback`, supporting injectable PCM frames, configurable zero-sample return, and simulated device removal (D-3, PITFALLS Pitfall 8)
- [ ] **FOUND-05**: Project ships a `Ft8DecoderFake` (or equivalent test seam) so controller tests can run without loading `libft8vc.so` on the JVM (D-3, PITFALLS Pitfall 5)
- [ ] **FOUND-06**: Project ships a golden-trace test harness that replays a recorded decode sequence through real domain types (`QsoMachine`, `SlotCollector`, `QsoMessages`, `TxSlotParity`) and asserts on the resulting QSO state transitions (D-3, PITFALLS Pitfalls 5, 10)
- [ ] **FOUND-07**: Project captures a behavior-parity baseline under `.planning/field-sessions/baseline-<date>/` — a 5-minute decode/TX session log from the reference FT-891 + Digirig rig (UTC timestamps, slot indices, dial freq, PTT edges) — committed before any controller extraction begins (D-4, PITFALLS Pitfall 1)
- [ ] **FOUND-08**: Project records a Compose recomposition-count baseline for the Operate tab over one full slot cycle, captured pre-refactor and re-captured at the end of each refactor phase; documented in the promotion checklist (D-5, PITFALLS Pitfall 6)

### Refactor (Controller Split + Coroutine Migration)

Executes the in-code roadmap from `OperateViewModel.kt` lines 66–81, with extraction order anchored to PITFALLS.md (lowest-coupling first) and the coroutine migration interleaved per ARCHITECTURE.md.

- [ ] **REFACTOR-01**: Extract `SettingsBridge` controller — observes settings flow, maps to UI state. Owned in its own commit; `OperateViewModel` becomes a thin delegator for settings-related state (CONCERNS §"Monolithic OperateViewModel", ARCHITECTURE §5 Phase 1)
- [ ] **REFACTOR-02**: Extract `RigSession` controller — owns CAT operations, dial presets, busy flag, PTT routing. Lives on its own `catDispatcher` (single-thread `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`) (CONCERNS §"Monolithic OperateViewModel"; PITFALLS Pitfall 2)
- [ ] **REFACTOR-03**: Extract `DecodeController` — owns decode executor, `SlotCollector`, `Ft8Native` calls. Lives on its own `decodeDispatcher`. Emits `SharedFlow<DecodeBatch>` consumed by sibling controllers (CONCERNS §"Monolithic OperateViewModel"; PITFALLS Pitfalls 2, 6)
- [ ] **REFACTOR-04**: Extract `QsoSessionController` — wraps `QsoMachine`, abandon counter, auto-seq. Lives on its own `qsoDispatcher`; removes the `qsoLock + @Volatile qsoRunning` pattern entirely. Invariants the old lock enforced are documented in a header comment on the new controller (CONCERNS §"Volatile + Synchronized Mixed Pattern", §"QSO State Machine Thread Safety"; PITFALLS Pitfall 7)
- [ ] **REFACTOR-05**: Extract `TxOrchestrator` — manages encode + `UsbAudioPlayback` + PTT + TX thread. Lives on its own `encodeDispatcher` (CONCERNS §"Monolithic OperateViewModel"; PITFALLS Pitfall 3)
- [ ] **REFACTOR-06**: `OperateViewModel` reduces to constructor + lifecycle + intent dispatch + a `combine(controllerA, ..., controllerE) { … }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` flow assembly; all `_state.update {}` calls are owned by exactly one controller (ARCHITECTURE §3; PITFALLS Pitfall 6)
- [ ] **REFACTOR-07**: Every `Thread.sleep()` in decode, CAT, and QSO loops is replaced with coroutine `delay()`; every `Executors.newSingleThreadExecutor()` is wrapped via `.asCoroutineDispatcher()` and the underlying `ExecutorService` is `close()`d in `onCleared()` of its owning controller (CONCERNS §"Manual Thread Management", §"Direct Calls to Thread.sleep() in QSO Loop"; PITFALLS Pitfall 2)
- [ ] **REFACTOR-08**: Cancellation honors JNI thread affinity — every JNI call (`Ft8Native.decode`, `Ft8Native.encode`) runs inside `withContext(decodeDispatcher) { … }` and the launching code `cancelAndJoin()`s any in-flight job before starting a new one (PITFALLS Pitfall 2)
- [ ] **REFACTOR-09**: After every extraction commit, the golden-trace test (FOUND-06) and the behavior-parity replay (FOUND-07) both pass; documented in the commit body (PITFALLS Pitfalls 1, 5)

### RF Safety

The highest-stakes work in the milestone. All four PTT-safety layers and the emergency-halt state machine land together — partial defense is not deliverable.

- [ ] **SAFETY-01**: Every TX path is wrapped in a `try-finally` that unconditionally calls `rig.releasePtt()` (CONCERNS §"Audio Capture/Playback Not Guaranteed Closed"; PITFALLS Pitfall 3, layer 1)
- [ ] **SAFETY-02**: The TX session object implements `AutoCloseable`/`use { }` so structured concurrency cleanup releases PTT on coroutine cancellation (PITFALLS Pitfall 3, layer 2)
- [ ] **SAFETY-03**: Every TX block is wrapped in `withTimeoutOrNull(SLOT_DURATION_MS + 500)`; expiry forces `releasePtt()` (PITFALLS Pitfall 3, layer 3)
- [ ] **SAFETY-04**: `TxOrchestrator` runs a watchdog coroutine that checks every 250 ms: if `pttKeyed == true` AND the app is not in an active TX slot, force `releasePtt()` and surface a TX-safety status (PITFALLS Pitfall 3, layer 4)
- [ ] **SAFETY-05**: `onCleared()` of `TxOrchestrator` (and any controller that ever calls `keyPtt()`) unconditionally calls `releasePtt()`, independent of any `pttKeyed` flag (PITFALLS Pitfall 3)
- [ ] **SAFETY-06**: When the watchdog (SAFETY-04) fires, the app surfaces a snackbar "TX safety halt — PTT forced released" AND a persistent status chip in the existing Operate header until the operator dismisses it (TS-2, D-1; PITFALLS UX Pitfalls table)
- [ ] **SAFETY-07**: App defines an `AppRfState { READY, RX_ONLY, EMERGENCY_HALT }` enum; `ACTION_USB_DEVICE_DETACHED` transitions the app into `EMERGENCY_HALT`, which (a) releases PTT (idempotent), (b) sets `qsoRunning=false`, (c) tears down `RigSession`, (d) blocks TX until explicit user action (TS-3, D-2; PITFALLS Pitfall 4)
- [ ] **SAFETY-08**: USB reconnect after detach routes through the same cold-init path as fresh start — license acknowledgment is re-checked; no auto-resume of TX (TS-14; PITFALLS Pitfall 4)
- [ ] **SAFETY-09**: `Ft8Native` exposes a `version()` JNI export and Kotlin verifies it on load; mismatch records a load-failure state (TS-6, CONCERNS §"Hardcoded Native Library Version")
- [ ] **SAFETY-10**: TX is disabled (button non-interactive with helper text) when `Ft8Native.loaded == false` OR the version check (SAFETY-09) failed; Settings → About shows the current native version and load status (TS-5, TS-6; CONCERNS §"Hardcoded Native Library Version")

### Reliability

CAT timeouts, audio recovery, decode-loop telemetry — the items that turn silent failures into recoverable, operator-visible states.

- [ ] **RELY-01**: All CAT operations are guarded by a coroutine `withTimeoutOrNull(5_000)` (outer) and a driver-level read timeout (inner, < 5 s); see RELY-02 for cascade handling (TS-1, CONCERNS §"CAT Timeout Only on Throwable"; PITFALLS Pitfall 9)
- [ ] **RELY-02**: On CAT timeout, the serial port is closed and reopened before the next CAT attempt; consecutive timeouts beyond a threshold (N=3) transition CAT into "unreachable" state, stop retrying, and surface "CAT unreachable — tap to retry" in the existing CAT status row (TS-15; PITFALLS Pitfall 9)
- [ ] **RELY-03**: CAT timeout state surfaces as a persistent status chip in the existing CAT status row (never a dismissible toast) (TS-1; PITFALLS UX Pitfalls table)
- [ ] **RELY-04**: Audio capture stop path calls `stop()` + `release()` inside a `finally` block, with `Thread.interrupt()` followed by `Thread.join(timeout)`; failure-to-stop emits a snackbar "Audio thread didn't stop cleanly — recovering" and forces capture-chain recreation (TS-7, CONCERNS §"Audio Capture/Playback Not Guaranteed Closed")
- [ ] **RELY-05**: AudioRecord/AudioTrack hot-swap is detected by TWO independent signals: (a) `AudioDeviceCallback.onAudioDevicesRemoved()` registered via `AudioManager.registerAudioDeviceCallback()`, AND (b) zero-samples-returned for >2 consecutive slots cross-checked against `AudioManager.getDevices()` for USB presence (TS-4; PITFALLS Pitfall 8)
- [ ] **RELY-06**: USB device detach broadcast (`ACTION_USB_DEVICE_DETACHED`) surfaces a snackbar "Digirig disconnected — RX only" and a persistent status chip until reconnect or explicit dismiss (TS-3, CONCERNS §"No Disconnect Notification"; PITFALLS Pitfall 4)
- [ ] **RELY-07**: Decode-loop failures (caught `Throwable` inside `decodeSlot`) increment a counter; counter ≥ 1 over the last ~5 slots surfaces a "Decodes dropped: N" status chip in the Operate header (auto-clears when stable) (TS-8, CONCERNS §"Silent Failures in Decode Loop")

### UX

UI deltas that surface the safety/reliability work without crowding the main product. Strictly inline — no new top-level screens.

- [ ] **UX-01**: Decode list caps at 500 rows; oldest dropped (front of list); the existing `clearDecodes()` ViewModel function is wired to a visible Clear button in the existing `DecodeListPanel` toolbar (TS-11, CONCERNS §"Unbounded Decode List Growth")
- [ ] **UX-02**: When the decode list is capped (UX-01), a small "showing last 500 — older cleared" indicator appears at the top of the panel (TS-11; PITFALLS Pitfall 11, UX Pitfalls)
- [ ] **UX-03**: Decode rows carry a stable `id: Long` (e.g., `slotStart * 1000 + indexInSlot`); `LazyColumn` uses `key = { it.id }` and the list type is `ImmutableList<DecodeRow>` (PITFALLS Pitfall 11)
- [ ] **UX-04**: All `OperateUiState` types feeding Compose are stable — primitives, `@Immutable` data classes, or `ImmutableList`; verified by `recomposeHighlighter` showing no extra recompositions per state change (PITFALLS Pitfall 6)
- [ ] **UX-05**: Every flow collected by `OperateScreen` (and other tabs that consume controller flows) uses `collectAsStateWithLifecycle()`; no plain `collectAsState()` survives in the codebase (PITFALLS Pitfall 6)
- [ ] **UX-06**: Settings → About row gains a "Decoder library: loaded v<version>" entry; failure mode displays "Decoder library: FAILED — reinstall app" (TS-5, TS-6)

### Hygiene & Security

The lowest-cost items in the milestone. Each touches a single file or two and removes a known wart.

- [ ] **HYG-01**: `usb_device_filter.xml` is tightened to only the Digirig parts: CP2102 (VID `0x10C4` / PID `0xEA60`) and FT240X (VID `0x0403` / PID `0x6015`); no broader matches (TS-9, CONCERNS §"USB Device Descriptor Not Validated"; PITFALLS Security/Safety table)
- [ ] **HYG-02**: USB device permission auto-grants via the intent-filter + `usb_device_filter.xml` path; reconnect does not re-prompt for permission (verify and document) (TS-10, CONCERNS §"USB Device Descriptor Not Validated")
- [ ] **HYG-03**: `android.permission.INTERNET` is removed from `AndroidManifest.xml`; will be re-added when NTP (or any other network feature) is actually implemented (TS-13, CONCERNS §"INTERNET Permission Declared but Unused")
- [ ] **HYG-04**: Logbook auto-exports to ADIF in `getExternalFilesDir()` after every QSO commit + on app pause + on a daily rolling timer (whichever first); writes are atomic (write-temp + rename); export runs on `ApplicationScope` not `viewModelScope`; Settings → Logbook gains a "Last backup: X min ago" row and a "Backup now" button (TS-12, CONCERNS §"No Local Backup of Logbook"; PITFALLS Integration Gotchas)
- [ ] **HYG-05**: `TxSlotParity` enum is used end-to-end; raw `Int` `0`/`1` for parity does not appear in production code; conversion to `Int` only occurs at I/O boundaries (D-7, CONCERNS §"Stringly-Typed Slot Parity")

### Test Coverage

Tests are a hard deliverable, not aspirational. Coverage shape is "every transition documented in `QsoMachine` is exercised by an integration-style test through the controllers" — see PITFALLS Pitfall 5.

- [ ] **TEST-01**: `SettingsBridge` has unit tests using mocked platform boundaries; tests instantiate at least one *real* sibling controller (not all-mocks) (PITFALLS Pitfall 5)
- [ ] **TEST-02**: `RigSession` has unit tests using `FakeRigBackend`; covers CAT timeout (RELY-01), close+reopen (RELY-02), and consecutive-failure threshold (PITFALLS Pitfall 9)
- [ ] **TEST-03**: `DecodeController` has unit tests using `FakeUsbAudio` + `Ft8DecoderFake`; covers decode-failure counter (RELY-07), rapid-restart cancellation (REFACTOR-08), and ordering vs sibling controllers (PITFALLS Pitfalls 2, 5)
- [ ] **TEST-04**: `QsoSessionController` has unit tests on `qsoDispatcher`; covers the invariants the old `qsoLock` enforced (REFACTOR-04), rapid start/stop, and decode-during-TX-slot ordering (PITFALLS Pitfalls 5, 7)
- [ ] **TEST-05**: `TxOrchestrator` has unit tests covering all four PTT-safety layers (SAFETY-01..04) AND a "throw inside playback callback" scenario; PTT confirmed released in every failure path; tests exercise the watchdog directly (PITFALLS Pitfall 3)
- [ ] **TEST-06**: Golden-trace test (FOUND-06) is wired into CI and runs on every commit (PITFALLS Pitfalls 5, 10)
- [ ] **TEST-07**: A "rapid restart" smoke test (start-stop x 10 in 5 s) is included in the manual promotion checklist (FOUND-01) and documented in `.planning/promotion-checklist.md` (PITFALLS Pitfall 2)
- [ ] **TEST-08**: A "cable wiggle" recovery test (detach-then-reattach within 1 slot) is included in the manual promotion checklist (PITFALLS Pitfall 8)

### Cross-Cutting (Behavior Parity)

The non-negotiable bar that every phase must clear before promotion.

- [ ] **PARITY-01**: After every phase, the behavior-parity replay (FOUND-07) passes against the recorded baseline — RX/TX/CAT/QSO behavior is byte-equivalent to v1.0 on the reference rig (PITFALLS Pitfall 1)
- [ ] **PARITY-02**: Before any promotion from `unstable` to `main`, the full promotion checklist (FOUND-01) is signed off in the PR, with field-session evidence (log or screenshot) committed under `.planning/field-sessions/<date>/` (D-6, PITFALLS Pitfall 12)
- [ ] **PARITY-03**: No phase introduces user-visible behavior changes outside those explicitly enumerated in this REQUIREMENTS.md (CAT timeout chip, USB disconnect snackbar/chip, TX-safety snackbar/chip, decode-counter chip, decode-list cap indicator + Clear button, Settings → About decoder row, Settings → Logbook backup row); any new UX must surface inline via snackbars, chips, or existing Settings rows — no new top-level screens or tabs (PROJECT.md Constraints; PITFALLS AF-1, AF-4)

## v2 Requirements

Deferred to a future milestone. Tracked but not in this roadmap.

### Future Hardening

- **V2-01**: Cloud / Drive backup of logbook (re-introduces INTERNET permission via OAuth flow) — explicit out of scope per PROJECT.md
- **V2-02**: Logbook PIN / biometric encryption — explicit out of scope per PROJECT.md
- **V2-03**: NTP clock sync (the placeholder reason `android.permission.INTERNET` was originally declared)
- **V2-04**: `tx_enabled=true` resume requires re-confirm after abnormal shutdown — flagged by PITFALLS Security/Safety table; could fold into v1.x if a phase has cycles, otherwise v1.2

### Future Performance

- **V2-05**: Waterfall circular buffer rewrite — CONCERNS notes "no issue today"; defer until it bites
- **V2-06**: `kotlinx-coroutines` 1.11+ upgrade — version-skew with Kotlin 2.3.21 pin per STACK.md; its own future phase

### Future Product

- **V2-07**: Wider rig support beyond FT-891 (additional CAT dialects)
- **V2-08**: Decoder weak-signal tuning / DSP improvements
- **V2-09**: POTA workflow expansion (auto-spot to API, hunter-side enhancements, etc.)

## Out of Scope

Explicitly excluded for this milestone. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| New rig support beyond FT-891 | Different milestone; abstraction stays but isn't widened |
| Decoder quality / weak-signal improvements | Separate milestone — don't bundle with refactor |
| POTA workflow expansion | Already shipping in v1.0; not touched here |
| Cloud / Drive logbook backup | Local ADIF auto-export (HYG-04) is the deliverable; cloud is future |
| Logbook PIN / biometric encryption | Separate UX project; not bundled with refactor |
| Lowering `minSdk` below 28 | Current minSdk holds; no API-guard work needed |
| Spectrum performance rework | CONCERNS notes "no issue today"; defer |
| Dedicated diagnostics tab / drawer | Violates PROJECT.md "no new screens" hard constraint (AF-1, AF-4) |
| Modal CAT-error dialogs mid-QSO | Costs the operator a slot; persistent chips instead (AF-2) |
| In-app telemetry / crash uploads | Contradicts INTERNET-permission-removal posture (AF-3) |
| Auto-resume TX after USB reconnect | RF safety — explicit user action required (AF-5) |
| Animated/flashing safety alerts | False-alarm UX harm; calm persistent chips instead (AF-8) |
| Toast spam for every CAT call | Trains operator to dismiss; status chip carries state (AF-9) |
| Auto-retry CAT silently after timeout | Hides real failure; bounded retry then visible stop (AF-11) |
| Settings toggle to disable RF safety watchdog | No responsible power-user case (AF-12) |
| Confirmation dialog for "stop CQ?" | Costs the operator a slot; existing UX is sufficient (AF-13) |
| In-app decoder auto-download / update prompt | Requires INTERNET + trust model; out of scope (AF-14) |
| New v1.1 feature point release tied to this milestone | Release shape is rolling on `unstable`; promote when verified |
| Stealing real estate from Operate/Spectrum/Log/Settings | UX inline only — snackbars, chips, existing rows (PROJECT.md, PARITY-03) |

## Traceability

Empty initially; populated by the roadmapper in Step 8.

| Requirement | Phase | Status |
|-------------|-------|--------|
| FOUND-01..08 | — | Pending |
| REFACTOR-01..09 | — | Pending |
| SAFETY-01..10 | — | Pending |
| RELY-01..07 | — | Pending |
| UX-01..06 | — | Pending |
| HYG-01..05 | — | Pending |
| TEST-01..08 | — | Pending |
| PARITY-01..03 | — | Pending |

**Coverage:**
- v1 requirements: 56 total (8 FOUND + 9 REFACTOR + 10 SAFETY + 7 RELY + 6 UX + 5 HYG + 8 TEST + 3 PARITY)
- Mapped to phases: 0 (roadmapper to fill)
- Unmapped: 56 ⚠️ (expected at this stage)

---
*Requirements defined: 2026-06-21*
*Last updated: 2026-06-21 after initial definition*
