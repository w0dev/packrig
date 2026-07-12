# Core module (`core/`)

Pure Kotlin module with no Android dependencies. Holds FT8 timing, slot buffering,
QSO message parsing/formatting, the QSO state machine, WAV I/O for test fixtures,
and app-wide constants.

## Purpose

Keeping slot alignment and QSO sequencing out of Android code makes them unit-testable
and reusable. `OperateViewModel` in `app` orchestrates I/O; `core` owns the protocol
logic.

## Key types

### `AppInfo`

App-wide constants:

| Constant | Value | Meaning |
|----------|-------|---------|
| `SAMPLE_RATE_HZ` | 12000 | FT8 internal audio rate |
| `SLOT_SECONDS` | 15 | FT8 slot length |
| `VERSION_NAME` | `1.0.0` | Display version |
| `currentPhase` | `PHASE_5_RELEASE` | Development phase marker |

### `SlotTiming` / `TxSlotParity` / `TxSlotSelection`

UTC-aligned 15-second slot grid helpers:

- `slotIndexInMinute(epochMillisUtc)` — slot 0..3 within the minute
- `slotStart(epochMillisUtc)` — epoch ms of the current slot start
- `nextSlotStart(epochMillisUtc)` — next boundary
- `millisUntilNextSlot(epochMillisUtc)` — countdown to next boundary
- `isEvenSlot(epochMillisUtc)` — slots 0 and 2 (:00 and :30 UTC)

[TxSlotParity] is **Even** (:00/:30) or **Odd** (:15/:45). [TxSlotSelection] picks TX parity for calling CQ (user preference) or answering (opposite of the heard decode slot). The Operate status bar exposes Even/Odd chips and a **TX Ns** countdown until the next matching period.

### `SlotCollector`

Accumulates mono PCM into UTC slots. When wall clock crosses a slot boundary,
invokes a callback with the completed slot's samples (if at least ~85% of a full
slot was captured). Time is injected via `add(frames, nowMillisUtc, onSlot)` for
testability.

Used by `OperateViewModel` to batch live audio into decode-sized chunks.

### `QsoMessages` / `QsoRx`

Formats and parses the standard FT8 QSO message set:

```
CQ {call} {grid}
{target} {sender} {grid|report|Rreport|RRR|RR73|73}
```

Directed messages use `{to} {from} {payload}`. Reports are signed two-digit
(`-05`, `+13`). Parses modifier CQs like `CQ POTA W0DEV EM26`. `cq(myCall, myGrid, modifier)`
formats activator CQs (`CQ POTA W0DEV EM26`).

### `ActivationProfile`

Portable / POTA activation helpers:

- `cqModifier(potaEnabled)` — returns `POTA` when activation mode is on
- `normalizeParkRef(ref)` / `isValidParkRef(ref)` — park reference validation for ADIF

### `AnswerPolicy` / `AnswerSelector` / `MaidenheadGrid` / `DecodeDistance`

When several decodes qualify in one slot, [AnswerSelector] picks one using [AnswerPolicy]:

| Policy | Behavior |
|--------|----------|
| **FIRST** | First matching decode in native slot order |
| **BEST_SNR** | Highest SNR |
| **FURTHEST** | Greatest 4-char Maidenhead distance from your grid |

Used for CQ pileups ([QsoMachine] calling CQ), answer-when-called ([QsoResume]), and auto-answer-CQ.

[DecodeDistance] extracts a 4-char grid from CQ/grid-reply messages and computes great-circle km from your grid for the Operate decode list (reports and RRR show no distance).

### `StationProfileValidator`

Validates the operator's callsign and Maidenhead grid before transmit. `isValidCall` accepts 3–10 characters of alphanumerics and `/` and requires at least one digit and one letter (so portable suffixes like `W0DEV/P` and placeholders like `TEST` work correctly). `isValidGrid` defers to [MaidenheadGrid] for 4-char grids and accepts a 6-char extension. `isComplete(call, grid)` is the single guard used in [OperateViewModel] to gate **Start CQ**, **Answer CQ**, and **Resume QSO**.

### `DecodePrefix`

Returns a single-glyph prefix for a decode message — `●` (CQ), `→` (directed to my call, idle), `▸` (active QSO partner) — so the row type is visible without relying on color. Used by the Operate decode list.

### `CallBaseName`

Normalizes a callsign to its base form for identity matching: strips `/portable`
and `-suffix` decorations and uppercases, so `W0DEV`, `W0DEV/P`, and `W0DEV-1`
resolve to one key. `of(call)` returns `null` for unparseable input. Shared by
[AbandonedPartners], [QsoMessages]`.senderCall`, and blocklist matching.

### `CallsignCountry`

Maps a decoded callsign to a two-letter ISO 3166 country code via a generated
prefix table (`CallsignCountryTable`, produced by `tools/gen_country_table.py`);
returns `null` when the prefix is unresolved. Feeds the **CC** column in the
Operate decode list.

### `ClockCorrection`

Holds the operator-applied offset (ms) between the device clock and FT8 band
time (the DT self-cal feature). `OperateViewModel.alignClock()` applies the
residual estimated from decodes; `resetClockAlignment()` clears it. The app feeds
the corrected time into slot timing so RX/TX slot boundaries track real band time
even when the phone lacks NTP sync.

### `AbandonedPartners`

Two disjoint sets keyed on base callsign ([CallBaseName]`.of`):

- **`userBlocked`** (`blockUser`) — stations the operator explicitly blocked by
  long-pressing a decode. Hidden from the decode list and listed in the
  Settings → Blocklist manager (`userBlockedSnapshot`); `isUserBlocked` drives
  the row hiding.
- **`autoSuppressed`** (`suppressAuto`) — stations dropped by a no-reply timeout.
  Excluded from auto-resume, auto-answer-CQ, and CQ-pileup selection, but never
  hidden and never shown in the manager.

`snapshot()` returns the union (everything excluded from auto selection).
`allowResume(call)` removes a call from both sets (tap-to-resume, or per-call
**Unblock**); `clear()` empties both (**Clear all**).

### `MonitorDecodeFilter` / `DecodeViewMode`

Display-only filters for the Operate decode list (decoder and [QsoMachine] still see all decodes):

| Mode | Shows |
|------|--------|
| **Band** (`ALL`) | Every decode in the passband |
| **Focus** (`OPERATE`, default) | All CQ calls; any decode to/from your callsign; active QSO partner; decodes within ±150 Hz of TX tone |

Optional **CQ·73** chip (Band mode only) narrows to CQ and sign-offs (plus partner during QSO). Persisted in DataStore.

### `QsoForm` / `QsoTxStep`

Internal helpers for composing FT8 lines from step + fields (unit-tested). **`OperateTxOptions`**
defines the Operate TX dropdown: free text only when idle; full QSO step list during a QSO.
The Operate screen shows a single message field with that menu — not separate report/grid controls.

### `QsoMachine`

Pure FT8 auto-sequencer with no I/O:

```
Initiator:  CQ → grid reply → report → R-report → RRR → 73 → complete
Answerer:   grid → report → R-report → RRR/RR73 → 73 → complete
```

The caller:

1. Reads `txMessage()` on TX slots and transmits it (CQ may include modifier from constructor; `customTxMessage` overrides until sent)
2. Calls `recordTransmitted()` after each TX (`unansweredTxCycles` for abandon timeout; terminal 73 → `Complete` after one TX; clears custom override)
3. Feeds RX decodes to `onDecodes()` to advance state (resets no-reply counter) — skipped when `manualControl` is true (Settings/advanced only; Operate text edits use one-shot `customTxMessage` without pausing Auto Seq)
4. `applyForm()` / `applyStep()` — programmatic step edits (tests and internal recovery)

Abandoned partners are excluded from pileup and auto-resume via `excludedDx` on `onDecodes()`.

States: `Idle`, `CallingCq`, `Answering`, `SendingReport`, `SendingRReport`,
`SendingRoger`, `SendingSeventyThree`, `Complete`.

### `WavIo`

Minimal reader for uncompressed 16-bit PCM WAV files. Stereo is downmixed to mono.
Used by instrumented decode tests to load golden FT8 capture clips.

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
