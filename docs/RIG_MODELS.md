# Supported radios (multi-rig)

Registry: `rig/src/main/java/net/packrig/rig/RigRegistry.kt`. Protocol tables:
`YaesuCat.FT891` and `YaesuModels`. "Verified" = confirmed on real hardware
(CAT sync, TX keys, full QSO); "CAT from manual" = protocol authored from the
CAT manual + cross-checked against FT8CN, transport fields (PID/port) best-guess.

| Model    | id      | Connection            | Status            |
|----------|---------|-----------------------|-------------------|
| FT-891   | ft891   | Digirig (CP2102)      | Verified (reference rig) |
| FT-991A  | ft991a  | built-in USB          | CAT from manual   |
| FTDX10   | ftdx10  | built-in USB (CP2105) | CAT from manual   |
| FT-710   | ft710   | built-in USB          | CAT from manual   |
| FTDX101  | ftdx101 | built-in USB (CP2105) | CAT from manual   |
| FTX-1    | ftx1    | built-in USB (hub: CP2105 `10c4:ea70` + aux CDC + C-Media codec) | Verified on owner hardware 2026-07-09 (second reference rig): CAT read/write @ 38400 on port 0 = Enhanced, TX + audio field-checked; covers VHF/UHF (observed at 444.0925 MHz) |

## Rig profiles (Phase 2.5 — implemented)

Operators save up to 5 named profiles (Settings → Radio → Add rig); the
model dropdown is gone and registry entries are presets that prefill the
form. Generic presets for unlisted hardware: `generic-digirig` (CAT + RTS
PTT through a Digirig), `generic-cat` (rig's own USB CAT), `generic-rts`
(audio-only, RTS keying, dial frequency set from the band picker and used
for logging). CAT protocol is a knob inside the CAT generics
(`CatProtocols`, Yaesu new-CAT only today) — Kenwood/Icom join that
dropdown in Phases 3/4; no new generic presets are ever added per family.
The profile form's Test CAT button reports sync / garbage / silence in
plain language; field checks (2026-07-11) showed a Yaesu baud mismatch
reads as **silence**, not garbage — the rig never parses the corrupted
query — so the silence copy leads with the baud rate. The CAT port
override is exposed for **every** CAT-capable preset on a multi-port
bridge (widened from generic-cat-only, owner decision 2026-07-11);
presets still ship their defaults. `transportVerified` stays a
preset-table concept — user profiles are self-verified via Test CAT.
Legacy `RADIO_MODEL` installs migrate to a single selected profile on
first run. All four Phase 2.5 field gates (migration, FTX-1 preset,
generic-rts QSO, Test CAT spot-checks) verified 2026-07-11.

## Roadmap: other radio families

The multi-rig milestone (spec:
`docs/superpowers/specs/2026-07-04-multi-rig-support-design.md`, Phasing
section) covers two more families beyond Yaesu new-CAT. Neither is
implemented yet; each gets its own spec + plan when picked up:

- **Phase 3 — Kenwood** (`KenwoodCat`: TS-590SG, TS-890S, …). Small delta:
  Yaesu new-CAT descends from Kenwood's ASCII dialect.
- **Phase 4 — Icom CI-V** (`IcomCiV`: IC-7300 `0x94`, IC-705 `0xA4`, …).
  New binary parser: `0xFE 0xFE … 0xFD` framing, BCD frequencies, per-model
  bus addresses, own-echo handling.
- **Authoring references** (decided in the milestone spec): hamlib's per-rig
  backend sources and FT8CN's `rigs/*RigConstant.java` tables are the
  command-string/quirk references for writing our tables — used as data,
  not as dependencies.

Out of scope for the milestone: pre-new-CAT Yaesu binary protocol
(FT-857D, FT-818) — a genuinely different parser, not a table entry.

## Adding / verifying a model

1. Add or confirm the `YaesuModelSpec` (tuning range from the CAT manual).
2. Add the `RigDescriptor` to `RigRegistry` (baud, `catPortIndex`, PTT, any
   `customProbePids`).
3. On real hardware: confirm CAT sync, the correct CAT port index (dual-UART),
   TX keying, and built-in-codec audio; flip the row to Verified and set
   `transportVerified = true`.
