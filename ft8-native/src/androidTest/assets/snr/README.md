# SNR calibration fixtures

`Ft8SnrCalibrationTest` reads every `*.wav` in this folder that has a sibling
`*.expected.txt`. Without fixtures the test self-skips, so CI stays green.

## How to add a fixture

1. Drop a 15-second, **12 kHz mono** FT8 slot WAV here, e.g. `band20m_01.wav`.
   WSJT-X ships sample WAVs under its `samples/FT8/` directory, or capture your
   own off-air.
2. Open the same WAV in **WSJT-X** (File → Open) and read its decode table.
3. Write `band20m_01.expected.txt` with one line per decode you want to assert,
   in the format:

   ```
   <freqHz> <wsjtxSnrDb> <message>
   ```

   Use WSJT-X's `Freq` (audio Hz) and `dB` columns. Example:

   ```
   1503 -15 CQ K1ABC FN42
   1620 -8 W9XYZ K1ABC R-12
   ```

## Calibrating DEFAULT_OFFSET_DB

1. In `Ft8SnrCalibrationTest`, temporarily set `TOL_DB = 100`.
2. Run on a device/emulator:
   `./gradlew :ft8-native:connectedDebugAndroidTest --tests "net.ft8vc.ft8native.Ft8SnrCalibrationTest"`
3. In logcat, read the `FIT spread=… wsjtx=… ours=…` lines.
4. Set `SnrEstimator.DEFAULT_OFFSET_DB = mean(spread − wsjtx)` across all lines.
5. Restore `TOL_DB = 3` and re-run — every matched decode must be within 3 dB.
