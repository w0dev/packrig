# Troubleshooting

Symptom-first. Most problems announce themselves as a status chip on the
Operate tab — start from the chip if you have one.

## No decodes

Work down the audio chain:

1. **Is audio flowing?** The level meter next to the volume icon should move
   with band noise. Pinned at zero: wrong input selected (Settings → Audio),
   a charge-only USB cable, or the rig's audio output level at zero.
   Showing **CLIP** or solid red: back off the input gain (volume icon) or
   the rig's output level.
2. **Right input?** With a USB interface attached it's auto-selected, but a
   connected headset can win — check Settings → Audio.
3. **Right frequency and mode?** Confirm the dial is on an active FT8
   sub-band and the rig is in its data mode (the Operate tab warns and
   offers **Set DATA-U** when CAT can see the wrong mode).
4. **Clock.** FT8 needs your clock within about a second. If decodes show up
   but nobody answers you, or a **Clock ±Ns** chip appears, tap the chip (or
   Settings → Clock alignment → **Align now**).
5. **Rig-free sanity check.** Select the phone microphone as input and play
   FT8 audio from another receiver or a recording near the phone — if that
   decodes, the app is fine and the problem is in the USB audio path.

## Decoding stopped after running for a while

A capture watchdog detects a silent RX thread and restarts USB audio
automatically — usually you'll only notice a one-slot gap. If automatic
recovery is exhausted, the **Audio capture failed — tap to retry** chip
appears; tap it. If retries keep failing, unplug and replug the interface
(power delivery on long OTG chains is the usual culprit — try a powered
hub).

## CAT unavailable, or Test CAT fails

- **"CAT unavailable — connect the radio's serial link and grant USB
  permission"**: the serial device isn't attached, or you declined the USB
  permission prompt. Reconnect the interface and accept the prompt; no app
  restart needed.
- **Test CAT says "No response"**: check the **CAT baud rate** first — on
  Yaesu rigs a baud mismatch reads as silence, not garbage, because the
  radio never recognizes the corrupted query and never replies (field-
  verified on the FT-891; menu 05-06 must match the profile, the reference
  setup uses 38400). Then check the **CAT port** on a multi-port USB bridge
  (try the other port, or Automatic), the cable, and that CAT is enabled on
  the radio.
- **Test CAT says "Received data but couldn't understand it"**: bytes are
  arriving but don't parse — an echoing or looped-back serial line, or a
  non-Yaesu-protocol device on the selected port.
- **CAT unreachable — tap to retry** chip mid-session: the radio stopped
  answering (band change on the rig menu, cable bump). Tap to re-probe.
- **USB diagnostics** (Settings → Radio) lists every attached USB device —
  if your serial bridge isn't in the list, Android isn't seeing it at all:
  suspect the cable, OTG adapter, or hub.

## PTT doesn't key, or the rig keys but puts out no power

- **No key at all**: check the profile's **PTT method** matches your wiring —
  CAT PTT needs working CAT; RTS PTT needs the serial line wired through
  (Digirig). On the FT-891, menu **05-08 CAT RTS** must be **DISABLE** or
  RTS can latch TX or block un-key (see [Hardware setup](../HARDWARE.md)).
- **Keys but ~0 W**: the rig is in plain USB instead of **DATA-U** (mic path
  keyed, rear data audio muted), or DATA IN isn't set to REAR.
- **Keys but low power**: check the **phone's media volume for the USB
  output** — Android volume directly scales TX audio drive, and a
  few notches down can mean single-digit watts. Turn the media volume to
  maximum and set drive with the rig's data gain instead.

## "TX safety halt — see Settings"

The PTT watchdog had to force-release a transmission (stuck key
protection). TX is deliberately gated until you go to **Settings → TX** and
tap **Acknowledge TX safety halt**. If it recurs, suspect the serial link
dropping mid-TX — cable, hub power, or RTS wiring.

## "Digirig disconnected — RX only"

The serial device vanished mid-session. RX keeps running over USB audio;
PTT and CAT are inert until the device returns. Replug the interface; FT8VC
re-binds without a restart. Frequent disconnects point at OTG power — use a
powered hub and a known-good data cable.

## App restarted mid-QSO

QSO state is in-memory by design. When the app comes back, your partner's
next transmission shows in the decode list directed at your call — **tap
that row** to resume the QSO from the right step in the sequence.

## Log and export problems

- **ADIF export failed / "Invalid park list"**: POTA mode is on with a
  missing or malformed park reference. Exports fail closed rather than
  produce a bad file — fix the reference in Settings → POTA (format
  `US-3315`, comma-separated).
- **Lost the log?** Check `Documents/ft8vc` — an ADIF backup is written
  there after every QSO and survives uninstall. Re-import it via
  Settings → Logbook → **Import ADIF…**.

## "Decoder library: FAILED — reinstall app"

Shown in Settings → About when the native FT8 decoder didn't load — the
install is broken (wrong-ABI or corrupted APK). Reinstall from a fresh
download.

## Still stuck?

Grab the **USB diagnostics** text (Settings → Radio), note the app version
(Settings → About) and channel (stable vs unstable), and open an issue on
GitHub with the symptom and what you've ruled out.
