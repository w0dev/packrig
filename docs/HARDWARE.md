# Hardware setup: Yaesu FT-891 + Digirig Mobile + Android

This is the wiring and menu reference for Packset's primary reference rig.
Menu values below were confirmed on-air 2026-06-02. For other radios
(including the FTX-1 second reference rig and the generic presets), see
[RIG_MODELS.md](RIG_MODELS.md).

> Always test TX into a dummy load first, and only transmit if you are
> licensed.

## Signal chain

```
Phone (USB-C, OTG host)
  └─ USB-C cable (data + power)
       └─ Digirig Mobile
            ├─ Audio (3.5mm TRRS): RX audio, TX audio, hardware PTT, ground
            └─ Serial (3.5mm TRRS): CAT control + RTS PTT
                 └─ FT-891 (DATA / ACC + CAT ports)
```

The Digirig exposes two USB devices behind an internal hub:

- **CM108 USB audio** — standard USB audio class; used for RX/TX audio.
- **CP2102 USB serial** — used for **PTT via the RTS line** and optional FT-891 CAT.

## Android requirements

- USB host / OTG support.
- Enough OTG power for the Digirig; a **powered OTG hub** may be required.
- A USB-C cable that carries **data and power** (not charge-only).

## FT-891 menu settings (to confirm)

Digital/data operation, typical starting points — verify against the FT-891
manual and your on-air results:

- Operating mode: `DATA-U` (USB data) for FT8.
- `MENU` items for DATA input source, DATA gain, and CAT rate/parameters.
- CAT baud rate: match the app's serial config (FT-891 supports up to 38400).
- Disable/parameterize speech processor and ALC per digital-mode best practice.

| Setting | Menu # | Value | Notes |
|---------|--------|-------|-------|
| CAT RTS | 05-08 | **DISABLE** | Required for Packset CAT PTT (`TX1;`/`TX0;`). If enabled, asserting the Digirig RTS line can latch TX or block un-key. Desktop CAT PTT software often does not drive RTS, so this menu may appear fine on PC until Android touches RTS. |
| CAT rate | 05-06 | **38400** | Must match the **CAT baud rate** in the Packset rig profile (Settings → Radio; 38400 is the FT-891 preset default). |
| DATA IN | 08-09 | **REAR** | Digirig audio on the 6-pin DATA jack. |
| Operating mode | — | **DATA-U** (`D-U` on display) | Plain USB keys the mic path; rear data audio stays muted (0 W). |

Confirmed on-air 2026-06-02: phone + Digirig OTG, Packset CAT PTT, ~20 W after ~1 s key-up, spots on PSK Reporter.

## Digirig configuration

- **CAT PTT** (`TX1;`/`TX0;` at 38400) when the rig answers CAT — the standard "CAT" PTT method used by most digital-mode software.
- **RTS** on the CP2102 is the hardware PTT fallback if CAT readback fails; keep Menu **05-08 CAT RTS** disabled when using CAT PTT.
- Serial port electrical level must match the FT-891 (set via Digirig solder
  switches per Digirig docs).
- Confirm the correct transceiver cable for the FT-891.

## Validation checklist

1. Phone enumerates the Digirig **audio** and **serial** USB devices.
2. App receives audio (level meter moves on band noise).
3. PTT keys the radio — CAT `TX1;`/`TX0;` and/or Digirig RTS (dummy load first).
4. TX audio produces power on the meter (D-U, REAR data in, phone USB audio out).
5. Full FT8 TX decodes elsewhere (e.g. PSK Reporter spots or a second receiver).
