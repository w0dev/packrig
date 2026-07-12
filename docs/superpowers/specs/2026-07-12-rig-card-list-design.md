# Rig Card List Design

**Date:** 2026-07-12
**Status:** Approved (owner approved design in session; mockup screenshots provided)

## Problem

The Rigs tab selects the active rig through an `ExposedDropdownMenu` plus a
row of Add/Edit/Delete text buttons (`MyRigsBlock` in
`RadioSettingsSection.kt`). The owner wants the rigs laid out as a visible
radio-button card list instead (mockup provided): a "None" card, one card
per saved rig with Edit/× controls, and a dashed "+ Add Rig" button.

## Requirements (from owner + mockup)

- Card list replaces the dropdown and the Add/Edit/Delete button row.
- First card: **None** — "No radio connected" subtitle, radio button, no
  Edit/× controls. Selecting it deselects the rig (new capability).
- One card per saved rig: radio button, profile name, one-line subtitle
  (model — CAT/PTT summary, ellipsized), **Edit** text button and **×**
  delete icon on every rig card. Whole card tappable to select.
- Selected card visually highlighted (primary-color border).
- **+ Add Rig**: full-width dashed-border button below the list.
- Owner decisions (2026-07-12):
  - Subtitle = model — CAT/PTT summary built from preset + overrides
    (no live USB port names).
  - Deleting the currently selected rig falls back to **None** (was: first
    remaining rig).

## Non-goals

- No changes below the rig list: dial frequency, mode row, DATA-U button,
  CAT status, USB diagnostics, and the rig profile editor dialog stay as-is.
- No changes to `RigProfile` persistence format or the 5-rig cap.

## Design

### 1. Deselection plumbing

Null selection already exists as a handled state (`RigController.State.NoModel`
→ NoOp backend); only the setters must learn to write it.

- `SettingsRepository.selectRigProfile(id: String?)` — null removes
  `Keys.SELECTED_RIG_PROFILE`; non-null keeps the existing
  must-exist-in-profiles guard.
- `OperateViewModel.selectRigProfile(id: String?)` — passthrough.
- `RadioSettingsSection(onSelectRigProfile: (String?) -> Unit)` and the
  `SettingsScreen` call site (`vm::selectRigProfile` still binds).
- `RigProfileList.selectionAfterDelete` — when the deleted rig was the
  current selection, return null (None). Keep-current-if-it-survives is
  unchanged. Existing unit tests updated to the new contract.

### 2. Subtitle helper

New pure object `RigCardSummary` in `net.ft8vc.app.settings` (pattern:
`RigProfileForm`):

```kotlin
object RigCardSummary {
    /** One-line card subtitle: "<model> — <CAT part>, <PTT part>". */
    fun subtitle(profile: RigProfile): String
}
```

- Model = `RigRegistry.byId(profile.presetId)?.displayName`, else
  `"Unknown preset"` (defensive; profiles are validated on save).
- CAT part = `"CAT @ <effective baud>"` when the preset has a
  `protocolFactory`, else `"no CAT"`. Effective baud =
  `profile.baud ?: descriptor.defaultBaud`.
- PTT part = `"<method> PTT"` from
  `profile.pttMethod ?: descriptor.defaultPtt`, rendered lowercase-ish:
  `AUTO` → "auto PTT", `RTS` → "RTS PTT", `CAT` → "CAT PTT".
- Example: `"Yaesu FT-891 — CAT @ 38400, auto PTT"`.

### 3. Card list UI

New file `app/src/main/java/net/ft8vc/app/settings/RigCardList.kt`:

```kotlin
@Composable
fun RigCardList(
    profiles: List<RigProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,   // null = None
    onAdd: () -> Unit,
    onEdit: (RigProfile) -> Unit,
    onDelete: (RigProfile) -> Unit,
)
```

- Plain `Column` (max 6 cards; parent already scrolls).
- Each card: `OutlinedCard` with `RadioButton` + text column
  (name `bodyLarge`/SemiBold, subtitle `bodySmall` onSurfaceVariant,
  `maxLines = 1`, `TextOverflow.Ellipsis`). Rig cards add a trailing
  "Edit" `TextButton` and a `Close` `IconButton`
  (contentDescription "Delete <name>").
- Selection: card `onClick` and the radio button both call
  `onSelect(profile.id)` / `onSelect(null)` for None. Selected card gets
  `BorderStroke(2.dp, MaterialTheme.colorScheme.primary)` and
  `surfaceVariant`-tinted container; unselected cards use the default
  outline.
- `enabled` (same gating as today: `!catBusy && !isTransmitting`) disables
  clicks, radio buttons, Edit, and ×.
- **+ Add Rig**: full-width `TextButton`-styled box with a dashed rounded
  border drawn via `Modifier.drawBehind` with
  `PathEffect.dashPathEffect` (Compose `BorderStroke` cannot dash).
  Disabled at the cap; the existing "Maximum of 5 rigs." caption stays.
- `MyRigsBlock` in `RadioSettingsSection.kt` is deleted; the section calls
  `RigCardList` directly. The empty-list hint text ("Add your rig to
  enable…") and the delete `AlertDialog` stay in `RadioSettingsSection`.

## Error handling

- Deleting the selected rig → selection cleared (None), CAT/PTT become
  no-ops — same path as never having selected a rig.
- `selectRigProfile(null)` with nothing selected is a harmless no-op remove.

## Testing

- **Unit (JVM):**
  - `RigCardSummaryTest` — named preset with defaults, baud override,
    PTT override, no-CAT generic, unknown preset id.
  - `RigProfileListTest` — update `selectionAfterDelete` cases: deleting
    the selected rig yields null; deleting an unselected rig keeps the
    selection.
- **Compile + suite:** `:app:compileDebugKotlin` and `testDebugUnitTest`
  green (app + core modules).
- **Device smoke check (pending, owner):** cards render on the Rigs tab,
  None deselects (CAT drops to no-op), Edit/× work per card, selected
  highlight, dashed Add Rig opens the editor, cap behavior at 5 rigs.
