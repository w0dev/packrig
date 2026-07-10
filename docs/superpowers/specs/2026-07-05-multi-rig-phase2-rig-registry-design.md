# Multi-Rig Phase 2 — Rig Registry + Radio Model Selection — Design

**Date:** 2026-07-05
**Branch:** `multi-rig` (milestone continues; Phase 1 code-complete at `ec563ad`)
**Status:** Approved design, pre-plan
**Parent spec:** `docs/superpowers/specs/2026-07-04-multi-rig-support-design.md` (Phase 2 scope)

## Goal

Make the radio model an explicit, user-selected setting backed by a descriptor
registry, so the Phase 1 seams (`SerialTransport` / `CatProtocol` /
`PttStrategy`) are composed from per-model data instead of a hardcoded FT-891.
Ship the full Yaesu new-CAT family, including the dual-UART built-in-USB path
needed for the FTX-1.

## Decisions (locked during brainstorm 2026-07-05)

1. **No default rig.** There is no implicit FT-891. The operator must select a
   radio model before any CAT/PTT binding occurs. This replaces Phase 1's
   hardcoded `YaesuCat(FT891)`.
2. **Full Yaesu family** ships this phase: FT-891 (hardware-verified),
   FT-991A, FTDX10, FT-710, FTDX101, FTX-1 (community-verified tier — CAT
   authored from manuals + cross-checked against FT8CN, not yet run on metal).
3. **Minimal port override.** The CAT port index lives in each descriptor; a
   user-facing "CAT port" override (default "Auto") is surfaced **only** when
   the bound device exposes more than one serial port.
4. **No in-app "unverified" marker.** Verification status is tracked in docs
   only; the UI stays clean.
5. **Model selection applies that model's default baud + PTT method**,
   overwriting the current values; the existing CAT-baud and PTT-preference
   pickers still let the operator adjust afterward.

## Architecture

Data-driven registry composed onto the Phase 1 seams. (Alternatives rejected:
a sealed class per rig — more boilerplate than a parameterizable family needs;
an external JSON config — parsing + a failure mode, YAGNI for a curated list.)

### `RigDescriptor` (rig module, new)

```kotlin
data class RigDescriptor(
    val id: String,                       // stable persisted key, e.g. "ft891"
    val displayName: String,              // "Yaesu FT-891"
    val protocolFactory: () -> CatProtocol, // e.g. { YaesuCat(YaesuCat.FT891) }
    val defaultBaud: Int,
    val catPortIndex: Int,                // serial port carrying CAT; 0 = single-port
    val defaultPtt: PttMethod,            // AUTO | RTS | CAT (maps to PttPreference)
    val customProbePids: List<UsbId> = emptyList(), // extra prober entries if stock table misses it
    val transportVerified: Boolean,       // false = transport fields are best-guess (docs tracking only)
)
```

`protocolFactory` (not a bare `YaesuModelSpec`) keeps the registry open for
Phase 3/4 Kenwood/Icom without a type change. `PttMethod` is a small rig-module
enum (`AUTO`, `RTS`, `CAT`) that the app's existing `PttPreference`
(`AUTO`/`CAT`/`RTS`) maps onto one-to-one. FT-891 defaults to `AUTO` to preserve
Phase 1's CAT-answer-else-RTS probe; the built-in-USB rigs default to `CAT`.

### `RigRegistry` (rig module, new)

Static ordered list of descriptors + `byId(id): RigDescriptor?`. **No `default`
member** — an unset selection is a real state the app handles, not a fallback.
Registry invariant (unit-tested): ids unique, every `protocolFactory` resolves,
`catPortIndex >= 0`.

Model table (protocol from Yaesu CAT manuals + FT8CN cross-ref; `*` = transport
fields best-guess pending hardware):

| id        | display        | data-mode | defaultBaud | catPortIndex | defaultPtt | verified |
|-----------|----------------|-----------|-------------|--------------|------------|----------|
| ft891     | Yaesu FT-891   | DATA-U    | 38400       | 0            | AUTO       | yes  |
| ft991a    | Yaesu FT-991A  | DATA-U    | 38400       | 0            | CAT        | no*  |
| ftdx10    | Yaesu FTDX10   | DATA-U    | 38400       | 0 (Enhanced) | CAT        | no*  |
| ft710     | Yaesu FT-710   | DATA-U    | 38400       | 0            | CAT        | no*  |
| ftdx101   | Yaesu FTDX101  | DATA-U    | 38400       | 0 (Enhanced) | CAT        | no*  |
| ftx1      | Yaesu FTX-1    | DATA-U    | 38400       | 0 (Enhanced) | CAT        | no*  |

Exact per-model tuning ranges and any mode-code deltas are captured in each
`YaesuModelSpec` during implementation; the FT-891 spec is unchanged from
Phase 1 and remains byte-equivalence-tested.

### `RigController` changes (rig module)

- Holds a nullable `descriptor: RigDescriptor?`. When null, `bindIfPermitted`
  is a no-op and `state()` reports a new `NoModel` case → no CAT/PTT.
- On bind: `SerialRigBackend(UsbSerialTransport(driver.ports[effectiveIndex],
  descriptor.defaultBaud-or-user-baud), descriptor.protocolFactory())`, where
  `effectiveIndex = userPortOverride ?: descriptor.catPortIndex`, bounds-checked
  against `driver.ports.size`.
- Prober augmented with `descriptor.customProbePids`; if the default prober
  returns no driver, fall back to `CdcAcmSerialDriver(device)` (FT8CN's pattern
  — some built-in-USB rigs enumerate as CDC-ACM rather than CP210x).
- Everything else (permission flow, PTT probe, RTS-safety de-assert) unchanged.

### Settings + persistence (app module)

- New DataStore keys: `RADIO_MODEL` (string id, **default null/unset**) and
  `CAT_PORT_OVERRIDE` (nullable int, default null = Auto).
- `StationSettings` gains `radioModelId: String? = null` and
  `catPortOverride: Int? = null`. `SettingsBridge`/`OperateUiState` carry them.
- `RadioModelPicker` in `RadioSettingsSection` (follows the existing
  `CatBaudPicker` pattern): lists the registry; **no pre-selection** — shows a
  "Select your radio model" placeholder until set.
- When a device exposes >1 serial port, an advanced "CAT port" picker appears
  (Auto = descriptor index, or an explicit index). Hidden for single-port
  devices (Digirig).
- Selecting a model writes `RADIO_MODEL` and applies the descriptor's
  `defaultBaud` → `CAT_BAUD` and `defaultPtt` → `PTT_PREFERENCE`.
- The `"Rig (FT-891 CAT)"` section title and the hardcoded FT-891 footer line
  (`SettingsScreen.kt:378`) become dynamic on the selected model (generic text
  when unset).

### `OperateViewModel` wiring

On settings load / change, resolve `RigRegistry.byId(radioModelId)` and push the
descriptor + port override into `RigController` before binding. No model → the
rig stays unbound; the Operate/Settings rig area shows "Select your radio model
to enable CAT."

## Data Flow

`SettingsRepository(RADIO_MODEL) → OperateViewModel → RigRegistry.byId → RigDescriptor
→ RigController.setDescriptor → bindIfPermitted → SerialRigBackend(UsbSerialTransport
@ port index, YaesuCat(spec))`.

## Error Handling

- **No model selected:** `RigController.state() == NoModel`; bind is a no-op;
  UI prompts selection. USB diagnostics remain available for troubleshooting.
- **Selected port index out of range** (override or descriptor beyond
  `driver.ports.size`): bind fails cleanly, `catStatus` explains, no crash.
- **Prober finds no driver:** CDC-ACM fallback attempted; if that also fails,
  same clean unbound state as Phase 1.
- **Unknown persisted model id** (registry changed between versions):
  `byId` returns null → treated as unset, operator re-selects.

## The FTX-1 (known unknown)

Its CAT **protocol** is solid — FTX-1 uses the FTDX10 new-CAT dialect, encoded
from the manual + FT8CN's `YaesuDX10Rig`/`Yaesu3*`. Its **transport** fields
(exact VID:PID, CP210x-vs-CDC-ACM, real Enhanced-port index) are best-guess
until hardware is available. Safety valves already in the design cover a wrong
guess without a new build: the CDC-ACM prober fallback and the user port
override. Marked `transportVerified = false`; tracked in docs.

## Behavior Parity (test gate)

With **FT-891 selected**, RX/TX/CAT wire behavior is byte-identical to Phase 1
(same protocol bytes, baud, port 0, PTT probe). This is the reframed parity bar
now that there is no implicit default. Existing multi-rig-branch installs
(e.g. the field phone) select their radio once after this lands — expected, and
no stable users are affected since the milestone is unpromoted.

## Testing

- **Per-model protocol:** `YaesuModelSpec` unit tests — command bytes for each
  model from its CAT manual, cross-checked against FT8CN. FT-891 keeps its
  Phase 1 byte-equivalence coverage.
- **Registry:** unique ids, all factories resolve, `catPortIndex >= 0`,
  no `default` leakage.
- **Port selection:** override vs descriptor vs out-of-bounds, against a fake
  multi-port `UsbSerialDriver`/`SerialTransport`.
- **Settings:** `radioModelId`/`catPortOverride` round-trip; unset default;
  selecting a model applies baud + PTT; unknown id → unset.
- **No-model state:** `RigController` unbound and safe with null descriptor.
- **Hardware:** FT-891 + Digirig regression (parity) now; FTX-1 built-in-USB
  when owner hardware is available (CAT sync, TX, built-in codec audio, and
  Enhanced-port index confirmation).

## Non-Goals (this phase)

- Kenwood / Icom families (Phase 3 / 4).
- Auto-detecting the model from USB descriptor (operator selects).
- Any change to the audio path — a rig's built-in USB Audio Class codec
  enumerates as a standard `AudioDeviceInfo` and is already selectable.
- In-app "unverified" labeling (docs only, per decision 4).

## Open Items

- FTX-1 USB descriptor (VID:PID, interface layout, port index) — captured when
  owner hardware is plugged in; updates the `ftx1` descriptor's transport fields
  and flips `transportVerified`.
- Confirm per-model tuning ranges while authoring each `YaesuModelSpec`.
