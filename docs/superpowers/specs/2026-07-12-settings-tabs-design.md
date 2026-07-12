# Settings Tabs Design

**Date:** 2026-07-12
**Status:** Approved (owner requested spec + plan + implementation autonomously)

## Problem

The Settings screen is a single long scroll of ten sections separated only by
headers (Station, Radio, Audio, TX, Auto TX, POTA, Clock alignment, Display,
QRZ Logbook, About). Finding a specific setting means scrolling through
everything. The owner asked for lightweight tab headers that group the
sections into four categories.

## Requirements (from owner)

Four tabs inside the Settings screen:

| Tab | Contents |
|---|---|
| **General** | Station (with renames below), Audio, TX, Auto TX (incl. Blocklist and Auto behaviors), POTA, Clock alignment, About |
| **Rigs** | Rig settings and USB diagnostics (the existing Radio section) |
| **Display** | Dark mode and decode coloring (the existing Display section) |
| **Integrations** | QRZ Logbook settings |

Field label renames in the Station section:
- "My call" → **"Callsign"**
- "My grid" → **"Grid"**

## Non-goals

- No change to any setting's behavior, persistence key, or ViewModel wiring.
- No change to the bottom navigation bar (Settings remains one destination).
- No new dependencies.

## Approaches considered

1. **Material 3 tab row inside the existing Scaffold (chosen).** A
   `PrimaryTabRow`/`TabRow` with four text tabs directly under the top app
   bar; the selected tab swaps which sections the scrollable column shows.
   Lightweight, matches "tab headers" as requested, zero navigation changes.
2. **Nested navigation destinations per tab.** Heavier: adds routes, back-stack
   semantics, and animation concerns for what is a simple content switch.
   Rejected.
3. **Collapsible sections (accordion).** Doesn't match the request. Rejected.

## Design

### Tab model

New file `app/src/main/java/net/ft8vc/app/settings/SettingsTab.kt`:

```kotlin
enum class SettingsTab(val title: String) {
    GENERAL("General"),
    RIGS("Rigs"),
    DISPLAY("Display"),
    INTEGRATIONS("Integrations"),
}
```

A plain enum keeps the tab order and titles unit-testable without Compose.

### Screen structure

`SettingsScreen` keeps its `Scaffold` + `TopAppBar("Settings")`. Below the
app bar, a Material 3 `TabRow` renders one `Tab` per `SettingsTab.entries`
value. Selected index is held in `rememberSaveable` (survives rotation and
tab-away/tab-back within the same process) and defaults to General.

Each tab's content is its own private composable
(`GeneralSettingsTab`, `RigsSettingsTab`, `DisplaySettingsTab`,
`IntegrationsSettingsTab`), each a `verticalScroll` column of the existing
`SettingsSection` blocks, moved verbatim from the current screen. Scroll
state is per-tab (`rememberScrollState` inside each tab composable) so
switching tabs doesn't inherit another tab's scroll offset.

Section headers are retained inside tabs (e.g. General still shows
"Station", "Audio", "TX", …) since General holds seven sections. Rigs,
Display, and Integrations each hold a single section; their lone header
("Radio", "Display", "QRZ Logbook") is kept for continuity and because the
existing section composables render supporting text under it.

### QRZ warning visibility

Today the QRZ Logbook section header shows a warning icon when uploads are
enabled but not connected (`state.qrz.warning`). Behind a tab, that icon
would be invisible until the user opens Integrations. To preserve the
at-a-glance warning, the **Integrations tab label gets the same warning
icon** (small `Icons.Filled.Warning`, error tint) when `state.qrz.warning`
is true. The in-section badge stays as-is.

### Label renames

In the Station section: `label = { Text("Callsign") }` and
`label = { Text("Grid") }`. Placeholders, validators, and error text are
unchanged.

## Error handling

No new failure modes: tab switching is pure UI state. All existing
validation/error surfaces move with their sections.

## Testing

- **Unit (JVM):** `SettingsTabTest` asserts tab order and titles
  (General, Rigs, Display, Integrations) — the contract the UI renders from.
- **Compile + existing suite:** `:app:compileDebugKotlin` and the full
  `testDebugUnitTest` suite must stay green.
- **Device smoke check (pending, owner):** all four tabs render, every
  section reachable, Callsign/Grid labels renamed, QRZ warning badge shows
  on the Integrations tab when applicable.
