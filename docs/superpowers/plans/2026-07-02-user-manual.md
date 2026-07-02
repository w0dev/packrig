# User Manual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `docs/manual/` — six Markdown pages of end-to-end user-facing documentation of FT8VC usage and behavior — per `docs/superpowers/specs/2026-07-02-user-manual-design.md`.

**Architecture:** A workflow spine (`getting-started.md`, `operating.md`) plus flat reference pages (`logging.md`, `settings.md`, `troubleshooting.md`) under an index (`README.md`). Every behavioral claim is extracted from source files named in each task, never from memory or the README. Root README and docs index gain links; nothing existing moves.

**Tech Stack:** Markdown only. No build tooling, no screenshots, no code changes.

## Global Constraints

- Audience: licensed ham who knows FT8 (WSJT-X-class); never explain the protocol, only this app.
- Voice: second person, imperative, no marketing language.
- **Bold** for UI labels exactly as rendered (**Start CQ**, **Settings → Station**).
- One `#` H1 per page; relative links between pages; target ≤200 lines per page.
- Version scope note appears ONLY in `docs/manual/README.md` ("documents current `unstable` behavior").
- Every behavioral claim verified against the source file/symbol listed in the task's Source map BEFORE writing the prose. If source contradicts this plan's prose or the root README, **source wins** — write what the code does and note the discrepancy in the commit message body.
- No code changes anywhere in this plan. Only files under `docs/` and the root `README.md` are touched.
- Each page committed separately with `docs(manual): …` messages.
- Link check after every task (command given in each task; all relative links must resolve).

---

### Task 1: Manual skeleton and link integration

**Files:**
- Create: `docs/manual/README.md`
- Modify: `README.md` (root — add Documentation section after the `## Install` section)
- Modify: `docs/README.md` (add manual entry to the index)

**Interfaces:**
- Produces: `docs/manual/README.md` linking five pages that do not exist yet (`getting-started.md`, `operating.md`, `logging.md`, `settings.md`, `troubleshooting.md`). Later tasks create them with exactly these filenames. The link-check step for THIS task alone tolerates those five as known-pending; every later task requires all links to resolve.

- [ ] **Step 1: Write `docs/manual/README.md`**

```markdown
# FT8VC Operator's Manual

How FT8VC behaves and how to use it, end to end. This manual assumes you are a
licensed amateur operator who has used FT8 before (WSJT-X or similar) and is
new to FT8VC. It documents the current **`unstable`** channel behavior; the
stable channel may lag behind what is described here.

## Reading order for a first session

1. [Getting started](getting-started.md) — install, hook up the rig, configure,
   and see your first decodes.
2. [Operating](operating.md) — the Operate and Spectrum tabs: decode list,
   status bar, calling, answering, and QSO automation.
3. [Logging and ADIF export](logging.md) — what gets logged and how to get it
   out.

## Reference

- [Settings reference](settings.md) — every setting: default, behavior,
  interactions.
- [Troubleshooting](troubleshooting.md) — symptom-first fixes.

## Related documentation

- [Hardware setup](../HARDWARE.md) — Yaesu FT-891 + Digirig wiring and menu
  values for the reference setup.
- [Release channels](../RELEASE.md) — stable vs unstable, signing, install
  channels.
```

- [ ] **Step 2: Add Documentation section to root `README.md`**

Insert after the `## Install` section (before `## Quick start`):

```markdown
## Documentation

The [Operator's Manual](docs/manual/README.md) covers usage and behavior end to
end — getting started, operating, QSO automation, logging, a full settings
reference, and troubleshooting. The quick start below is the condensed version.
```

- [ ] **Step 3: Add manual entry to `docs/README.md`**

Read `docs/README.md` first; add this line at the TOP of its doc list (exact
placement: first entry, since it is the only user-facing doc):

```markdown
- [manual/](manual/README.md) — **Operator's Manual**: end-to-end usage and
  behavior for operators (not developers).
```

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: exactly five `BROKEN` lines, all from `docs/manual/README.md`,
pointing at `getting-started.md`, `operating.md`, `logging.md`, `settings.md`,
`troubleshooting.md` (created by later tasks). Any other output is a failure.

- [ ] **Step 5: Commit**

```bash
git add docs/manual/README.md README.md docs/README.md
git commit -m "docs(manual): index page and README/docs links"
```

---

### Task 2: getting-started.md

**Files:**
- Create: `docs/manual/getting-started.md`

**Source map (read these BEFORE writing; every claim traces to one of them):**

| Fact to extract | Source |
|---|---|
| minSdk / Android version floor | `app/build.gradle.kts` (`minSdk`), `gradle/libs.versions.toml` (`minSdk = "28"` → Android 9) |
| Stable vs unstable package ids, side-by-side install | `app/build.gradle.kts` (`applicationId`, `applicationIdSuffix`), `docs/RELEASE.md` |
| USB permission flow, device discovery, fallback when denied | `rig/src/main/java/net/ft8vc/rig/RigController.kt`, `app/src/main/AndroidManifest.xml` (USB intent filters) |
| Station settings fields and validation (call, grid 4/6-char, POTA toggle + park ref) | `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`, `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt` |
| Audio input selection | `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (Audio section), `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt` |
| CAT check + one-tap DATA-U | `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (Rig section), `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt` |
| Receive-only default; license ack gates TX | `SettingsRepository.kt` (`LICENSE_ACK ?: false`, `TX_ENABLED ?: false`), TX gate usage in `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` |
| First decodes within one 15 s slot | `core/src/main/java/net/ft8vc/core/SlotCollector.kt` (slot boundary flush) |

- [ ] **Step 1: Read the Source map files and note the facts**

For each row, open the file and record the actual behavior (defaults, prompts,
button labels). UI labels in the page must match `SettingsScreen.kt` string
literals exactly.

- [ ] **Step 2: Write the page with this skeleton**

```markdown
# Getting started

## What you need
   (license; phone with USB-C OTG; Digirig-class interface; Android version
    floor from minSdk; rig — link ../HARDWARE.md for the FT-891 reference)
## Install
   (stable vs unstable channels; side-by-side; upgrade-in-place; link
    ../RELEASE.md)
## Connect the hardware
   (summary chain phone→OTG→Digirig→rig; delegate menus to ../HARDWARE.md)
## Grant USB permissions
   (what Android prompts for — audio + serial; what happens on decline —
    fallback behavior from RigController)
## Configure, in order
### Station   (call, grid, optional POTA)
### Audio     (pick the USB input)
### Rig       (CAT check, Set DATA-U)
### Enable transmit   (license acknowledgment; receive-only default)
## Your first receive session
   (Operate → Start; what "working" looks like: decodes within one slot,
    level meter, slot progress; link operating.md for everything after)
```

Prose fills each section; keep ≤200 lines total.

- [ ] **Step 3: Claim audit**

Re-read the finished page. For each sentence that states app behavior, confirm
you saw it in the Source map file (not the root README). Fix any claim you
cannot point to source for.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: four `BROKEN` lines remaining (the manual pages not yet written),
none of them `getting-started.md`.

- [ ] **Step 5: Commit**

```bash
git add docs/manual/getting-started.md
git commit -m "docs(manual): getting started — install through first decodes"
```

---

### Task 3: operating.md — screen reference half

**Files:**
- Create: `docs/manual/operating.md` (this task writes the first four sections; Task 4 appends the rest to the SAME file)

**Source map:**

| Fact to extract | Source |
|---|---|
| Status bar chips: dial (tap-to-retune), mode, TX tone, POTA, Halt TX, slot progress, UTC countdown, Even/Odd, TX Ns countdown | `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt`, `TxSlotParityToggle.kt`, `TxToneIndicator.kt` |
| Clock-offset chip: threshold to show, color states, value meaning | `OperateStatusBar.kt` (clock chip block), `core/src/main/java/net/ft8vc/core/ClockOffsetEstimator.kt` |
| Decode row anatomy and colors (CQ green, to-you amber, partner bold/primary, dimming, worked-before shading, TX row background) | `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` (`DecodeRowItem`), `app/src/main/java/net/ft8vc/app/DecodeRow` usages |
| Distance column condition (4-char grid in message) | `DecodeListPanel.kt` + `core/src/main/java/net/ft8vc/core/MaidenheadGrid.kt` |
| Message prefix markers | `core/src/main/java/net/ft8vc/core/DecodePrefix.kt` |
| Focus vs Band exact filter rules; CQ·73 chip | `core/src/main/java/net/ft8vc/core/DecodeViewMode.kt` and its filter application in `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` |
| Empty-list messages per mode | `DecodeListPanel.kt` (`emptyDecodeMessage`) |
| SNR is an estimate (typically within a few dB of WSJT-X); the report sent comes from it | `core/src/main/java/net/ft8vc/core/SnrEstimator.kt` KDoc, `core/src/main/java/net/ft8vc/core/QsoMachine.kt` (`answerCq` stores `reportSent = snr`) |

- [ ] **Step 1: Read the Source map files and note the facts**

Chip labels, color names, and filter predicates must come from the composables
and `DecodeViewMode` logic, not from the README feature list.

- [ ] **Step 2: Write the first four sections**

```markdown
# Operating

## The 15-second rhythm
   (one short paragraph: UTC slot alignment, even/odd TX periods as THIS APP
    presents them — no protocol tutorial)
## Status bar
   (subsection or definition-list per chip; include the clock-offset chip:
    when it appears, what the number means, color thresholds, and the fix —
    sync phone clock)
## Decode list
   (row anatomy: time, SNR, distance, freq, message; color rules; prefix
    markers; self-TX row; SNR-is-an-estimate note here)
## View modes
   (Focus vs Band exact rules; CQ·73 chip; empty-state messages)
```

End the file after `## View modes` — Task 4 appends.

- [ ] **Step 3: Claim audit**

Re-read the finished sections. For each sentence that states app behavior,
confirm you saw it in a Source map file (not the root README). Fix any claim
you cannot point to source for.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: three `BROKEN` lines (`logging.md`, `settings.md`,
`troubleshooting.md`), none `operating.md`.

- [ ] **Step 5: Commit**

```bash
git add docs/manual/operating.md
git commit -m "docs(manual): operating — status bar, decode list, view modes"
```

---

### Task 4: operating.md — contacts and automation half

**Files:**
- Modify: `docs/manual/operating.md` (append after `## View modes`)

**Interfaces:**
- Consumes: `docs/manual/operating.md` exists with sections `# Operating`, `## The 15-second rhythm`, `## Status bar`, `## Decode list`, `## View modes` (Task 3).

**Source map:**

| Fact to extract | Source |
|---|---|
| QSO sequence: states and what is transmitted at each stage; RRR vs RR73; Send RR73 log-on-send | `core/src/main/java/net/ft8vc/core/QsoMachine.kt`, `core/src/main/java/net/ft8vc/core/QsoMessages.kt`, `SettingsRepository.kt` (`SEND_RR73 ?: true`) |
| Start CQ waits for chosen parity slot; late-start TX | `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`, `SettingsRepository.kt` (`LATE_START_TX_ENABLED ?: true`) |
| Answer when called (default ON) vs Auto answer CQ (default OFF); independence | `SettingsRepository.kt` (`ANSWER_WHEN_CALLED ?: true`, `AUTO_ANSWER_CQ ?: false`), `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt` |
| Answer policy First / Best SNR / Furthest | `core/src/main/java/net/ft8vc/core/AnswerPolicy.kt` (or enum location via `grep -rn "AnswerPolicy" core/src/main`), selection in `core` (`selectCq`) |
| Abandon after N cycles (default 5) + session blocklist + clearing | `SettingsRepository.kt` (`MAX_UNANSWERED_TX ?: 5`), `core/src/main/java/net/ft8vc/core/AbandonedPartners.kt` |
| Auto-resume CQ (default OFF) | `SettingsRepository.kt` (`AUTO_CQ_RESUME ?: false`), resume logic in `QsoSessionController.kt` |
| Halt TX / Stop QSO / Abandon / resume semantics | `app/src/main/java/net/ft8vc/app/ui/operate/OperateControls.kt`, `OperateViewModel.kt` |
| Waterfall: tap/drag sets TX tone; tone persistence; passband range; dial/band picker over CAT | `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumScreen.kt`, `app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt`, `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt` (max freq 4000) |
| Early decode: default ON, preview pass ~12 s into the slot, rows identical to full-pass rows | `SettingsRepository.kt` (`EARLY_DECODE_ENABLED ?: true`), `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` (`EARLY_OFFSET_MS = 12_000L`), `app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelEarlyParityTest.kt` |

- [ ] **Step 1: Read the Source map files and note the facts**

Walk `QsoMachine.kt` state by state and write down what message each state
transmits and what reply advances it — the manual's sequence table comes
straight from this.

- [ ] **Step 2: Append these sections**

```markdown
## Making contacts
   (tap-a-CQ to answer; Start CQ + slot wait + late-start; the sequence table:
    stage → what FT8VC sends → what advances it; RRR vs RR73 and log-on-send)
## Automation
   (Answer when called; Auto answer CQ; answer policy; abandon after N cycles
    and the session blocklist — what it blocks, how to clear; auto-resume CQ;
    state each default inline and cross-link settings.md for the full table)
## Taking manual control
   (Halt TX; Stop QSO / Abandon; what resume does afterward)
## Spectrum tab and your TX tone
   (waterfall tap/drag; persistence across tabs; passband; dial/band picker)
## Early decode
   (what the toggle does; a preview pass partway through the slot; early rows
    look and behave identically to full-pass rows)
```

Total file stays ≤200 lines; if it will not fit, tighten prose — do not drop
sections.

- [ ] **Step 3: Claim audit**

Re-read the appended sections. For each sentence that states app behavior,
confirm you saw it in a Source map file (not the root README). Fix any claim
you cannot point to source for.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: three `BROKEN` lines (`logging.md`, `settings.md`,
`troubleshooting.md`).

- [ ] **Step 5: Commit**

```bash
git add docs/manual/operating.md
git commit -m "docs(manual): operating — contacts, automation, spectrum, early decode"
```

---

### Task 5: logging.md

**Files:**
- Create: `docs/manual/logging.md`

**Source map:**

| Fact to extract | Source |
|---|---|
| What triggers auto-log; fields recorded | `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt` (log call sites), `data/src/main/java/net/ft8vc/data/Logbook.kt`, `data/src/main/java/net/ft8vc/data/db/` (QsoEntity fields) |
| Log-on-send for RR73 | `QsoMachine.kt` / `QsoSessionController.kt` (RR73 path) |
| Log tab UI | `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt` |
| ADIF version + export via share intent | `data/src/main/java/net/ft8vc/data/adif/AdifWriter.kt`, export trigger in `LogScreen.kt`/`LogViewModel` |
| POTA `MY_SIG`/`MY_SIG_INFO`; fail-closed validation | `data/src/main/java/net/ft8vc/data/adif/AdifNormalizer.kt`, `data/src/main/java/net/ft8vc/data/adif/AdifValidator.kt` |
| QSO session state lost on app exit (by design) | CLAUDE.md anti-pattern note; verify in `OperateViewModel.kt` (in-memory QsoMachine) |

- [ ] **Step 1: Read the Source map files and note the facts**

- [ ] **Step 2: Write the page**

```markdown
# Logging and ADIF export

## What gets logged, and when
   (completion events incl. RR73 log-on-send; the exact fields recorded)
## The Log tab
   (list, counts, what is shown per QSO)
## Exporting ADIF
   (ADIF 3.1.4; share intent flow; where the file goes)
## POTA fields
   (MY_SIG / MY_SIG_INFO; export fails closed when POTA is on without a valid
    park reference — say exactly what the user sees)
## What is NOT persisted
   (in-flight QSO state is lost on app exit by design; how to pick a contact
    back up — link operating.md manual-control section)
```

- [ ] **Step 3: Claim audit**

Re-read the finished page. For each sentence that states app behavior, confirm
you saw it in a Source map file (not the root README). Fix any claim you
cannot point to source for.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: two `BROKEN` lines (`settings.md`, `troubleshooting.md`).

- [ ] **Step 5: Commit**

```bash
git add docs/manual/logging.md
git commit -m "docs(manual): logging and ADIF export"
```

---

### Task 6: settings.md

**Files:**
- Create: `docs/manual/settings.md`

**Source map:**

| Fact to extract | Source |
|---|---|
| Group structure and every setting's UI label | `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (mirror its section order exactly) |
| Every default | `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:29-56` — known defaults: txToneHz 1000; pttPreference AUTO; licenseAcknowledged false; txEnabled false; autoSeq true; answerWhenCalled true; autoAnswerCq false; lateStartTx true; earlyDecode true; sendRr73 true; autoCqResume false; answerPolicy FIRST; maxUnansweredTxCycles 5; inputGain 1.0; potaMode false; cq73Filter false; decodeViewMode OPERATE (=Focus); txSlotParity EVEN; useDarkTheme true |
| Behavior/interactions per setting | The controller that consumes it: `TxOrchestrator.kt` (late-start, parity), `QsoSessionController.kt` (auto modes, abandon), `DecodeController.kt` (early decode, view mode), `RigSession.kt`/`RigController.kt` (PTT preference), `data/src/main/java/net/ft8vc/data/adif/AdifValidator.kt` (POTA) |

- [ ] **Step 1: Read `SettingsScreen.kt` top to bottom**

Record every group heading and setting label in on-screen order. The page's
structure must match the screen's, so a user can read down both in parallel.

- [ ] **Step 2: Write the page**

One section per Settings group; within each, one entry per setting:

```markdown
# Settings reference

Ordered exactly as the Settings screen presents them.

## Station
### Callsign
- **Default:** empty (required before TX)
- **Behavior:** …
- **Interactions:** …
(…repeat the three-field entry for every setting in every group:
 Station, Audio, Rig, Operating, Display — matching Step 1's inventory…)
```

Every setting present in `SettingsScreen.kt` MUST have an entry; if a setting
exists in `SettingsRepository` but has no UI, it is out of scope — skip it.

- [ ] **Step 3: Claim audit**

Diff your entries against the Step 1 inventory (no missing/extra settings) and
each default against `SettingsRepository.kt:29-56`.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: one `BROKEN` line (`troubleshooting.md`).

- [ ] **Step 5: Commit**

```bash
git add docs/manual/settings.md
git commit -m "docs(manual): settings reference"
```

---

### Task 7: troubleshooting.md

**Files:**
- Create: `docs/manual/troubleshooting.md`

**Source map:**

| Fact to extract | Source |
|---|---|
| USB fallback + snackbar on disconnect | `rig/src/main/java/net/ft8vc/rig/RigController.kt` (NoOpRigBackend fallback), snackbar events in `OperateViewModel.kt` |
| No-decode causes | `SlotCollector.kt` (<85% samples → silent skip), input device/gain (`UsbAudioCapture.kt`), clock offset (`ClockOffsetEstimator.kt`), DATA-U (`Ft891Cat.kt`) |
| CAT timeout presentation | `Ft891Cat.kt` (timeout → empty), `catStatus` in `OperateUiState` |
| TX-won't-key gates, in order | license ack → TX enable → PTT preference → Halt TX state; `OperateViewModel.kt` / `TxOrchestrator.kt` |
| Clock-offset chip thresholds | `OperateStatusBar.kt`, `ClockOffsetEstimator.kt` |

- [ ] **Step 1: Read the Source map files; for each symptom write the ACTUAL
gate order and user-visible message text**

- [ ] **Step 2: Write the page**

```markdown
# Troubleshooting

Each entry: what you see → checks in order → why.

## No USB device / permission prompt loops
## No decodes
   (ordered checklist: input device selected → level meter moving → clock
    offset chip → dial on an FT8 frequency → rig in DATA-U → antenna)
## Frequency or mode reads wrong or stale (CAT)
## Transmit will not key
   (the gate order from source, each with its Settings location)
## You call but nobody comes back
   (TX tone placement in passband, Even/Odd slot choice)
## Where did my QSO go? (app exit)
   (by design; what is and is not recoverable — link logging.md and
    operating.md)
```

- [ ] **Step 3: Claim audit**

Re-read the finished page. For each sentence that states app behavior, confirm
you saw it in a Source map file (not the root README). Message text quoted in
the page must match source string literals. Fix any claim you cannot point to
source for.

- [ ] **Step 4: Link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: NO `BROKEN` lines anywhere.

- [ ] **Step 5: Commit**

```bash
git add docs/manual/troubleshooting.md
git commit -m "docs(manual): troubleshooting"
```

---

### Task 8: End-to-end verification pass

**Files:**
- Modify: any `docs/manual/*.md` (fixes only)

**Interfaces:**
- Consumes: all six manual pages complete (Tasks 1–7).

- [ ] **Step 1: Full link check**

Run from repo root:

```bash
for f in docs/manual/*.md README.md docs/README.md; do
  grep -oE '\]\(([^)#]+\.md)' "$f" | sed 's/](//' | while read -r link; do
    [ -f "$(dirname "$f")/$link" ] || echo "BROKEN: $f -> $link"
  done
done
```

Expected: no output. Fix any breakage.

- [ ] **Step 2: Cold read-through**

Read all six pages in the index's reading order as if new to the app. Flag and
fix: contradictions between pages, protocol-tutorial drift, marketing tone,
UI labels not in bold, pages over ~200 lines.

- [ ] **Step 3: Emulator spot-check (if an emulator is available)**

Launch the app (`./gradlew :app:installDebug` on a running emulator, open
FT8VC). Walk Settings and the four tabs comparing on-screen labels and
group order against `settings.md` and `operating.md`. No rig attached —
hardware-dependent states are out of scope; verify labels/structure only.
If no emulator is available, note that in the commit message and skip.

- [ ] **Step 4: Style pass**

Confirm: one H1 per page; scope note only in the index; second person
imperative throughout.

- [ ] **Step 5: Commit fixes**

```bash
git add docs/manual/
git commit -m "docs(manual): verification pass — link, consistency, and label fixes"
```

If no fixes were needed, skip the commit and record "verification clean" in
the task summary.
