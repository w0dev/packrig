# Rig module (`rig/`)

PTT and CAT control for a USB-serial-connected transceiver, targeting the
Yaesu FT-891 over the Digirig Mobile's CP2102 bridge today. Falls back to
no-op when no hardware is connected (emulator-safe).

## Purpose

Phase 3 deliverable: key the radio via RTS on the Digirig serial port, and read/set
VFO frequency and operating mode over FT-891 CAT on the same UART. The
multi-rig phase 1 pass (below) re-layered this behind reusable transport and
protocol seams so other rigs/interfaces can be added without touching PTT or
CAT call sites.

## Signal chain

```
RigController
  ├── find CP210x (VID 0x10C4, PID 0xEA60/0xEA61)
  ├── USB permission flow
  └── SerialRigBackend (or NoOpRigBackend)
        ├── transport: UsbSerialTransport (usb-serial-for-android)
        │     ├── PTT: RtsPttStrategy → serial RTS line
        │     └── CAT: ASCII writes/reads over the serial port
        └── protocol: YaesuCat(YaesuModelSpec.FT891) — command build/parse
```

## Key types

### `RigBackend` (interface)

- `keyPtt()` / `releasePtt()`

### `CatControl` (interface)

- `frequencyHz()` / `setFrequencyHz(hz)`
- `modeLabel()` / `setDataMode()` / `dataModeLabel()`
- `catPtt(on)` — CAT-based PTT (`TX1;`/`TX0;`) for rigs with no hardware PTT line

### `RigController`

Android-facing facade:

| Method | Purpose |
|--------|---------|
| `findDevice()` | Locate a connected supported USB-serial device |
| `state()` | `NoDevice`, `NeedsPermission`, or `Ready` |
| `ensureReady(onResult)` | Request USB permission and bind |
| `bindIfPermitted()` / `rebind()` | Open (or reopen) backend if permission already granted |
| `configurePttFromCatProbe()` | Pick RTS vs CAT PTT from whether the rig answers a frequency query |
| `keyPtt()` / `releasePtt()` | Route to the open serial backend or no-op |
| CAT methods | Delegate to the open `SerialRigBackend` |
| `close()` | Release USB connection |

Implements both `RigBackend` and `CatControl` so callers have a single entry point.
Phase 1 hardcodes the FT-891 protocol table when binding; phase 2's RigDescriptor
registry makes the model selectable.

### `SerialRigBackend`

Composes a `SerialTransport` (byte pipe) with a `CatProtocol` (per-rig command
builder/parser) into `RigBackend` + `CatControl`. Hardware PTT is
`RtsPttStrategy` over the transport's RTS line; CAT PTT is the protocol's
`pttCommand`. CAT exchanges are serialized on an internal lock and are
blocking — call off the main thread. Replaces the retired, fused
`DigirigRigBackend`.

### `NoOpRigBackend`

Safe fallback when no supported serial device is present — PTT calls succeed silently.

## Serial transport & protocol seams (multi-rig phase 1)

The rig module is layered so radios and interfaces vary independently:

- `SerialTransport` — byte pipe. Production impl `UsbSerialTransport` wraps
  [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
  **3.9.0** (JitPack, scoped to `com.github.mik3y` via `exclusiveContent`;
  checksum-pinned in `gradle/verification-metadata.xml`). Upgrade runbook:
  [USB_SERIAL_LIB_UPGRADE.md](USB_SERIAL_LIB_UPGRADE.md).
- `CatProtocol` — pure per-family command builder/parser. `YaesuCat` +
  `YaesuModelSpec` cover the Yaesu new-CAT ASCII family; the FT-891 table is
  byte-equivalent to the retired `Ft891Cat`.
- `PttStrategy` — `RtsPttStrategy` (Digirig hardware PTT) or `CatPttStrategy`
  (`TX1;`/`TX0;`). `RigController` still probes CAT reachability to pick the
  method at bind time.
- `SerialRigBackend` — composes the three; `RigController` builds it for the
  FT-891 (phase 2 adds the RigDescriptor registry and a Radio model setting).

Safety invariant: RTS is hardware PTT on the Digirig — nothing may assert RTS
except `keyPtt()`; open/close paths explicitly de-assert it.

## Dependencies

- Android USB host APIs (`UsbManager`, `UsbDeviceConnection`)
- `usb-serial-for-android` 3.9.0 (via `UsbSerialTransport`)
- No dependency on `core`, `audio`, or `ft8-native`

## Tests

Pure protocol tests on the JVM. See [TESTING.md](TESTING.md#rig--2-test-classes-15-tests).

```powershell
.\gradlew.bat :rig:testDebugUnitTest
```

USB/PTT integration is validated manually — see [HARDWARE.md](HARDWARE.md).

## Integration in the app

`RigSession` (owned by `OperateViewModel`'s controllers):

- Binds/rebinds `RigController` when TX is enabled — discovers the serial device, requests permission
- QSO loop — `rig.keyPtt()` before playback, `releasePtt()` after
- CAT slice — frequency read, mode read/set, and PTT-method probing run serialized on a dedicated CAT dispatcher

## Related docs

- [HARDWARE.md](HARDWARE.md) — wiring, FT-891 menu settings, validation checklist
- [APP.md](APP.md) — TX and CAT UI wiring
- [USB_SERIAL_LIB_UPGRADE.md](USB_SERIAL_LIB_UPGRADE.md) — upgrade runbook for the pinned serial library
