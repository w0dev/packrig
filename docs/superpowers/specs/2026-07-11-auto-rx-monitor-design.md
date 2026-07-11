# Auto RX-Monitor on Radio Connect — Design

**Date:** 2026-07-11
**Status:** Approved
**Branch target:** own feature branch/worktree off `multi-rig` (not stacked on `rig-profiles`)

## Problem

Plugging in the radio launches the app (USB_DEVICE_ATTACHED) but starts no audio
capture, so the Spectrum waterfall stays dark until the operator goes to Operate
and presses Start. The waterfall is the natural "is my radio connected and is the
band alive?" check during field setup; today the app gives no audio-path feedback
until the operator commits to operating.

## Decision summary

- **RX monitor only** on connect: audio capture starts automatically, feeding the
  waterfall and decode list. CAT, PTT, band restore, and QSO session remain
  untouched until Start is pressed. (Chosen over full auto-Operate and over
  start-on-Spectrum-tab.)
- **Settings toggle, default ON**: `autoMonitorEnabled`, switch labeled
  "Start receive when radio connects" (or similar) near the audio device picker.
- **Triggers**: USB plug-in event AND app launch/foreground with radio already
  connected.
- **Monitor pauses when the app leaves the foreground** (screen off or switched
  away) and resumes on return. Accepted trade-off: the waterfall does not survive
  screen-off in monitor mode. Operating keeps its existing KEEP_SCREEN_ON
  behavior. No foreground service.

## Behavior

Start monitor capture when ALL of:

1. `autoMonitorEnabled` setting is on
2. App is in the foreground
3. A USB audio input device is present
4. RECORD_AUDIO permission is already granted (monitor never prompts)
5. Not already capturing or transmitting (`!isCapturing && !isTransmitting`)

Monitor mode = `isCapturing = true`, `isOperating = false`. Decodes flow for free:
`DecodeController.onFrames()` is driven by capture frames with no `isOperating`
gate; waterfall-only would require adding a gate, so decodes stay on.

Stop monitor capture when:

- App leaves the foreground (unless operating — operating already holds
  KEEP_SCREEN_ON, so it doesn't background from screen timeout)
- The USB audio device is removed (see onAudioDevicesRemoved change below)
- The operator presses Start (capture is adopted, not stopped — see below)

## Components

### 1. Setting (`SettingsRepository` + Settings UI)

- New DataStore preference `autoMonitorEnabled: Boolean`, default `true`.
- New switch row in Settings near the audio device picker.
- Plumbed through `SettingsBridge` slice like existing prefs.

### 2. `maybeStartMonitor()` in `OperateViewModel`

Single guarded entry point implementing the condition list above; calls the
existing private `beginCapture()`. Call sites:

- `onUsbAttached()` → after `refreshDevices()` completes
- Process lifecycle observer: add `onStart` (foreground) hook alongside the
  existing `onStop` (ADIF backup) hook
- ViewModel `init` (covers launch with radio already plugged in)

### 3. Foreground/background handling

- `onStart` (process lifecycle): `maybeStartMonitor()`
- `onStop`: if capturing and NOT operating → `stopCapture()`. Operating sessions
  are unaffected.
- This also keeps the capture watchdog from seeing silent slots (Android 9+ cuts
  mic input for backgrounded apps) and firing recovery loops.

### 4. Unplug path (`onAudioDevicesRemoved`)

Existing behavior: any USB input removal while capturing → snackbar
"Audio device removed — restarting capture" + `restartCapture()`. Change: if
monitoring (capturing, not operating) and no USB input remains, `stopCapture()`
quietly instead of restart-with-snackbar. Operating keeps the existing
restart/recovery behavior.

### 5. `startOperating()` adopts a running monitor capture

Today `startOperating()` unconditionally does `waterfall.clear()`,
`decodeController.reset()`, and `beginCapture()`. When monitor capture is already
running, Start should:

- Skip `beginCapture()` (capture already live)
- NOT wipe the waterfall/decode list the operator was just looking at
- Still do `prepareRig()`, `restoreLastBandIfNeeded()`, QSO session setup, and
  state flip to `isOperating = true`

### 6. Status bar "Monitoring" state

`OperateStatusBar` shows a lightweight "Monitoring" status when
`isCapturing && !isOperating`, so a live waterfall without Operate isn't
confusing. Must not crowd main-screen real estate (milestone UX rule).

## Explicitly out of scope

- Foreground service / capture surviving screen-off in monitor mode
- Auto-Operate (auto CAT/PTT/QSO on plug-in)
- Any change to the RECORD_AUDIO permission flow (first-ever Start still grants)
- Any TX-path or license-gate change (monitor is receive-only by construction)

## Error handling

- `beginCapture()` failure callback already resets `isCapturing`/`isOperating`
  and snackbars the error; monitor start reuses it unchanged.
- Permission not yet granted → monitor silently does not start (no prompt, no
  snackbar). After the operator's first Start grants permission, monitor works
  from then on.
- Capture watchdog: unchanged; it already polls only while `isCapturing`, and
  the foreground-only rule prevents background silent-slot false positives.

## Testing

- Unit tests for the `maybeStartMonitor()` gating matrix: setting off, no USB
  device, no permission, already capturing, transmitting, backgrounded.
- Unit test: `startOperating()` while monitoring adopts capture (no second
  `beginCapture`, no waterfall/decode wipe).
- Unit test: unplug during monitor stops quietly; unplug during operate keeps
  existing restart behavior.
- Field check (FT-891 + Digirig): plug in → waterfall live within a slot;
  Start → CAT/PTT/QSO work normally; unplug → clean stop, no snackbar spam;
  screen off/on → monitor pauses and resumes; toggle off → plug-in stays dark.
