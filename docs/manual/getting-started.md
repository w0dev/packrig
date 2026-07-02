# Getting started

## What you need

- An amateur radio license valid for your jurisdiction and band/mode. FT8VC
  gates transmit behind a one-time license acknowledgment; receive works
  without one.
- An Android phone with USB-C **OTG** (USB host) support and enough OTG power
  for the interface — a powered OTG hub may be required.
- A Digirig-class USB audio + serial interface (CM108-class USB audio plus a
  CP2102 USB-serial adapter). This is how the phone gets RX/TX audio and PTT/CAT
  to the rig.
- Android 9 (API 28) or newer.
- A rig. FT8VC's verified reference setup is the Yaesu FT-891 + Digirig Mobile
  — see [Hardware setup](../HARDWARE.md) for wiring and FT-891 menu values.

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

The signal chain is phone → USB-C OTG cable → Digirig → rig: the Digirig's
audio jack carries RX/TX audio (and hardware PTT), and its serial jack carries
CAT control and RTS PTT to the radio. Plug the interface in before or after
opening the app — USB permission is requested per session, not tied to launch
order. For the exact cable, jack, and FT-891 menu settings, follow
[Hardware setup](../HARDWARE.md).

## Grant USB permissions

Android needs two things before FT8VC can talk to the interface: the
`RECORD_AUDIO` permission (for the USB audio input) and USB device permission
for the Digirig's serial port (for CAT/PTT). The audio permission prompt
appears the first time you start operating; the USB serial permission prompt
appears when FT8VC finds a matching device and asks Android to grant access to
it.

If you decline USB permission, or no matching device is present, FT8VC falls
back to a no-op rig backend: PTT and CAT become inert (keying and frequency
reads do nothing), and the **Rig (FT-891 CAT)** section in Settings reports
CAT as unavailable. Audio capture is independent of this — RX still works over
USB audio even without CAT/PTT permission. Re-granting permission (or
reconnecting the interface) lets FT8VC bind to it without restarting the app.

## Configure, in order

Open the **Settings** tab and work through these sections before your first
session.

### Station

Enter **My call** and **Grid** in the **Station** section. A grid locator can
be 4 or 6 characters. If you operate from a POTA park, turn on **POTA mode**
under **Activation (POTA)** and enter a **Park reference**; comma-separate
multiple references for a two-fer.

### Audio

In the **Audio** section, use the **Audio input** picker to select your USB
interface. FT8VC lists whatever input devices Android reports; pick the
Digirig's USB audio device. Fine-tune the RX input level from the level meter
on the Operate tab once you're capturing.

### Rig

In the **Rig (FT-891 CAT)** section, once CAT is available, use **Read rig**
to confirm FT8VC can query the radio's frequency and mode. If the mode isn't
already FT8's data mode, tap **Set DATA-U (FT8 mode)** to switch the rig over
CAT. If CAT isn't available yet, this section shows "CAT unavailable — connect
Digirig serial and grant USB permission" instead of the frequency/mode
controls.

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

Go to the **Operate** tab and tap **Start**. FT8VC opens the USB audio input,
and the level meter next to the volume icon starts moving with band noise —
that's your first sign audio is flowing. A progress bar and UTC clock track
your position in the current 15-second slot.

Decodes appear as soon as a 15-second slot's window closes and the decode
pass finishes — you'll see your first batch within one slot of starting. If
nothing decodes after a couple of slots, check the level meter isn't pinned at
zero or clipping, and confirm the rig is tuned to an active FT8 sub-band.

From here, see [Operating](operating.md) for the decode list, calling and
answering, and QSO automation.
