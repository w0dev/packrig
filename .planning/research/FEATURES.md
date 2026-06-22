# Feature Research — FT8VC v1.x Code Health Milestone

**Domain:** Android amateur-radio / field-instrument app (brownfield, RF-safety-sensitive, solo-maintained)
**Researched:** 2026-06-21
**Confidence:** HIGH for items mapped 1:1 to CONCERNS.md + PITFALLS.md; MEDIUM for peer-app comparisons (WSJT-X, JS8Call, FT8CN, HRD) and general Android USB/audio practice.

> **Framing.** This is a code-health milestone, not a feature milestone. "Features" here means *operational-reliability deliverables* — the snackbar texts, the status chips, the manifest line-edits, the watchdog timers, and the auto-export cadences that make the existing v1.0 product trustworthy in the field. Every entry below maps to a CONCERNS.md item, a PITFALLS.md pitfall, or is explicitly named as an anti-feature.
>
> **Hard UX constraint (from PROJECT.md):** no new top-level screens, no new tabs, no crowding of Operate/Spectrum/Log/Settings. Every surfaced state lives in a **snackbar**, a **status chip in existing chrome**, or **existing Settings rows**. Anything that would need a new screen is, by definition, an anti-feature for this milestone.

---

## Feature Landscape

### Table Stakes (Users Expect These — Missing = Field Bite)

Features the operator assumes are present in any 2026 field-grade FT8 app. Missing any of these means the operator gets bitten — stuck PTT, lost decodes, silent failure, or unrecoverable state.

| # | Feature | Why Expected | Complexity | UX Footprint | Maps to |
|---|---------|--------------|------------|--------------|---------|
| TS-1 | **CAT timeout with visible "CAT timeout" status + retry** | A blocked CAT read currently hangs the UI indefinitely (CONCERNS Error-Handling/CAT). Every peer app (WSJT-X, JS8Call, HRD) surfaces CAT failure within a few seconds — silent hang is below baseline. | MEDIUM (driver-level + coroutine outer guard per Pitfall 9) | **Persistent status chip** in the existing CAT status row (not a toast — Pitfall 9 + UX Pitfalls table). Settings → Rig gets a "Retry CAT" button in existing rows. | CONCERNS §"CAT Timeout Only on Throwable"; PITFALLS Pitfall 9 |
| TS-2 | **Guaranteed PTT release on any exception path (4-layer defense)** | A stuck-key into the antenna at 50 W on DATA-U is the single highest-severity failure this app can produce — FCC violation, finals damage. Every serious digital-mode app implements defense in depth; only toy apps rely on a single try-finally. | HIGH (4 layers: try-finally, AutoCloseable use{}, withTimeout, watchdog coroutine — all four required) | Invisible during normal operation; surfaces as a **"TX safety halt — PTT forced released" snackbar + persistent chip** when watchdog fires (operator must see it). | CONCERNS §"Audio Capture/Playback Not Guaranteed Closed"; PITFALLS Pitfall 3 |
| TS-3 | **USB device disconnect notification (ACTION_USB_DEVICE_DETACHED → snackbar + status chip)** | A field-grade app must tell the operator within 5 s that the rig is gone. Silent transition from "RX OK" to "decoding nothing" is a known FT8CN complaint (Digirig forum threads cite this exact symptom). | LOW–MEDIUM (broadcast receiver + state plumbing) | **Snackbar on detach** ("Digirig disconnected — RX only") + **persistent status chip** until reconnect or explicit dismiss. Status chip lives in existing top status bar. | CONCERNS §"No Disconnect Notification"; PITFALLS Pitfall 4 + UX Pitfalls table |
| TS-4 | **AudioRecord/AudioTrack hot-swap recovery** | Cable wiggle is the dominant USB-OTG failure mode in the field. Android AudioRecord behavior on USB removal is inconsistent across vendors (some return ERROR_DEAD_OBJECT, some return 0 samples silently). A field-grade app must detect both. | MEDIUM (return-value check + `AudioDeviceCallback` + zero-samples-watchdog — both signals) | Same snackbar/chip as TS-3 (single "device gone" UX path); no new surface. | PITFALLS Pitfall 8 |
| TS-5 | **Native library load handshake — refuse TX if `Ft8Native.loaded == false`** | Silent "Start CQ" that does nothing — or worse, keys an empty carrier — is a field-safety bug. CONCERNS calls this out explicitly. Operator currently has no way to know the decoder is dead. | LOW (boolean check at TX gate; visible status in Settings → About) | **Settings → About** gains a "Decoder library: loaded vN" / "Decoder library: FAILED — reinstall app" row. TX button is **disabled with helper text** when load failed. No new screen. | CONCERNS §"Hardcoded Native Library Version"; PITFALLS Pitfall 3 (4-layer defense), Security/Safety table |
| TS-6 | **Native library version handshake** | A version skew between `libft8vc.so` and the calling Kotlin (manual builds, sideloads, ABI mismatch) currently fails silently or much later with cryptic NDK errors. Standard practice in any JNI-backed app: native exports a `version()` and Kotlin verifies on load. | LOW (one new JNI export `version()`; one startup compare) | Same Settings → About row as TS-5 — displays the version. Mismatch surfaces as a **persistent chip in Operate header** ("Decoder version mismatch — TX disabled") with the same TX disable as TS-5. | CONCERNS §"Hardcoded Native Library Version"; PROJECT.md Active list |
| TS-7 | **Capture thread interrupt-with-timeout (no orphan threads)** | Calling `Thread.interrupt()` and walking away leaves a JNI/AudioRecord blocked thread alive. Standard pattern: `interrupt()` → `join(timeout)` → escalate. CONCERNS calls this out explicitly. | LOW–MEDIUM | Invisible on success; orphan-detected case emits a **"Audio thread didn't stop cleanly — recovering" snackbar** and forces capture-chain recreation. | CONCERNS §"Audio Capture/Playback Not Guaranteed Closed"; PITFALLS Pitfall 8 |
| TS-8 | **Decode-loop failure counter surfaced as a status chip** | Currently caught and logged, then silently dropped (CONCERNS §Silent Failures). Operator sees "no decodes" and assumes propagation. A counter like "decodes dropped: N" lets the operator know it's a tooling issue, not a band issue. | LOW (counter + chip) | **Status chip in Operate header** showing "Decodes dropped: 3" only when N > 0 in the last ~5 slots. Auto-clears when stable. | CONCERNS §"Silent Failures in Decode Loop"; PROJECT.md Active list |
| TS-9 | **USB device filter scoped to Digirig parts (CP2102 + FT240X)** | A too-permissive `usb_device_filter.xml` will pop the "open ft8vc when device attaches?" prompt for unrelated USB devices (mice, keyboards on OTG hubs). That's the kind of paper-cut that turns into a 1-star review. CP2102 is **VID 0x10C4 PID 0xEA60**; FT240X (FTDI FT-X family) is **VID 0x0403 PID 0x6015**. | LOW (XML edit) | **Invisible** — fewer spurious prompts. | CONCERNS §"USB Device Descriptor Not Validated"; PITFALLS Security/Safety table; PROJECT.md Active list |
| TS-10 | **USB permission persisted via `usb_device_filter.xml`; no re-prompt on every reconnect** | Re-prompting on every device attach is a major peer-app complaint (see Digirig forum threads). The auto-grant path via XML filter + intent-filter is the table-stakes fix. | LOW (already partially in place; verify) | **Invisible** — reconnect is silent except for TS-3 snackbar restore. | CONCERNS §"USB Device Descriptor Not Validated"; PITFALLS Integration Gotchas table |
| TS-11 | **Decode list bounded sliding window (cap ~500) with Clear wired up** | After 1 hour of monitoring, the list grows to thousands of rows; LazyColumn stutters and memory pressure climbs. CONCERNS notes the Clear button exists in the ViewModel but isn't wired to the UI. | LOW (cap + wire existing button) | **Existing `DecodeListPanel`**: add "showing last 500 — older cleared" indicator at top when capped; **wire the existing Clear button** in the existing toolbar — no new chrome. | CONCERNS §"Unbounded Decode List Growth", §"Decode List Updates Not Atomic"; PITFALLS Pitfall 11 |
| TS-12 | **Local ADIF auto-export to app-private storage on a sane cadence** | The Room DB is the only copy. Factory reset, uninstall, OS migration = total QSO loss. Peer apps log to a flat ADIF file in addition to (or instead of) a database — WSJT-X writes `wsjtx_log.adi` continuously, JS8Call follows the same pattern. The flat-file copy is the data-loss insurance, not the primary store. **Recommended cadence: after each QSO commit + at app pause + on a daily rolling timer (whichever first).** Written to `getExternalFilesDir()` so Android backup honors it but external apps can't sniff it. | LOW (one writer, atomic rename, ApplicationScope) | **Settings → Logbook** gains a "Last backup: 15 min ago" line + "Backup now" button — both inside existing rows. No new screen. | CONCERNS §"No Local Backup of Logbook"; PROJECT.md Active list; PITFALLS Security/Safety table |
| TS-13 | **Remove `android.permission.INTERNET` from manifest** | App declares it for an unimplemented NTP feature; granting INTERNET without using it is a permission-fatigue precedent and a privacy posture problem (any future code can quietly use network without re-prompting the user). | TRIVIAL (one-line manifest edit) | **Invisible** — fewer permissions on the Play / install page. | CONCERNS §"INTERNET Permission Declared but Unused"; PITFALLS Security/Safety table; PROJECT.md Active list |
| TS-14 | **License acknowledgment re-checked on every USB reconnect / cold init path** | If the reconnect path skips license re-check, TX can be enabled after a hot-swap without the operator re-confirming. PITFALLS Pitfall 4 names this as a Critical pitfall — RF safety, not a nice-to-have. | LOW (route reconnect through the same init that cold start uses) | **Invisible** when license already acked this session; surfaces the **existing license sheet** if state was reset. No new UI. | PITFALLS Pitfall 4 + Security/Safety table |
| TS-15 | **CAT-port close+reopen on timeout; consecutive-failure threshold** | A single CAT timeout often cascades — the underlying USB-serial driver `read()` is not interruptible by coroutine cancel, so the port stays held. Standard recovery is close+reopen the serial handle and bound consecutive failures (e.g., N=3 → "CAT unreachable" persistent chip, stop retrying until user action). | MEDIUM (driver-aware) | Same persistent chip as TS-1; after threshold, chip text becomes **"CAT unreachable — tap to retry"** in existing CAT status row. | PITFALLS Pitfall 9 |

### Differentiators (FT8VC Can Lead Here)

Features that no peer FT8-on-Android app currently does well — implementing them makes FT8VC the example for FT8-on-Android reliability without needing new screens.

| # | Feature | Value Proposition | Complexity | UX Footprint | Maps to |
|---|---------|-------------------|------------|--------------|---------|
| D-1 | **PTT watchdog coroutine as a publicly documented safety primitive** | Most digital-mode apps (including desktop WSJT-X/JS8Call) trust their try-finally and call it a day. FT8VC's 4-layer defense (try-finally + AutoCloseable + withTimeout + watchdog) is materially stronger — and **operator-visible feedback when the watchdog fires** ("TX safety halt — PTT forced released") teaches operators something they can't get from peer apps. Becomes a talking point: "FT8VC will never leave your finals keyed." | MEDIUM (the work is already in TS-2; differentiation is in surfacing + documenting it) | Same snackbar/chip as TS-2; **README + Settings → About** mention the safety model. | PITFALLS Pitfall 3 |
| D-2 | **Emergency-halt state machine on USB detach (no auto-resume TX)** | Peer apps either silently reconnect (FT8CN behavior reported on Digirig forums) or freeze. FT8VC explicit `EMERGENCY_HALT → RX_ONLY → user-action → READY` state machine, with the operator forced to re-tap to enable TX after reconnect, is a real-world field safety win. Snackbar wording can teach: "Digirig reconnected — tap TX to resume." | MEDIUM (one enum + one reconnect path) | Snackbar + chip; no new screen. | PITFALLS Pitfall 4 |
| D-3 | **Golden-trace QSO replay tests + `FakeRigBackend` harness shipped in-repo** | A reproducible "feed a recorded sample → verify QsoState transitions" harness lets contributors and downstream maintainers refactor without bricking RX/TX. Open-sourcing this is a meaningful contribution to the FT8-on-Android community — no other app I've seen ships one. Not user-visible, but reviewer-visible and contributor-visible. | MEDIUM | Invisible to operator; visible in repo (`core/src/test/`, `app/src/test/fakes/`). | PITFALLS Pitfalls 5, 10 |
| D-4 | **Behavior-parity snapshot fixtures committed alongside refactor commits** | A 5-minute decode/TX session log captured on the reference rig, replayed against each extraction commit. Catches "the refactor looked fine in unit tests but PTT timing drifted 200 ms" before promotion. Committing the fixture under `.planning/field-sessions/` makes regression diagnosis trivial. | MEDIUM (one good capture; replay tooling reuses TS-3's `FakeRigBackend`) | Invisible to operator; visible in repo. | PITFALLS Pitfalls 1, 12 |
| D-5 | **Compose recomposition budget — measured, regression-tested, documented** | Most Compose apps don't measure recomposition counts. The 5-controller split risks a recomposition storm (PITFALLS Pitfall 6). Measuring before/after with `recomposeHighlighter` and gating promotion on "no waterfall flicker at slot boundary" is a discipline peer apps don't apply. | LOW–MEDIUM (one measurement run per phase; documented in checklist) | Invisible to operator; visible in promotion checklist. | PITFALLS Pitfall 6 |
| D-6 | **Promotion checklist as a committed artifact with field-session evidence** | Most ham-radio apps "promote when it feels stable." A committed `.planning/promotion-checklist.md` with explicit field items + log/screenshot evidence under `.planning/field-sessions/<date>/` becomes a reputation moat. Stable users get genuinely stable builds. | LOW (one markdown file + discipline) | Invisible to operator; visible in `.planning/` and PR template. | PITFALLS Pitfall 12 |
| D-7 | **`TxSlotParity` enum used end-to-end** | Eliminates an entire class of "0/1 swapped" bugs that other FT8 apps have shipped (and quietly hot-fixed). Cheap, mechanical, and lasting. | LOW | Invisible to operator. | CONCERNS §"Stringly-Typed Slot Parity"; PROJECT.md Active list |

### Anti-Features (Deliberately NOT Built — Especially Anything Needing a New Screen)

| # | Anti-Feature | Why It Looks Appealing | Why It's Wrong For This Milestone | Better Approach |
|---|--------------|------------------------|-----------------------------------|-----------------|
| AF-1 | **Dedicated "Diagnostics" tab / screen** | Engineers love diagnostics screens. Easy to dump every counter, every log line, every state into a tab. | **Violates PROJECT.md "no new top-level screens" hard constraint.** It also normalizes "the failures live over there in the diag screen" — operators stop looking. Failures must surface where the operator is *already looking* (Operate header chips, snackbars). | Inline status chips in existing chrome (TS-1, TS-3, TS-8). Detailed logs stay in logcat. |
| AF-2 | **Modal "CAT error" dialog mid-QSO** | Feels appropriately urgent. Forces acknowledgment. | A modal in the middle of an FT8 slot **costs the operator the slot** — the slot boundary will pass while they're dismissing the dialog. Peer apps that do this get bad reviews from POTA/contest operators. | Persistent status chip + snackbar (TS-1). Operator decides when to act. |
| AF-3 | **In-app telemetry / crash uploads** | "We could learn so much about how the app fails in the field." | INTERNET permission is being **removed** (TS-13). Adding telemetry contradicts the privacy posture for this milestone and breaks "receive-only by default" trust. Open-source app on F-Droid–style trust: don't phone home. | Operator-driven bug reports via GitHub Issues + logcat dumps. No network. |
| AF-4 | **Full diagnostics drawer / sliding pane** | "Not a new tab! Just a drawer!" | Same problem as AF-1 — a drawer is a new screen that crowds the operating UI and trains the operator to ignore in-context failures. | See AF-1. |
| AF-5 | **Auto-resume TX after USB reconnect** | "Saves the operator a tap." | RF safety issue — re-keying into a half-initialized rig backend with stale serial state can produce undefined CAT behavior, possibly a stuck PTT (PITFALLS Pitfall 4). The cost of one extra tap is far less than the cost of one unintended emission. | Emergency-halt → explicit user tap to resume (D-2 + TS-14). |
| AF-6 | **Cloud / Google Drive logbook backup** | Operators *do* want this. | Out of scope per PROJECT.md (explicit). Brings INTERNET back, brings OAuth flows, brings privacy review — all of which break the "code health, no new feature surface" frame. | Local ADIF auto-export (TS-12) is the data-loss insurance for this milestone. Cloud is a separate future milestone. |
| AF-7 | **Logbook PIN / biometric encryption** | CONCERNS notes it as a privacy concern (callsigns + locations + timestamps). | Out of scope per PROJECT.md (explicit). Encryption changes Room schema, export pipeline, and UX in ways that fight the refactor focus. | App-private storage (`getExternalFilesDir()`) for auto-export (TS-12); explicit Share Intent for manual export. Encryption deferred. |
| AF-8 | **Animated/blinking error states ("PTT STUCK!" flashing red)** | "Operator can't miss it." | False alarms — the watchdog is correct but the operator's adrenaline doesn't know that. Train operators with calm, persistent, dismissible-on-resolve chips. | Persistent status chip with calm color (warning, not critical) + one snackbar at event time (TS-2, D-1). |
| AF-9 | **Toast for every CAT call result** | "It's nice to know it worked." | Toasts are dismissible-only — Pitfall 9 / UX Pitfalls table calls this out. Toast spam trains operators to dismiss everything, including the safety toasts. | Status chip reflects current CAT state; toasts reserved for state *changes* the operator must notice. |
| AF-10 | **"Decode dropped: N — tap for details" with a detail screen** | "Curious operator wants to know why." | The detail screen is the new screen we said we wouldn't build (AF-1). The number itself is enough signal — if N grows, operator restarts or files an issue. | Bare counter chip (TS-8). Details land in logcat for the user who wants to `adb logcat`. |
| AF-11 | **Auto-retry CAT silently after timeout** | "Self-healing UX." | Silent retry hides a real failure mode (Pitfall 9 cascade). The user must see the timeout to know the device needs attention. | Single retry with port close+reopen (TS-15); after threshold, surface "CAT unreachable" and **stop** retrying until user action. |
| AF-12 | **Settings toggle for "disable RF safety watchdog"** | "Power users want it off." | There is no responsible power-user case for disabling the watchdog. The only reason to add the toggle is to satisfy a hypothetical complaint — which would also be the bug report we don't want to receive. | The watchdog is non-optional. If it triggers too aggressively, fix the watchdog. |
| AF-13 | **Confirmation dialog for "are you sure you want to stop CQ?"** | "Prevent fat-finger." | Costs a slot. Operators are practiced — they don't fat-finger Stop. | Existing tap-Stop UX, no confirmation. Undo via tap-Start. |
| AF-14 | **In-app "decoder version mismatch — auto-download fix" prompt** | "Self-healing!" | Requires INTERNET (being removed), requires hosting downloadable native blobs, requires a signature/trust model. Out of scope by a wide margin. | Disabled TX + helpful "reinstall the app" message (TS-5, TS-6). |

---

## Feature Dependencies

```
TS-5 (native lib load handshake)
  └── enables ── TS-6 (version handshake) — same Settings → About row
  └── blocks  ── TX path enable (TS-2 requires Ft8Native.loaded)

TS-2 (4-layer PTT defense)
  ├── requires ── TS-7 (capture thread interrupt-with-timeout)
  ├── requires ── D-1 (watchdog as documented safety primitive — operator surfacing)
  └── enables  ── TS-14 (license re-check on reconnect can lean on emergency-halt)

TS-3 (USB disconnect snackbar)
  ├── requires ── TS-4 (AudioRecord hot-swap recovery — same code path)
  └── enables  ── D-2 (emergency-halt state machine builds on detach handler)

TS-1 (CAT timeout chip)
  └── requires ── TS-15 (CAT close+reopen + threshold — the recovery story)

TS-11 (decode list cap + wired Clear)
  └── requires ── stable keys on DecodeRow + ImmutableList (PITFALLS Pitfall 11)
  └── interacts ── Compose stability work (D-5)

TS-12 (ADIF auto-export)
  └── independent — no blocking deps; can ship any phase

TS-13 (remove INTERNET permission)
  └── independent — trivial; can ship any phase

TS-9, TS-10 (USB filter tighten + permission persist)
  └── enables ── TS-3 (cleaner detach signal when filter is scoped)

D-3 (FakeRigBackend), D-4 (behavior-parity snapshot)
  └── enables ── every other feature's test (must land in Phase 1)

D-5 (Compose recomposition budget)
  └── enables ── TS-2/TS-3/TS-8 chips landing without visual regression
```

### Dependency Notes

- **TS-5 must precede TX paths in the new TxOrchestrator.** Disabling TX when `Ft8Native.loaded == false` is the gate that prevents the "silent Start CQ" failure mode.
- **TS-3 and TS-4 are one feature in two costumes.** The user-visible snackbar (TS-3) and the AudioRecord recovery (TS-4) share a detach signal. Implement them in the same commit; don't split.
- **TS-2 and D-1 are one feature in two costumes.** The 4-layer defense is the code; the operator-facing snackbar/chip + README mention is the differentiator. Don't ship the code without the surfacing — invisible safety is half-credit.
- **D-3 (`FakeRigBackend`) must land in Phase 1** — every subsequent feature's test needs it (PITFALLS Pitfall 10). It is foundational, not optional.
- **TS-12 (ADIF auto-export) is independent** — no blocking deps. Safe to slot into any phase that has cycles. Recommend placing it adjacent to the LogViewModel work for cache-locality of reviewer attention.
- **TS-13 (remove INTERNET) is trivial and independent** — ship in the same phase as the manifest review (TS-9 USB filter), single AndroidManifest.xml change set.

---

## MVP Definition

> "MVP" here = the minimum set whose absence would leave a known field-bite from CONCERNS.md uncovered. The milestone goal is "no surprises on the FT-891 + Digirig reference rig."

### Ship This Milestone (v1.x — required for stable promotion)

All TS-* items. Skip any of them and CONCERNS.md issues remain open:

- [ ] **TS-1** CAT timeout + retry — CONCERNS HIGH
- [ ] **TS-2** 4-layer PTT defense — RF safety, **non-negotiable**
- [ ] **TS-3** USB disconnect snackbar + chip — CONCERNS MEDIUM
- [ ] **TS-4** AudioRecord hot-swap recovery — bundles with TS-3
- [ ] **TS-5** Native lib load handshake + TX gate — CONCERNS HIGH (unranked)
- [ ] **TS-6** Native lib version handshake — bundles with TS-5
- [ ] **TS-7** Capture thread interrupt-with-timeout — CONCERNS HIGH
- [ ] **TS-8** Decode-loop failure counter chip — CONCERNS MEDIUM
- [ ] **TS-9** USB device filter tightened — CONCERNS LOW
- [ ] **TS-10** USB permission persisted (verify, no re-prompt) — CONCERNS LOW
- [ ] **TS-11** Decode list cap + wired Clear — CONCERNS MEDIUM
- [ ] **TS-12** ADIF auto-export to app-private storage — CONCERNS LOW
- [ ] **TS-13** INTERNET permission removed — CONCERNS LOW
- [ ] **TS-14** License re-check on reconnect — RF safety, bundles with TS-3/D-2
- [ ] **TS-15** CAT close+reopen + failure threshold — bundles with TS-1

### Ship This Milestone if Time Permits (differentiators — strongly recommended)

These are the items that make the milestone *exemplary* rather than merely correct:

- [ ] **D-1** Operator-facing safety surfacing for the watchdog — half the value of TS-2 lives here
- [ ] **D-2** Emergency-halt state machine — formalizes TS-3 + TS-14 into a reviewable enum
- [ ] **D-3** `FakeRigBackend` + golden-trace harness — **strongly recommended in Phase 1** (PITFALLS Pitfall 10 names it required)
- [ ] **D-4** Behavior-parity snapshot fixture — Phase 1 capture, replay every phase
- [ ] **D-5** Compose recomposition budget — measure pre/post split (PITFALLS Pitfall 6)
- [ ] **D-6** Promotion checklist as committed artifact — Phase 0 setup
- [ ] **D-7** `TxSlotParity` enum end-to-end — CONCERNS MEDIUM

### Defer (Future Milestone)

- [ ] Cloud / Drive backup of logbook (AF-6) — explicit out of scope
- [ ] Logbook PIN / biometric (AF-7) — explicit out of scope
- [ ] NTP clock sync (placeholder for INTERNET permission to be re-added)
- [ ] Spectrum circular-buffer rewrite (CONCERNS "no issue today")
- [ ] Wider rig support beyond FT-891 (explicit out of scope)
- [ ] Decoder weak-signal tuning (explicit out of scope)

---

## Feature Prioritization Matrix

Priority key: **P1** = must-ship for milestone (any miss reopens a CONCERNS HIGH). **P2** = should-ship (differentiator or CONCERNS MEDIUM). **P3** = nice-to-have within milestone.

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| TS-1 CAT timeout chip + retry | HIGH | MEDIUM | **P1** |
| TS-2 4-layer PTT defense | HIGH (RF safety) | HIGH | **P1** |
| TS-3 USB disconnect snackbar + chip | HIGH | LOW | **P1** |
| TS-4 AudioRecord hot-swap recovery | HIGH | MEDIUM | **P1** |
| TS-5 Native lib load handshake + TX gate | HIGH (RF safety) | LOW | **P1** |
| TS-6 Native lib version handshake | MEDIUM | LOW | **P1** |
| TS-7 Capture interrupt-with-timeout | MEDIUM | LOW–MEDIUM | **P1** |
| TS-8 Decode-loop failure counter chip | MEDIUM | LOW | **P1** |
| TS-9 USB device filter tightened | MEDIUM | LOW | **P1** |
| TS-10 USB permission persist (verify) | LOW | LOW | **P1** |
| TS-11 Decode list cap + wired Clear | MEDIUM | LOW | **P1** |
| TS-12 ADIF auto-export | HIGH (data-loss insurance) | LOW | **P1** |
| TS-13 INTERNET permission removed | MEDIUM (privacy) | TRIVIAL | **P1** |
| TS-14 License re-check on reconnect | HIGH (RF safety) | LOW | **P1** |
| TS-15 CAT close+reopen + threshold | HIGH | MEDIUM | **P1** |
| D-1 Operator-facing watchdog surfacing | HIGH | LOW (on top of TS-2) | **P1** (half of TS-2's value) |
| D-2 Emergency-halt state machine | HIGH (RF safety) | MEDIUM | **P2** |
| D-3 FakeRigBackend + golden-trace | HIGH (reviewer / future-maintainer) | MEDIUM | **P1** (Phase 1 prerequisite per PITFALLS) |
| D-4 Behavior-parity snapshot fixture | HIGH | LOW | **P2** |
| D-5 Compose recomposition budget | MEDIUM | LOW | **P2** |
| D-6 Promotion checklist committed | MEDIUM | LOW | **P1** (Phase 0 prerequisite per PITFALLS) |
| D-7 `TxSlotParity` enum end-to-end | LOW (correctness, not user-visible) | LOW | **P2** |

---

## Competitor / Peer-App Analysis

| Feature | WSJT-X (desktop) | JS8Call (desktop) | FT8CN (Android) | HRD (desktop) | **FT8VC Approach** |
|---------|------------------|-------------------|-----------------|---------------|--------------------|
| **ADIF backup cadence** | Continuous append to `wsjtx_log.adi` on every QSO commit; also writes ALL.TXT for debugging. | Continuous append to `js8call.log` (ADIF-shaped). | Local export on-demand; no automatic background backup documented; recurrent forum requests for it. | Manual export + cloud sync (paid). | **TS-12:** append on every QSO commit + flush on app pause + daily rolling timer, written atomically to `getExternalFilesDir()`. Closest to WSJT-X baseline. |
| **CAT timeout handling** | Surfaces "Rig control" status; "Test CAT" button; timeouts produce visible status text. | Similar to WSJT-X. | Reports of indefinite hang when serial breaks (Digirig forum). | Modal dialog (notorious for interrupting workflow). | **TS-1 + TS-15:** persistent status chip (not modal), close+reopen on timeout, consecutive-failure threshold. Avoids HRD's modal anti-pattern. |
| **USB disconnect** | N/A (desktop USB rarely yanked mid-QSO). | N/A. | Reported silent transition to no-decode; forum complaints. | N/A. | **TS-3 + D-2:** snackbar + persistent chip + emergency-halt state machine + license re-check on reconnect. **Differentiator — no peer mobile app does this well.** |
| **PTT release on exception** | try-finally + explicit TX0 on shutdown. Generally trusted. | Same. | Reports of stuck PTT in rare failure modes (forum). | Watchdog timer exists in pro version. | **TS-2 + D-1:** 4-layer defense (try-finally + AutoCloseable + withTimeout + watchdog). **Differentiator — explicitly stronger than the desktop apps' single-layer approach.** |
| **Native lib version mismatch** | Bundled, single binary — not a typical failure. | Same. | Not exposed; failures present as "doesn't decode." | Not applicable. | **TS-5 + TS-6:** version handshake + Settings → About visibility + TX disabled if load failed. **Differentiator — turns a silent failure into a recoverable one.** |
| **Decode list bound** | Caps at ~window size; scrollback to ALL.TXT for history. | Similar. | Unbounded in current builds (memory complaints on long sessions). | Cap configurable. | **TS-11:** 500-row cap + stable keys + ImmutableList + wired Clear button + "showing last 500" indicator. Matches WSJT-X baseline. |
| **USB device filter scope** | N/A. | N/A. | Permissive filter — claims devices that aren't intended (forum reports). | N/A. | **TS-9:** scoped to CP2102 (0x10C4:0xEA60) + FT240X (0x0403:0x6015) only. Tighter than FT8CN baseline. |
| **Telemetry / crash upload** | None. | None. | None. | Optional, opt-in. | **AF-3:** explicit anti-feature. INTERNET permission being removed (TS-13). Stronger privacy posture than HRD. |
| **Diagnostic UI** | "Log QSO" dialog only; rig status in a corner; no dedicated diag tab. | Log + waterfall + chat; no diag tab. | Settings → About with minimal info. | Multiple diagnostic dialogs and tabs (operators find them noisy). | **AF-1, AF-4:** explicit anti-features. All diagnostics inline (chips, snackbars, Settings → About row). Closer to WSJT-X aesthetic than HRD. |

---

## Quality Gate Self-Check

- [x] **Categories explicit and justified** — Table-stakes / Differentiators / Anti-features each have intro paragraph + per-row rationale.
- [x] **Each feature maps to a CONCERNS.md item or is explicitly ruled out** — every TS-* row cites CONCERNS section; every AF-* row cites the reason for exclusion.
- [x] **UX footprint noted for every feature** — every TS-* and D-* row names: snackbar / status chip in existing chrome / existing Settings row / invisible. **Zero items create new screens** (and the New-Screen items are filed as anti-features AF-1 / AF-4).
- [x] **Concrete references to how peer apps handle each** — Competitor Analysis table covers WSJT-X, JS8Call, FT8CN, HRD per feature.
- [x] **Aligns with PITFALLS.md UX prescriptions** — no dismissible-only toasts for safety events (TS-1, TS-2, TS-3 all use **persistent chips** plus a transient snackbar for the state-change moment; matches PITFALLS UX Pitfalls table).

---

## Sources

- `.planning/PROJECT.md` (Active list, Constraints, Out of Scope) — the canonical scope for this milestone.
- `.planning/codebase/CONCERNS.md` (2026-06-21 audit) — every TS-* item maps back to a numbered concern.
- `.planning/codebase/STRUCTURE.md` — confirms 4-tab UI and existing chrome (status bar, settings rows) where every feature must surface.
- `.planning/research/PITFALLS.md` (sibling research) — Pitfalls 1–12, UX Pitfalls table, Security/Safety table, Integration Gotchas — the "how not to build it" companion to this file.
- [WSJT-X User Guide 2.6.1](https://wsjt.sourceforge.io/wsjtx-doc/wsjtx-main-2.6.1.html) — ADIF logging behavior baseline.
- [WSJT-X groups.io — wsjt-x_log.adi storage thread](https://wsjtx.groups.io/g/main/topic/storing_the_wsjt_x_log_adi/88445230) — confirms continuous-append cadence operators expect.
- [Digirig Forum — FT8CN with FT-891 / DR-891](https://forum.digirig.net/t/ft8cn-with-a-ft-891-using-the-new-digirig-dr-891/4101) — competitor app behavior, CAT vs RTS PTT tradeoffs.
- [Digirig Forum — FT8CN with FT-857D](https://forum.digirig.net/t/ft8cn-android-app-with-yaesu-ft-857d/1528) — single-device selection limitations, USB device handling.
- [Digirig Forum — Android Software thread](https://forum.digirig.net/t/android-software/141) — operator complaints about silent disconnect handling.
- [Digirig Troubleshooting Digital Modes](https://digirig.net/troubleshooting-digital-modes/) — common PTT/CAT failure modes in the field.
- [usb-serial-for-android device_filter.xml example](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialExamples/src/main/res/xml/device_filter.xml) — reference for tight `usb_device_filter.xml`; confirms CP2102 VID/PID format.
- [Digirig forum — CP2102 driver dependency](https://forum.digirig.net/t/utilizing-digirig-on-microcontroller-without-cp2102-driver/3146) — confirms CP2102 (0x10C4:0xEA60) as the Digirig serial bridge VID/PID.
- General Android USB / AudioRecord field experience — `AudioDeviceCallback` semantics, vendor inconsistency on USB device removal (Pitfall 8 source).

---
*Feature research for: Android amateur-radio FT8 transceiver — operational-reliability milestone*
*Researched: 2026-06-21*
