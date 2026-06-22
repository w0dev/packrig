# Project Research Summary

**Project:** FT8VC v1.x Code Health Milestone
**Domain:** Android amateur-radio transceiver refactor (brownfield, RF-safety-sensitive, solo-maintained)
**Researched:** 2026-06-21
**Confidence:** HIGH

## Executive Summary

FT8VC is a shipping Android FT8 transceiver app driving a Yaesu FT-891 + Digirig over USB. The v1.x code-health milestone is a disciplined refactor of a 1,135-line monolithic ViewModel into 5 focused controllers (SettingsBridge, RigSession, DecodeController, TxOrchestrator, QsoSessionController) with a simultaneous migration from manual threads/Executors to structured coroutines, plus 15 operational-reliability hardening items drawn from a concerns audit. This is not a feature milestone -- every deliverable either closes a known field-safety gap or makes the codebase testable.

The recommended approach is incremental extraction: one controller per commit, lowest-coupling first, with dedicated single-thread coroutine dispatchers replacing Executors for JNI and USB-serial work. Three new test-scope dependencies (Turbine, MockK, kotlinx-collections-immutable) are the entire addition to the dependency graph. The architecture lands on a combine + distinctUntilChanged + stateIn flow assembly pattern that preserves the current single-StateFlow UI contract while splitting ownership across 5 controllers. A FakeRigBackend + golden-trace test harness is the foundational test artifact -- it must exist before any controller extraction begins.

The dominant risk is RF safety: a stuck PTT at 50W is an FCC violation and can damage rig finals. The mitigation is a 4-layer defense (try-finally, AutoCloseable, withTimeoutOrNull, watchdog coroutine) owned entirely by TxOrchestrator, plus an emergency-halt state machine on USB disconnect that refuses to auto-resume TX. Secondary risks are coroutine cancellation races on JNI calls (mitigated by dedicated single-thread dispatchers) and Compose recomposition storms from 5 flows feeding one screen (mitigated by the combine pattern). Every phase gates on real-rig field verification -- no emulator-only promotions.

## Key Findings

### Recommended Stack

The stack recommendation is "add the minimum that earns its keep." Only three new dependencies, all justified by specific pitfall mitigations. No DI framework (Hilt/Koin are explicit anti-recommendations for a 5-controller solo refactor). No Robolectric. No JUnit 5 migration. The existing Kotlin 2.3.21 + AGP 9.2.1 + Compose BOM 2026.05.01 stack stays untouched.

**Core additions:**
- **Turbine 1.2.1**: Pull-based StateFlow/Flow test assertions -- required for golden-trace and controller unit tests
- **kotlinx-collections-immutable 0.4.0**: ImmutableList for Compose stability -- required to prevent recomposition storms
- **MockK 1.14.7**: Kotlin-native mocking for platform boundary types (JNI, USB, AudioRecord facades)

**Critical version constraint:** Stay on kotlinx-coroutines 1.10.2 (do not bump to 1.11.0, which targets Kotlin 2.2.20, not 2.3.21).

### Expected Features

This is a code-health milestone, so "features" are operational-reliability deliverables. See FEATURES.md for full detail.

**Must have (table stakes -- 15 items, all P1):**
- 4-layer PTT defense against stuck key (RF safety, non-negotiable)
- CAT timeout with persistent status chip + close/reopen recovery + consecutive-failure threshold
- USB disconnect snackbar + emergency-halt state machine + no auto-resume TX
- AudioRecord hot-swap recovery (return-value check + AudioDeviceCallback + zero-samples watchdog)
- Native lib load/version handshake -- TX disabled if decoder not loaded
- Capture thread interrupt-with-timeout (no orphan threads)
- Decode-loop failure counter surfaced as status chip
- USB device filter scoped to Digirig PIDs (CP2102 + FT240X)
- Decode list capped at 500 with wired Clear button + ImmutableList + stable keys
- ADIF auto-export to app-private storage
- Remove INTERNET permission
- License re-check on USB reconnect

**Should have (differentiators -- 7 items):**
- Operator-visible watchdog feedback ("TX safety halt" snackbar/chip)
- Emergency-halt enum state machine (READY / RX_ONLY / EMERGENCY_HALT)
- FakeRigBackend + golden-trace QSO replay test harness (shipped in-repo)
- Behavior-parity snapshot fixtures committed alongside refactor commits
- Compose recomposition budget measured and regression-tested
- Promotion checklist as committed artifact with field-session evidence
- TxSlotParity enum used end-to-end (no raw Int 0/1)

**Defer (future milestones):**
- Cloud/Drive logbook backup, logbook encryption, NTP clock sync, wider rig support, decoder tuning, spectrum circular-buffer rewrite

### Architecture Approach

The post-refactor target is a thin orchestrator ViewModel (~250 LOC) that constructs 5 controllers, wires their StateFlow slices via a single combine operator, and routes UI intents. Each controller is a plain Kotlin class (not a ViewModel) taking a CoroutineScope + dedicated dispatcher + test-seam interfaces. Three dedicated single-thread dispatchers (decode, encode, CAT) replace the current Executors; Dispatchers.IO is reserved for Room/file I/O. The QSO loop migrates from Thread.sleep to delay on a fourth dedicated dispatcher, eliminating qsoLock.

**Major components:**
1. **SettingsBridge** -- reads settings repo, emits settings slice, fires station-identity-changed events
2. **RigSession** -- owns CAT polling, PTT key/release, USB device discovery, emergency-halt entry point
3. **DecodeController** -- owns RX audio capture, slot collection, JNI decode, decode list (capped, ImmutableList), emits DecodeBatch to QSO
4. **TxOrchestrator** -- owns encode + playback + PTT with 4-layer defense + watchdog coroutine
5. **QsoSessionController** -- owns QsoMachine, auto-seq, parity, slot clock; consumes decodes, requests TX

### Critical Pitfalls

1. **Big-bang extraction** -- Extract one controller per commit in coupling order; behavior-parity snapshot before/after each. A 5-controller simultaneous extraction hides ordering bugs that only surface on real hardware.
2. **JNI cancellation race** -- JNI calls are not cooperatively cancellable. Must use dedicated single-thread dispatchers (not Dispatchers.IO), join() previous job before launching next. Rapid start/stop x10 smoke test required.
3. **PTT stuck after exception** -- try-finally alone is insufficient (cross-thread exceptions). All four defense layers are non-optional. Watchdog coroutine is the last-line guard.
4. **USB disconnect re-keys PTT** -- Detach handler must call emergencyHalt(), tear down rig session, and require explicit user action to resume. No auto-restore of qsoRunning.
5. **qsoLock removal breaks ordering** -- Document all lock invariants before removal; replace with single-thread dispatcher (not actor); golden-trace test catches violations.

## Implications for Roadmap

Based on research, the architecture document proposes 8 phases. The dependency graph and risk profile strongly support this structure.

### Phase 0: Foundations
**Rationale:** Test seams and fake harnesses must exist before any extraction. PITFALLS Pitfall 10 makes FakeRigBackend a hard prerequisite.
**Delivers:** AudioInput/AudioOutput interfaces, FakeRigBackend, FakeAudioInput/FakeAudioOutput, Ft8Decoder facade, ControllerDispatchers class, golden-trace fixture, promotion checklist template.
**Addresses:** D-3, D-4, D-6 (foundational artifacts)
**Avoids:** Pitfall 10 (emulator-only verification), Pitfall 12 (promotion-gate rot)

### Phase 1: Extract SettingsBridge
**Rationale:** Lowest coupling (one dependency: SettingsRepository). Proves the controller pattern and slice-merging shape before complex controllers.
**Delivers:** SettingsBridge controller + SettingsSlice + unit tests
**Addresses:** Part of the HIGH Architecture requirement (OperateViewModel decomposition)
**Avoids:** Pitfall 1 (big-bang extraction), Pitfall 5 (rubber-stamp tests)

### Phase 2: Extract RigSession
**Rationale:** CAT and PTT are pre-existing abstractions in RigController. Extracting the orchestration layer unlocks DecodeController and TxOrchestrator.
**Delivers:** RigSession controller + CAT polling + PTT suspend functions + RigSlice + tests with FakeRigBackend
**Addresses:** Part of HIGH Architecture; begins CAT timeout work (TS-1)
**Avoids:** Pitfall 9 (CAT serial blocking -- mechanism swap here, layered guards in Phase 6)

### Phase 3: Extract DecodeController
**Rationale:** Most user-visible controller; benefits most from dedicated dispatcher. Depends on RigSession only weakly.
**Delivers:** DecodeController + decode dispatcher + ImmutableList decode rows + stable keys + DecodeBatch SharedFlow + tests with FakeAudioInput/FakeFt8Decoder
**Addresses:** Decode list cap (TS-11), Compose stability prep (D-5), decode failure counter (TS-8)
**Avoids:** Pitfall 2 (cancellation race), Pitfall 6 (recomposition storm prep), Pitfall 11 (list stability)

### Phase 4: Extract QsoSessionController + Remove qsoLock
**Rationale:** Highest coupling -- needs stable interfaces from all other controllers. Removing qsoLock in its own phase keeps the lock-invariant documentation separate from TX extraction.
**Delivers:** QsoSessionController + qsoDispatcher + Thread.sleep to delay migration + qsoLock removal + golden-trace test passing through real controller stack
**Addresses:** HIGH Architecture (volatile/synchronized race window), coroutine migration
**Avoids:** Pitfall 7 (qsoLock ordering), Pitfall 2 (rapid restart)

### Phase 5: Extract TxOrchestrator + Introduce combine Block
**Rationale:** TX touches RF safety -- do it last after all supporting controllers are stable. The combine block is only meaningful once all 5 slices exist.
**Delivers:** TxOrchestrator with 4-layer PTT defense + watchdog + combine flow assembly in residual ViewModel + collectAsStateWithLifecycle audit
**Addresses:** RF safety (TS-2, D-1), Compose stability (Pitfall 6), TS-14 (license re-check via emergency-halt)
**Avoids:** Pitfall 3 (PTT stuck), Pitfall 4 (USB disconnect re-keys), Pitfall 6 (recomposition storm)

### Phase 6: CAT + Audio Reliability Hardening
**Rationale:** Layered guards (driver-level timeout, port reopen, consecutive-failure threshold, AudioDeviceCallback) are one-controller changes that depend on the refactored RigSession and DecodeController.
**Delivers:** CAT timeout layered guards (TS-1, TS-15), AudioRecord hot-swap recovery (TS-4), USB disconnect notification (TS-3), emergency-halt state machine (D-2)
**Addresses:** HIGH Reliability items, MEDIUM Robustness items
**Avoids:** Pitfall 8 (AudioRecord lifecycle), Pitfall 9 (CAT cascade)

### Phase 7: UX Polish + Manifest Hygiene
**Rationale:** Low-risk, single-file changes that depend on the refactored controllers being stable.
**Delivers:** Decode list Clear button wire-up + "showing last 500" indicator, TxSlotParity enum cleanup (D-7), USB filter tightening (TS-9, TS-10), INTERNET permission removal (TS-13), native lib version display (TS-5, TS-6)
**Addresses:** MEDIUM Robustness, LOW Hygiene/Security items

### Phase 8: ADIF Auto-Export
**Rationale:** Independent of the refactor; can slot anywhere but benefits from stable ApplicationScope patterns established in earlier phases.
**Delivers:** Room snapshot to atomic ADIF write to getExternalFilesDir() on QSO commit + app pause + daily timer; Settings Logbook "Last backup" row (TS-12)
**Addresses:** LOW Hygiene (data-loss insurance)

### Phase Ordering Rationale

- Phases 0-5 follow the architecture document's dependency graph: foundations first, then controllers in ascending coupling order, with the combine block landing last alongside the highest-risk TX extraction.
- Phases 6-8 are post-refactor reliability and polish work, each touching only one controller -- validating that the split was correct.
- RF safety work (PTT defense, emergency-halt) concentrates in Phase 5-6 to allow focused field testing on the reference rig.
- The qsoLock removal (Phase 4) is deliberately separated from TxOrchestrator extraction (Phase 5) to keep the invariant-documentation commit clean.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 4:** qsoLock invariant documentation requires close reading of the current synchronization pattern; golden-trace fixture design needs careful slot-timing modeling
- **Phase 5:** 4-layer PTT defense implementation has subtle cross-thread exception semantics; watchdog coroutine timing needs real-rig calibration
- **Phase 6:** CAT driver-level timeout behavior varies by usb-serial-for-android version; AudioDeviceCallback vendor inconsistencies on Android 9-14

Phases with standard patterns (skip research-phase):
- **Phase 0:** Boilerplate interface + fake creation; well-documented patterns
- **Phase 1:** Trivial extraction; one dependency, one slice
- **Phase 7:** Manifest edits, enum cleanup, button wiring -- mechanical work
- **Phase 8:** Room query + file write + atomic rename -- standard Android patterns

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Versions verified against Maven Central; minimal additions; coroutines version constraint well-justified |
| Features | HIGH | Every item maps 1:1 to CONCERNS.md or PITFALLS.md; peer-app analysis grounds UX decisions |
| Architecture | HIGH | Controller boundaries match the developer's in-code roadmap; combine pattern is NIA-standard; dispatcher model is prescriptive |
| Pitfalls | HIGH | All critical pitfalls grounded in CONCERNS.md defects + domain-specific RF safety requirements; recovery strategies documented |

**Overall confidence:** HIGH

### Gaps to Address

- **Slot-timing jitter under coroutine delay**: The SlotTimingJitter test helper is prescribed but untested on real hardware. Phase 4 planning should include a jitter characterization session on the reference device.
- **usb-serial-for-android timeout API**: The driver-level timeout variant (serialPort.read(buf, timeoutMillis)) availability should be verified against the specific driver version in the project before Phase 6 planning.
- **AudioDeviceCallback vendor behavior**: Android 9-14 vendor inconsistencies for USB audio removal are documented anecdotally, not systematically. Phase 6 may need a focused spike on the reference device.
- **Compose recomposition measurement tooling**: The recomposeHighlighter debug tool is referenced but not yet integrated. Phase 5 planning should confirm the measurement approach.

## Sources

### Primary (HIGH confidence)
- .planning/codebase/CONCERNS.md -- the canonical issue list driving this milestone
- .planning/codebase/ARCHITECTURE.md, STACK.md, TESTING.md, INTEGRATIONS.md -- current codebase state
- .planning/PROJECT.md -- scope, constraints, core value
- OperateViewModel.kt lines 66-81 -- developer's pre-documented controller split
- kotlinx.coroutines releases (GitHub) -- version compatibility verified
- Turbine, MockK, kotlinx-collections-immutable releases -- versions verified against Maven Central
- Android Developers docs -- Compose stability, broadcast receiver flags, lifecycle APIs

### Secondary (MEDIUM confidence)
- Digirig forum threads -- competitor app behavior (FT8CN disconnect handling, CAT timeout reports)
- WSJT-X User Guide -- ADIF logging cadence baseline
- ProAndroidDev / Koin docs -- DI framework comparison (informed "no DI" recommendation)
- Now in Android architecture guide -- controller-with-slice pattern reference

---
*Research completed: 2026-06-21*
*Ready for roadmap: yes*
