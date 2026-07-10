# Supported radios (multi-rig)

Registry: `rig/src/main/java/net/ft8vc/rig/RigRegistry.kt`. Protocol tables:
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
| FTX-1    | ftx1    | built-in USB (hub: CP2105 `10c4:ea70` + aux CDC + C-Media codec) | CAT verified on hardware 2026-07-09 (read/write @ 38400, port 0 = Enhanced; rig covers VHF/UHF — observed at 444.0925 MHz). TX keying + audio decode pending |

## Adding / verifying a model

1. Add or confirm the `YaesuModelSpec` (tuning range from the CAT manual).
2. Add the `RigDescriptor` to `RigRegistry` (baud, `catPortIndex`, PTT, any
   `customProbePids`).
3. On real hardware: confirm CAT sync, the correct CAT port index (dual-UART),
   TX keying, and built-in-codec audio; flip the row to Verified and set
   `transportVerified = true`.
