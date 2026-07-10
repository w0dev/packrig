# Rig Profiles — presets over knobs (multi-rig Phase 2.5)

**Date:** 2026-07-10
**Milestone:** multi-rig (extends `docs/superpowers/specs/2026-07-04-multi-rig-support-design.md`)
**Status:** Approved design, pre-plan

## Problem

Every supported radio today is a hand-authored `RigDescriptor` in
`RigRegistry`, shipped in a release. That makes the registry a standing
maintenance obligation: each new model — even one whose only delta is a baud
rate or port index — needs an app release. Other FT8 apps (WSJT-X, jtcat)
avoid this by letting the operator configure and save their own rig.

The protocol layer is *not* user-configurable — nobody settings-their-way
into Icom CI-V — but per-model deltas within a protocol family are entirely
knob-shaped (baud, CAT port, PTT method, freq range, mode code, CI-V
address). Families are few; models are many. Ship parsers per family,
let users supply the per-model knobs.

## Goals

- Operators can add, name, save, and switch among rig configurations
  ("profiles") without waiting for a release. Max **5** profiles.
- Three **generic presets named by hardware setup** (not protocol):
  `generic-digirig` (CAT + RTS PTT through a Digirig), `generic-cat`
  (rig's own USB CAT, CAT PTT), and `generic-rts` (audio-only interface,
  no CAT, RTS keying). `generic-rts` resolves the 2026-07-04 spec's open
  item ("Generic Digirig — RTS PTT, no CAT"). Generic presets that speak
  CAT carry a required, visible **CAT protocol** field (Yaesu new-CAT is
  the only option today; Kenwood/Icom join in Phases 3/4) so it is always
  explicit which dialect the app speaks — a generic preset is *not* a
  promise that any CAT rig works.
- A deliberately small settings surface: each preset exposes only the
  knobs that matter for that setup (see Settings UX); everything else
  keeps internal defaults and never appears in the UI.
- Named models in the registry become curated **presets** (prefilled
  profiles), not support obligations.
- In-app **Test CAT** diagnostics so unverified user configs are
  self-serviceable instead of bug reports.

## Non-Goals

- User-authored protocol definitions (raw CAT command strings). Rejected
  permanently: unbounded support surface, unreproducible bug reports;
  hamlib exists because this is genuinely hard.
- Kenwood / Icom parsers (Phases 3/4 — this design makes them cheaper but
  does not include them).
- Import/export of profile files.
- Any change to the license gate or receive-only default.

## Decisions (from brainstorm)

| Question | Decision |
|---|---|
| One custom config or named profiles? | **Multiple named profiles, max 5.** "Add rig" flow; first saved profile auto-selects; selector appears once there is more than one. |
| Profiles vs existing model dropdown? | **Profiles replace the dropdown.** Every operated rig is a profile; built-in models become presets that prefill the form. |
| No-CAT dial frequency for logging? | **Reuse the existing band picker.** Picking a band sets the standard FT8 dial frequency for logging and the waterfall label; operator keeps the dial matched. No new UI. |
| Generic preset taxonomy? (owner revision, 2026-07-10) | **Name generics by hardware setup, not protocol:** `generic-digirig`, `generic-cat`, `generic-rts`. CAT protocol is a required knob inside the CAT generics, not a preset. Settings exposure kept deliberately minimal (see form-field table). |

## Architecture

### Presets over knobs

`RigRegistry` stays as-is structurally and becomes the **preset table**:
the six current models plus three synthetic presets named by hardware
setup:

- `generic-digirig` ("Digirig with CAT") — CAT through the Digirig's
  single serial port, PTT via RTS on the same port (the FT-891-style
  wiring). Requires a CAT protocol choice.
- `generic-cat` ("USB CAT cable / built-in USB") — CAT via the rig's own
  bridge, PTT via CAT command, CAT-port picker exposed for dual-UART
  bridges. Requires a CAT protocol choice.
- `generic-rts` ("Audio only — no CAT") — no CAT protocol; PTT via RTS
  through the existing `PttStrategy` path. CAT status surfaces as
  "No CAT (manual)", not an error.

**CAT protocol is a knob, not a preset.** The CAT-speaking generics carry
a required protocol field; its only value today is Yaesu new-CAT
(`YaesuCat` with a wide default `YaesuModelSpec` — data-mode code and
frequency bounds are family defaults, never user-edited). Phases 3/4 add
Kenwood and Icom CI-V to this dropdown instead of adding new generic
presets; the CI-V bus address appears as a contextual field only when
Icom is selected. Display copy for the protocol field stays plain
("Yaesu (modern USB CAT)") per the existing UI-copy conventions.

### RigProfile

New pure-data type in the `rig` module:

```kotlin
data class RigProfile(
    val id: String,           // UUID, stable
    val name: String,         // user-visible, default = preset displayName
    val presetId: String,     // RigRegistry id ("ft891", "generic-digirig", "generic-cat", "generic-rts", …)
    val catProtocolId: String?, // required for CAT generics ("yaesu-newcat"); null for named models (fixed) and generic-rts
    val baud: Int?,           // null = preset default
    val catPortIndex: Int?,   // exposed only for generic-cat
    val pttMethod: PttMethod?,
)
```

Frequency range and data-mode code are **not** profile fields: they are
per-protocol family defaults baked into the preset (wide bounds for the
generics). Fewer knobs, less confusion; the band list for generics is
simply the full FT8 table.

### Resolution

One function, unit-tested: `resolve(profile, RigRegistry.byId(profile.presetId)) →
RigDescriptor`. Non-null profile knobs override the preset; nulls fall
through. `catProtocolId` is honored only for the CAT generics. Everything
downstream of the existing seam (`RigController`, `SerialRigBackend`,
band-preset derivation in `Ft8Bands`) keeps consuming `RigDescriptor`
unchanged. The three `RigRegistry.byId(radioModelId)` call sites in the app
module become "resolve the selected profile".

## Persistence & Migration

- DataStore: `RIG_PROFILES` (JSON array, hand-rolled or `org.json` — no new
  dependency) + `SELECTED_RIG_PROFILE` (UUID). Cap of 5 enforced in
  `SettingsRepository`.
- **Migration** (one-time, on first read): if legacy `RADIO_MODEL` is set,
  synthesize a profile named after the model carrying over existing
  baud/PTT preferences, select it, clear the legacy key. Fresh installs
  start with zero profiles.
- Unknown `presetId` on load (e.g., downgrade): profile is kept but marked
  unusable in UI; selecting it resolves to the existing `NoModel` state.

## Settings UX

"Radio model" dropdown is replaced by a **My rigs** section:

- **Empty state:** "Add rig" button only.
- **With profiles:** selected-rig indicator + list (max 5) with per-row
  edit/delete; "Add rig" hidden at the cap.
- **Add/Edit form:** base setup picker first (named models, then the
  three generics) → the form shows *only* the fields that matter for that
  setup. Saving the first profile auto-selects it.

  | Form field | Named model | generic-digirig | generic-cat | generic-rts |
  |---|---|---|---|---|
  | Name | ✓ prefilled | ✓ | ✓ | ✓ |
  | CAT protocol | — (fixed) | ✓ required | ✓ required | — |
  | Baud | ✓ prefilled | ✓ (38400) | ✓ (38400) | — |
  | CAT port | — (preset) | — (port 0) | ✓ (plain names) | — |
  | PTT method | ✓ prefilled | ✓ (RTS) | ✓ (CAT) | — (fixed RTS) |

  Nothing else is exposed, deliberately: no frequency range, no data-mode
  code, no port index where the setup determines it. A `generic-rts`
  profile is: pick it, name it, save.
- **Delete:** deleting the selected profile selects the first remaining
  profile, or falls back to the existing `NoModel` state if none remain.
- UX deltas must not claim main-screen real estate (milestone constraint);
  everything lives in Settings.

## No-CAT operating behavior

- PTT keys via RTS (the FT-891/Digirig-proven path); CAT reads are absent
  by construction, `catReady` stays false, status reads "No CAT (manual)".
- Band picker presents the full standard FT8 band table (no rig range to
  filter by); the picked dial frequency feeds the log and waterfall label
  exactly as CAT readback would.

## Diagnostics — Test CAT

Button in the profile form (hidden for `generic-rts`): opens the transport with
the form's current knobs and reports in plain language:

- *Sync OK at 38400 — rig reports 14.074.000 MHz.*
- *Received garbage — likely wrong baud rate.*
- *No response — check CAT port, cable, and rig menu.*

This ships **with** the feature, not after: it is what converts an
unverified user config from a support liability into self-service.
`transportVerified` remains a docs/registry concept for presets; profiles
are user-verified via Test CAT.

## Error handling

- Profile JSON that fails to parse → treated as absent (fresh state), never
  a crash; log to state as with other CAT errors.
- Cap violations rejected at save with an inline message. Profile names
  must be unique (case-insensitive); duplicates are rejected the same way.
- Resolution of a selected-but-missing profile id → `NoModel` state.

## Testing & field gates

Unit (JVM, `rig` + `app`):
- resolve(): override wins, null falls through, `catProtocolId` ignored
  for non-generic presets.
- JSON round-trip, corrupt-JSON tolerance, cap enforcement, unique names,
  deletion fallback, migration (legacy key → profile, byte-identical
  descriptor).
- **Parity regression:** migrated FT-891 profile resolves to a
  `RigDescriptor` equal to today's registry entry.

Field gates before promotion (core value: the rig still keys):
1. FT-891 + Digirig full regression *through the migration path* (upgrade
   an install that has `RADIO_MODEL=ft891` set).
2. FTX-1 direct-USB regression via a preset-created profile.
3. One real no-CAT QSO: any rig + Digirig on a `generic-rts` profile,
   confirming RTS keying, decode, and correct logged frequency from the
   band picker.

## Milestone phasing impact

Lands as **Phase 2.5 — Rig profiles** on the multi-rig milestone, between
Phase 2 (Yaesu family, done) and Phase 3 (Kenwood). Phases 3/4 are then
built generic-first:

- Phase 3 ships `KenwoodCat`, which appears as a new entry in the generic
  presets' CAT-protocol dropdown (dialect is nearly uniform); named
  Kenwood models become optional courtesy presets.
- Phase 4 ships `IcomCiV` as another protocol entry, with the CI-V hex
  address as a contextual field shown only when Icom is selected — one
  parser plus a user-entered address covers essentially every Icom, no
  release needed.
- No new generic presets are ever added for a protocol family; the
  protocol dropdown grows instead.

## Open items

- Exact JSON shape / versioning field for `RIG_PROFILES` (decide in plan).
- Whether Test CAT reuses `RigController`'s connect path or a lightweight
  one-shot probe (decide in plan; must not disturb an active session).
- Copy for "No CAT (manual)" status and Test CAT results (plain-language
  pass at implementation).
