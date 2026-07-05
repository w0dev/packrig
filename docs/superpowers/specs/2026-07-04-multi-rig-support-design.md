# Multi-Rig Support — Design

**Date:** 2026-07-04
**Branch:** `multi-rig` (own milestone; `readiness` ships v1.x as-is)
**Status:** Approved design, pre-implementation

## Goal

Support modern transceivers beyond the Yaesu FT-891 — Yaesu new-CAT family,
Kenwood, and Icom CI-V — over multiple connection paths: Digirig Mobile
(CP210x), the rig's built-in USB (serial + audio codec), and generic USB-serial
CAT cables. Reference peers: FT8CN, Potacat (JTCAT), WSJT-X (hamlib) — all use
the same layering this design adopts (transport / protocol / rig table).

**Reference hardware:**

- Yaesu FT-891 + Digirig Mobile — existing rig; behavior must stay
  byte-equivalent. Field regression on this setup gates every phase.
- Yaesu FTX-1 over built-in USB — second reference rig (owner hardware);
  verifies the direct-USB serial + audio path.

## Non-Goals

- Older/binary Yaesu CAT (FT-857D, FT-818 5-byte protocol) — different parser,
  out of scope for this milestone.
- Bluetooth or network CAT — USB only.
- Rig control beyond what FT8 operation needs (frequency, data mode, PTT).
  No filter/power/menu control.
- Auto-detecting the rig model. The operator selects it; only the PTT method
  keeps its probe-with-default behavior.

## Architecture

Four components, all in the `rig` module. Today's stack
(`RigController` → `DigirigRigBackend` → hand-rolled `Cp210x` transfers +
hardcoded `Ft891Cat`) is refactored into:

```text
RigSession (app)                       — unchanged: timeouts, unreachable latch
  └─ RigController                     — composes the three below from RigDescriptor
       ├─ SerialTransport              — byte pipe (usb-serial-for-android behind seam)
       ├─ CatProtocol                  — pure per-family command builder/parser
       └─ PttStrategy                  — RTS | DTR | CAT command
```

### 1. `SerialTransport` (transport seam)

Thin interface owned by us: `open/close`, `read/write` with timeout,
`setRts/setDtr`, baud/8N1 config. Sole production implementation wraps
**usb-serial-for-android** (`com.github.mik3y:usb-serial-for-android:3.9.0`,
see Dependency Decision). This replaces the hand-rolled CP210x control-transfer
code and brings driver coverage for:

- CP210x — Digirig Mobile (current path)
- CP2105 dual-UART — Yaesu built-in USB; CAT rides the **Enhanced** port, and
  the library exposes both ports so we select correctly (the current
  "first bulk interface" heuristic cannot)
- FTDI, CH340, PL2303 — generic CAT cables and SignaLink-style interfaces
- CDC-ACM — Icom built-in USB (e.g. IC-705)

Protocol drivers never import the library; tests use a scripted
`FakeSerialTransport`.

### 2. `CatProtocol` (per-family protocol drivers)

Pure builders/parsers in the mold of today's `Ft891Cat` object — no I/O, no
Android, transcript-fixture unit tests. Interface exposes only what the app
needs:

```kotlin
interface CatProtocol {
    fun readFrequencyCommand(): ByteArray
    fun parseFrequency(reply: ByteArray): Long?
    fun setFrequencyCommand(hz: Long): ByteArray
    fun readModeCommand(): ByteArray
    fun parseModeLabel(reply: ByteArray): String?
    fun setDataModeCommand(): ByteArray      // rig's FT8 data mode (e.g. DATA-U)
    fun pttCommand(on: Boolean): ByteArray?  // null if family has no CAT PTT
    val replyTerminator: ReplyFraming        // ';' for ASCII families, 0xFD for CI-V
}
```

Families (ByteArray, not String, so CI-V binary fits the same interface):

- **`YaesuCat`** — existing new-CAT ASCII dialect, parameterized by a per-model
  table (tuning range, mode-code map, data-mode code). FT-891 is the first
  entry and must produce byte-identical commands to today's `Ft891Cat`.
  Further entries: FT-991A, FTDX10, FT-710, FTDX101, FTX-1.
  *Open item:* confirm the FTX-1 CAT reference manual matches the FT-710/FTDX10
  dialect before its table entry is trusted.
- **`KenwoodCat`** — TS-590SG, TS-890S, etc. Close ancestor dialect of Yaesu
  new-CAT; small delta from `YaesuCat`.
- **`IcomCiV`** — binary framed (0xFE 0xFE … 0xFD), BCD frequencies,
  parameterized by CI-V address (IC-7300 = 0x94, IC-705 = 0xA4, IC-9700,
  IC-7100, …). Must handle the echo of our own frame on the bus.

### 3. `PttStrategy`

Explicit per-descriptor setting with three implementations:

- **RTS** — Digirig hardware PTT (current default for FT-891 + Digirig)
- **DTR** — some CAT cables key on DTR
- **CAT** — `TX1;`/`TX0;` (Yaesu), `TX;`/`RX;` (Kenwood), `0x1C 0x00` (CI-V)

The existing probe (`configurePttFromCatProbe`) survives as the
default-selection heuristic within the chosen rig model; the descriptor's
default wins when the probe is inconclusive. The RTS-always-deassert-after-TX
safety behavior in `RigController.releasePtt()` is preserved.

### 4. `RigDescriptor` registry + Radio model setting

Static table: display name, protocol family + parameters (freq range,
data-mode code, CI-V address), default CAT baud, default PTT method, serial
port selection hint (e.g. "Enhanced port" for CP2105). Settings gets a
**Radio model** dropdown persisted in `SettingsRepository`, defaulting to
**FT-891 (Digirig)** so existing installs see zero behavior change.
`RigController` composes transport + protocol + PTT from the descriptor
instead of hardcoding `DigirigRigBackend`.

## Interface Changes (app-facing)

- `CatControl` drops the `Ft891Cat.Mode` leak:
  `mode(): Ft891Cat.Mode?` → `modeLabel(): String?`;
  `setMode(Ft891Cat.Mode)` → `setDataMode(): Boolean`.
  Call sites: `RigSession.setMode(...)` and
  `OperateViewModel` (single `DATA_USB` call) shrink accordingly.
- `RigBackend` (keyPtt/releasePtt) unchanged.
- `RigSession` and its CAT timeout / unreachable-latch policy unchanged — it
  already talks only to the interfaces.
- USB device discovery moves from `Cp210x.matches(vid, pid)` to the library's
  prober (extended with custom entries if the FTX-1 needs one).

## Audio Path

No changes required. `UsbAudioCapture`/`UsbAudioPlayback` are generic
`AudioRecord`/`AudioTrack` with `AudioDeviceInfo` selection; a rig's built-in
USB Audio Class codec enumerates like the Digirig's does and appears in the
existing device picker. FTX-1 field test confirms this assumption.

## Error Handling

Unchanged by design: `SerialTransport` implementations return null/false on
timeout exactly as `DigirigRigBackend` does today; `RigSession`'s outer
`withTimeoutOrNull`, consecutive-failure counter, and "CAT unreachable — tap to
retry" latch sit above the seam and apply to every rig family identically.
CI-V framing errors (bad checksum-less frames, echo confusion) parse to null
and count as CAT failures like any malformed reply.

## Dependency Decision: usb-serial-for-android via JitPack

The library is MIT-licensed, single-module, plain Java, no meaningful
transitive deps, and distributed **only via JitPack**. Accepted with these
mitigations (all land in phase 1):

1. **Scoped repo:** JitPack added in `settings.gradle.kts`
   `dependencyResolutionManagement` inside an `exclusiveContent` block limited
   to `com.github.mik3y` — it can never resolve any other artifact.
2. **Exact pin:** `usbSerial = "3.9.0"` in `gradle/libs.versions.toml`
   (latest upstream tag as of 2026-07-04). Never a dynamic version.
3. **Checksum verification:** Gradle dependency verification metadata for the
   artifact, so CI fails if the bytes change for the same version.
4. **Vendoring escape hatch:** because all consumers go through
   `SerialTransport`, dropping the (MIT) sources in as a local module later is
   a build-file change only. Documented in
   [USB_SERIAL_LIB_UPGRADE.md](../../USB_SERIAL_LIB_UPGRADE.md), the runbook
   that mirrors [FT8_LIB_UPGRADE.md](../../FT8_LIB_UPGRADE.md).

This matches the project's existing trust posture: `ft8_lib` is already
fetched from GitHub at build time by pinned commit.

## Testing & Verification

- **Protocol drivers:** unit tests per family from transcript fixtures (real
  command/reply bytes from CAT operation manuals and hamlib test data).
  `YaesuCat`'s FT-891 entry gets a byte-equivalence test against the current
  `Ft891Cat` outputs before that object is deleted.
- **Transport:** `FakeSerialTransport` (scripted request → reply, injectable
  timeouts) drives protocol-over-transport tests, including CI-V echo handling
  and partial-read reassembly.
- **Regression bar (every phase):** FT-891 + Digirig field check — RX decodes,
  TX keys, full QSO completes. Phase 1 (transport swap) is where the risk
  concentrates; it does not merge without this.
- **New-rig bar:** FTX-1 over built-in USB verified on owner hardware.
  Other models ship as a "community-verified" tier: table entries + unit tests
  from manuals, flagged in the manual until someone confirms on real hardware.

## Phasing

1. **Seams + transport swap.** `SerialTransport` + usb-serial-for-android
   (with the three dependency mitigations), `CatProtocol` extraction with the
   FT-891 byte-equivalence test, `CatControl` mode-leak fix, `PttStrategy`.
   No user-visible change. Gate: FT-891 + Digirig field regression.
2. **Rig model setting + Yaesu family.** `RigDescriptor` registry, Settings
   dropdown, Yaesu table (991A, FTDX10, FT-710, FTDX101, FTX-1). Gate: FTX-1
   direct-USB field verification (serial CAT + built-in codec audio).
3. **Kenwood.** `KenwoodCat` + table entries. Community-verified tier.
4. **Icom CI-V.** New binary parser + address table. Biggest protocol work;
   community-verified tier unless hardware becomes available.

## Open Items

- **FTX-1 USB descriptor dump** (owner, on hardware): Settings screen's USB
  diagnostic line (`usbDeviceSummary()`) output with the rig attached — decides
  whether the prober needs a custom VID/PID entry and confirms the codec
  enumerates as a USB audio device.
- **FTX-1 CAT reference manual check** before trusting its `YaesuCat` table
  entry.
- Whether Digirig users with non-Yaesu rigs need a PTT-only descriptor
  ("Generic Digirig — RTS PTT, no CAT") — cheap to add to the registry;
  decide during phase 2.
