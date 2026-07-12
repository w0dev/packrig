# Getting started

## What you need

- An amateur radio license valid for your jurisdiction and band/mode. FT8VC
  gates transmit behind a one-time license acknowledgment; receive works
  without one.
- An Android phone with USB-C **OTG** (USB host) support and enough OTG power
  for the interface — a powered OTG hub may be required.
- A USB audio + serial path to the rig. Two common shapes:
  - A **Digirig-class interface** (CM108-class USB audio plus a CP2102
    USB-serial adapter) cabled to the rig's data and CAT ports — the FT-891
    reference setup works this way.
  - The **rig's built-in USB port**, where the radio itself exposes a USB
    audio codec and serial CAT port — the FTX-1 reference setup, and most
    current Yaesu models.
- Android 9 (API 28) or newer.
- A rig. FT8VC's verified reference setups are the Yaesu FT-891 + Digirig
  Mobile and the Yaesu FTX-1 over built-in USB — see
  [Hardware setup](../HARDWARE.md) for FT-891 wiring and menu values, and
  [Supported radios](../RIG_MODELS.md) for the full model table. Unlisted
  radios work through generic presets (see [Add your rig](#radio-add-your-rig)
  below).

## Install

FT8VC ships two release channels that install side by side on the same
device: stable (`net.ft8vc`) and unstable (`net.ft8vc.unstable`). Unstable is
the day-to-day development channel and gets new builds first; stable lags
until a milestone is verified end-to-end. Installing one does not remove or
conflict with the other — use unstable for field testing and stable for
everyday operating.

Installing a new APK for a channel you already have upgrades in place and
keeps your settings and log. See [Release channels](../RELEASE.md) for how
builds are produced and signed.

## Connect the hardware

With a Digirig, the signal chain is phone → USB-C OTG cable → Digirig → rig:
the Digirig's audio jack carries RX/TX audio, and its serial jack carries CAT
control and RTS PTT to the radio. With a built-in-USB rig, the phone plugs
straight into the radio and both audio and serial arrive over the one cable.
Plug the interface in before or after opening the app — USB permission is
requested per session, not tied to launch order. For the exact cable, jack,
and FT-891 menu settings, follow [Hardware setup](../HARDWARE.md).

## Grant USB permissions

Android needs two things before FT8VC can talk to the interface: the
`RECORD_AUDIO` permission (for the USB audio input) and USB device permission
for the serial port (for CAT/PTT). The audio permission prompt appears the
first time capture starts; the USB serial permission prompt appears when
FT8VC finds a device matching your rig profile and asks Android to grant
access to it. Note that this USB prompt covers only the serial port: the
audio side is a standard USB audio device that Android's audio framework
handles on its own, with no per-app USB permission needed.

If you decline USB permission, or no matching device is present, FT8VC falls
back to a no-op rig backend: PTT and CAT become inert (keying and frequency
reads do nothing), and the **Radio** section in Settings reports CAT as
unavailable. Audio capture is independent of this — RX still works over USB
audio even without CAT/PTT permission. Re-granting permission (or
reconnecting the interface) lets FT8VC bind to it without restarting the app.

## Configure, in order

Open the **Settings** tab and work through these sections before your first
session. The [Settings reference](settings.md) documents every control; this
is the minimum path.

### Station

Enter **My call** and **Grid** in the **Station** section. A grid locator can
be 4 or 6 characters. If you operate from a POTA park, turn on **POTA mode**
in the **POTA** section and enter a **Park reference**; comma-separate
multiple references for a two-fer.

### Radio: add your rig

In the **Radio** section, tap **Add rig**. The editor asks for:

- **Radio model** — pick your radio if it's listed (FT-891, FT-991A, FTDX10,
  FT-710, FTDX101D/MP, FTX-1), or one of three generic presets for anything
  else: **Digirig — CAT + RTS PTT**, **USB CAT cable / built-in USB — CAT
  PTT**, or **Serial PTT only (RTS), no CAT**. Presets prefill the CAT baud,
  serial port, and PTT method; you can override any of them.
- **CAT baud rate** — must match the CAT rate menu setting on the radio
  itself (the FT-891 reference setup uses 38400).
- **CAT port** and **PTT method** — leave on the preset values unless you
  know your wiring differs.

Tap **Test CAT** before saving: it probes the radio and reports, in plain
language, whether CAT is in sync, answering with wrong-baud garbage, or
silent. Fix baud/port until it syncs, then **Save**. You can store up to five
rigs and switch between them with the **My rig** dropdown.

Once CAT is up, the section shows the dial frequency and mode. **Read rig**
re-queries the radio; if the mode isn't FT8's data mode, tap **Set FT8 mode
(DATA-U)** to switch it over CAT.

If you chose the no-CAT preset, you instead pick your **dial frequency**
manually here (and keep the radio's dial on it) so the display and your log
entries stay correct.

### Audio

The **Audio input** picker lists whatever input devices Android reports.
When a USB audio interface is attached, FT8VC selects it automatically — you
normally only need to confirm it's the one shown, not pick it yourself. The
picker matters when more than one input is available (say, a headset
alongside the Digirig), or for rig-free testing: select the phone's built-in
microphone to decode FT8 audio played from a nearby speaker.

The picker reads `Automatic (system default)` when no USB interface is
attached, `Automatic — <name> (<type>)` when routing picked one for you, or
the device name alone once you've picked one manually. Selecting `Automatic
(system default)` from the dropdown returns to automatic routing.

The picker is disabled while audio capture or transmit is running; stop
operating before switching inputs. Once audio is running, tap the volume icon
on the Operate tab's status bar to open an input-gain slider; watch the
adjacent level meter to confirm levels are healthy.

### Enable transmit

FT8VC is receive-only until you turn it on. Flip **Enable transmit** in the
**TX** section to allow the app to key the radio. The first time you actually
try to transmit — starting a CQ, or tapping a decode row to answer or resume a
QSO on the Operate tab — FT8VC shows a one-time "Confirm before transmitting"
dialog reminding you that transmitting requires a valid license and that you
are responsible for lawful operation; tap **I understand** to acknowledge and
proceed, or **Cancel** to back out. This acknowledgment persists after the
first time.

## Your first receive session

Go to the **Operate** tab and tap **Start decoding**. FT8VC opens the USB
audio input and the level meter next to the volume icon starts moving with
band noise — that's your first sign audio is flowing. A progress bar and UTC
clock track your position in the current 15-second slot.

Decodes appear as soon as a 15-second slot's window closes and the decode
pass finishes — you'll see your first batch within one slot of starting. If
nothing decodes after a couple of slots, check the level meter isn't pinned at
zero or clipping, and confirm the rig is tuned to an active FT8 sub-band. The
[Troubleshooting](troubleshooting.md) chapter walks the no-decodes case
step by step.

From here, the [Operating](operating.md) chapter covers the decode list,
calling and answering, and QSO automation.
