# Decoder test clips

Place golden FT8 capture files here to enable the decode regression test in
`Ft8DecodeInstrumentedTest`:

- `ft8_test.wav` — a 12 kHz mono 16-bit PCM WAV of a real FT8 slot (~15 seconds).
  Any FT8 capture tool that records at 12 kHz mono will work.
- `ft8_test.expected.txt` (optional) — one expected message substring per line,
  e.g. a callsign or `CQ`. Each line must appear in some decode.

The test loads the WAV, runs `Ft8Native.decode`, and asserts at least one message
decodes (plus any expected substrings). If `ft8_test.wav` is absent the test
self-skips, so CI stays green until you add real data.
