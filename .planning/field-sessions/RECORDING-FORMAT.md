# Field-Session Recording Format — JSONL Schema v1

Defines the on-disk format consumed by the golden-trace harness at `app/src/test/java/net/ft8vc/app/foundations/golden/GoldenTrace.kt`. Phase 0 Plan 04 introduces this schema; Phase 1+ may extend it (with a version bump and coordinated parser change in the same commit).

## Format

- Line-delimited JSON ("JSONL"). One event per line.
- Each line is a flat JSON object. The only nested structure permitted is the optional `decodes` array (only on `DECODE_BATCH` events).
- The `payload` object holds free-form **string→string** entries — values are quoted strings, no nested objects, no arrays.
- Comments allowed on lines starting with `//` (whitespace-tolerant). Blank lines also allowed.
- Trailing newline on the final event is optional.

## Required Fields per Event

| Field | Type | Required | Description |
|---|---|---|---|
| `ts_ms` | integer | yes | Monotonic milliseconds from trace start (event 0 has `ts_ms: 0`). |
| `kind` | string | yes | One of: `TRACE_START`, `DECODE_BATCH`, `UI_ACTION`, `CAT_READ`, `PTT_KEY_EXPECTED`, `PTT_RELEASE_EXPECTED`, `TX_SLOT`, `TRACE_END`. |
| `payload` | object | optional | Flat string→string map. Use for action names, dx calls, dial frequency labels, etc. |
| `decodes` | array | only on `DECODE_BATCH` | Array of decode objects (shape below). |

## Decode-Object Shape

Each entry in the `decodes` array is a flat object with the following fields. Field names mirror the `Ft8DecodeResult` Kotlin data class (`ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecodeResult.kt`) so the parser can map directly without translation:

| Field | Type | Description |
|---|---|---|
| `message` | string | Decoded FT8 message text (e.g., `"CQ K1ABC FN42"`). |
| `snr` | integer | Approximate SNR in dB (signed). |
| `dt` | number | Time offset of the signal within the slot (seconds, may be fractional). |
| `freq_hz` | number | Audio frequency of the signal in Hz (may be fractional). |
| `score` | integer | Raw Costas sync score (decoder confidence; higher is better). |

## Example Trace

```jsonl
// Tiny three-event trace covering one decoded CQ.
{"ts_ms": 0, "kind": "TRACE_START", "payload": {"my_call": "W0DEV", "my_grid": "EM26"}}
{"ts_ms": 15000, "kind": "DECODE_BATCH", "payload": {"slot_start_ms": "15000"}, "decodes": [{"message": "CQ K1ABC FN42", "snr": -8, "dt": 0.5, "freq_hz": 1500.0, "score": 120}]}
{"ts_ms": 15001, "kind": "TRACE_END", "payload": {}}
```

The canonical synthetic trace at `app/src/test/resources/traces/cq-answer-73.jsonl` is the reference example that exercises a full QSO cycle end-to-end through the harness.

## Event-Kind Semantics

| Kind | When emitted | Effect during replay |
|---|---|---|
| `TRACE_START` | First event in every trace | Sets operator identity in payload (`my_call`, `my_grid`); informational only. |
| `DECODE_BATCH` | Once per completed RX slot | Replay feeds the `decodes[]` into the in-test `QsoMachine.onDecodes(...)` and also queues the equivalent `Ft8DecodeResult` list into `Ft8DecoderFake`. |
| `UI_ACTION` | Operator interaction in the recording | Replay maps `payload.action` to a `QsoMachine` method (`answer_cq` → `answerCq(dxCall, dxGrid, snr)`, `start_cq` → `startCq()`). |
| `CAT_READ` | A CAT read returned a value | Optional — replay does not currently assert on CAT reads; included for forward compatibility. |
| `PTT_KEY_EXPECTED` | Recorded PTT key edge | Informational at this version; future versions may compare against `FakeRigBackend.pttEdgesSnapshot()`. |
| `PTT_RELEASE_EXPECTED` | Recorded PTT release edge | Same as `PTT_KEY_EXPECTED`. |
| `TX_SLOT` | One TX slot boundary | Replay drives `rig.keyPtt()` → `machine.markTransmitted()` → `rig.releasePtt()` if `machine.txMessage()` is non-null, producing observable PTT edges in `FakeRigBackend`. |
| `TRACE_END` | Last event in every trace | Stops replay iteration. |

## Capture Procedure (Manual, Phase 0)

1. **Build and install the unstable APK on the reference device:**
   ```
   ./gradlew :app:installDebug
   ```
   Debug variant is acceptable for capture — what matters is that the build is signed with the same key the field session will be replayed against, and that logging is verbose enough to extract events.

2. **Connect the reference rig** — FT-891 + Digirig over USB-OTG. Confirm Digirig is recognized (Settings → Audio device → Digirig appears) and CAT works (Operate tab shows dial frequency within 2 s of opening).

3. **Run a 5-minute session that exercises:**
   - Cold boot (kill app, relaunch) → CAT read returns within 2 s.
   - At least 5 RX decodes across 3 consecutive RX slots.
   - One CQ → answer → 73 QSO cycle on dummy load (use auto-seq on).
   - One PTT cycle on CAT mode (Settings → PTT preference → CAT).
   - One PTT cycle on RTS mode (Settings → PTT preference → RTS).

4. **Capture the session via logcat:**
   ```
   adb logcat -d -s OperateViewModel:I UsbAudioCapture:I DigirigRigBackend:I Ft891Cat:I QsoMachine:I > raw-logcat.txt
   ```

5. **Convert raw logcat → JSONL** via the Converter Script below. Phase 1+ will wire a debug-build env-var (`FT8VC_FIELD_TRACE=1`) so the app emits JSONL directly; for Phase 0 this conversion is a one-shot scripted (or hand-authored) step.

6. **Commit the baseline** under `.planning/field-sessions/baseline-<YYYY-MM-DD>/` with `trace.jsonl` + `README.md` (operator notes per `README.md` schema in this directory).

## Converter Script

A minimal `awk` recipe for the Phase 0 manual conversion. This is intentionally rudimentary — Phase 1+ replaces it with an in-app emitter. Adapt as needed; the only hard constraint is that the resulting `trace.jsonl` parses via `GoldenTrace.loadJsonl` and the replay reaches `QsoState.Complete`.

```bash
#!/usr/bin/env bash
# raw-logcat-to-jsonl.sh — Phase 0 one-shot converter.
# Usage: ./raw-logcat-to-jsonl.sh raw-logcat.txt > trace.jsonl

awk '
BEGIN { ts0 = 0; trace_started = 0 }
# Match decode lines from OperateViewModel like:
#   06-22 14:00:15.123  1234 5678 I OperateViewModel: decode @15000: "CQ K1ABC FN42" snr=-8 dt=0.5 freq=1500.0 score=120
/decode @[0-9]+:/ {
  if (trace_started == 0) {
    print "{\"ts_ms\": 0, \"kind\": \"TRACE_START\", \"payload\": {\"my_call\": \"REPLACE_ME\", \"my_grid\": \"REPLACE_ME\"}}"
    trace_started = 1
  }
  # Extract ts_ms from "@<int>:" group; extract decode fields from message tail.
  match($0, /@([0-9]+):/, ts);  ts_ms = ts[1] + 0
  match($0, /"([^"]+)"/, msg);  message = msg[1]
  match($0, /snr=(-?[0-9]+)/, s);  snr = s[1]
  match($0, /dt=([0-9.]+)/, d);  dt = d[1]
  match($0, /freq=([0-9.]+)/, f);  freq = f[1]
  match($0, /score=([0-9]+)/, sc);  score = sc[1]
  printf "{\"ts_ms\": %d, \"kind\": \"DECODE_BATCH\", \"payload\": {\"slot_start_ms\": \"%d\"}, \"decodes\": [{\"message\": \"%s\", \"snr\": %d, \"dt\": %s, \"freq_hz\": %s, \"score\": %d}]}\n", ts_ms, ts_ms, message, snr, dt, freq, score
}
# Match TX slot markers, PTT edges, etc. (left as exercise — Phase 0 operator can hand-author these from logcat).
END {
  if (trace_started == 1) {
    print "{\"ts_ms\": 999999, \"kind\": \"TRACE_END\", \"payload\": {}}"
  }
}
' "$1"
```

Hand-authoring the JSONL based on logcat is fully acceptable for Phase 0 if scripting is impractical. The only hard requirement: the resulting JSONL parses through `GoldenTrace.loadJsonl` and the harness replay reaches `QsoState.Complete` (which is the assertion the `cq-answer-73` smoke test enforces against the synthetic trace).

## Schema Version Stability

This is **schema v1**. Any field rename, enum value, or new required field is a v2 bump. The bump MUST land in the same commit as the corresponding `GoldenTrace.kt` parser update so existing recordings remain replayable or are explicitly migrated. The current GoldenTrace parser does not stamp a version into the file; v2+ will introduce a `// schema: v2` directive at the head of each trace file to make the contract explicit.
