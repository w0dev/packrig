# Rig module (`rig/`)

PTT and CAT control for the Digirig Mobile's CP2102 USB-serial bridge, targeting
the Yaesu FT-891. Falls back to no-op when no hardware is connected (emulator-safe).

## Purpose

Phase 3 deliverable: key the radio via RTS on the Digirig serial port, and read/set
VFO frequency and operating mode over FT-891 CAT on the same UART.

## Signal chain

```
RigController
  ├── find CP2102 (VID 0x10C4, PID 0xEA60)
  ├── USB permission flow
  └── DigirigRigBackend (or NoOpRigBackend)
        ├── PTT: CP210x SET_MHS → RTS line
        └── CAT: bulk UART @ 38400 8N1 → FT-891
```

## Key types

### `RigBackend` (interface)

- `keyPtt()` / `releasePtt()`

### `CatControl` (interface)

- `frequencyHz()` / `setFrequencyHz(hz)`
- `mode()` / `setMode(mode)`

### `RigController`

Android-facing facade:

| Method | Purpose |
|--------|---------|
| `findDevice()` | Locate connected CP2102 |
| `state()` | `NoDevice`, `NeedsPermission`, or `Ready` |
| `ensureReady(onResult)` | Request USB permission and bind |
| `bindIfPermitted()` | Open backend if permission already granted |
| `keyPtt()` / `releasePtt()` | Route to Digirig or no-op |
| CAT methods | Delegate to open Digirig backend |
| `close()` | Release USB connection |

Implements both `RigBackend` and `CatControl` so callers have a single entry point.

### `DigirigRigBackend`

Opens CP2102 interface 0, enables UART, configures 8N1 at `DEFAULT_CAT_BAUD` (38400).
PTT uses `Cp210x.REQUEST_SET_MHS` with RTS mask/state. CAT commands are ASCII
terminated by `;`, sent/received over bulk endpoints with serialized access (`catLock`).

### `NoOpRigBackend`

Safe fallback when no Digirig is present — PTT and CAT calls succeed silently.

### `Cp210x`

Pure constants and helpers for Silicon Labs CP2102 control transfers:

- Vendor/product IDs for Digirig detection
- `mhsValue(rts)` — RTS modem line for PTT
- `baudRateBytes()`, `LINE_CTL_8N1` — UART configuration

### `Ft891Cat`

Pure builder/parser for FT-891 CAT (no serial I/O):

| Command | Example | Purpose |
|---------|---------|---------|
| `FA;` / `FAnnnnnnnnn;` | `FA014074000;` | VFO-A frequency query/set (Hz, 9 digits) |
| `MD0;` / `MD0x;` | `MD0C;` | Mode query/set (`DATA_USB` = `C`) |

Frequency range: 30 kHz – 56 MHz. Default CAT baud must match rig menu 05-06.

## Dependencies

- Android USB host APIs (`UsbManager`, `UsbDeviceConnection`)
- No dependency on `core`, `audio`, or `ft8-native`

## Tests

Pure protocol tests on the JVM. See [TESTING.md](TESTING.md#rig--2-test-classes-15-tests).

```powershell
.\gradlew.bat :rig:testDebugUnitTest
```

USB/PTT integration is validated manually — see [HARDWARE.md](HARDWARE.md).

## Integration in the app

`MonitorViewModel`:

- `prepareRig()` when TX is enabled — discovers Digirig, requests permission
- `transmitNextSlot()` / QSO loop — `rig.keyPtt()` before playback, `releasePtt()` after
- CAT panel — `readRig()`, `setRigFrequency()`, `setRigDataUsb()` on background executor

## Related docs

- [HARDWARE.md](HARDWARE.md) — wiring, FT-891 menu settings, validation checklist
- [APP.md](APP.md) — TX and CAT UI wiring
