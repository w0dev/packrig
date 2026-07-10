# Audio module (`audio/`)

USB audio capture and playback at 12 kHz, plus DSP for rate conversion and the live
waterfall spectrum display.

## Purpose

The Digirig Mobile exposes a CM108 USB audio device. FT8 protocol internals run at
12 kHz mono PCM. This module captures at the device's native rate (typically 48 kHz),
decimates to 12 kHz, and upsamples on playback.

## Architecture

```
UsbAudioCapture                    UsbAudioPlayback
  AudioRecord (48/24/12 kHz)         AudioTrack (48/24/12 kHz)
       │                                    ▲
       ▼                                    │
  FirDecimator                         Upsampler
  (low-pass, ÷4/÷2/÷1)                (linear, ×4/×2/×1)
       │                                    │
       └──► 12 kHz mono PCM ────────────────┘
                    │
                    ▼
            SpectrumProcessor → waterfall columns (0–3000 Hz)
```

## Key types

### `AudioEngine` (interface)

Contract for mono 16-bit PCM capture at `AppInfo.SAMPLE_RATE_HZ`. Transport-agnostic
so the FT8 engine does not care whether audio comes from USB, internal mic, or a test
WAV.

### `UsbAudioCapture`

- Opens `AudioRecord` at the highest supported integer multiple of 12 kHz (48k → ÷4,
  24k → ÷2, 12k → passthrough)
- Disables AGC, noise suppression, and echo cancellation (nonlinear processing
  destroys FT8 fidelity)
- Runs capture on a dedicated thread; delivers decimated frames via callback
- Requires `RECORD_AUDIO` permission (checked by caller before `start()`)

### `CaptureLifecycle`

Serializes `AudioEngine.start()`/`stop()` onto a dedicated single thread so the
`AudioRecord` framework calls — which can block for seconds on a wedged or
detaching USB device — never run on the main thread (field ANR, 2026-07-03).
Ops execute strictly in submission order, preserving stop-then-start semantics
for device swap, capture restart, and TX pause/resume. `stop(onStopped)` runs
its callback after the engine has actually stopped; a failing `start` reports
via callback only while it is still the newest op.

The TX handoff is the one caller that must not fire-and-forget:
`TxCaptureControl.pauseForTx` (app module) suspends on the `stop(onStopped)`
callback — bounded at 1.5 s — so PTT never keys while the capture engine still
holds the USB codec. An async stop racing TX playback produced intermittent
zero-RF transmissions in the field (2026-07-03).

### `UsbAudioPlayback`

- Opens `AudioTrack` at 48/24/12 kHz
- `playBlocking(samples12k, preferredDeviceId)` upsamples and plays on the calling thread
  (used during TX so PTT timing stays aligned). Returns `false` if interrupted by [stop].
- `stop()` halts in-progress playback and releases the active track (used by **Halt TX**).

### `AudioInputs` / `AudioOutputs`

Lists available input/output devices; helpers to find the first USB device (Digirig).

### DSP (`audio/dsp/`)

| Class | Role |
|-------|------|
| `FirDecimator` | Low-pass FIR + decimation from 48/24 kHz → 12 kHz |
| `Upsampler` | Linear interpolation for 12 kHz → 48/24 kHz playback |
| `Fft` | In-place radix-2 FFT (power-of-two sizes) |
| `SpectrumProcessor` | Hann window, 50% overlap, magnitude dB columns for 0–3000 Hz |

The waterfall in `app` consumes `SpectrumProcessor` output via `Waterfall.addColumn()`.

## Dependencies

- `core` — `AppInfo.SAMPLE_RATE_HZ`
- Android `AudioRecord` / `AudioTrack` APIs

## Tests

See [TESTING.md](TESTING.md#audio--3-test-classes-7-tests).

```powershell
.\gradlew.bat :audio:testDebugUnitTest
```

DSP tests use synthetic tones and deterministic FFT inputs — no audio hardware
required.

## Operational notes

- Select the Digirig USB input in the Settings → Audio device dropdown
- Level metering and clip detection run as frames arrive (`DecodeController.onFrames`, driven by `OperateViewModel`)
- Capture pauses during TX (half-duplex); resumes after transmission completes
- USB output for TX is selected via `AudioOutputs.firstUsb()`

## Related docs

- [HARDWARE.md](HARDWARE.md) — Digirig audio wiring
- [APP.md](APP.md) — `OperateViewModel` capture/TX loop
- [FT8_NATIVE.md](FT8_NATIVE.md) — decode/encode on 12 kHz PCM
