# User Manual — Design

**Date:** 2026-07-02
**Status:** Approved
**Deliverable:** `docs/manual/` — end-to-end user-facing documentation of FT8VC
usage and behavior.

## Goal

Give a licensed amateur operator everything needed to install, configure, and
operate FT8VC end-to-end, and to look up exactly how any visible behavior or
setting works. Today that knowledge lives in the README feature tour, scattered
module docs, and the code itself; there is no operator's manual.

## Decisions (settled during brainstorming)

1. **Format:** a `docs/manual/` tree of focused Markdown pages in the repo,
   versioned with the code, linked from the root README. No wiki, no site
   generator, no new tooling.
2. **Audience:** knows FT8 (WSJT-X-class experience), new to FT8VC. The manual
   never teaches the protocol — only this app's behavior.
3. **Overlap policy:** the manual is canonical for usage/behavior. The root
   README keeps its short quick start and links to the manual for depth.
   `docs/HARDWARE.md` stays as-is (reference-rig specific); the manual links to
   it. No existing content is deleted or moved.
4. **Screenshots:** none for now. Text must stand alone. A screenshot pass is
   deferred until the UI settles at a stable promotion.
5. **Organization:** hybrid — a workflow spine (`getting-started`, `operating`)
   plus flat reference pages (`settings`, `logging`, `troubleshooting`).

## Pages

```
docs/manual/
  README.md            index, reading order, scope note
  getting-started.md   install → hardware → permissions → settings → first RX
  operating.md         Operate + Spectrum tabs, QSO automation, end to end
  logging.md           auto-log rules, Log tab, ADIF export, POTA fields
  settings.md          reference for every setting: default, behavior, interactions
  troubleshooting.md   symptom-first diagnosis
```

### README.md (index)

- What the manual covers, page-by-page reading order for a first session.
- Scope note: documents current `unstable` behavior; the stable channel may lag.
  This is the only place version scope is stated (no per-page stamps).
- Links out: `docs/HARDWARE.md` (FT-891 + Digirig wiring/menus),
  `docs/RELEASE.md` (channels, signing).

### getting-started.md

- Prerequisites: amateur license, USB-C OTG cable/hub, Digirig-class interface,
  supported Android version.
- Install: stable vs unstable channels, side-by-side install, upgrade-in-place.
- Hardware hookup summary; rig menu specifics delegated to `docs/HARDWARE.md`.
- Android USB permission flow (audio + serial devices, what the prompts look
  like, what happens on decline).
- Settings walkthrough in first-run order: Station (call, grid, POTA toggle +
  park ref) → Audio (pick USB input) → Rig (CAT check, one-tap DATA-U) →
  license acknowledgment and TX enable. Emphasize receive-only default.
- First RX session: Operate → Start; what "working" looks like (decodes within
  one 15 s slot, level meter, slot progress).

### operating.md

The core page. Sections:

- **Status bar** — every chip defined: dial MHz (tap-to-retune), mode, TX tone,
  POTA, Halt TX, slot progress, UTC countdown, Even/Odd TX slot, TX Ns
  countdown, clock-offset chip (states, thresholds, what to do when red).
- **Decode list** — row anatomy (time/SNR/distance/freq/message), color rules
  (CQ, directed-to-you, QSO partner, dimming, worked-before shading), the
  self-TX row.
- **View modes** — Focus vs Band exact filter rules; CQ·73 chip.
- **Making contacts** — tap-a-CQ to answer; Start CQ and slot waiting; the
  auto-seq walkthrough: what the app transmits at each QSO stage, RRR vs RR73
  behavior, Send RR73 (log on send).
- **Automation** — Answer when called vs Auto answer CQ (independent toggles);
  answer policy (First / Best SNR / Furthest); abandon after N TX cycles and
  the session blocklist (what it blocks, how to clear); auto-resume CQ.
- **Manual control** — Halt TX, Stop QSO / Abandon, resume semantics.
- **Spectrum tab** — waterfall interactions, TX tone set/persist, passband
  range, dial/band picker over CAT.
- **Early decode** — what the toggle does (preview pass mid-slot), and that
  early rows are indistinguishable from full-pass rows.
- **SNR note** — reported SNR is an estimate (typically within a few dB of
  WSJT-X); the report you send comes from it.

### logging.md

- What triggers an auto-log entry (QSO completion events), what fields are
  recorded.
- Log tab usage; QSO state lost on app exit is by design (cross-ref
  troubleshooting).
- ADIF 3.1.4 export via share intent; POTA `MY_SIG`/`MY_SIG_INFO` fields;
  fail-closed validation (export refuses when POTA is on without a valid park
  reference).

### settings.md

- Mirrors the Settings screen's group structure (Station, Audio, Rig,
  Operating, Display).
- Every setting gets three facts: **default**, **exact behavior**, and
  **interactions** (e.g., POTA on + missing ref → ADIF export fails closed;
  answer policy only applies when an auto mode is on).

### troubleshooting.md

Symptom-first entries, each: symptom → ordered checks → explanation.

- No USB device / permission prompt loop.
- No decodes (checklist: input device, level meter, clock-offset chip, dial
  frequency, DATA-U mode, antenna).
- CAT timeout / stale frequency.
- TX won't key (license ack, TX enable, PTT method, Halt TX state).
- Calling with no answers (TX tone placement, slot choice).
- By-design notes: QSO state lost on app exit; recovery via answer/resume.

## Voice and accuracy rules

- Second person, imperative, no marketing language. Bold for UI labels
  (**Start CQ**), one H1 per page, relative links, target ≤200 lines per page.
- **Every behavioral claim is verified against source before it is written**:
  defaults from `SettingsRepository`, sequencing from `QsoMachine`, filter rules
  from the decode view-mode code, chip behavior from the status bar
  composables. Nothing is documented from memory or from the README.
- Where the app is approximate, say so plainly (SNR estimate).

## Integration changes

- Root `README.md`: add a short **Documentation** section linking
  `docs/manual/README.md`.
- `docs/README.md`: add the manual to the docs index.
- No deletions, no moved files, no code changes.

## Verification

- All relative links resolve (check by script or by hand).
- Spot-check each page's behavioral claims against the cited source files.
- Read-through with the app running on the emulator for every path reachable
  without a rig attached.

## Out of scope

- Screenshots (deferred to a stable promotion).
- FT8 protocol tutorial content.
- Translations.
- In-app help surface.
