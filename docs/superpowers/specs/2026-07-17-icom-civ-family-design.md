# Icom CI-V family support (multi-rig Phase 4)

**Date:** 2026-07-17
**Milestone:** multi-rig (extends `docs/superpowers/specs/2026-07-04-multi-rig-support-design.md`,
builds on `2026-07-10-rig-profiles-design.md`)
**Status:** Approved design, not yet implemented.

## Problem

PackRig speaks one CAT dialect: Yaesu new-CAT. The second-largest rig
population in the field — Icom, plus every Xiegu (all Xiegu rigs clone
Icom's CI-V protocol) — cannot CAT-sync or CAT-key. The multi-rig
milestone reserved Phase 4 for exactly this: a new binary parser
(`0xFE 0xFE … 0xFD` framing, BCD frequencies, per-model bus addresses)
behind the existing `CatProtocol` seam.

CI-V also breaks two assumptions baked into today's
`SerialRigBackend` exchange loop:

1. **Echo.** On echoing setups (single-wire CI-V remote jack behind a
   Digirig, or the rig's "CI-V USB Echo Back" setting ON) the first
   terminated frame read after a write is the controller's *own command
   echoed back*, not the reply. Today's "first frame wins" loop would
   parse-fail every query on such setups.
2. **Acks.** Unlike Yaesu, CI-V rigs answer every *set* command with an
   `0xFB` (OK) / `0xFA` (NG) frame. Today's fire-and-forget `catWrite`
   would leave those unread, poisoning the next exchange's read buffer.
   (Phase 1 carried over an "optional input flush before catExchange"
   note anticipating this.)

## Goals

- **`IcomCiV` protocol family**: read/set frequency, read mode, select
  the FT8 data mode, CAT PTT — parameterized by CI-V bus address and a
  per-model quirk table, covering Icom and Xiegu with one parser.
- **Frame-stream refactor** of `SerialRigBackend` (owner decision:
  approach B): the backend consumes classified frames rather than
  scanning for a terminator byte, giving correct echo discard, real
  ack-based success/failure for CI-V set commands, and passive
  transceive updates.
- **Flagship presets** at "CAT from manual" tier: IC-7300, IC-705,
  IC-7100, Xiegu G90, Xiegu X6100. Everything else uses the generic
  presets + protocol knob + address field.
- **Protocol dropdown grows** exactly as the Phase 2.5 spec reserved:
  "Icom CI-V" joins `CatProtocols`; the CI-V hex address appears as a
  contextual field. No new generic presets.
- **Sharper Test CAT**: the frame layer distinguishes a new
  **Echo only** outcome (wiring echoes, rig silent → wrong address or
  rig off) alongside Sync / Garbage / Silence.
- **Community verification path**: no CI-V hardware is on the owner's
  bench. Presets ship unverified; a GitHub issue template collects
  tester reports (model, connection, Test CAT result, QSO confirmation)
  to flip `docs/RIG_MODELS.md` rows to Verified.

## Non-Goals

- **Network transports.** POTACAT-style TCP CAT, Icom RS-BA1/UDP,
  rigctld, FlexRadio, K4 remote — remote-control features contrary to
  PackRig's phone-cabled-to-rig field premise. PackRig's one transport
  is USB serial (+ USB audio). Recorded here so the question stays
  answered: a top-level "interface" selector is POTACAT's answer to
  transport diversity PackRig does not have; PackRig's equivalent is
  the protocol knob inside the CAT generics.
- **Kenwood / Elecraft (Phase 3).** Elecraft (KX2, KX3, K4) rides the
  Kenwood ASCII family — Yaesu new-CAT's ancestor dialect — as a
  variant table on the future `KenwoodCat`, own spec when picked up.
  This design updates the `docs/RIG_MODELS.md` roadmap to name
  Elecraft explicitly.
- **Bluetooth CAT** (IC-705 / Xiegu BLE). Plausible future second
  transport; if it ever lands it is a per-profile connection attribute
  behind `SerialTransport`, not a protocol concern. Nothing in this
  design blocks it.
- **hamlib integration** — already rejected in the milestone spec;
  hamlib and FT8CN sources are authoring references (data), not
  dependencies.
- User-authored protocol strings (rejected permanently, Phase 2.5).
- Any change to the license gate or receive-only default.

## Decisions (from brainstorm)

| Question | Decision |
|---|---|
| POTACAT-style top-level interface selector? | **No.** Rederived from first principles: protocol is a property of the radio, not an operator preference; the only place a protocol selector earns UI is the unlisted-rig escape hatch, which Phase 2.5 already built (protocol knob inside CAT generics). POTACAT's selector exists for transport diversity PackRig doesn't have. |
| Sequencing | **CI-V first** (this spec); Kenwood/Elecraft after, as the small ASCII-sibling delta. |
| Preset scope | **Flagship presets** (IC-7300, IC-705, IC-7100, G90, X6100) at "CAT from manual" tier; the generic + address field covers the rest. No broad table. |
| Verification | Owner cannot obtain CI-V hardware. **Desk-verify at best effort** (every command byte cross-checked against ≥2 of hamlib / FT8CN / wfview, discrepancies documented), ship community-tier, collect field reports via issue template. |
| Frame filtering architecture | **B — frame-stream refactor** (owner's call over the minimal-seam option). Made safe by: byte-identical Yaesu regression tests against the fake transport, and a hard merge gate re-verifying FT-891 + FTX-1 on the bench (both reference rigs are on hand, so B's usual hardware cost is cheap here). |
| CI-V address on named presets | **Prefilled and editable** (not fixed): users can change the rig-side address and a mismatch is otherwise undebuggable — same reasoning that widened the CAT port override on 2026-07-11. |
| `RigProfile` shape | **Flat nullable knob** (`civAddress: Int?`), consistent with the shipped JSON persistence scheme. A sealed per-family parameter block was considered and rejected: one extra field doesn't justify reworking persistence + form + resolve. |

## Architecture

### Frame layer (the refactor)

`SerialRigBackend`'s terminator-scan loop is replaced by a frame reader
driven by protocol-supplied framing knowledge. New `CatProtocol`
members (defaults preserve current Yaesu semantics exactly):

```kotlin
/** Split accumulated bytes into complete frames + unconsumed remainder. */
fun splitFrames(bytes: ByteArray): FrameSplit
// ASCII default: split after each ';'. CI-V: split after each 0xFD,
// dropping bytes that precede a valid 0xFE 0xFE preamble.

/** What a complete frame is, relative to the last command sent. */
fun classifyFrame(frame: ByteArray): FrameClass
// Default: Reply (accept everything — today's behavior).
enum class FrameClass { Reply, Echo, Ack, Nak, Broadcast, Junk }

/** Whether stale input must be drained before each exchange. */
val wantsInputFlush: Boolean   // default false; CI-V true

/** Whether set commands are acknowledged (await Ack/Nak). */
val setCommandsAcked: Boolean  // default false; CI-V true
```

A CAT exchange becomes: flush stale input (if `wantsInputFlush`) →
write command → accumulate reads, split into frames, and consume until
a `Reply` arrives or the existing 1 s deadline expires. `Echo` and
`Junk` are dropped (counted for diagnostics), `Ack`/`Nak` outside a
set-command context are dropped, `Broadcast` is forwarded to an
optional listener. Set commands: when `setCommandsAcked`, `catWrite`
becomes an exchange awaiting `Ack`/`Nak` and returns truthful
success — `setDataMode`, `setFrequencyHz`, and `catPtt` gain real
error reporting on CI-V. Yaesu set commands stay fire-and-forget.

`replyTerminator` disappears from the `CatProtocol` interface
(subsumed by `splitFrames`); `probeFrequency` is rebuilt on the frame
reader (see Test CAT). The `catLock` serialization, 200 ms transfer
timeout, and 1 s reply deadline are unchanged.

### `IcomCiV` parser

Pure `CatProtocol` implementation, no I/O, parameterized by
`IcomModelSpec`. Controller address `0xE0`; frames
`0xFE 0xFE <to> <from> <cmd> [<sub>] [data…] 0xFD`. `0xFD` cannot occur
inside a frame body (reserved by the CI-V spec), so frame splitting on
`0xFD` is sound.

| Operation | CI-V command |
|---|---|
| Read frequency | `0x03` → 5 BCD bytes, little-endian digit pairs (10 digits) |
| Set frequency | `0x05` + 5 BCD bytes |
| Read mode | `0x04` → BCD mode + filter |
| Set FT8 data mode | per-model strategy, see below |
| PTT | `0x1C 0x00 0x01` / `0x1C 0x00 0x00` |

Classification: a frame whose `to` address is `0xE0` (or the broadcast
`0x00`) and `from` is the rig address is a `Reply`/`Ack`/`Nak`
(`0xFB`/`0xFA` command bytes) — a frame whose `from` is `0xE0` is our
own `Echo`. Command `0x00`/`0x01` frames addressed to `0x00` are
transceive `Broadcast`s; they parse into passive updates of the cached
frequency/mode (polling cadence unchanged — broadcasts just make dial
tracking snappier when the rig sends them). Anything else, and any
frame with a malformed preamble or BCD payload, is `Junk` → parse
methods return null per house error-handling style.

**Data-mode strategy** is an enum in `IcomModelSpec` because the
command differs by rig generation:

- `CMD_26`: one-shot `0x26 0x00 <mode> <data=0x01> <filter>` —
  modern rigs (IC-7300, IC-705 class).
- `CMD_06_PLUS_1A`: `0x06 <mode>` then the model's `0x1A` data-mode
  subcommand — older Icom and Xiegu firmware.

Per-model strategy assignment, exact `0x1A` subcommand bytes, USB
default bauds, and frequency bounds are **authored during
plan-writing** from hamlib backend sources, FT8CN's
`rigs/*RigConstant.java`, and wfview — each entry cross-checked
against at least two of the three, with discrepancies noted inline in
the model table. Values in this spec are factory defaults from vendor
documentation and subject to that cross-check.

### Model table + presets

New `IcomModelSpec` (mirrors `YaesuModelSpec`: display name, bus
address, data-mode strategy, frequency bounds) and `IcomModels` table.
Five `RigRegistry` presets, all `transportVerified = false`:

| Preset | id | Address | Connection |
|---|---|---|---|
| Icom IC-7300 | `ic7300` | `0x94` | built-in USB (CP210x class) |
| Icom IC-705 | `ic705` | `0xA4` | built-in USB |
| Icom IC-7100 | `ic7100` | `0x88` | built-in USB |
| Xiegu G90 | `xiegu-g90` | `0x70` | Digirig (RTS PTT default) |
| Xiegu X6100 | `xiegu-x6100` | `0xA4` | built-in USB |

USB bridge PIDs (whether the stock prober covers each rig's bridge or
`customProbePids` entries are needed) are confirmed during
plan-writing alongside the protocol cross-check.

### Profiles + Settings

- `CatProtocols` gains `ICOM_CIV = "icom-civ"` ("Icom CI-V") — the
  dropdown inside `generic-digirig` / `generic-cat` grows; ids
  persisted, never renamed.
- `RigProfile` gains `civAddress: Int?` (null = preset default),
  resolved through the existing override-wins scheme. Any new field
  must also be mirrored through `SettingsBridge`'s `SettingsSlice`
  (Phase 2.5 lesson).
- Form field **CI-V address** (2-digit hex, validated `0x01`–`0xDF`,
  `0xE0` reserved for the controller): **required** when a CAT generic
  selects the CI-V protocol; **prefilled and editable** on the five
  CI-V presets; absent everywhere else. Follows the
  `SettingsTextField` pattern for persisted free-text fields.
- A read-only **Protocol** info row on named-model presets ("Icom
  CI-V, address 94" / "Yaesu CAT") — one-line delta for debuggability;
  presets' protocol stays non-editable.
- Display copy stays plain-language per the settings-audit register
  (no "bus", no "controller"; the address field's helper text points
  at the rig's CI-V menu).

### Test CAT + community verification

`probeFrequency` on the frame layer classifies:

- **Sync** — a parseable frequency `Reply` arrived.
- **Echo only** *(new)* — only our own echoed command came back:
  "The cable echoes commands but the radio didn't answer — check the
  CI-V address matches the radio's menu, and that the radio is on."
- **Garbage** — bytes arrived but no valid frame parsed (wrong baud on
  echoing setups, non-CI-V device).
- **Silence** — nothing arrived (copy continues to lead with baud
  rate, per the 2026-07-11 field finding).

Community path: `docs/RIG_MODELS.md` gains the five rows at "CAT from
manual" tier plus a "How to verify a CI-V rig" section; a GitHub issue
template asks for model, connection path, Test CAT outcome, and a
completed-QSO confirmation. Rows flip to Verified (and
`transportVerified = true`) on tester reports.

## Error handling

- Malformed frames (bad preamble, short body, non-BCD digits) parse to
  null; the poll loop's existing stale-value behavior applies.
- `Nak` (`0xFA`) on a set command → the command reports failure;
  surfaced through the existing `catStatus` string path.
- Junk/echo frame counts are exposed to the Test CAT diagnostic only —
  no new main-screen UI (behavior-parity constraint).
- Ack deadline uses the existing 1 s reply budget; timeout on an acked
  set command reports failure, logged.

## Testing

- **Yaesu parity (the refactor's bar):** recorded command/reply byte
  sequences from the existing `YaesuCat` tests replayed through the
  new frame layer must be byte-identical on the wire — same commands
  written, same parses returned. Fake-transport regression suite
  asserts this before any CI-V code lands.
- **CI-V protocol-over-fake-transport suites:** frames split across
  reads; echo ON and OFF; ack interleaved with replies; transceive
  broadcast bursts arriving mid-exchange; wrong-address (rig answers
  another controller) → treated as Junk; malformed BCD; `0xFA` Nak on
  set; flush-before-exchange draining stale acks.
- **Desk verification:** per-model command tables cross-checked
  against ≥2 of hamlib / FT8CN / wfview, recorded in the plan.
- **Hardware gates:**
  - *Hard merge gate:* FT-891 + FTX-1 bench re-verification on the
    refactored frame layer (CAT sync, TX key, Test CAT spot-checks) —
    the milestone core value (byte-equivalent behavior on the
    reference rigs) applies to the refactor, not just to features.
  - *Community gate (non-blocking):* CI-V presets ship unverified;
    Verified status follows tester reports.
