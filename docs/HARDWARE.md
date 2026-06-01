# Hardware setup: Yaesu FT-891 + Digirig Mobile + Android

> Draft. Fill in with confirmed menu values once validated on-air. Always test
> TX into a dummy load first, and only transmit if you are licensed.

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
| TBD     | TBD    | TBD   | confirm on-air |

## Digirig configuration

- PTT method: **RTS** of the CP2102 serial port (Digirig Mobile hardware PTT).
- Serial port electrical level must match the FT-891 (set via Digirig solder
  switches per Digirig docs).
- Confirm the correct transceiver cable for the FT-891.

## Validation checklist

1. Phone enumerates the Digirig **audio** and **serial** USB devices.
2. App receives audio (level meter moves on band noise).
3. RTS PTT keys the radio (no RF / into dummy load first).
4. Single-tone TX audio produces clean output.
5. Full FT8 TX into a dummy load decodes on a second receiver (e.g. WSJT-X on PC).
