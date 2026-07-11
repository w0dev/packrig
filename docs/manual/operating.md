# Operating

The **Operate** tab is where a session happens: status bar on top, decode
list in the middle, the TX message strip and the Start / CQ / End QSO buttons
at the bottom. The **Spectrum** tab holds the full-screen waterfall and the
TX tone picker. This chapter walks both, then the QSO automation that ties
them together.

## Starting and stopping

Tap **Start** to begin operating: FT8VC opens the USB audio input, aligns to
the 15-second UTC slots, and decodes each slot as it closes. **Stop** ends
the session.

If **Start receive when radio connects** is on (Settings → Audio, default
on), receive may already be running before you press anything — the status
bar shows a **Monitoring** chip. Monitoring is receive-only; pressing
**Start** adopts the running capture into a full operating session without
losing the current slot.

## The status bar

The compact status bar packs the session state into three rows:

- **Dial frequency (MHz)** — read over CAT. Tap it to open the band picker
  and retune the rig to a preset FT8 dial frequency; the band list adapts to
  the selected radio model (6 m / 2 m / 70 cm appear where the rig covers
  them). With a no-CAT rig this shows the manual dial frequency from
  Settings → Radio.
- **Mode** — the rig's current mode. If it isn't `DATA-U`, a warning banner
  offers one-tap **Set DATA-U**.
- **TX tone** — your audio offset in Hz, set on the Spectrum tab.
- **POTA chip** — shows `CQ POTA` when POTA mode is active (tap to edit the
  park reference), or `POTA?` when POTA mode is on but the reference is
  missing or invalid.
- **HALT** — immediately cancels an in-progress transmission and un-keys the
  rig. Always available while transmitting.
- **Slot progress and UTC** — a progress bar across the current 15 s slot,
  the UTC clock, seconds to the next slot boundary, and — when TX is enabled —
  a `TX Ns` countdown to your next transmit period.
- **Even/Odd chips** — pick your TX period: Even transmits on :00/:30 slots,
  Odd on :15/:45.
- **Level meter and input gain** — the meter runs whenever audio is flowing
  (green → amber → red/CLIP). Tap the volume icon to open the input gain
  slider without leaving the tab.

Status chips appear in the bar when something needs attention:

| Chip | Meaning |
|------|---------|
| **Monitoring** | RX is running via auto-monitor without a full operating session |
| **Clock +N.Ns** | Phone clock is off vs received stations — tap to align (see Settings → Clock alignment) |
| **Digirig disconnected — RX only** | The serial device vanished; PTT/CAT inert until it returns |
| **CAT unreachable — tap to retry** | CAT reads are timing out; tap to re-probe |
| **Audio capture failed — tap to retry** | The capture watchdog exhausted its automatic restarts |
| **TX safety halt — see Settings** | PTT was force-released by the watchdog; TX is gated until acknowledged in Settings → TX |
| **Decodes dropped: N** | Decode passes were skipped (e.g. the device fell behind) |

## The decode list

Each decoded message is a row: **UTC**, **SNR** (dB), **DIST** (great-circle
km, when the message carries a 4-character grid and your grid is set), a
two-letter **CC** country column, and the message text. New decodes arrive at
the **bottom**; the list stays pinned to the newest row unless you scroll up
to read history. The UTC cell is tinted by slot parity so Even and Odd
traffic read apart at a glance.

Rows are color-coded by category — your own TX, your QSO partner, messages
directed to your call, CQs, stations you've worked before, and everything
else. The palette is customizable in **Settings → Display → Decode colors**.

### Filters

- **Focus** (default) shows only what matters while working: traffic
  involving your call, the active QSO partner, and signals near your TX tone.
- **Band** shows the full passband.
- The **CQ·73** chip narrows either view to CQs and QSO-enders — useful when
  hunting.

The header shows `visible/total` counts, and **Clear decodes** empties the
list.

### Acting on a decode

- **Tap a CQ row** to answer it (TX must be enabled and no QSO active).
- **Tap a row directed to your call** to start or resume a QSO with that
  station from the right point in the sequence — the recovery path if the
  app restarted mid-contact.
- **Long-press any row** to block its sender. Blocked stations disappear
  from the decode list and are excluded from auto-answer; manage them under
  **Settings → Auto TX → Blocklist**.

The first TX-causing tap shows the one-time license acknowledgment dialog
(see [Getting started](getting-started.md#enable-transmit)).

## Calling CQ

**Start CQ** (labelled **CQ POTA** when POTA mode is on) begins calling on
your chosen Even/Odd period; the first transmission waits for your slot.
Answers are handled by the QSO state machine from there. The button requires
your callsign and grid to be set.

## The TX message strip

Above the control buttons, the TX strip shows what will be sent in your next
TX slot. It normally reads **Auto** — the auto-sequenced message for the QSO
state. Tap **Msg ▾** to pick a different step in the standard sequence or
enter **free text**; the reset action returns to the auto-sequenced value.
When TX is disabled it reads "RX only — enable TX in Settings."

## QSO automation

FT8VC runs a standard FT8 sequence: CQ → grid exchange → signal reports →
RRR/RR73 → 73, advancing automatically when the expected reply is decoded
(**Auto sequence**, on by default). The automation toggles live in
**Settings → Auto TX**:

- **Answer when called** — someone calling you (grid, report, …) starts or
  resumes a QSO even when you're idle.
- **Auto answer CQ** — when idle, pick a CQing station and call them.
- **Answer selection** — when several stations qualify in one slot: **First
  decoded**, **Best signal (SNR)**, or **Furthest station** by grid distance.
- **Abandon after no reply** — after N TX cycles with no decode progress,
  stop calling and drop that station from auto-selection for the session.
- **Send RR73** — on: send RR73 and log immediately; off: send RRR and log
  when the partner's 73 arrives.
- **Resume CQ after QSO** — after a contact you started by calling CQ, keep
  calling CQ. Search-and-pounce contacts don't auto-resume.
- **Late-start TX** — transmit a truncated waveform up to 7 s into the slot
  so a late answer still goes out this cycle.
- **Early decode** — an extra decode pass partway through each slot surfaces
  CQs ~3 s sooner.

**End QSO** abandons the active contact immediately. Completed QSOs log
automatically — see [Logging and ADIF](logging.md).

## The Spectrum tab

The waterfall shows the received passband, newest at the top. **Tap or drag**
to set your TX audio offset; a bold red WSJT-X-style goalpost marker brackets
your 50 Hz footprint and a live **TX Hz** readout confirms the value. The
tone persists across tabs and app restarts, and FT8VC holds it for the whole
QSO — it never QSYs onto the DX's tone.

When CAT is ready, the **dial** label doubles as the band picker, same as on
the Operate tab.

## Safety and recovery

- **HALT** cancels a transmission mid-slot.
- A PTT **watchdog** guards against a stuck key. If it ever has to
  force-release PTT, a **TX safety halt** latches: TX stays gated until you
  acknowledge the halt in **Settings → TX**.
- A capture **watchdog** restarts USB audio automatically if the RX thread
  goes silent; if retries are exhausted, the **Audio capture failed — tap to
  retry** chip appears.

For symptom-by-symptom fixes, see [Troubleshooting](troubleshooting.md).
