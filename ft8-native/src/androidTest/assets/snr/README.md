# SNR fixtures (WSJT-X ground truth)

These WAVs + their `.expected.txt` are WSJT-X-decoded sample slots used to keep
the `SnrEstimator` honest. They drive two tests:

- **`core`/`ft8-native` host test** `SnrEstimatorWavTest` (JVM, no device) — feeds
  WSJT-X's published frequencies into the estimator and asserts it stays
  directionally correct (positive slope, strong→positive, weak→mostly-negative).
- **Instrumented** `Ft8SnrCalibrationTest` (on device) — decodes the WAV through
  ft8_lib end-to-end and checks the recomputed SNR's median error vs WSJT-X.

The estimator is POTACAT's method (signal = mean of the 8 tone-bin powers, noise =
median band floor, ref 2500 Hz via `SnrEstimator.CALIBRATION_DB`). It is
directionally correct, **not** WSJT-X-exact — overlapping signals within ~50 Hz
can read high. See the audit report for the full analysis.

## Adding a fixture

1. Drop a 15-second, **12 kHz mono** FT8 slot WAV here (WSJT-X ships samples under
   its `samples/FT8/` directory, or capture your own).
2. Open it in WSJT-X and read the Band Activity table.
3. Write a sibling `<name>.expected.txt`, one line per decode:

   ```
   <freqHz> <wsjtxSnrDb> <message>
   ```

   using WSJT-X's `Freq` (audio Hz) and `dB` columns. Example:

   ```
   1503 -15 CQ K1ABC FN42
   ```

## Re-tuning `CALIBRATION_DB`

`SnrEstimator.CALIBRATION_DB` (currently −20.3) is the mean-centered offset over
the committed sample(s). With more fixtures, recompute it as
`mean(rawEstimate − wsjtxSnr)` across all matched decodes (the raw estimate is
`10·log10(signal/noise)` before the offset) and update the constant.
