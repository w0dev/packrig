# Decode Slot Tint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Operate-tab decode list a subtle per-slot cue by tinting the UTC (first) column cell on EVEN-parity slots, leaving ODD slots dark.

**Architecture:** Add one pure, JVM-testable helper `slotTintAlpha(TxSlotParity): Float` that returns the tint alpha for EVEN and `0f` for ODD. Wire it into the existing `DecodeRowItem` UTC `Text` as a background whose alpha comes from the helper — so ODD rows resolve to a fully transparent background of identical size, keeping every monospace column aligned across parities.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit4 (`org.junit.Assert`).

## Global Constraints

- Tech stack: Kotlin + Jetpack Compose; no new top-level dependency.
- Platform: Android `minSdk = 28`.
- Display-only change: RX/TX/CAT/QSO behavior must remain byte-equivalent on the reference rig (Yaesu FT-891 + Digirig).
- No new feature surface, no new setting/preference, no added main-screen vertical space.
- Neutral grey only — must never compete with the semantic category colors (OWN_TX / PARTNER / MY_CALL fills, OTHER dimming).
- Tinted parity is EVEN (fixed).
- Kotlin Official style, 4-space indent, no wildcard imports, one top-level public type/function focus per file.

---

### Task 1: Pure slot-tint helper

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/ui/operate/SlotTint.kt`
- Test: `app/src/test/java/net/ft8vc/app/ui/operate/SlotTintTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.core.TxSlotParity` (existing enum with `EVEN`, `ODD`).
- Produces:
  - `const val SLOT_TINT_ALPHA: Float` — the alpha for the neutral UTC-cell tint on EVEN slots.
  - `fun slotTintAlpha(parity: TxSlotParity): Float` — returns `SLOT_TINT_ALPHA` for `EVEN`, `0f` for `ODD`. Pure, no Compose/Android dependency.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/ui/operate/SlotTintTest.kt`:

```kotlin
package net.ft8vc.app.ui.operate

import net.ft8vc.core.TxSlotParity
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotTintTest {

    @Test
    fun evenSlotIsTinted() {
        assertEquals(SLOT_TINT_ALPHA, slotTintAlpha(TxSlotParity.EVEN), 0f)
    }

    @Test
    fun oddSlotIsNotTinted() {
        assertEquals(0f, slotTintAlpha(TxSlotParity.ODD), 0f)
    }

    @Test
    fun tintAlphaIsSubtleAndValid() {
        // Neutral, subtle overlay: in-range and low enough not to compete
        // with the semantic category fills (FILL_ALPHA = 0.16f).
        assertEquals(true, SLOT_TINT_ALPHA in 0.04f..0.10f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.SlotTintTest"`
Expected: FAIL — compilation error, `slotTintAlpha` / `SLOT_TINT_ALPHA` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/ui/operate/SlotTint.kt`:

```kotlin
package net.ft8vc.app.ui.operate

import net.ft8vc.core.TxSlotParity

/**
 * Neutral-grey tint alpha for the decode list's UTC cell, keyed to slot parity.
 * EVEN slots are tinted; ODD slots get no tint. Kept low so it never competes
 * with the semantic category fills.
 */
const val SLOT_TINT_ALPHA: Float = 0.07f

/** Tint alpha for [parity]: [SLOT_TINT_ALPHA] on EVEN, 0f (transparent) on ODD. */
fun slotTintAlpha(parity: TxSlotParity): Float =
    if (parity == TxSlotParity.EVEN) SLOT_TINT_ALPHA else 0f
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.SlotTintTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/SlotTint.kt \
        app/src/test/java/net/ft8vc/app/ui/operate/SlotTintTest.kt
git commit -m "feat(ui): slot-parity tint alpha helper for decode list

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Apply the tint to the UTC cell in DecodeRowItem

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` (the UTC `Text` inside `DecodeRowItem`, currently at lines 326–331)

**Interfaces:**
- Consumes: `slotTintAlpha(row.slotParity)` from Task 1; `row.slotParity: TxSlotParity` (existing `DecodeRow` field).
- Produces: no new public surface.

**Alignment note (why no extra padding):** the monospace columns line up because every cell keeps its intrinsic width. We add ONLY a `background` (no size-changing padding), and ODD rows get an alpha of `0f` — a fully transparent background of identical size. So EVEN and ODD rows stay pixel-aligned, and the untinted `UTC` header cell still lines up with the data column.

- [ ] **Step 1: Modify the UTC Text to carry the parity tint background**

In `DecodeRowItem`, replace the existing UTC cell (lines 326–331):

```kotlin
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
```

with:

```kotlin
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(
                    MaterialTheme.colorScheme.onSurface
                        .copy(alpha = slotTintAlpha(row.slotParity)),
                ),
        )
```

No new imports are needed: `background` (line 3), `RoundedCornerShape` (line 18), `clip` (line 31), `Color`/`dp` are already imported, and `slotTintAlpha` is in the same package.

- [ ] **Step 2: Verify it compiles and unit tests still pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.*"`
Expected: PASS (existing operate unit tests plus `SlotTintTest`), no compile errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt
git commit -m "feat(ui): tint decode UTC cell by slot parity

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Compose UI regression check + field verification note

**Files:**
- Reference (do not necessarily modify): `app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelEarlyParityTest.kt` and siblings in that directory.

**Interfaces:** none produced.

- [ ] **Step 1: Confirm existing Compose UI tests still pass on device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.ft8vc.app.ui.operate.*"`
Expected: PASS. These exercise decode-row rendering (follow/scroll/category-color/early-parity); the tint must not regress them.

> NOTE (per project memory): `connectedAndroidTest` uninstalls the app and can wipe the field phone's logbook. If running on the field phone, adb-pull the logbook first. Otherwise run on a throwaway emulator.

- [ ] **Step 2: If no device is available, note it explicitly**

If `connectedDebugAndroidTest` cannot run in this environment, do not claim it passed. Record in the execution report that the JVM unit tests passed and the Compose regression run is pending device verification.

- [ ] **Step 3: Field verification (manual, tracked — not a code step)**

On Yaesu FT-891 + Digirig, confirm:
- EVEN-slot decodes show a faint grey behind the UTC time; ODD slots stay dark.
- The tint reads as slot timing, not a signal category, and does not muddy the OWN_TX / PARTNER / MY_CALL fills.
- Columns stay aligned between EVEN and ODD rows.

This is the milestone's field bar; leave it as an open verification item until eyeballed on the reference rig.

---

## Self-Review

- **Spec coverage:** UTC-cell tint on one parity (Task 2) ✓; EVEN tinted, ODD dark (Task 1) ✓; neutral grey via `onSurface` low alpha (Tasks 1–2) ✓; always-on, no setting (no preference added) ✓; header untinted / alignment preserved (Task 2 alignment note) ✓; pure testable helper (Task 1) ✓; display-only behavior parity (Task 3) ✓; field verification (Task 3) ✓.
- **Placeholder scan:** none — all steps carry concrete code/commands.
- **Type consistency:** `slotTintAlpha`/`SLOT_TINT_ALPHA` names and `Float` types match across Task 1 (defined) and Task 2 (consumed); `TxSlotParity.EVEN/ODD` match the existing `net.ft8vc.core.TxSlotParity` enum.
