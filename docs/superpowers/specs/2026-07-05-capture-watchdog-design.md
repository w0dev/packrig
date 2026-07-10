# Capture Watchdog — Silent Capture-Stall Recovery

**Date:** 2026-07-05
**Branch:** `multi-rig` (implementation to land via a worktree off `multi-rig`)
**Status:** Design approved, pending spec review

## Problem

After a long operating session, RX decoding can stop silently and only a full
app restart recovers it. Root-cause analysis of the RX pipeline shows the failure
mode:

`UsbAudioCapture` runs a single capture thread whose loop is the *only* source of
`onFrames` — which drives level, spectrum, slot collection, and decode
triggering. On a negative `AudioRecord.read()` (most commonly `ERROR_DEAD_OBJECT`,
which the platform returns when the USB audio object dies / re-enumerates during a
long session), the loop does `Log.e` + `break` and the thread exits — but
`running` stays `true`, so `isCapturing` keeps reporting healthy. Nothing restarts
the thread, nothing notifies the VM, and the operator sees decoding simply stop
([UsbAudioCapture.kt:53-65](../../../audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt)).

A second, subtler mode is a live-but-starved thread (`read()` returns 0
indefinitely on a wedged device): the thread spins, no frames flow.

The existing zero-sample watchdog ([OperateViewModel.kt:334](../../../app/src/main/java/net/ft8vc/app/OperateViewModel.kt))
cannot catch either mode: it keys off `decodeController.slice` updates, which only
occur when frames are *already flowing*. Both stall modes stop the frame flow, so
the slice never updates and the watchdog never fires.

This is the failure that motivated the whole timing investigation; it is distinct
from clock accuracy (the DT self-cal feature) and, per the milestone's core value,
directly threatens "decodes still arrive … in the field."

## Goal

Detect a capture stall (no frames arriving while we believe we are capturing) and
recover automatically by recreating the `AudioRecord`, with guards that prevent
thrash and keep UI state honest. No new dependencies; no change to the audio
module's public interface.

## Non-Goals

- No migration to Oboe/AAudio. (Considered and rejected for this milestone: a
  whole new NDK audio engine is a new top-level dependency, is not a controller
  seam, and swapping the proven `AudioRecord` RX/TX path risks the byte-equivalent
  fidelity bar for zero benefit on a 12 kHz, latency-insensitive workload. The
  watchdog delivers the one relevant benefit — auto-restart on death — locally.)
- No engine-side error callback (`AudioEngine.start` stays unchanged). See
  Decisions.
- No change to the existing zero-sample watchdog or the device-removal path.

## Decisions (resolved during brainstorming)

1. **Detection: frame-arrival heartbeat only.** Stamp a last-frame timestamp on
   each `onFrames`; a monitor detects "no frames for `stallThresholdMs` while
   capturing and not transmitting." One mechanism catches BOTH the dead-thread and
   starved-thread modes, with zero change to `AudioEngine` / `CaptureLifecycle` /
   `UsbAudioCapture`. An engine self-report would only catch the dead-thread case,
   adds interface surface, and buys marginal detection latency in a 15-second-slot
   app — rejected (YAGNI; revisit only if the field shows the heartbeat is
   insufficient).

2. **Give-up policy: cap, then stop + persistent retry chip.** Auto-recover for
   the first `maxRestarts` consecutive stalls (handles transient USB glitches);
   if it still fails, stop retrying, set `isCapturing = false` (fixing today's
   stale-state lie), and surface a persistent, tappable "Audio capture failed —
   tap to retry" chip (mirroring the existing CAT-unreachable retry). Chosen over
   retry-forever (battery drain / log spam on dead hardware) and stop-silently
   (operator discovers the outage too late). In the field the fix is often
   physical (reseat the Digirig), so operator-in-control at the cap is correct.

3. **Placement: a pure, testable `CaptureWatchdog` seam** rather than more logic
   inlined in the 1135-line `OperateViewModel`. Aligns with the milestone's
   monolith-refactor aim and makes the debounce/backoff/cap logic unit-testable.

## Architecture

### New: `CaptureWatchdog` (pure state machine)

No Android imports; time injected. Lives in `app/.../controllers/` to sit with the
RX pipeline it serves (it needs no app types, so it is trivially unit-testable).

```kotlin
class CaptureWatchdog(
    private val stallThresholdMs: Long,   // ~3000: no frames this long => stalled
    private val restartGraceMs: Long,     // ~3000: after a restart, allow spin-up before re-judging
    private val maxRestarts: Int,         // ~3: consecutive failed recoveries before GiveUp
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun onFrame()          // lastFrameAtMs = clock()
    fun onCaptureStarted() // lastFrameAtMs = clock(); open a restartGrace window (does NOT clear restartCount)
    fun reset()            // full reset: lastFrameAtMs = clock(), restartCount = 0, grace cleared

    /** Called on each monitor tick; may mutate internal counters (see below). */
    fun poll(isCapturing: Boolean, isTransmitting: Boolean, devicePresent: Boolean): Decision
}

sealed interface Decision {
    object Idle : Decision       // nothing to do
    object Recover : Decision    // stalled within retry budget => restart
    object GiveUp : Decision     // exhausted retries => stop + surface failure
}
```

`poll` is the single driver of the state machine (there is no separate
`onHealthy` hook — recovery is detected inside `poll`). Logic:

1. If `!isCapturing || isTransmitting || !devicePresent` → `Idle`.
2. Let `stalled = now - lastFrameAtMs >= stallThresholdMs`.
3. If **not** stalled: if `restartCount > 0` and we are past the `restartGrace`
   window, a prior recovery succeeded → clear `restartCount`; return `Idle`.
4. If stalled but still within the `restartGrace` window (just restarted, giving
   the new `AudioRecord` time to spin up) → `Idle`.
5. If stalled and `restartCount >= maxRestarts` → `GiveUp` (fires once; the VM
   then sets `isCapturing = false`, so subsequent polls short-circuit at step 1).
6. Otherwise (stalled, budget remaining) → increment `restartCount`, and return
   `Recover`. The VM pairs this with `restartCapture()`, whose `beginCapture`
   calls `onCaptureStarted` to open a fresh grace window.

Only *consecutive* failed recoveries count toward `maxRestarts`; a healthy
interval past the grace window (step 3) resets the counter.

### VM wiring (`OperateViewModel`)

- Own one `CaptureWatchdog`.
- Wrap the `onFrames` handed to `captureLifecycle.start`: `{ frames -> watchdog.onFrame(); decodeController.onFrames(frames) }` (currently passes `decodeController::onFrames` directly in `beginCapture`).
- `beginCapture()` calls `watchdog.onCaptureStarted()`; `stopCapture()` calls `watchdog.reset()`.
- A periodic monitor coroutine (mirroring the existing zero-sample watchdog coroutine) ticks ~1 s and calls `watchdog.poll(isCapturing, isTransmitting, devicePresent)` where `devicePresent = AudioInputs.list(app).any { it.isUsb }` (same check the zero-sample path uses). Acts on the `Decision`:
  - `Recover` → transient snackbar "Audio stalled — restarting capture"; `restartCapture()` (which already tears down + recreates the `AudioRecord`; `beginCapture` inside it re-arms the grace window via `onCaptureStarted`).
  - `GiveUp` → set `isCapturing = false`; publish `captureFailed = true` for the chip.
  - `Idle` → nothing.
- Recovery-success detection lives inside `poll` (step 3 above) — no per-frame VM hook is needed beyond `onFrame`.

### UI (`OperateStatusBar` + `OperateUiState`)

- Add `captureFailed: Boolean` to `OperateUiState`.
- Surface it in the existing reliability-chip row (alongside `catUnreachable`,
  `decodeFailureRecent`, `digirigDisconnected`): a tappable "Audio capture failed —
  tap to retry" chip. No new main-screen real estate; only shown when down.
- `vm.retryCapture()`: `restartCapture()` + `watchdog.reset()` + clear
  `captureFailed`. Wired from the chip.

## Interplay with existing mechanisms (no conflict)

- **Zero-sample watchdog** (frames arriving but all-zero): unchanged; complementary
  by construction (it needs frames flowing, this fires when none do). Both funnel
  through the same guarded `restartCapture()`.
- **`AudioDeviceCallback` / USB-detach**: unchanged. A genuinely removed device is
  handled there ("device removed"); the watchdog's `devicePresent` guard keeps it
  from fighting that path.
- **TX**: capture is intentionally stopped during TX (`TxCaptureControl`). The
  `!isTransmitting && isCapturing` guard plus resetting the stamp on
  `onCaptureStarted` (called by `beginCapture` on TX-resume) means the TX gap is
  never mistaken for a stall.

## Testing

- **`CaptureWatchdogTest`** (pure, exhaustive):
  - no stall (recent frame) → `Idle`
  - frame gap ≥ threshold, capturing, not TX, device present → `Recover`
  - within `restartGrace` after a restart → `Idle` (spin-up not judged a stall)
  - `maxRestarts` consecutive stalls → `GiveUp` (once)
  - a healthy interval past the grace window clears `restartCount` (a later
    isolated stall → `Recover`, not `GiveUp`)
  - each guard independently suppresses `Recover`: `!isCapturing`,
    `isTransmitting`, `!devicePresent`
  - TX-gap simulation: `onCaptureStarted` after a transmit gap → next poll `Idle`
- **VM-level** (existing controller-test style where feasible): the wrapped
  `onFrames` stamps the watchdog; a `GiveUp` decision sets `isCapturing = false`
  and `captureFailed = true`.

Proposed constants (field-tunable, in a companion object): `stallThresholdMs = 3000`,
`restartGraceMs = 3000`, `maxRestarts = 3`, monitor tick ~1000 ms.

## Scope & Behavior Parity

Files touched:

- **New:** `app/src/main/java/net/ft8vc/app/controllers/CaptureWatchdog.kt` (+ test)
- `app/.../OperateViewModel.kt`: own the watchdog; wrap `onFrames`; call
  `onCaptureStarted`/`reset`; add the monitor coroutine; `retryCapture()`.
- `app/.../OperateUiState.kt`: `captureFailed` field.
- `app/.../ui/operate/OperateStatusBar.kt`: reliability chip + retry wiring.

**Default behavior parity:** in the healthy steady state frames arrive
continuously (~every 400 ms buffer), so the watchdog sits `Idle` and changes
nothing about RX/TX/CAT timing or fidelity. It only acts when frames have already
stopped — i.e., only in the failure the app currently cannot recover from.

Lands on `unstable` via a worktree off `multi-rig`; field-verified on the Yaesu
FT-891 + Digirig before promotion — including deliberately inducing a stall (e.g.,
a USB glitch / long session) and confirming automatic recovery, and confirming the
give-up chip appears and its retry works.
