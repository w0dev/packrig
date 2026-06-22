# PITFALLS — FT8VC v1.x Code Health Milestone

**Domain:** Android amateur-radio transceiver refactor (brownfield, solo-maintained, RF-safety-sensitive)
**Researched:** 2026-06-21
**Confidence:** HIGH for items that are direct extensions of CONCERNS.md + CONVENTIONS.md; MEDIUM for items drawn from general Android/Kotlin/coroutines field experience and ham-radio app norms.

This file extends — does not duplicate — `.planning/codebase/CONCERNS.md`. Where CONCERNS already names a defect, this document names the *mistake we are likely to make while fixing it*.

---

## Critical Pitfalls

### Pitfall 1: Big-bang controller extraction (all five at once)

**What goes wrong:** Engineer pulls `SettingsBridge`, `DecodeController`, `TxOrchestrator`, `QsoSessionController`, and `RigSession` out in a single branch. Compiles. UI looks right in emulator. Ships to `unstable`. On the real FT-891, decodes arrive but PTT timing is off by ~200 ms because two controllers now race on `txSlotParity.bit` reads that used to be serialized inside the old ViewModel. Two weeks of bisection.

**Why it happens:** The roadmap is already documented in `OperateViewModel.kt` lines 66–81, which makes the split *look* mechanical. It isn't — the current monolith hides ordering coupling (qsoLock ↔ decode thread ↔ TX thread) that disappears when you separate the classes.

**How to avoid:**
- Extract **one controller per commit** in this order: `SettingsBridge` (lowest coupling) → `RigSession` → `DecodeController` → `TxOrchestrator` → `QsoSessionController` (highest coupling, last).
- Each extraction commit must keep `OperateViewModel` as a thin delegator that forwards to the new controller — no behavior change in the same commit.
- Coroutine migration is a *separate* commit after extraction, even though PROJECT.md groups them in the same phase. "Same phase" ≠ "same commit."
- Behavior-parity snapshot: before starting, capture a 5-minute decode/TX session log on the reference rig (UTC timestamps, slot indices, dial freq, PTT edges). Replay-compare after each extraction commit.

**Warning signs:**
- A controller's constructor takes more than 4 collaborators → coupling not actually reduced; rethink seams.
- The diff for the "extraction" commit changes any line that isn't a `private fun` → moving + editing in one commit; split it.
- Tests for the new controller need a mock of a *sibling* controller → you split the wrong seam.

**Phase to address:** Phase 1 (controller extraction). Set a hard commit-size gate (e.g., < 600 LOC moved per commit).

---

### Pitfall 2: Coroutine migration loses cancellation on rapid slot restart

**What goes wrong:** `Executors.newSingleThreadExecutor()` + `Thread.interrupt()` is replaced with `viewModelScope.launch(Dispatchers.IO) { … }`. User taps "Stop CQ" then "Start CQ" twice in 2 seconds (common during band changes). The new job's `cancel()` is honored, but the *previous* decode coroutine is still inside a JNI call to `Ft8Native.decode()` which is not cancellable — two decoders now race against one `SlotCollector` and the UI shows decode rows from the wrong slot.

**Why it happens:** `kotlinx.coroutines` cooperative cancellation does **not** preempt blocking JNI calls. The current Executor model has the same flaw, but it's hidden because the single-thread executor serializes work. Move to `Dispatchers.IO` (which has ~64 threads) and the race becomes visible.

**How to avoid:**
- Native calls run on a **dedicated single-thread dispatcher** per concern: `Executors.newSingleThreadExecutor().asCoroutineDispatcher()` for decode, another for CAT. Use `Dispatchers.IO` only for non-JNI blocking work.
- Wrap every JNI call in `withContext(decodeDispatcher) { … }` so the dispatcher acts as the serialization point.
- On `cancel()`, **`join()` the previous coroutine before launching the next** — never assume cancel = stopped when JNI is involved.
- Add an `isCanceled` check inside the native wrapper layer (Kotlin side) before invoking JNI, so a queued-but-not-started call drops fast.

**Warning signs:**
- Logs show two `decodeSlot` entries with overlapping timestamps for the same slotStart.
- The decode list ever shows the same row twice with different SNRs.
- Tap-test (start/stop/start/stop in 1 second) leaves the UI in `txBusy=true` for >1 slot.

**Phase to address:** Phase 2 (Executor → coroutine swap). Add a "rapid restart" smoke test (start-stop x 10 in 5 s) to the manual checklist before promotion.

---

### Pitfall 3: PTT left keyed after exception (the `try-finally` that lies)

**What goes wrong:** CONCERNS.md item already prescribes "wrap all TX paths in `try-finally` that unconditionally calls `rig.releasePtt()`." Engineer dutifully adds the wrapper. Six weeks later, an unrelated change to `UsbAudioPlayback` throws inside the playback thread (a *different* thread from the one holding the try-finally). PTT stays keyed until the USB session times out — potentially full carrier into the antenna for 30+ seconds.

**Why it happens:** `try-finally` only protects the call stack it's installed on. TX involves at least two threads (TX orchestration + audio playback) and the PTT-release responsibility lives in the orchestration thread, which has no idea the playback thread exploded.

**How to avoid (defense in depth — all four):**
1. **`try-finally`** on the TX orchestrator path (already prescribed).
2. **`AutoCloseable`/`use { }` wrapper** around the TX session object so structured concurrency cleanup releases PTT on coroutine cancellation.
3. **Hard timeout**: `withTimeoutOrNull(SLOT_DURATION_MS + 500)` around every TX block — if we're still keyed 500 ms past slot end, force release.
4. **Watchdog coroutine** in `TxOrchestrator` that checks every 250 ms: "if `pttKeyed` is true AND we are not in an active TX slot → call `releasePtt()` and surface a TXSAFETY status to UI." This is the last-line guard that catches *all* of the above failing.
5. **`onCleared()`** of every controller that ever calls `keyPtt()` must unconditionally call `releasePtt()` — not optional, not conditional on `pttKeyed` flag (that flag can lie).

**Warning signs:**
- Any new TX-path code added without touching the watchdog test.
- `pttKeyed` boolean exists *only* in one place (it should be cross-checked: orchestrator flag vs. rig backend state).
- The unit test for `TxOrchestrator` doesn't include "throw inside playback callback" as a scenario.

**Phase to address:** Phase 3 (RF safety + reliability). This is the single most important pitfall in the milestone — a keyed PTT on a real FT-891 in DATA-U at 50W is RF in the antenna with no audio, which is illegal on most ham bands and can damage the finals if held.

---

### Pitfall 4: USB disconnect race re-keys PTT

**What goes wrong:** Digirig unplugged mid-TX. `ACTION_USB_DEVICE_DETACHED` fires. Naive handler: "stop TX, clear state, prepare for reconnect." If reconnect handler then auto-restores `qsoRunning=true` because that flag survived in the ViewModel, the next slot keys PTT into a half-initialized rig backend whose serial handle is stale → undefined CAT behavior, possibly stuck PTT via cached RTS state on re-enumeration.

**Why it happens:** Two state machines (USB lifecycle + QSO lifecycle) don't share a single source of truth about "are we actually ready to TX right now?"

**How to avoid:**
- USB-detach broadcast handler must call `TxOrchestrator.emergencyHalt()` which: (a) releases PTT (idempotent — see Pitfall 3), (b) sets `qsoRunning=false`, (c) tears down `RigSession`, (d) requires *explicit user action* to resume (no auto-restart on reconnect this milestone).
- Reconnect path goes through the same init code as cold start — no shortcut that skips license check / TX-enabled check.
- Test fixture: a `FakeUsbBackend` that supports `simulateDetach()` mid-`keyPtt()` call. Required test for `TxOrchestrator`.

**Warning signs:**
- The detach handler and the resume handler don't share an `Enum AppRfState { READY, RX_ONLY, EMERGENCY_HALT }`.
- "Snackbar: Digirig disconnected — RX only" prescribed by PROJECT.md is shown but `txEnabled` setting stays true.
- Reconnect doesn't re-run the license-ack check.

**Phase to address:** Phase 3 (RF safety) — must land at the same time as USB-disconnect snackbar, not a phase later.

---

### Pitfall 5: Tests that rubber-stamp the refactor

**What goes wrong:** PROJECT.md mandates "Unit-test coverage for **every** extracted controller using mocked I/O — hard deliverable." Engineer writes 50 tests against `DecodeController`. All pass. They mock `Ft8Native`, mock `SlotCollector`, mock `StateFlow` emissions. The tests verify that *when DecodeController is told a decode happened, it adds it to the list.* They never verify the controller correctly sequences with `QsoSessionController` on a real slot boundary. Refactor "has tests" but the actual coupling — the part that broke during the refactor — is untested.

**Why it happens:** TESTING.md shows this codebase uses **no mocking framework** and pure-function tests in `core/`. The refactor will require mocks for the first time. Without discipline, mocks become "verify the method I just wrote called the method I just wrote" tautologies.

**How to avoid:**
- For each controller, add **at least one test that exercises a real sibling controller** (the next one down the dependency chain). E.g., `DecodeControllerTest` constructs a real `QsoSessionController` with mocked I/O — not a mocked `QsoSessionController`.
- Mock only the platform boundary (Ft8Native, AudioRecord, SerialPort), never domain types (`QsoMachine`, `QsoMessages`, `SlotCollector`, `TxSlotParity`).
- Add a **golden-trace test**: feed a recorded decode sequence (samples → expected `QsoState` transitions) and assert on the trace. This is the test the refactor would actually break if it broke.
- Coverage target is not "lines" — it's "every transition documented in `QsoMachine` is covered by an integration-style test through the controllers."

**Warning signs:**
- A test file imports more `Mockito.`/`mockk.` than it imports production types.
- Tests don't fail when you delete a line of production code (mutation-test smoke check on a sample of methods).
- No test exists that names a specific real-rig scenario ("CQ → answer → report → R-15 → 73 with auto-seq on").

**Phase to address:** Phase 1 (controller extraction) — tests authored alongside extraction commit, not retrofitted.

---

### Pitfall 6: Compose recomposition storm from 5 controllers feeding one screen

**What goes wrong:** Five controllers each expose a `StateFlow`. `OperateScreen` collects all five with `collectAsStateWithLifecycle()`. During a TX slot, decode arrives (DecodeController flow updates), PTT state changes (TxOrchestrator updates), CAT busy clears (RigSession updates), QSO state advances (QsoSessionController updates) — all within ~50 ms of each other. The screen recomposes 4 times. The waterfall, which is expensive, redraws 4 times. UI jank visible at slot boundaries.

**Why it happens:** The current monolith publishes a single `OperateUiState`; the UI recomposes once per `_state.update`. Split into 5 flows naively and you get 5 recomposition triggers.

**How to avoid:**
- **Combine before collecting**: `combine(a, b, c, d, e) { … OperateUiState(…) }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)`. One observable state for the UI.
- **Stable types**: every field that goes into Compose must be a stable type or marked `@Immutable`. Lists of decodes especially — use `ImmutableList` (kotlinx.collections.immutable) or wrap in a `@Immutable` data class.
- Use `collectAsStateWithLifecycle()` **everywhere** — never plain `collectAsState()`. The latter keeps collecting in background and will fight slot timing.
- Profile with Compose layout inspector before and after; recomposition count per slot should not increase.

**Warning signs:**
- Waterfall flickers at slot boundaries that didn't flicker in v1.0.
- `recomposeHighlighter` (debug tool) shows >2 recompositions/sec for the operate screen.
- A controller's flow emits on every internal field change instead of every "user-visible state change."

**Phase to address:** Phase 1 (controller extraction) — the `combine { … }` lives in the ViewModel that orchestrates the controllers. Get this right at extraction time; retrofitting is painful.

---

### Pitfall 7: Hidden ordering dependency in `qsoLock` removal

**What goes wrong:** CONCERNS.md flags `@Volatile + synchronized(qsoLock)` mix. Engineer "fixes" it by moving `QsoMachine` access into an `actor { }` (channel-based serializer). Compiles. Tests pass. On the real rig, occasionally a CQ is transmitted with a stale `qsoTxParity` because the parity update was queued *after* the TX-encode message but processed *before* it. The old `synchronized` block accidentally enforced an ordering that the actor doesn't.

**Why it happens:** Locks are coarse-grained and accidentally serialize *multiple* things in a known order. Replacing a lock with a finer-grained primitive can preserve correctness within each access while breaking inter-access ordering.

**How to avoid:**
- Before removing the lock, write down — in a comment block at the top of the class — every invariant the lock currently enforces. Reviewer signs off on the list before refactor.
- Replace with a **single-threaded coroutine dispatcher** (`newSingleThreadContext("qso")` or `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`) instead of an `actor` — preserves total ordering of all access.
- The golden-trace test from Pitfall 5 catches this.

**Warning signs:**
- The PR description for the lock removal says "now thread-safe" without enumerating which invariants it was protecting.
- New tests added for the lock removal are all "concurrent N writers, no NPE" — none assert ordering.
- `qsoTxParity` is read in two different controllers (now it should be owned by exactly one — `QsoSessionController`).

**Phase to address:** Phase 2 (coroutine migration) — same phase as `Thread.sleep()` removal, since both touch the same lock pattern.

---

### Pitfall 8: AudioRecord/AudioTrack lifecycle on USB device hot-swap

**What goes wrong:** AudioRecord is bound to a specific `AudioDeviceInfo`. Digirig unplugs and re-plugs (common in field — cable wiggle). New `AudioDeviceInfo` has a different `id`. The capture loop is still calling `read()` on the old AudioRecord, which returns `ERROR_DEAD_OBJECT` on some Android 9-14 builds but **returns 0 (no error) on others** — the loop spins, the UI shows "RX OK," no decodes arrive, nothing is logged.

**Why it happens:** Android AudioRecord behavior on USB device removal is inconsistent across vendor builds. The contract says you get `ERROR_DEAD_OBJECT`; in practice Samsung/Pixel/MIUI differ.

**How to avoid:**
- Treat **any** return of 0 samples for more than 2 consecutive slots (~30 s) as a probable device-loss signal; cross-check against `AudioManager.getDevices()` for USB presence; if device is gone, tear down and recreate the capture chain (or surface "Digirig disconnected" — same path as the USB broadcast).
- Don't rely on `ERROR_DEAD_OBJECT` alone — combine with `AudioDeviceCallback` registered via `AudioManager.registerAudioDeviceCallback()`. Two independent signals.
- Stop AudioRecord with `stop()` + `release()` in that order, in a `finally` block, with a join-timeout (CONCERNS.md already flags this).

**Warning signs:**
- "RX OK" status while no decodes have arrived for >2 minutes.
- `AudioDeviceCallback.onAudioDevicesRemoved()` is not implemented anywhere.
- Field test with deliberate cable wiggle does not recover within one slot of re-plug.

**Phase to address:** Phase 3 (reliability) — bundle with USB disconnect handling. minSdk=28 is fine for all needed APIs (no compat shims required).

---

### Pitfall 9: CAT serial blocking read ignores cancellation

**What goes wrong:** CONCERNS.md prescribes coroutine `withTimeoutOrNull` around CAT operations. Engineer wraps the call: `withTimeoutOrNull(5_000) { rig.frequencyHz() }`. Times out at 5 s. But the underlying `InputStream.read()` on the USB-serial driver doesn't unblock on coroutine cancel or `Thread.interrupt()` — it sits there forever, holding the serial port handle. Next CAT call also times out. Now every CAT call times out.

**Why it happens:** Most USB-serial driver libraries (e.g., usb-serial-for-android) implement `read()` as a blocking call on the USB request that ignores Java interrupts. `withTimeoutOrNull` cancels the coroutine but the underlying read does not return until data arrives or the USB endpoint dies.

**How to avoid:**
- Use the driver's **timeout-aware read variant** if it exists (most do: `serialPort.read(buf, timeoutMillis)`). Drive the timeout from there, not from coroutine layer.
- The coroutine `withTimeoutOrNull` is the *outer* guard; the *inner* guard is the driver-level timeout. Set inner < outer (e.g., 4 s inner, 5 s outer) so the driver returns before the coroutine cancels.
- If a CAT timeout occurs, **close and reopen the serial port** before the next attempt. Do not assume timeout-then-retry recovers; the kernel-side endpoint may need a reset.
- Track a "consecutive timeouts" counter; after N=3, surface "CAT unreachable — check Digirig" and stop trying until user action.

**Warning signs:**
- After a single timeout, all subsequent CAT calls also time out (the smoking gun).
- The fix PR uses only `withTimeoutOrNull` without touching the underlying read.
- No test exists that simulates "read never returns."

**Phase to address:** Phase 3 (reliability) — CAT timeout is explicitly named in PROJECT.md.

---

### Pitfall 10: "Worked on the emulator" — real-hardware-only failures

**What goes wrong:** Refactor is verified in Android Studio emulator. No USB-OTG. No actual Digirig. No JNI. No real-time slot timing pressure. Ships to `unstable`. Field test on FT-891: decodes arrive 200 ms late (because the emulator's `Thread.sleep`/`delay` is hyper-accurate while real-device coroutine wakeups jitter up to 50 ms), causing every other slot to miss the decode window.

**Why it happens:** The emulator has no USB-OTG (you cannot plug a Digirig into it via standard tooling), no `ft8_lib` JNI ARM build is exercised on x86_64 by default, and slot-timing accuracy is unrealistic.

**How to avoid:**
- **Test pyramid for this milestone:**
  1. **JVM unit tests** (core + controllers with mocked I/O) — every commit.
  2. **`FakeRigBackend` + `FakeUsbAudio` harness** running on JVM — every commit. Simulates: slot timing jitter, USB detach mid-call, CAT timeout, decode burst, rapid restart.
  3. **Android instrumented tests** on a real Android device (not emulator) with mocked rig — pre-promotion gate.
  4. **Real FT-891 + Digirig field test** — required before any promotion from `unstable` to `main`. Documented checklist (see Pitfall 12).
- Establish the `FakeRigBackend` early (Phase 1) — every controller needs it for unit testing anyway.
- Add a `SlotTimingJitter` test helper that wraps coroutine `delay` with `±50 ms` random jitter; run the QSO golden-trace test with jitter enabled.

**Warning signs:**
- The phrase "tested on emulator" appears in any PR description without "+ field test scheduled."
- No `FakeRigBackend` exists by the end of Phase 1.
- The CI runs only unit tests, no JVM-level integration tests against the fake harness.

**Phase to address:** Phase 1 (build the fake harness alongside the first controller extraction) — and every subsequent phase reuses it.

---

### Pitfall 11: Decode list bound interacts badly with `combine { }` and Compose

**What goes wrong:** Sliding window of 500 decodes is implemented. Every new decode causes the *entire* `OperateUiState.decodes` list reference to change, which causes Compose to recompose the `DecodeListPanel` from scratch (LazyColumn measures all 500 items). User sees scroll position jump or stutter on every slot.

**Why it happens:** Replacing `List<Row>` with a new `List<Row>` is correct for state but defeats LazyColumn's item-stability optimization unless items have stable keys.

**How to avoid:**
- LazyColumn must use a stable `key = { row -> row.id }` per item.
- Decode row gets a stable `id: Long` field (slot start ms + index within slot is sufficient).
- Use `ImmutableList<DecodeRow>` (kotlinx-collections-immutable) so Compose can verify list-level stability.
- The sliding window should drop from the **front** (oldest) and append to the **back** — if you reverse this, every item's index changes every slot.

**Warning signs:**
- Decode list scroll position resets when a new decode arrives.
- LayoutInspector shows DecodeListPanel recomposing on every state change instead of only the visible items.
- DecodeRow doesn't have an `id` field, or the id isn't unique-and-stable.

**Phase to address:** Phase 4 (decode list bound + UX) — coordinate with the Compose stability work from Pitfall 6.

---

### Pitfall 12: Promotion-gate rot — "verified on the rig" becomes "I plugged it in"

**What goes wrong:** PROJECT.md sets the bar: "verified on a real rig before each promotion from `unstable` to `main`." For phases 1–3 the developer takes this seriously: 30-minute field session, log of decodes/QSOs, no PTT-stuck incidents. By phase 5 (low-stakes hygiene work like INTERNET permission removal) the gate becomes "I keyed once on dummy load, looked fine, promoted." Phase 6 imports a regression from phase 5 that was never caught because the gate rotted.

**Why it happens:** Manual checklists rot under deadline pressure, especially for "low-risk" phases. No automated enforcement of the gate.

**How to avoid:**
- **Promotion checklist as a committed file** (`.planning/promotion-checklist.md`) with explicit items, signed off via PR template checkbox.
- Minimum field-session checklist (every promotion, no exceptions):
  - [ ] App boots cold on the reference device, claims the Digirig automatically.
  - [ ] CAT reads dial frequency within 2 s of opening Operate.
  - [ ] At least 5 decodes received in 3 consecutive slots.
  - [ ] PTT keys (CAT mode) and releases cleanly — verified on rig's TX indicator.
  - [ ] PTT keys (RTS mode) and releases cleanly.
  - [ ] One complete CQ → answer → 73 cycle on dummy load with auto-seq.
  - [ ] Unplug Digirig mid-RX → "Digirig disconnected" snackbar within 5 s, PTT confirmed not keyed.
  - [ ] App killed and relaunched → no PTT-stuck on resume, license ack still gating TX.
- A "low-risk" phase still runs the gate. The point of the gate is to catch surprises, and surprises are by definition unpredicted.
- If a phase truly cannot affect RF behavior (e.g., docstring-only PR), the checklist allows an "RF-irrelevant" skip with a single-line justification — but that justification is in the PR, visible.

**Warning signs:**
- A promotion PR description says "trivial change, no field test."
- The checklist file hasn't been updated in 2+ phases.
- "Verified" appears with no log/screenshot/checkbox evidence.

**Phase to address:** Phase 0 (set up promotion checklist before any phase ships). Recurring — every phase end.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Extract a controller without moving its tests (test the old ViewModel only) | Faster commit | Tests now lie about what's being tested | Never — tests-per-controller is a PROJECT.md hard deliverable |
| Use `Dispatchers.IO` for JNI calls | One-line change | Concurrency bugs (Pitfall 2), unpredictable thread affinity | Only for non-JNI blocking I/O |
| Catch `Throwable` broadly and continue | App doesn't crash | Silent failures; RF safety incidents masked | Only at the topmost coroutine handler, with telemetry + status surfacing |
| Skip the `FakeRigBackend` harness and rely on emulator | Saves a day | Real-hardware-only bugs ship to field (Pitfall 10) | Never — fake harness ROI is positive within Phase 1 |
| Combine 5 flows into 1 via naive `combine` without `distinctUntilChanged` | Works | Recomposition storm (Pitfall 6) | Never — `distinctUntilChanged` is a one-liner |
| Defer ADIF auto-export to a future milestone | Reduces scope | Data-loss risk on uninstall | Only if field-validation proves Phase 4–5 are at risk; ship a manual "Backup now" button as a stopgap |
| Suppress the `INTERNET` permission removal | One fewer change | Misleading manifest, permission-fatigue precedent | Never — re-adding later prompts the user properly |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| USB Audio (AudioRecord) | Assume `read()` returns `ERROR_DEAD_OBJECT` on device removal | Combine return-value check + `AudioDeviceCallback` + `AudioManager.getDevices()` polling (Pitfall 8) |
| USB Serial (CAT) | Wrap `read()` only in coroutine timeout | Driver-level timeout primary, coroutine timeout outer guard; close+reopen port on timeout (Pitfall 9) |
| JNI (`Ft8Native`) | Call from `Dispatchers.IO` | Dedicated single-thread dispatcher per native concern; never assume cancellable; serialize at the dispatcher layer (Pitfall 2) |
| FT-891 CAT (`TX1;`/`TX0;`) | Send `TX0;` once on stop | Send `TX0;` in `finally`, again on `onCleared()`, and in the watchdog (Pitfall 3) — idempotent commands are safe to over-send |
| Android USB permission | Re-prompt on every reconnect | `usb_device_filter.xml` for auto-grant; persist permission via `UsbManager.requestPermission` only on first attach; tighten filter to Digirig PID/VID |
| Room (logbook) | Block UI thread on DB write | All DB access via `withContext(Dispatchers.IO)`; auto-ADIF-export uses `ApplicationScope`, not `viewModelScope` |
| Compose Lifecycle | Use plain `collectAsState()` | Always `collectAsStateWithLifecycle()` |
| StateFlow updates from native callback thread | Update flow directly | Marshal to main/single dispatcher first |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Decode list unbounded growth | Memory creep over multi-hour session | Sliding window cap 500 + stable keys (Pitfall 11) | ~2 hours of active monitoring |
| Compose recomposition storm from 5 flows | Waterfall flicker at slot boundaries | Combine flows with `distinctUntilChanged` (Pitfall 6) | Immediately on first split |
| Waterfall Bitmap re-allocated on rotation | Heap pressure, GC pauses | Cache bitmap in ViewModel / hoist | Every rotation; field-relevant on screen toggles |
| `combine { }` recomputes whole state on any sub-flow change | Per-slot CPU spike, UI lag | Sub-state caching: each controller emits only when its slice changes | At 5 sub-flows × 4 emissions/slot = 20 recomputes/slot |
| JNI calls on `Dispatchers.IO` thread pool | Decode races, native state corruption (Pitfall 2) | Single-thread dispatcher per JNI concern | Under rapid start/stop; on multi-core devices |
| ADIF auto-export holds Room transaction | Logbook insert stalls during export | Snapshot read → write to temp file → atomic rename; never hold tx across file I/O | When logbook >1000 rows |

---

## Security / Safety Mistakes (RF-specific)

| Mistake | Risk | Prevention |
|---------|------|------------|
| PTT release relies on single try-finally | Stuck carrier on antenna (FCC violation, finals damage) | 4-layer defense (Pitfall 3) |
| USB reconnect auto-resumes TX | RF in antenna without operator awareness | Detach → emergency-halt → explicit user action to resume (Pitfall 4) |
| License acknowledgment check skipped in reconnect path | TX possible without ack | Reconnect goes through full init; license check is non-skippable (Pitfall 4) |
| `usb_device_filter.xml` too permissive | App claims unrelated USB devices | Tight PID/VID filter — CP2102 (10C4:EA60) + FT240X only |
| INTERNET permission declared but unused | Permission fatigue; future code can quietly use network without prompt | Remove until NTP feature lands |
| Logbook on external storage without scoping | Privacy leak (callsigns + locations + timestamps) | Auto-export to `getExternalFilesDir()` (app-private); user export via Share Intent only on explicit action |
| `tx_enabled` survives across crashes without re-confirm | After a crash, app may TX-enable on first restart | Consider gating `tx_enabled=true` resume behind a one-tap re-confirm if last shutdown was abnormal — flag for v1.2 if not in this milestone |
| Native library load fails silently | User can press "Start CQ" with no decoder → may key empty carrier | Visible failure state in Settings → About; refuse to enable TX if `Ft8Native.loaded == false` |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| "CAT timeout" surfaced as a toast that disappears | User misses the warning while looking at the waterfall | Persistent status chip in the existing CAT status row |
| USB disconnect snackbar dismisses after 5 s | User notices a missing-decode pattern but no longer sees the cause | Snackbar alerts; state persists as status chip until reconnect or dismiss |
| Decode-count cap silently drops decodes | User loses logbook-relevant decodes | Show "showing last 500 — older cleared" indicator at top of list when capped |
| ADIF auto-export saves silently | User doesn't know backups exist or where | "Last backup: 15 min ago" line in Settings → Logbook |
| Native lib version mismatch shows raw error string | User cannot interpret "UnsatisfiedLinkError" | "Decoder library couldn't load — please reinstall the app" with Settings → About link |
| New controllers' state changes cause flicker | Operator perceives instability mid-QSO | Compose stability work (Pitfall 6); pre/post jank comparison required for promotion |

---

## "Looks Done But Isn't" Checklist

Run this before declaring any phase done:

- [ ] **Controller extracted:** Tests exist that exercise it with at least one *real* sibling controller, not all-mocks (Pitfall 5).
- [ ] **Coroutine migration:** Rapid start/stop test (10× in 5 s) passes on real device with no orphan decode and no stuck PTT (Pitfall 2).
- [ ] **PTT safety:** All four defense layers present: `try-finally`, `use { }`/AutoCloseable, `withTimeout`, watchdog coroutine (Pitfall 3).
- [ ] **USB disconnect:** Snackbar + status chip + emergency-halt + license re-check all present (Pitfall 4).
- [ ] **CAT timeout:** Driver-level timeout + coroutine timeout + port close+reopen + consecutive-failure threshold all present (Pitfall 9).
- [ ] **AudioRecord lifecycle:** Both return-value check and `AudioDeviceCallback` are wired (Pitfall 8).
- [ ] **Decode list bound:** Stable keys, ImmutableList, oldest-dropped semantics (Pitfall 11).
- [ ] **Compose stability:** `combine + distinctUntilChanged`, `@Immutable` data classes, `collectAsStateWithLifecycle`, recompose count measured (Pitfall 6).
- [ ] **Tests:** At least one golden-trace test through real controller stack with mocked platform boundary (Pitfall 5, 10).
- [ ] **FakeRigBackend:** Exists and is used by tests for this controller (Pitfall 10).
- [ ] **Native lib check:** App refuses to enable TX if `Ft8Native.loaded == false`.
- [ ] **Slot parity:** No `Int` literal `0`/`1` appears for parity in this commit; only `TxSlotParity` enum.
- [ ] **Manifest:** INTERNET permission removed (Phase 5); `usb_device_filter.xml` tightened (Phase 3 or 5).
- [ ] **Promotion checklist** signed off in PR before merging to `unstable` for end-of-milestone (Pitfall 12).
- [ ] **Field session** performed on FT-891 + Digirig with log/screenshots committed under `.planning/field-sessions/` before promoting to `main`.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Big-bang extraction regressed RX/TX | HIGH | Revert via the phase-commit manifest (`/gsd-undo`), redo extraction in 5 commits per Pitfall 1 ordering |
| Coroutine cancellation regression (decode race) | MEDIUM | Add dedicated single-thread dispatcher (Pitfall 2); add rapid-restart test; re-test on real rig |
| PTT stuck after exception | HIGH (operator must manually unkey; possible damage) | Hotfix: add watchdog coroutine immediately + ship hotfix to `unstable`; then audit all TX paths for the 4-layer defense |
| USB disconnect re-keys PTT | HIGH (safety) | Hotfix: emergency-halt path; remove auto-resume; force re-init through license check |
| Tests rubber-stamp refactor (caught by field bug) | MEDIUM | Add golden-trace test for the missed scenario; require it for every future controller PR |
| Compose recomposition storm | LOW | One-commit fix: introduce `combine + distinctUntilChanged + @Immutable` |
| CAT-timeout cascade (port stuck) | MEDIUM | Add close+reopen on timeout; add consecutive-failure threshold; ship as point hotfix |
| AudioRecord doesn't recover on hot-plug | MEDIUM | Add `AudioDeviceCallback`; add zero-samples-watchdog; recreate capture on detected loss |
| Promotion shipped without field test | HIGH (reputation; users on stable affected) | Roll back stable to previous APK from Releases; re-do field test; tighten promotion checklist enforcement |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 1. Big-bang controller extraction | **Phase 0** (set ordering & commit-size gate) + **Phase 1** (execute) | One-controller-per-commit visible in `git log`; behavior-parity snapshot diff |
| 2. Coroutine cancellation loses cancellations | **Phase 2** (Executor → coroutine) | Rapid-restart smoke test passes on real device |
| 3. PTT left keyed after exception | **Phase 3** (RF safety) | Watchdog test fires on injected exception; rig TX indicator not lit after exception scenario |
| 4. USB disconnect re-keys PTT | **Phase 3** (RF safety) — same phase as #3, non-negotiable | Detach-during-TX test on real hardware; license re-check verified |
| 5. Tests rubber-stamp the refactor | **Phase 1** (controller extraction, tests authored alongside) | Golden-trace test exists; mutation-test sample shows tests fail on production changes |
| 6. Compose recomposition storm | **Phase 1** (set the combine pattern at extraction time) | Recompose count measured pre/post; no waterfall flicker at slot boundary |
| 7. `qsoLock` removal breaks ordering | **Phase 2** (alongside coroutine migration) | Invariants documented in PR; golden-trace passes |
| 8. AudioRecord doesn't recover on hot-plug | **Phase 3** (reliability) | Cable-wiggle test recovers within 1 slot |
| 9. CAT serial read ignores cancellation | **Phase 3** (CAT timeout) | Driver-level timeout configured; port reopen on timeout verified; cascade-failure test passes |
| 10. Emulator-only verification | **Phase 1** (build `FakeRigBackend` early) + **every phase** (real-rig gate) | FakeRigBackend committed by end of Phase 1; field session before every promotion |
| 11. Decode list bound interacts badly with Compose | **Phase 4** (decode list cap + UX) | Stable keys verified; no scroll jump on slot tick |
| 12. Promotion-gate rot | **Phase 0** (set up checklist) + **every phase end** | Checklist file exists; every promotion PR has the checkbox completed; field log committed |

**Cross-cutting (every phase):**
- "Looks Done But Isn't" checklist run before phase declared complete.
- Promotion checklist run before any merge from `unstable` to `main`.
- Real-rig field session for any phase that touches RF, USB, CAT, audio, or threading.

---

## Sources

- `.planning/codebase/CONCERNS.md` (audit dated 2026-06-21) — the issues this document extends.
- `.planning/codebase/TESTING.md` — test stack reality (JUnit 4, no mocking framework, pure-function bias).
- `.planning/codebase/INTEGRATIONS.md` — hardware boundary (Digirig USB audio + CP2102 serial, FT-891 CAT).
- `.planning/PROJECT.md` — Core Value ("rig still keys, decodes still arrive"), Constraints (behavior parity, real-rig gate), Out of Scope.
- `OperateViewModel.kt` lines 66–81 — the controller split the developer pre-documented.
- General Android/Kotlin field experience: cooperative cancellation semantics, USB driver behaviors across Android 9–14, Compose recomposition stability rules.
- Amateur radio operator practice: defense-in-depth for PTT, idempotent unkey commands, license-gated TX.
