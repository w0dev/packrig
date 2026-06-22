---
phase: 00-foundations
plan: 03
subsystem: testing
tags: [test-fakes, foundations, FOUND-03, FOUND-04, FOUND-05]
dependency_graph:
  requires:
    - 00-02   # test classpath wiring (Turbine, MockK, coroutines-test)
  provides:
    - FakeRigBackend
    - FakeUsbAudioCapture
    - FakeUsbAudioPlayback
    - Ft8DecoderFake
    - UsbAudioPlaybackFake     # parallel test-side interface (Phase 5 will bridge in production)
    - Ft8DecoderApi            # parallel test-side interface (Phase 3/5 will bridge in production)
  affects:
    - Plan 04 (golden-trace harness) — imports all three fakes
    - Phase 1-5 controllers — every controller unit test depends on these fakes
tech_stack:
  added: []                    # no new top-level deps; uses Plan 02's test classpath
  patterns:
    - "Test-only fake at the production interface boundary (FakeRigBackend ↔ RigBackend+CatControl, FakeUsbAudioCapture ↔ AudioEngine)"
    - "Parallel test-side interface declared in same file where production exposes only a concrete class (UsbAudioPlaybackFake, Ft8DecoderApi) — Phase 3/5 plans will bridge by introducing the production interface and adapting the concrete type"
    - "Failure-injection switches as public methods (configure*, simulate*)"
    - "Defensive-copy snapshot accessors (pttEdgesSnapshot, playbackRecordsSnapshot, decodeInvocationsSnapshot)"
    - "Thread-safe state via @Volatile scalars + synchronized(lock) list guards"
key_files:
  created:
    - rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackend.kt
    - rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackendSelfTest.kt
    - audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioCapture.kt
    - audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioPlayback.kt
    - audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioSelfTest.kt
    - ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt
    - ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFakeSelfTest.kt
  modified: []
decisions:
  - "All three fakes ship with a self-test that proves the failure switches. This avoids 'inert fake' regressions during Phase 1-5 — if a switch silently no-ops, the self-test catches it before any controller test fails for the wrong reason."
  - "Used `testDebugUnitTest --tests \"...\"` instead of the plan's literal `:rig:test --tests \"...\"` form. Android Gradle Plugin's `test` lifecycle task does not accept `--tests`; the per-variant `testDebugUnitTest` is the AGP-correct invocation. Documented under Deviations."
  - "Added a per-worktree `local.properties` (gitignored) pointing at the host Android SDK so Gradle could configure the project — the parent repo has this file but worktrees inherit no local.properties."
metrics:
  duration_seconds: 585
  completed_date: 2026-06-22
status: complete
---

# Phase 0 Plan 03: Test Fakes (FakeRigBackend, FakeUsbAudio, Ft8DecoderFake) Summary

Author the three test fakes that PITFALLS Pitfall 10 names as Phase 0's hardest prerequisite — FakeRigBackend (FOUND-03), FakeUsbAudioCapture + FakeUsbAudioPlayback (FOUND-04), and Ft8DecoderFake (FOUND-05) — each at the same boundary as the production sibling it stands in for, with failure-injection switches and a self-test.

## What Was Built

Seven Kotlin files in three modules' `src/test/.../fakes/` subpackages. No production source touched. Every fake is plain Kotlin (no MockK in any self-test), supports the realistic edge cases Phases 1-5 controller tests will need, and ships with self-tests that prove the failure switches work.

### Fakes and Their Boundaries

| Fake                       | Production sibling             | Interface implemented in Phase 0                                                     |
| -------------------------- | ------------------------------ | ------------------------------------------------------------------------------------ |
| `FakeRigBackend`           | `DigirigRigBackend`            | `RigBackend, CatControl` (both production interfaces, implemented directly)          |
| `FakeUsbAudioCapture`      | `UsbAudioCapture`              | `AudioEngine` (production interface, implemented directly)                            |
| `FakeUsbAudioPlayback`     | `UsbAudioPlayback`             | `UsbAudioPlaybackFake` (test-side interface in same file; Phase 5 will bridge production) |
| `Ft8DecoderFake`           | `Ft8Native` (Kotlin `object`)  | `Ft8DecoderApi` (test-side interface in same file; Phase 3/5 will bridge production)  |

### File Inventory

| File                                                                              | Lines | Role                                |
| --------------------------------------------------------------------------------- | ----- | ----------------------------------- |
| `rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackend.kt`                          | 167   | Fake + `PttEdgeKind` + `PttEdge`    |
| `rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackendSelfTest.kt`                  | 140   | 8 JUnit 4 contract cases            |
| `audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioCapture.kt`                 | 84    | Fake (AudioEngine impl)             |
| `audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioPlayback.kt`                | 110   | Interface + `PlaybackRecord` + fake |
| `audio/src/test/java/net/ft8vc/audio/fakes/FakeUsbAudioSelfTest.kt`                | 145   | 8 JUnit 4 contract cases            |
| `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt`             | 141   | Interface + invocation types + fake |
| `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFakeSelfTest.kt`     | 121   | 8 JUnit 4 contract cases            |

### Failure-Injection Switches

**FakeRigBackend** (FOUND-03):

- `configureLatencyMs(ms)` — `frequencyHz()` / `mode()` sleep before returning
- `configureTimeoutHz(true)` / `configureTimeoutMode(true)` — one-shot null reply
- `simulateDetach()` / `reattach()` — every keyPtt/releasePtt records a `DETACHED` edge; CAT methods return null/false
- `configureCatPttResponse(value)` — controls `catPtt()` return value
- `pttEdgesSnapshot()` — defensive copy of the PTT edge history with timestamps from an injectable clock
- `catPttInvocations` — call counter for verification
- `currentFrequencyHz`, `currentMode`, `pttKeyed` — read-only state accessors

**FakeUsbAudioCapture** (FOUND-04, RX half):

- `injectFrames(frames)` — synchronous frame delivery on the test thread (no background thread; deterministic)
- `configureZeroSampleMode(true)` — Pitfall 8 scenario: AudioRecord returns 0 with no error code
- `simulateDeviceRemoved()` / `reattachDevice()` + `deviceRemovedEventCount` — USB unplug mid-session
- `lastStartDeviceId` — observation of caller's `preferredDeviceId`
- `isCapturing` — lifecycle observation

**FakeUsbAudioPlayback** (FOUND-04, TX half):

- `playbackRecordsSnapshot()` — defensive copy of every `playBlocking` call (pcm copy, deviceId, halted flag)
- `configureBlockingMs(ms)` — simulate real playback duration so `stop()` from another thread can interrupt
- `configureFailFastReturn(true)` — Pitfall 3 scenario: simulate "throw inside playback" without throwing on the test thread

**Ft8DecoderFake** (FOUND-05):

- `queueDecodeResults(list)` — FIFO canned decode sequences (each call queues one batch; one batch consumed per `decode()`)
- `configureIsAvailable(false)` — collapses `decode()` / `encode()` to empty arrays (matches production Ft8Native when `!loaded`)
- `configureVersion(null)` — returns `"not loaded"` (matches production behaviour)
- `configureEncodeProducer { msg, hz, sr -> ... }` — substitute deterministic TX byte patterns
- `decodeInvocationsSnapshot()` / `encodeInvocationsSnapshot()` — full call history with sample counts and returned sizes
- `reset()` — clear all state between tests

### Self-Test Results

Each self-test was verified directly via Gradle and a combined run.

| Module       | Test class                  | Tests | Skipped | Failures | Errors | Duration |
| ------------ | --------------------------- | ----- | ------- | -------- | ------ | -------- |
| `rig`        | `FakeRigBackendSelfTest`    | 8     | 0       | 0        | 0      | 0.071 s  |
| `audio`      | `FakeUsbAudioSelfTest`      | 8     | 0       | 0        | 0      | 0.036 s  |
| `ft8-native` | `Ft8DecoderFakeSelfTest`    | 8     | 0       | 0        | 0      | 0.015 s  |

Combined run (`./gradlew :rig:testDebugUnitTest :audio:testDebugUnitTest :ft8-native:testDebugUnitTest --rerun-tasks`) → **BUILD SUCCESSFUL**, 24 fake self-tests + pre-existing tests in those modules all pass.

### Requirements Satisfied

- **FOUND-03** — FakeRigBackend with PTT edges, latency, timeout, detach, CAT-PTT controls.
- **FOUND-04** — FakeUsbAudioCapture (frame injection, zero-sample mode, device removal) + FakeUsbAudioPlayback (playback records, blocking-stop interruption, fail-fast).
- **FOUND-05** — Ft8DecoderFake (canned decode queue, isAvailable/version switches, encode producer override, invocation history).

### No Production Source Modified

`git diff --name-only 2e73a19..HEAD | grep -E "src/main/"` returns **empty**. All seven files live under `*/src/test/java/net/ft8vc/{module}/fakes/`. Phase 0 constraint honoured.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Gradle invocation switched from `:rig:test --tests` to `:rig:testDebugUnitTest --tests`**

- **Found during:** Task 1 verification
- **Issue:** The plan's literal command `./gradlew :rig:test --tests "..."` fails with `Unknown command-line option '--tests'` because AGP's `test` lifecycle task does not accept Gradle's per-task `--tests` filter — only the per-variant `testDebugUnitTest` task does.
- **Fix:** Used `testDebugUnitTest --tests "..."` for each module's self-test verification, and `:rig:testDebugUnitTest :audio:testDebugUnitTest :ft8-native:testDebugUnitTest --rerun-tasks` for the combined run.
- **Files modified:** none — Gradle invocation only
- **Commit:** n/a (verification step)

**2. [Rule 3 - Blocking] Created per-worktree `local.properties`**

- **Found during:** Task 1 verification
- **Issue:** Gradle could not configure the project — the main repo's `local.properties` (with `sdk.dir`) was not present in this worktree. AGP requires `sdk.dir` or `ANDROID_HOME`.
- **Fix:** Created `.claude/worktrees/agent-ab3a0b709f116ce17/local.properties` pointing at `/Users/bsmirks/Library/Android/sdk`. The file is in `.gitignore` (root `.gitignore` entry `local.properties`) so it never enters git history.
- **Files modified:** `local.properties` (gitignored, will not be committed)
- **Commit:** n/a (untracked, gitignored)

**3. [Setup] Synced worktree base to expected SHA `2e73a19`**

- **Found during:** worktree_branch_check at the top of execution
- **Issue:** Worktree HEAD was on a stale commit (`4492527`) created by a setup-time snapshot that pre-dated the orchestrator's Wave 1 merges. The agent's `<worktree_branch_check>` required syncing to `2e73a19` before any plan work.
- **Fix:** `git reset --hard 2e73a1982c8a16ac3e1870e5087aebf51de16d44`. New work commits forward from the correct base.
- **Files modified:** none — worktree reset only
- **Commit:** n/a (setup step)

### Authentication Gates

None — purely local test-source work.

### Threat Surface Scan

The plan's `<threat_model>` covered: T-00-06 (multi-thread fake state — mitigated via `@Volatile` + `synchronized` locks + defensive-copy snapshots), T-00-07 (interface drift — fakes compile against production interfaces directly, so signature drift breaks tests immediately), T-00-08 (test fixture data — accepted, never leaves the test JVM), T-00-SC (supply chain — no new deps).

No new threat surfaces introduced. All fakes live in `src/test/` and never link into the device APK.

### Known Stubs

None. Every fake is fully functional. Future phases will:

- Add the production-side `Ft8NativeAdapter` bridging `Ft8Native` ↔ `Ft8DecoderApi` (Phase 3 or 5).
- Add the production `UsbAudioPlayback` interface that the concrete class will implement (Phase 5).

Neither is required for Phase 0 — controllers in later phases simply take the test-side interface and inject either the fake or the eventual production adapter.

## TDD Gate Compliance

All three tasks were planned as `tdd="true"`, but the plan asked for the fake + self-test in a single commit per task rather than a split RED → GREEN. This was the planner's intentional structure for test-fake authoring (the "feature" of a fake is its API contract, not a behaviour transition), so the gate is satisfied by the combined `test(...)` commit per task.

- `test(00): add FakeRigBackend + self-test for FOUND-03` — Task 1
- `test(00): add FakeUsbAudioCapture + FakeUsbAudioPlayback for FOUND-04` — Task 2
- `test(00): add Ft8DecoderFake + self-test for FOUND-05` — Task 3

## Commits

| Hash      | Message                                                                          |
| --------- | -------------------------------------------------------------------------------- |
| `4200c95` | test(00): add FakeRigBackend + self-test for FOUND-03                            |
| `c846933` | test(00): add FakeUsbAudioCapture + FakeUsbAudioPlayback for FOUND-04            |
| `284d8e6` | test(00): add Ft8DecoderFake + self-test for FOUND-05                            |

## Self-Check: PASSED

- All seven created files exist at their declared paths.
- All three task commits exist in the worktree branch (`4200c95`, `c846933`, `284d8e6`).
- 24 self-tests (8 + 8 + 8) pass; combined module run BUILD SUCCESSFUL.
- `git diff --name-only 2e73a19..HEAD | grep -E "src/main/"` → empty (no production changes).
- Each fake implements either a production interface directly (FakeRigBackend, FakeUsbAudioCapture) or a parallel test-side interface in the same file (UsbAudioPlaybackFake for playback; Ft8DecoderApi for decoder) per the Phase 0 constraint.
