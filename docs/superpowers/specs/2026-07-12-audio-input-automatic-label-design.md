# Audio Input "Automatic" Label & Recovery Design

**Date:** 2026-07-12
**Status:** Approved

## Problem

In Settings → Audio, the device picker shows **"No input selected"** when no
manual selection exists. In practice nothing needs to be selected: Android
routes audio automatically, and the app auto-picks the first USB audio device
into view state when one is attached
(`OperateViewModel.refreshDevices()`). The empty-state label reads like an
error, inviting users to "fix" it by picking a device — and a wrong manual
pick is persisted to DataStore with **no UI path back to automatic**
(`SettingsRepository.setSelectedAudioDeviceId(null)` supports clearing, but
the dropdown only lists concrete devices).

## Goals

1. The empty state reads as a healthy default, not a problem to fix.
2. Users can see whether the current device came from a manual pick or
   automatic routing.
3. A user who manually picked the wrong device can return to automatic
   without clearing app data.
4. Info text explains the automatic behavior and when a manual pick is
   actually useful (USB hub / multiple audio devices).

## Design

### 1. Distinguish manual vs automatic selection in state

Today `OperateUiState.selectedDeviceId` merges the persisted setting and the
auto-pick (`settings.selectedAudioDeviceId ?: view.selectedDeviceId`), so the
UI cannot tell them apart. Add a derived flag to `OperateUiState`:

- `audioDeviceManuallySelected: Boolean` — true iff
  `settings.selectedAudioDeviceId != null`.

No change to the merge logic or to capture behavior; the flag is
presentation-only.

### 2. DevicePicker label states

| State | Label |
|---|---|
| Manual pick persisted | `Digirig (USB)` (unchanged format) |
| Automatic, USB device auto-picked | `Automatic — Digirig (USB)` |
| Automatic, no device attached | `Automatic (system default)` |

Extract label derivation into a small pure function so it is unit-testable
without Compose.

### 3. "Automatic (system default)" dropdown entry

Add a first entry to the dropdown, above the concrete devices. Selecting it:

- changes `selectDevice` to accept `Int?`; `null` means automatic,
- persists `null` via `settingsRepo.setSelectedAudioDeviceId(null)` (removes
  the DataStore key),
- clears the view-state selection and re-runs the auto-pick
  (`refreshDevices()`), so an attached USB device is re-selected
  automatically,
- preserves the existing stop/restart-capture behavior around a selection
  change.

### 4. Info text reword

Replace the current Audio section info text with:

> "Audio routes automatically: when a USB interface (Digirig or the radio's
> built-in USB audio) is attached, it's used for RX and TX — no selection
> needed. Pick a device manually only if automatic routing chooses the wrong
> one (e.g. a USB hub or multiple audio devices). Adjust input level on the
> Operate tab while monitoring."

## Behavior parity

RX/TX/CAT behavior is unchanged: capture already skips
`setPreferredDevice()` when no id is set and the auto-pick logic is
untouched. This is a Settings-screen UX change plus a recovery path that
reuses existing persistence semantics.

## Testing

Unit tests (JVM, existing test conventions):

- `audioDeviceManuallySelected` derives correctly from the persisted setting
  (true when saved, false when only auto-picked or empty).
- `selectDevice(null)` clears the persisted device id and re-runs the
  auto-pick.
- Label derivation pure function: all three states produce the expected
  strings.
- Existing `selectDevice(id)` stop/restart-capture behavior still holds for
  the nullable signature.

## Out of scope

- Any change to audio routing/capture behavior.
- Output (playback) device selection — the picker remains input-focused as
  today.
