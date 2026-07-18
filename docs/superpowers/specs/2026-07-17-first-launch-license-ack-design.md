# First-launch license acknowledgment

**Date:** 2026-07-17
**Branch:** `first-launch-license` (off `unstable`; unrelated to the `icom-civ` branch)
**Status:** Approved design, not yet implemented.

## Problem

The TX/license acknowledgment currently appears reactively: the first time
an operator taps a TX-initiating action on the Operate screen
(`gateOnLicense` in `OperateScreen.kt`), a "Confirm before transmitting"
dialog interrupts the action. The owner wants the acknowledgment moved to
**first launch after install** — the operator answers once, up front, and
TX popups never appear again. Settings → General → Enable transmit becomes
the only TX control afterward.

## Owner decisions (from brainstorm)

| Question | Decision |
|---|---|
| Fate of the transmit-time popup | **Gone forever.** Both first-launch buttons persist the acknowledgment; the transmit-time dialog and its plumbing are deleted. Flipping Enable transmit later never re-prompts — the disclaimer text under the switch carries the message. |
| Dismissal | **Must choose.** No back-press or tap-outside dismissal; one of the two buttons must be tapped. RX-only is the safe non-committal answer, so forcing a choice costs nothing. |
| Existing installs | Installs that acknowledged via the old popup (`licenseAcknowledged == true`) never see the dialog. Never-acknowledged installs see it on next launch. |
| Branch | Own branch off `unstable` — not stacked on the CI-V branch or its hardware gate. |

## Design

### Trigger & state

- Reuses the existing persisted `licenseAcknowledged` flag
  (`SettingsRepository`, DataStore key `LICENSE_ACK`, default false). **No
  new preference.**
- The dialog shows when **settings have hydrated from DataStore** and
  `licenseAcknowledged == false`. The hydration guard is required: the
  flag defaults to false while DataStore loads async, so without it every
  launch would flash the dialog at already-acknowledged users for a frame.
  If the UI state exposes no "settings loaded" marker, add a minimal one
  (e.g. a `settingsLoaded: Boolean` set on first settings emission);
  discover the exact seam at plan time.
- The show/hide decision is a **pure function** so it is unit-testable:
  `showsFirstLaunchLicenseDialog(settingsLoaded: Boolean, licenseAcknowledged: Boolean): Boolean`
  — true only for `(true, false)`.

### Dialog

- Lives at the **navigation root** (`PackRigApp` in `Ft8NavHost.kt`) so it
  overlays whatever tab the app opens on. Not per-screen.
- Non-dismissable: `DialogProperties(dismissOnBackPress = false,
  dismissOnClickOutside = false)`.
- Copy (approved; wording may be polished in review but the structure is
  fixed — license requirement, liability disclaimer, RX-needs-no-license,
  Settings pointer):

  > **Before you get on the air**
  >
  > PackRig can key your radio and transmit. Transmitting requires a valid
  > amateur radio license for your jurisdiction — you are responsible for
  > lawful operation; this app and its authors are not. Receiving and
  > decoding need no license.
  >
  > You can change this anytime in Settings → General → Enable transmit.

- Buttons (confirm / dismiss slots of an `AlertDialog`):
  - **"I understand — turn on TX"** → `acknowledgeLicense()` +
    `setTxEnabled(true)`
  - **"Just looking around (RX only)"** → `acknowledgeLicense()` only;
    Enable transmit stays off.

### Deletions

In `OperateScreen.kt`: `gateOnLicense`, `pendingTxAction`,
`showLicenseDialog`, and the "Confirm before transmitting" `AlertDialog`
block. TX-initiating actions (`onAnswerCq`, `onResume`, `onStartCq`, and
any other `gateOnLicense` call sites) call the ViewModel directly again.

### Unchanged

- The downstream hard gate (`AppRfState.READY` requiring
  `licenseAcknowledged`) stays exactly as is — it is the actual safety
  interlock. After the dialog is answered it is always satisfied, but it
  remains the enforcement point per the milestone's license-gate
  constraint ("TX stays gated behind license acknowledgment; nothing
  weakens the receive-only default"). The receive-only default is
  untouched: RX-only is the dialog's non-committal answer and `txEnabled`
  still defaults to off.
- The disclaimer text under the Enable transmit switch in
  Settings → General stays.

## Error handling

- DataStore hydration failure follows existing settings behavior (defaults
  apply); the dialog then shows, which is the safe direction — a fresh
  ack is requested rather than skipped.
- Process death mid-dialog: nothing persisted until a button is tapped, so
  the dialog reappears next launch. Correct by construction.

## Testing

- Unit: `showsFirstLaunchLicenseDialog` truth table (the no-flash
  hydration race is the load-bearing case: `(false, false) → false`).
- Unit (VM/repository level): "turn on TX" persists `licenseAcknowledged =
  true` AND `txEnabled = true`; "RX only" persists `licenseAcknowledged =
  true` with `txEnabled` unchanged (off).
- Existing tests that exercise `gateOnLicense` wiring update to the
  direct-call wiring; assertions about the downstream `AppRfState` gate
  must survive unchanged.
- Device smoke check: fresh install (or cleared app data) shows the dialog
  once over the start tab; back-press does not dismiss; each button leaves
  the right Enable transmit state; relaunch shows no dialog.
