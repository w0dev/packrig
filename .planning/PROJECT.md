# FT8VC — v1.x Code Health Milestone

## What This Is

FT8VC is an open-source Android FT8 transceiver that drives an amateur radio rig (reference: Yaesu FT-891 + Digirig Mobile) over USB audio + serial from a phone — no laptop in the field. v1.0 ships as signed APKs from GitHub Releases with `unstable` as the day-to-day development channel. This milestone is a focused **code-health pass** on the v1.0 codebase: refactor the monolithic orchestrator, harden threading and resource lifecycle, close the worst error-handling and security gaps, and bring it all under test — without expanding the feature surface.

## Core Value

**The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field.** Every change in this milestone must preserve that. If a refactor risks RX/TX/CAT behavior on the reference setup, it doesn't ship.

## Requirements

### Validated

<!-- Inferred from the v1.0 codebase + codebase map (.planning/codebase/). These are shipping today and locked. -->

- ✓ USB audio RX from Digirig with 12 kHz, UTC slot-aligned decode — v1.0
- ✓ FT8 encode/decode via native `ft8_lib` JNI (`Ft8Native`) — v1.0
- ✓ QSO state machine (CQ → grid → reports → 73) via `QsoMachine` — v1.0
- ✓ TX path with PTT via Digirig serial RTS or CAT (`TX1;`/`TX0;`) — v1.0
- ✓ Yaesu FT-891 CAT (band/mode/freq, DATA-U) — v1.0
- ✓ Compose UI: Operate, Spectrum, Log, Settings tabs — v1.0
- ✓ Waterfall, focus mode, even/odd slot chips, distance/km display — v1.0
- ✓ Room-backed logbook with ADIF export — v1.0
- ✓ POTA activator flow (CQ formatting, `MY_SIG`/`MY_SIG_INFO` ADIF) — v1.0
- ✓ License acknowledgment behind first TX (readiness branch, recent) — v1.0
- ✓ Stable signing key + side-by-side `net.ft8vc` (stable) / `net.ft8vc.unstable` install — v1.0

### Active

<!-- Hypotheses for this milestone — derived from .planning/codebase/CONCERNS.md (2026-06-21).
     Scope: 7 numbered recommendations + the unranked items they naturally touch. -->

**HIGH — Architecture**
- [ ] Extract `OperateViewModel` (1,135 lines) into focused controllers: `SettingsBridge`, `DecodeController`, `TxOrchestrator`, `QsoSessionController`, `RigSession` (the roadmap already named in `OperateViewModel.kt` lines 66–81)
- [ ] Replace `Executors` + manual threads + `Thread.sleep()` in decode, CAT, and QSO loops with structured coroutines (`supervisorScope`, `withTimeoutOrNull`, `Dispatchers.IO`, `delay`) — done as part of the same phase as the controller split, not deferred
- [ ] Eliminate `@Volatile` + `synchronized(qsoLock)` race window around `qsoThread` / `qsoRunning` (`AtomicReference` or single lock) as part of the same refactor
- [ ] Unit-test coverage for **every** extracted controller using mocked I/O — hard deliverable, not aspirational

**HIGH — Reliability**
- [ ] CAT operations gain explicit timeouts (e.g., 5 s) and surface "CAT timeout" status; user-visible retry path
- [ ] RF safety: every TX path wrapped in `try-finally` that unconditionally calls `rig.releasePtt()`; capture/playback resources guaranteed closed on exception
- [ ] Native library load gains a version check + visible failure state (no silent "Start CQ" that does nothing)

**MEDIUM — Robustness**
- [ ] Decode list sliding window (cap ~500 rows) with wired-up Clear button — addresses unbounded growth + atomicity concerns
- [ ] USB device disconnect notification (`ACTION_USB_DEVICE_DETACHED` broadcast → snackbar "Digirig disconnected — RX only")
- [ ] Decode-loop failures surface a counter / status indicator instead of failing silently
- [ ] `TxSlotParity` enum used everywhere instead of raw `Int`; convert to `Int` only at I/O boundaries

**LOW — Hygiene & Security**
- [ ] Auto-export logbook to ADIF in app-private storage on a regular cadence (data-loss insurance; cloud backup remains out of scope)
- [ ] Remove `android.permission.INTERNET` from the manifest until NTP/network features actually land
- [ ] `usb_device_filter.xml` reviewed and tightened to Digirig PID/VID only (CP2102 + FT240X)

**Cross-cutting**
- [ ] No regression to RX/TX/CAT behavior on the reference Yaesu FT-891 + Digirig field setup — verified on a real rig before each promotion from `unstable` to `main`

### Out of Scope

- **New rig support beyond FT-891** — different milestone; keep the CAT layer's abstraction but don't widen it here
- **Decoder quality / weak-signal improvements** — DSP and decode tuning belong in their own milestone, not bundled with a refactor
- **POTA workflow expansion** — POTA is already shipping and works; not touched here
- **Cloud / Google Drive logbook backup** — local ADIF auto-export is the deliverable; cloud is a future phase
- **Logbook PIN / biometric encryption** — out of scope this milestone (concerns doc notes it, but it's a separate UX project)
- **Lowering `minSdk` below 28** — current minSdk holds; no API-guard work needed
- **Spectrum performance rework (circular buffer)** — concerns doc marks it "no issue today"; defer until it actually bites
- **Stealing real estate from the main Operate/Spectrum/Log/Settings tabs** — any new UX (timeouts, disconnect notices, decode counter) must surface inline (snackbars, status-bar chips, existing chrome) without adding new top-level screens or crowding the operating UI
- **A v1.1 "feature" point release tied to this milestone** — release shape is rolling on `unstable`; promotion to `main`/stable happens only when the milestone is verified end-to-end on a real rig

## Context

- **Brownfield project.** v1.0 is live on GitHub Releases; `unstable` channel is in active field use. Codebase already mapped: see `.planning/codebase/ARCHITECTURE.md`, `STACK.md`, `STRUCTURE.md`, `CONCERNS.md`, `CONVENTIONS.md`, `TESTING.md`, `INTEGRATIONS.md` (all refreshed 2026-06-21).
- **The concerns audit is the source of truth for this milestone's scope.** See `.planning/codebase/CONCERNS.md` for the full priority list with file paths and line numbers.
- **The refactor target is already documented in code.** `OperateViewModel.kt` lines 66–81 enumerate the five controllers and what each owns — this milestone executes that plan rather than redesigning it.
- **Existing test coverage** lives in `core/src/test/` (QsoMachine, QsoMessages, slot timing, DSP). `OperateViewModel` and `audio/UsbAudio*` are currently untested; this milestone changes that for the controllers extracted from `OperateViewModel`.
- **Recent work** on the `readiness` branch (`cc8c805`, `c53ef77`) tightened the license-acknowledgment flow and dropped dummy-load wording — that hardening continues here.
- **The "vibe-coded" framing matters.** FT8VC is a solo / small-team project — refactor scope must stay legible and reversible. Prefer incremental controller extraction over a big-bang rewrite.

## Constraints

- **Tech stack**: Kotlin + Jetpack Compose + Coroutines for the JVM side; C/C++ `ft8_lib` via JNI for DSP. No new top-level dependencies for this milestone unless they enable a controller seam (e.g., a DI / lifecycle helper).
- **Platform**: Android `minSdk = 28` (Android 9). API guards not required at this level.
- **Hardware fidelity**: The reference setup is **Yaesu FT-891 + Digirig Mobile over USB-C OTG**. Field verification on that rig is the bar before each promotion.
- **Release channel**: Land on `unstable` (`net.ft8vc.unstable`) phase-by-phase; promote to stable (`net.ft8vc` / `main`) only when the milestone is verified end-to-end.
- **Behavior parity**: RX/TX/CAT/QSO behavior must be byte-equivalent to v1.0 on the reference rig. UX deltas are allowed (CAT timeout status, USB disconnect snackbar, decode counter, ADIF auto-export), but they must not crowd or claim main-screen real estate.
- **License gate**: TX stays gated behind license acknowledgment; nothing in the refactor weakens the receive-only default.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Scope = all 7 CONCERNS.md recommendations + nearby unranked items they touch | Half-measures would leave the same code untouched twice; coherent sweep beats two passes | — Pending |
| Refactor + coroutines migration in one phase | Splitting controllers then re-touching them all to swap Executors → coroutines is wasteful and risks double-regression; do it once | — Pending |
| Release shape = rolling on `unstable`, promote to stable when done | Field testing happens on `unstable` anyway; lets each phase get real-rig validation before the next lands | — Pending |
| Unit tests required for every extracted controller (mocked I/O) | Concerns audit explicitly flagged "OperateViewModel not directly testable" as a HIGH issue; refactor without tests just shifts the same untested mass into 5 places | — Pending |
| UX improvements allowed inline; no new top-level screens | The Operate / Spectrum / Log / Settings layout is the product; CAT timeout, disconnect notices, decode counter must use snackbars, status-bar chips, or existing chrome | — Pending |
| Native library version check is in scope (unranked in concerns doc) | Silent "Start CQ" failure is a field-safety issue and trivially fixable when we touch `Ft8Native` for refactor | — Pending |
| Logbook backup = local ADIF auto-export only | Cloud backup is a real product; local ADIF in app-private storage is data-loss insurance and a one-evening change | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-06-21 after initialization*
