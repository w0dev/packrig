# Settings reference

The **Settings** tab is organized into four sub-tabs — **General**, **Rigs**,
**Display**, and **Integrations** — documented here in on-screen order.
Settings persist across app restarts; the QSO in progress does not (by
design — recover by tapping the partner's next message on the Operate tab).

## General

### Station

| Setting | Default | Behavior |
|---------|---------|----------|
| **Callsign** | empty | Your callsign. Validated as-you-type; TX features stay disabled until callsign and grid are set. |
| **Grid** | empty | 4- or 6-character Maidenhead locator (e.g. `FN31`). Used on-air in CQ/grid messages and for the decode list's distance column. |

### Audio

| Setting | Default | Behavior |
|---------|---------|----------|
| **Audio input** | `Automatic (system default)` | Input device for RX. Locked while capture or TX is running. |

Audio routes automatically: when a USB interface (Digirig or the radio's
built-in USB audio) is attached, it's used for RX and TX — no selection
needed. The picker reflects this:

- **`Automatic (system default)`** — no USB interface attached; the system
  default input is in use.
- **`Automatic — <name> (<type>)`** — a USB interface was picked
  automatically.
- **`<name> (<type>)`** — you picked this device manually from the dropdown.

Pick a device manually only if automatic routing chooses the wrong one (e.g.
a USB hub or multiple audio devices), or for mic-based rig-free testing.
Selecting **`Automatic (system default)`** from the dropdown returns to
automatic routing.

Input **gain** is adjusted on the Operate tab (volume icon in the status
bar), not here. Default gain is 100%.

### TX

| Setting | Default | Behavior |
|---------|---------|----------|
| **Enable transmit** | off | Master TX switch — the app cannot key the radio while this is off. Locked during a transmission. |

The TX tone is set on the Spectrum tab (default 1000 Hz). The first actual
transmit attempt additionally requires the one-time license acknowledgment
dialog.

**Acknowledge TX safety halt** appears here only after the PTT watchdog has
force-released a stuck transmission. TX stays gated until you acknowledge.

### Auto TX

Selection and limits apply to all auto TX modes.

| Setting | Default | Behavior |
|---------|---------|----------|
| **Answer selection** | First decoded | When several stations qualify in one slot (pileup, hunt, resume): **First decoded**, **Best signal (SNR)**, or **Furthest station (grid)**. |
| **Abandon after no reply** | 5 cycles | Stop TX and block auto-resume when a QSO makes no decode progress for N TX cycles. **Off** disables the limit. Abandoned stations are skipped by auto-selection for the rest of the session. |
| **Blocklist** | empty | Stations you long-pressed to block on the Operate tab. Hidden from the decode list and excluded from auto-answer. **Unblock** per call, or **Clear all**. |
| **Auto sequence** | on | Advance an active QSO when the expected reply is decoded. |
| **Answer when called** | on | Start or resume a QSO when someone calls you (grid, report, …). |
| **Auto answer CQ** | off | Call CQing stations when idle. |
| **Late-start TX (up to 7 s into slot)** | on | Send a truncated waveform so a late Answer/Resume still goes out this slot. |
| **Early decode (CQs ~3 s sooner)** | on | Run an extra decode pass partway through each slot. |
| **Send RR73 (log on send)** | on | On: send RR73 and log immediately. Off: send RRR and log on the partner's 73. |
| **Resume CQ after QSO** | off | After a QSO you started by calling CQ, keep calling CQ. Search-and-pounce QSOs never auto-resume. |

The TX slot parity (Even/Odd) is picked on the Operate tab status bar.

### POTA

| Setting | Default | Behavior |
|---------|---------|----------|
| **POTA mode** | off | CQs become `CQ POTA <call> <grid>` on-air; ADIF exports add `MY_SIG` / `MY_SIG_INFO`. |
| **Park reference** | empty | Required while POTA mode is on — format `US-3315`; comma-separate multiple parks for a two-fer. Exports fail closed if POTA mode is on without a valid reference. |

### Clock alignment

FT8 needs the clock within about one second of the other stations. This
section shows the **applied correction** and the current **offset vs
received stations** (measured from decode timing; needs a few decodes).

- **Align now** — apply the measured offset so your slots line up with the
  band. Also reachable from the clock-offset chip on the Operate tab.
- **Reset** — clear any applied correction.

### About

App version, and whether the native FT8 decoder library loaded (a load
failure means the install is broken — reinstall the APK).

## Rigs

Rig control is organized around **rig profiles** — up to 5 saved
configurations shown as a card list. Tap a card to select that rig; the
**None** card ("No radio connected") deselects rig control entirely.
Deleting the selected rig falls back to **None**.

- **+ Add Rig** (dashed button below the cards), or **Edit** on an existing
  card — opens the profile editor:
  - **Name** — free text, unique among your profiles.
  - **Radio model** — a preset that prefills everything below. Named models:
    Yaesu FT-891, FT-991A, FTDX10, FT-710, FTDX101D / MP, FTX-1 (see
    [Supported radios](../RIG_MODELS.md) for verification status). Generic
    presets for unlisted hardware: **Digirig — CAT + RTS PTT**, **USB CAT
    cable / built-in USB — CAT PTT**, and **Serial PTT only (RTS), no CAT**.
    Only the Yaesu new-CAT protocol is supported in this release.
  - **CAT baud rate** — must match the CAT rate menu setting on the radio.
  - **CAT port** — which serial channel carries CAT on multi-port USB
    bridges (shown only when the attached bridge has more than one port);
    **Automatic (recommended)** uses the preset's default.
  - **PTT method** — serial RTS line or CAT commands (`TX1;`/`TX0;`).
  - **Test CAT** — probes the radio with the current form values and reports
    the result in plain language: in sync, wrong-baud garbage, or silence.
    Test before saving.
- **×** on a card deletes that profile (after confirmation). Deleting
  removes only the saved configuration; the log is not affected.
- **Dial frequency / Mode / Read rig** — shown once CAT is up. **Read rig**
  re-queries; the dial dropdown retunes to preset FT8 frequencies (band list
  follows the radio model's coverage).
- **Set FT8 mode (DATA-U)** — one tap to put the rig in the FT8 data mode
  over CAT.
- **Manual dial frequency** — shown instead for no-CAT profiles: pick the
  frequency you've set on the radio's dial so the display and log entries
  stay correct.
- **USB diagnostics** — expandable dump of attached USB devices, for
  debugging cables and permissions.

The rig controls are locked while transmitting.

## Display

| Setting | Default | Behavior |
|---------|---------|----------|
| **Dark mode** | on | Dark color scheme across the entire app. |
| **Decode colors** | built-in palette | Per-category row colors for the decode list: **My call**, **QSO partner**, **My TX**, **CQ — new**, **CQ — worked (other band)**, **CQ — worked (this band)**. Tap a swatch to change it; **Reset to defaults** restores the palette. |

## Integrations

### QRZ Logbook

| Setting | Default | Behavior |
|---------|---------|----------|
| **Upload QSOs to QRZ** | off | New contacts upload to your QRZ Logbook automatically as they are logged; failed uploads queue and retry quietly. |
| **QRZ API key** | empty | Your QRZ Logbook API key (`XXXX-XXXX-XXXX-XXXX`). Masked by default (eye icon to reveal) and stored encrypted on the device. |

**Test connection** verifies the key against QRZ and reports the result.
When uploads are enabled but QRZ is unreachable (or the key is rejected), a
warning badge appears on the Integrations tab and the section shows how many
QSOs are waiting; they retry automatically when QRZ is reachable again.

## Logbook tools

Logbook backup and import moved to the **Log** tab: the **⋮** (Logbook
tools) menu holds the last-backup time, **Backup now**, and
**Import ADIF…**. See [Logging and ADIF](logging.md).
