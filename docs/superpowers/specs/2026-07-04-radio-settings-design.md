# Radio Settings — CAT Baud Rate (Design)

**Date:** 2026-07-04
**Branch:** `radio-settings` (off `readiness`)
**Status:** Approved (scope, placement, and apply-timing confirmed with user)

## Problem

The CAT baud rate is hardcoded to 38400 (`DigirigRigBackend.DEFAULT_CAT_BAUD`), and
`RigController.bindIfPermitted()` constructs the backend without passing a baud, so the
existing `catBaud` constructor parameter is dead. If the operator's FT-891 menu 05-06
(CAT RATE) is set to anything else — the rig ships at 4800 — CAT silently fails: frequency
reads time out, PTT auto-probe falls back to RTS, and nothing in the UI explains why.

FT8CN and WSJT-X both expose radio serial settings. FT8VC targets exactly one serial
bridge (Digirig CP2102) and one rig (FT-891), so most of that surface is YAGNI — framing
is always 8N1, handshake is always off. The one setting that bites in the field is baud.

## Scope

**In:** A user-configurable CAT baud rate, persisted, applied live, surfaced in a
dedicated Radio section of the Settings tab.

**Out (explicitly rejected during brainstorming):**
- Serial framing (data bits / stop bits / parity) — FT-891 CAT is always 8N1.
- CAT timing knobs (reply deadline, poll interval).
- PTT method override changes — the existing `PttPreference` picker stays as-is.
- A sub-screen; the anti-clutter goal is met by code structure, not navigation depth.

## Decisions

1. **Options:** 4800 / 9600 / 19200 / 38400 — the FT-891 menu 05-06 choices, nothing more.
2. **Default:** 38400. Behavior parity with v1.0: an operator who never touches the
   setting gets today's exact behavior.
3. **Apply timing:** Rebind immediately on change when a Digirig is bound and the app is
   not transmitting — close/reopen the CP2102 at the new baud, then re-probe CAT so
   `catStatus`/PTT method update without replugging. If transmitting, skip the immediate
   rebind; the controller holds the current value, so any later rebind (USB reattach,
   `prepareRig`) picks it up.
4. **UI placement:** The entire existing "Rig (FT-891 CAT)" section moves out of
   `SettingsScreen.kt` into a new `RadioSettingsSection.kt`, taking its private helpers
   (`PttPreferencePicker`, `UsbDiagnosticsExpandable`) with it. The baud picker is added
   there. `SettingsScreen.kt` shrinks by ~150 lines.

## Design

### Data layer (`app/.../settings/`)

- `SettingsRepository.Keys.CAT_BAUD = intPreferencesKey("cat_baud")`.
- `StationSettings.catBaud: Int = 38_400`; repository maps `prefs[CAT_BAUD] ?: 38_400`.
- `suspend fun setCatBaud(baud: Int)` — persists the value. Values come only from the
  dropdown, so no free-text validation; coerce to the known option set defensively
  (fall back to 38400 if not one of 4800/9600/19200/38400).

### Rig layer (`rig/.../RigController.kt`)

- New `@Volatile var catBaud: Int = DigirigRigBackend.DEFAULT_CAT_BAUD` on `RigController`.
- `bindIfPermitted()` passes it: `DigirigRigBackend(usbManager, device, catBaud)` —
  finally using the existing constructor parameter. `DigirigRigBackend` itself is unchanged.

### ViewModel (`app/.../OperateViewModel.kt`)

- `OperateUiState.catBaud: Int = 38_400`, mapped from settings in the state combine
  (the value flows `StationSettings` → `SettingsSlice` → `OperateUiState`).
- `fun setCatBaud(baud: Int)` only persists via `settingsRepo.setCatBaud(baud)`.
- The existing settings→controller mirror (`settingsBridge.slice.collect` in `init`)
  owns the apply path — a single code path for both user changes and startup:
  when `rig.catBaud != slice.catBaud`, set `rig.catBaud`, and if
  `rig.isDigirigReady && !state.value.isTransmitting`, launch on
  `rigSession.catDispatcher` → `rig.rebind()` → `prepareRig()` on Main, whose `Ready`
  branch re-probes CAT (`configurePttFromCatProbe()` → `onRigReady()`) and refreshes
  status so the operator sees the result immediately.
- This also heals the startup race: if the Digirig binds at the default 38400 before
  DataStore emits a persisted non-default baud, the first slice emission detects the
  mismatch and rebinds. If transmitting, the mirror updates `rig.catBaud` only; the
  next natural rebind (USB reattach) applies it. (The picker is disabled during TX
  anyway.)

### UI (`app/.../settings/RadioSettingsSection.kt`, new file)

- `RadioSettingsSection(state, vm-callbacks...)` composable containing everything the
  current `SettingsSection("Rig (FT-891 CAT)")` block holds, plus:
- `CatBaudPicker` — `ExposedDropdownMenuBox` matching the existing picker idiom
  (`AnswerPolicyPicker` / `MaxUnansweredTxPicker`):
  - Label: "CAT baud rate".
  - Supporting text: "Must match FT-891 menu 05-06 (CAT RATE)".
  - Enabled only when `!state.catBusy && !state.isTransmitting`.
  - Visible regardless of `catReady` — the whole point is recovering when CAT is *not*
    talking, so the picker must not be gated behind a working CAT link.
- `PttPreferencePicker` and `UsbDiagnosticsExpandable` move into this file as private
  composables. `SettingsScreen.kt` calls `RadioSettingsSection(...)` where the old
  section block was. No visual reordering of the Settings tab.

### Error handling

- Rebind failure at the new baud: `rig.rebind()` returns false; existing status strings
  (`txStatus`, `catStatus`) already surface "CAT unavailable" states, and the probe path
  updates `pttReady`/`txStatus`. No new error channel.
- A wrong baud choice is symmetric with today's wrong-hardcoded-baud failure mode, except
  now the operator can fix it from the phone.

### Threading

- All serial close/open happens on `rigSession.catDispatcher` (same discipline as
  `onUsbAttached()`), never on main; UI state updates hop back via the existing patterns.

## Testing

- **Unit (app):** `SettingsRepository` round-trip — unset key yields 38400; set 4800,
  read back 4800; setter coerces an unknown value to 38400.
- **Unit (rig):** `RigController.catBaud` defaults to `DEFAULT_CAT_BAUD`; construction of
  the backend with the controller's current value (via seam or code inspection —
  `Cp210x.baudRateBytes` is already covered).
- **Field bar (before promotion):** On the reference FT-891 + Digirig: set rig menu
  05-06 to 4800, change the app setting to 4800, confirm CAT frequency reads recover
  and PTT probe reports CAT — without replugging USB. Then restore both to 38400 and
  confirm normal operation.

## Behavior parity statement

Default 38400 + unchanged `DigirigRigBackend` means an untouched install is byte-identical
to v1.0 CAT behavior on the reference rig. The only new runtime path (live rebind) reuses
the field-proven `rebind()` used by USB reattach.
