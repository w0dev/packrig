# Core module (`core/`)

Pure Kotlin module with no Android dependencies. Holds FT8 timing, slot buffering,
QSO message parsing/formatting, the QSO state machine, WAV I/O for test fixtures,
and app-wide constants.

## Purpose

Keeping slot alignment and QSO sequencing out of Android code makes them unit-testable
and reusable. `MonitorViewModel` in `app` orchestrates I/O; `core` owns the protocol
logic.

## Key types

### `AppInfo`

App-wide constants:

| Constant | Value | Meaning |
|----------|-------|---------|
| `SAMPLE_RATE_HZ` | 12000 | WSJT-X / FT8 internal audio rate |
| `SLOT_SECONDS` | 15 | FT8 slot length |
| `VERSION_NAME` | `0.1.0-dev` | Display version |
| `currentPhase` | `PHASE_5_RELEASE` | Development phase marker |
| `VERSION_NAME` | `1.0.0` | Display version |

### `SlotTiming`

UTC-aligned 15-second slot grid helpers:

- `slotIndexInMinute(epochMillisUtc)` — slot 0..3 within the minute
- `slotStart(epochMillisUtc)` — epoch ms of the current slot start
- `nextSlotStart(epochMillisUtc)` — next boundary
- `millisUntilNextSlot(epochMillisUtc)` — countdown to next boundary

Accurate slot alignment is critical for mobile decode reliability.

### `SlotCollector`

Accumulates mono PCM into UTC slots. When wall clock crosses a slot boundary,
invokes a callback with the completed slot's samples (if at least ~85% of a full
slot was captured). Time is injected via `add(frames, nowMillisUtc, onSlot)` for
testability.

Used by `MonitorViewModel` to batch live audio into decode-sized chunks.

### `QsoMessages` / `QsoRx`

Formats and parses the standard FT8 QSO message set:

```
CQ {call} {grid}
{target} {sender} {grid|report|Rreport|RRR|RR73|73}
```

Directed messages use `{to} {from} {payload}`. Reports are signed two-digit
(`-05`, `+13`). Parses modifier CQs like `CQ POTA W0DEV EM26`.

### `QsoMachine`

Pure FT8 auto-sequencer with no I/O:

```
Initiator:  CQ → grid reply → report → R-report → RRR → 73 → complete
Answerer:   grid → report → R-report → RRR/RR73 → 73 → complete
```

The caller:

1. Reads `txMessage()` on TX slots and transmits it
2. Calls `markTransmitted()` after each TX (advances terminal 73 → complete)
3. Feeds RX decodes to `onDecodes()` to advance state

States: `Idle`, `CallingCq`, `Answering`, `SendingReport`, `SendingRReport`,
`SendingRoger`, `SendingSeventyThree`, `Complete`.

### `WavIo`

Minimal reader for uncompressed 16-bit PCM WAV files. Stereo is downmixed to mono.
Used by instrumented decode tests to load golden clips exported from WSJT-X.

## Dependencies

None (stdlib only). Other modules depend on `core`:

- `app`, `audio`, `ft8-native`, `data`

## Tests

See [TESTING.md](TESTING.md#core--5-test-classes-23-tests).

```powershell
.\gradlew.bat :core:testDebugUnitTest
```

## Related docs

- [FT8_NATIVE.md](FT8_NATIVE.md) — decode/encode called after slot collection
- [APP.md](APP.md) — wires `SlotCollector`, `QsoMachine`, and decode together
