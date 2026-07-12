# Decode Color Scheme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WSJT-X-style category-based decode row coloring (own TX, partner, my-call, CQ new/worked variants) with user-configurable colors behind a collapsible settings section.

**Architecture:** A pure `DecodeCategoryResolver` in `core/` classifies each decode row into one of seven fixed-priority categories; `DecodePrefix` glyphs and `DecodeRowItem` colors both derive from it so they can never disagree. A `DecodeColorScheme` (six ARGB ints) persists via DataStore in `SettingsRepository`, flows through `SettingsBridge` → `OperateUiState` → `DecodeListPanel`, and is edited from a collapsible "Decode colors" card in Settings with a curated 12-swatch palette.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), DataStore Preferences, JUnit4, Compose UI test (androidTest).

**Spec:** `docs/superpowers/specs/2026-07-04-decode-colorscheme-design.md` — read it before starting.

## Global Constraints

- No new dependencies (milestone rule). Pure Compose for the palette picker; no HSV picker.
- `core/` stays Android-free: Kotlin stdlib only, no Compose/AndroidX imports.
- One top-level public type per file; 4-space indent; no wildcard imports; Kotlin official style.
- Fixed category priority: `OWN_TX` > `PARTNER` > `MY_CALL` > CQ variants > `OTHER`. Not user-reorderable.
- Fill alpha is a fixed constant `0.16f`; only colors are user-configurable. `OTHER` is not configurable.
- Default ARGB values (verbatim from spec): OWN_TX `0xFFFFB347`, PARTNER `0xFFE63946`, MY_CALL `0xFFE63946`, CQ_NEW `0xFF3DDC97`, CQ_WORKED_OTHER_BAND `0xFF4CC9F0`, CQ_WORKED_THIS_BAND `0xFF9AA0A6`.
- DataStore keys (verbatim): `decode_color_own_tx`, `decode_color_partner`, `decode_color_my_call`, `decode_color_cq_new`, `decode_color_cq_worked_other`, `decode_color_cq_worked_this`.
- The QSO-active chatter dim (0.5f alpha on `OTHER` rows while a QSO is active) must be preserved exactly.
- **Precondition:** `readiness` must be clean of the pending autoscroll/TX-status changes (they touch `DecodeListPanel.kt` / `OperateUiState.kt`). If `git status` shows uncommitted changes to those files, STOP and ask the user before proceeding.
- Test commands: core unit tests `./gradlew :core:testDebugUnitTest`, app unit tests `./gradlew :app:testDebugUnitTest`. Compose androidTests need a connected device/emulator (`./gradlew :app:connectedDebugAndroidTest`); if none is attached, compile them with `./gradlew :app:compileDebugAndroidTestKotlin` and note the deferral in the commit message.
- androidTest method names must stay camelCase (backtick names fail D8 dexing at minSdk 28).

---

### Task 1: `DecodeCategory` enum + `DecodeCategoryResolver` (core)

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/DecodeCategory.kt`
- Create: `core/src/main/java/net/ft8vc/core/DecodeCategoryResolver.kt`
- Test: `core/src/test/java/net/ft8vc/core/DecodeCategoryResolverTest.kt`

**Interfaces:**
- Consumes: `WorkedBefore` (existing enum: `Never`, `ThisBand`, `OtherBand`).
- Produces: `enum class DecodeCategory { OWN_TX, PARTNER, MY_CALL, CQ_NEW, CQ_WORKED_OTHER_BAND, CQ_WORKED_THIS_BAND, OTHER }` and `DecodeCategoryResolver.resolve(isTx: Boolean, isCq: Boolean, isToMe: Boolean, workedBefore: WorkedBefore, qsoActive: Boolean, qsoDx: String?, message: String): DecodeCategory`. Tasks 2, 4, 5 depend on these exact names.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed-priority classification: OWN_TX > PARTNER > MY_CALL > CQ variants > OTHER.
 * See docs/superpowers/specs/2026-07-04-decode-colorscheme-design.md.
 */
class DecodeCategoryResolverTest {

    private fun resolve(
        isTx: Boolean = false,
        isCq: Boolean = false,
        isToMe: Boolean = false,
        workedBefore: WorkedBefore = WorkedBefore.Never,
        qsoActive: Boolean = false,
        qsoDx: String? = null,
        message: String = "K1ABC W1XYZ FN42",
    ): DecodeCategory = DecodeCategoryResolver.resolve(
        isTx = isTx,
        isCq = isCq,
        isToMe = isToMe,
        workedBefore = workedBefore,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
        message = message,
    )

    @Test
    fun ownTxOutranksPartner_txMessageContainsPartnerCall() {
        // A transmitted row's text contains the partner call — must stay OWN_TX.
        val category = resolve(
            isTx = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "K1ABC W0DEV R-07",
        )
        assertEquals(DecodeCategory.OWN_TX, category)
    }

    @Test
    fun partnerReplyMidQsoIsPartner_notMyCall() {
        // Partner reply contains my call too; PARTNER outranks MY_CALL (keeps ▸).
        val category = resolve(
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "W0DEV K1ABC -05",
        )
        assertEquals(DecodeCategory.PARTNER, category)
    }

    @Test
    fun partnerAnsweringAnotherStationIsStillPartner() {
        val category = resolve(
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "N5XYZ K1ABC -12",
        )
        assertEquals(DecodeCategory.PARTNER, category)
    }

    @Test
    fun tailEnderMidQsoIsMyCall() {
        // The original bug: a to-me message mid-QSO must classify strongly,
        // never blend into CQ/chatter styling.
        val category = resolve(
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "W0DEV N5XYZ EM10",
        )
        assertEquals(DecodeCategory.MY_CALL, category)
    }

    @Test
    fun toMeWhileIdleIsMyCall() {
        val category = resolve(isToMe = true, message = "W0DEV K1ABC FN42")
        assertEquals(DecodeCategory.MY_CALL, category)
    }

    @Test
    fun partnerRuleRequiresActiveQso() {
        // qsoDx set but QSO not active (e.g. stale form) — falls through.
        val category = resolve(
            qsoActive = false,
            qsoDx = "K1ABC",
            message = "N5XYZ K1ABC -12",
        )
        assertEquals(DecodeCategory.OTHER, category)
    }

    @Test
    fun cqFromNeverWorkedIsCqNew() {
        val category = resolve(isCq = true, message = "CQ K1ABC FN42")
        assertEquals(DecodeCategory.CQ_NEW, category)
    }

    @Test
    fun cqWorkedThisBand() {
        val category = resolve(
            isCq = true,
            workedBefore = WorkedBefore.ThisBand,
            message = "CQ K1ABC FN42",
        )
        assertEquals(DecodeCategory.CQ_WORKED_THIS_BAND, category)
    }

    @Test
    fun cqWorkedOtherBand() {
        val category = resolve(
            isCq = true,
            workedBefore = WorkedBefore.OtherBand,
            message = "CQ K1ABC FN42",
        )
        assertEquals(DecodeCategory.CQ_WORKED_OTHER_BAND, category)
    }

    @Test
    fun nonCqFromWorkedCallIsOther() {
        // Worked-before categories apply only to CQ rows (spec §1).
        val category = resolve(
            workedBefore = WorkedBefore.ThisBand,
            message = "K1ABC W1XYZ FN42",
        )
        assertEquals(DecodeCategory.OTHER, category)
    }

    @Test
    fun unrelatedChatterIsOther() {
        val category = resolve()
        assertEquals(DecodeCategory.OTHER, category)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.DecodeCategoryResolverTest"`
Expected: FAIL to compile — `Unresolved reference: DecodeCategory` / `DecodeCategoryResolver`.

- [ ] **Step 3: Write the implementation**

`core/src/main/java/net/ft8vc/core/DecodeCategory.kt`:

```kotlin
package net.ft8vc.core

/**
 * Visual category of a decode row, in fixed priority order (first match wins).
 * Resolved by [DecodeCategoryResolver]; drives both the row color treatment
 * and the [DecodePrefix] glyph so the two can never disagree.
 */
enum class DecodeCategory {
    /** A row synthesized from our own transmission. */
    OWN_TX,

    /** Mentions the current QSO partner during an active QSO. */
    PARTNER,

    /** Directed to my callsign — in or out of a QSO (tail-enders included). */
    MY_CALL,

    /** CQ from a station never worked. */
    CQ_NEW,

    /** CQ from a station worked before, but not on the current band. */
    CQ_WORKED_OTHER_BAND,

    /** CQ from a station already worked on the current band. */
    CQ_WORKED_THIS_BAND,

    /** Everything else (band chatter). */
    OTHER,
}
```

`core/src/main/java/net/ft8vc/core/DecodeCategoryResolver.kt`:

```kotlin
package net.ft8vc.core

/**
 * Classifies a decode row into a [DecodeCategory] with fixed priority:
 * OWN_TX > PARTNER > MY_CALL > CQ variants > OTHER.
 *
 * OWN_TX must be checked first: a transmitted row's message text contains
 * both the partner call and my call, so it would otherwise match PARTNER.
 * PARTNER outranks MY_CALL because partner replies also contain my call.
 * Worked-before categories apply only to CQ rows — the decision they serve
 * is "should I answer this CQ?".
 */
object DecodeCategoryResolver {

    fun resolve(
        isTx: Boolean,
        isCq: Boolean,
        isToMe: Boolean,
        workedBefore: WorkedBefore,
        qsoActive: Boolean,
        qsoDx: String?,
        message: String,
    ): DecodeCategory = when {
        isTx -> DecodeCategory.OWN_TX
        qsoActive && qsoDx != null && message.contains(qsoDx) -> DecodeCategory.PARTNER
        isToMe -> DecodeCategory.MY_CALL
        isCq && workedBefore == WorkedBefore.ThisBand -> DecodeCategory.CQ_WORKED_THIS_BAND
        isCq && workedBefore == WorkedBefore.OtherBand -> DecodeCategory.CQ_WORKED_OTHER_BAND
        isCq -> DecodeCategory.CQ_NEW
        else -> DecodeCategory.OTHER
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.DecodeCategoryResolverTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/DecodeCategory.kt \
        core/src/main/java/net/ft8vc/core/DecodeCategoryResolver.kt \
        core/src/test/java/net/ft8vc/core/DecodeCategoryResolverTest.kt
git commit -m "feat(core): DecodeCategory + fixed-priority resolver for decode rows"
```

---

### Task 2: Rebuild `DecodePrefix` on the resolver

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/DecodePrefix.kt`
- Test: `core/src/test/java/net/ft8vc/core/DecodePrefixTest.kt` (existing — one test's expectation changes)

**Interfaces:**
- Consumes: `DecodeCategory`, `DecodeCategoryResolver.resolve(...)` from Task 1.
- Produces: `DecodePrefix.glyphFor(category: DecodeCategory): String` (new — Task 4 uses it). Existing `DecodePrefix.prefixFor(message, isCq, isToMe, qsoActive, qsoDx): String` keeps its signature but is reimplemented as a wrapper.
- **Intended behavior change:** a to-me message mid-QSO from a non-partner station now gets the `→` glyph (was blank). This is the spec's fix, not a regression.

- [ ] **Step 1: Update the existing test for the new behavior and add glyph tests**

In `core/src/test/java/net/ft8vc/core/DecodePrefixTest.kt`, find the test named `toMeSuppressedDuringQso_unlessPartner` (it asserts a to-me, qsoActive, non-partner message gets `""`). Replace that entire test with:

```kotlin
    @Test
    fun toMeKeepsArrowDuringQso_tailEnder() {
        // Spec 2026-07-04: MY_CALL never turns off mid-QSO. A station other
        // than the partner calling me during a QSO keeps the → glyph.
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV N5XYZ EM10",
            isCq = false,
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
        )
        assertEquals(DecodePrefix.TO_ME, prefix)
    }

    @Test
    fun glyphForMapsEveryCategory() {
        assertEquals(DecodePrefix.PARTNER, DecodePrefix.glyphFor(DecodeCategory.PARTNER))
        assertEquals(DecodePrefix.TO_ME, DecodePrefix.glyphFor(DecodeCategory.MY_CALL))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_NEW))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_WORKED_OTHER_BAND))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_WORKED_THIS_BAND))
        assertEquals("", DecodePrefix.glyphFor(DecodeCategory.OWN_TX))
        assertEquals("", DecodePrefix.glyphFor(DecodeCategory.OTHER))
    }
```

Leave every other existing test untouched — they must still pass.

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.DecodePrefixTest"`
Expected: FAIL — `glyphForMapsEveryCategory` does not compile (`Unresolved reference: glyphFor`); after adding a stub, `toMeKeepsArrowDuringQso_tailEnder` fails with `expected:<→ > but was:<>`.

- [ ] **Step 3: Reimplement DecodePrefix on the resolver**

Replace the body of `core/src/main/java/net/ft8vc/core/DecodePrefix.kt` with:

```kotlin
package net.ft8vc.core

/**
 * Decorates decode messages with a single non-color glyph so the row type is
 * visible to operators who cannot rely on color (color-blindness, dim screens).
 *
 * - `●` — CQ (any worked-before state)
 * - `→` — directed to my callsign (in or out of a QSO)
 * - `▸` — current QSO partner during an active QSO
 * - blank — own TX rows and every other decode
 *
 * Glyphs derive from [DecodeCategoryResolver] so prefix and row color can
 * never disagree. Pure function so the same logic can be reused in tests
 * and previews.
 */
object DecodePrefix {

    const val CQ = "● "
    const val TO_ME = "→ "
    const val PARTNER = "▸ "

    fun glyphFor(category: DecodeCategory): String = when (category) {
        DecodeCategory.PARTNER -> PARTNER
        DecodeCategory.MY_CALL -> TO_ME
        DecodeCategory.CQ_NEW,
        DecodeCategory.CQ_WORKED_OTHER_BAND,
        DecodeCategory.CQ_WORKED_THIS_BAND -> CQ
        DecodeCategory.OWN_TX, DecodeCategory.OTHER -> ""
    }

    fun prefixFor(
        message: String,
        isCq: Boolean,
        isToMe: Boolean,
        qsoActive: Boolean,
        qsoDx: String?,
    ): String = glyphFor(
        DecodeCategoryResolver.resolve(
            isTx = false,
            isCq = isCq,
            isToMe = isToMe,
            workedBefore = WorkedBefore.Never,
            qsoActive = qsoActive,
            qsoDx = qsoDx,
            message = message,
        ),
    )
}
```

(`workedBefore = Never` is safe here: all CQ variants map to the same `●` glyph.)

- [ ] **Step 4: Run the full core suite**

Run: `./gradlew :core:testDebugUnitTest`
Expected: PASS. If any other `DecodePrefixTest` case fails, the resolver or glyph mapping is wrong — fix the implementation, not the old tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/DecodePrefix.kt \
        core/src/test/java/net/ft8vc/core/DecodePrefixTest.kt
git commit -m "feat(core): derive DecodePrefix glyphs from DecodeCategoryResolver

Behavior change per spec: to-me rows keep the arrow glyph during an
active QSO (tail-enders) instead of going blank."
```

---

### Task 3: `DecodeColorScheme` + DataStore persistence

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/DecodeColorScheme.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt` (add one field)
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` (keys, read, two setters)
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt` (`SettingsSlice` field + `toSlice()` line)
- Test: `app/src/test/java/net/ft8vc/app/settings/DecodeColorSchemeTest.kt`

**Interfaces:**
- Consumes: `DecodeCategory` from Task 1.
- Produces (Tasks 4–5 rely on these exact names):
  - `data class DecodeColorScheme(ownTx: Int, partner: Int, myCall: Int, cqNew: Int, cqWorkedOtherBand: Int, cqWorkedThisBand: Int)` with `companion object { val DEFAULT: DecodeColorScheme }`
  - `fun colorFor(category: DecodeCategory): Int?` (null for `OTHER`)
  - `fun withColor(category: DecodeCategory, argb: Int): DecodeColorScheme`
  - `StationSettings.decodeColors: DecodeColorScheme` and `SettingsSlice.decodeColors: DecodeColorScheme`
  - `suspend fun SettingsRepository.setDecodeColor(category: DecodeCategory, argb: Int)` and `suspend fun SettingsRepository.resetDecodeColors()`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/net/ft8vc/app/settings/DecodeColorSchemeTest.kt` (JVM unit test — the repo's app unit tests don't use Robolectric, so DataStore itself isn't exercised here; the bridge-propagation test mirrors `SettingsRepositoryEarlyDecodeTest`):

```kotlin
package net.ft8vc.app.settings

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.core.DecodeCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeColorSchemeTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    @Test
    fun defaultsMatchSpec() {
        val d = DecodeColorScheme.DEFAULT
        assertEquals(0xFFFFB347.toInt(), d.ownTx)
        assertEquals(0xFFE63946.toInt(), d.partner)
        assertEquals(0xFFE63946.toInt(), d.myCall)
        assertEquals(0xFF3DDC97.toInt(), d.cqNew)
        assertEquals(0xFF4CC9F0.toInt(), d.cqWorkedOtherBand)
        assertEquals(0xFF9AA0A6.toInt(), d.cqWorkedThisBand)
    }

    @Test
    fun colorForCoversEveryConfigurableCategory() {
        val d = DecodeColorScheme.DEFAULT
        assertEquals(d.ownTx, d.colorFor(DecodeCategory.OWN_TX))
        assertEquals(d.partner, d.colorFor(DecodeCategory.PARTNER))
        assertEquals(d.myCall, d.colorFor(DecodeCategory.MY_CALL))
        assertEquals(d.cqNew, d.colorFor(DecodeCategory.CQ_NEW))
        assertEquals(d.cqWorkedOtherBand, d.colorFor(DecodeCategory.CQ_WORKED_OTHER_BAND))
        assertEquals(d.cqWorkedThisBand, d.colorFor(DecodeCategory.CQ_WORKED_THIS_BAND))
        assertNull(d.colorFor(DecodeCategory.OTHER))
    }

    @Test
    fun withColorUpdatesOnlyTheGivenCategory() {
        val updated = DecodeColorScheme.DEFAULT
            .withColor(DecodeCategory.MY_CALL, 0xFF4CC9F0.toInt())
        assertEquals(0xFF4CC9F0.toInt(), updated.myCall)
        assertEquals(DecodeColorScheme.DEFAULT.partner, updated.partner)
        assertEquals(DecodeColorScheme.DEFAULT.ownTx, updated.ownTx)
        // OTHER is not configurable — a no-op, not a crash.
        assertEquals(updated, updated.withColor(DecodeCategory.OTHER, 0x11223344))
    }

    @Test
    fun stationSettingsDefaultCarriesDefaultScheme() {
        assertEquals(DecodeColorScheme.DEFAULT, StationSettings().decodeColors)
    }

    @Test
    fun sliceCarriesDecodeColors() = runTest {
        val custom = DecodeColorScheme.DEFAULT
            .withColor(DecodeCategory.CQ_NEW, 0xFFFFD166.toInt())
        val flow = MutableStateFlow(StationSettings(decodeColors = custom))
        val repo = mockk<SettingsRepository> { every { settings } returns flow }
        val bridge = SettingsBridge(repo, bridgeScope)
        assertEquals(custom, bridge.slice.value.decodeColors)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.DecodeColorSchemeTest"`
Expected: FAIL to compile — `Unresolved reference: DecodeColorScheme`.

- [ ] **Step 3: Implement**

`app/src/main/java/net/ft8vc/app/settings/DecodeColorScheme.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.core.DecodeCategory

/**
 * User-configurable ARGB colors for decode-row categories. One field per
 * configurable [DecodeCategory]; [DecodeCategory.OTHER] is intentionally not
 * configurable and always renders with the theme default.
 *
 * Defaults follow WSJT-X conventions: red for anything carrying my call,
 * amber for own TX, green for new CQs.
 */
data class DecodeColorScheme(
    val ownTx: Int = DEFAULT_OWN_TX,
    val partner: Int = DEFAULT_PARTNER,
    val myCall: Int = DEFAULT_MY_CALL,
    val cqNew: Int = DEFAULT_CQ_NEW,
    val cqWorkedOtherBand: Int = DEFAULT_CQ_WORKED_OTHER_BAND,
    val cqWorkedThisBand: Int = DEFAULT_CQ_WORKED_THIS_BAND,
) {

    fun colorFor(category: DecodeCategory): Int? = when (category) {
        DecodeCategory.OWN_TX -> ownTx
        DecodeCategory.PARTNER -> partner
        DecodeCategory.MY_CALL -> myCall
        DecodeCategory.CQ_NEW -> cqNew
        DecodeCategory.CQ_WORKED_OTHER_BAND -> cqWorkedOtherBand
        DecodeCategory.CQ_WORKED_THIS_BAND -> cqWorkedThisBand
        DecodeCategory.OTHER -> null
    }

    fun withColor(category: DecodeCategory, argb: Int): DecodeColorScheme = when (category) {
        DecodeCategory.OWN_TX -> copy(ownTx = argb)
        DecodeCategory.PARTNER -> copy(partner = argb)
        DecodeCategory.MY_CALL -> copy(myCall = argb)
        DecodeCategory.CQ_NEW -> copy(cqNew = argb)
        DecodeCategory.CQ_WORKED_OTHER_BAND -> copy(cqWorkedOtherBand = argb)
        DecodeCategory.CQ_WORKED_THIS_BAND -> copy(cqWorkedThisBand = argb)
        DecodeCategory.OTHER -> this
    }

    companion object {
        val DEFAULT_OWN_TX = 0xFFFFB347.toInt()               // Ft8Amber
        val DEFAULT_PARTNER = 0xFFE63946.toInt()              // Ft8Red
        val DEFAULT_MY_CALL = 0xFFE63946.toInt()              // Ft8Red
        val DEFAULT_CQ_NEW = 0xFF3DDC97.toInt()               // Ft8Green
        val DEFAULT_CQ_WORKED_OTHER_BAND = 0xFF4CC9F0.toInt() // cyan
        val DEFAULT_CQ_WORKED_THIS_BAND = 0xFF9AA0A6.toInt()  // muted gray

        val DEFAULT = DecodeColorScheme()
    }
}
```

In `StationSettings.kt`, after the `useDarkTheme` field, add:

```kotlin
    /** User-configurable decode row colors (spec 2026-07-04-decode-colorscheme). */
    val decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
```

In `SettingsRepository.kt`:

1. Inside `private object Keys`, add:

```kotlin
        val DECODE_COLOR_OWN_TX = intPreferencesKey("decode_color_own_tx")
        val DECODE_COLOR_PARTNER = intPreferencesKey("decode_color_partner")
        val DECODE_COLOR_MY_CALL = intPreferencesKey("decode_color_my_call")
        val DECODE_COLOR_CQ_NEW = intPreferencesKey("decode_color_cq_new")
        val DECODE_COLOR_CQ_WORKED_OTHER = intPreferencesKey("decode_color_cq_worked_other")
        val DECODE_COLOR_CQ_WORKED_THIS = intPreferencesKey("decode_color_cq_worked_this")
```

2. In the `settings` flow's `StationSettings(...)` construction, after `lastAdifBackupAtMs = ...`, add:

```kotlin
            decodeColors = DecodeColorScheme(
                ownTx = prefs[Keys.DECODE_COLOR_OWN_TX] ?: DecodeColorScheme.DEFAULT_OWN_TX,
                partner = prefs[Keys.DECODE_COLOR_PARTNER] ?: DecodeColorScheme.DEFAULT_PARTNER,
                myCall = prefs[Keys.DECODE_COLOR_MY_CALL] ?: DecodeColorScheme.DEFAULT_MY_CALL,
                cqNew = prefs[Keys.DECODE_COLOR_CQ_NEW] ?: DecodeColorScheme.DEFAULT_CQ_NEW,
                cqWorkedOtherBand = prefs[Keys.DECODE_COLOR_CQ_WORKED_OTHER]
                    ?: DecodeColorScheme.DEFAULT_CQ_WORKED_OTHER_BAND,
                cqWorkedThisBand = prefs[Keys.DECODE_COLOR_CQ_WORKED_THIS]
                    ?: DecodeColorScheme.DEFAULT_CQ_WORKED_THIS_BAND,
            ),
```

3. After `setLastAdifBackupAtMs`, add the setters (import `net.ft8vc.core.DecodeCategory` and `androidx.datastore.preferences.core.Preferences` is already imported):

```kotlin
    /** Persist one decode-row category color. No-op for the non-configurable OTHER. */
    suspend fun setDecodeColor(category: DecodeCategory, argb: Int) {
        val key = decodeColorKey(category) ?: return
        appContext.settingsDataStore.edit { it[key] = argb }
    }

    /** Remove all decode color overrides — the settings flow falls back to defaults. */
    suspend fun resetDecodeColors() {
        appContext.settingsDataStore.edit { prefs ->
            DecodeCategory.entries.forEach { category ->
                decodeColorKey(category)?.let { prefs.remove(it) }
            }
        }
    }

    private fun decodeColorKey(category: DecodeCategory): Preferences.Key<Int>? =
        when (category) {
            DecodeCategory.OWN_TX -> Keys.DECODE_COLOR_OWN_TX
            DecodeCategory.PARTNER -> Keys.DECODE_COLOR_PARTNER
            DecodeCategory.MY_CALL -> Keys.DECODE_COLOR_MY_CALL
            DecodeCategory.CQ_NEW -> Keys.DECODE_COLOR_CQ_NEW
            DecodeCategory.CQ_WORKED_OTHER_BAND -> Keys.DECODE_COLOR_CQ_WORKED_OTHER
            DecodeCategory.CQ_WORKED_THIS_BAND -> Keys.DECODE_COLOR_CQ_WORKED_THIS
            DecodeCategory.OTHER -> null
        }
```

In `SettingsBridge.kt`: add `val decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,` to `SettingsSlice` (import `net.ft8vc.app.settings.DecodeColorScheme`), and `decodeColors = decodeColors,` inside `StationSettings.toSlice()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.DecodeColorSchemeTest"`
Expected: PASS (5 tests). Then run the full app unit suite to catch any `SettingsSlice`/`StationSettings` constructor call sites broken by the new fields: `./gradlew :app:testDebugUnitTest` — Expected: PASS (the new fields have defaults, so existing call sites should compile unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/DecodeColorScheme.kt \
        app/src/main/java/net/ft8vc/app/settings/StationSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt \
        app/src/test/java/net/ft8vc/app/settings/DecodeColorSchemeTest.kt
git commit -m "feat(app): DecodeColorScheme model persisted via DataStore"
```

---

### Task 4: Category-driven rendering in the decode list

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (one field, Display section)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (one line in the `combine` mapping)
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt` (one argument)
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` (new param + `DecodeRowItem` rewrite)

**Interfaces:**
- Consumes: `DecodeCategory`, `DecodeCategoryResolver.resolve(...)`, `DecodePrefix.glyphFor(...)` (Tasks 1–2); `DecodeColorScheme.colorFor(...)`, `DecodeColorScheme.DEFAULT` (Task 3).
- Produces: `OperateUiState.decodeColors: DecodeColorScheme`; `DecodeListPanel(..., decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT, ...)`. Task 6's UI test relies on the row test tag `decodeRow_<CATEGORY-NAME>` added here.

- [ ] **Step 1: Add the state field and wire it through**

In `OperateUiState.kt`, Display section (after `cq73OnlyFilter`):

```kotlin
    /** User-configurable decode row colors. */
    val decodeColors: net.ft8vc.app.settings.DecodeColorScheme =
        net.ft8vc.app.settings.DecodeColorScheme.DEFAULT,
```

(Use a plain import `net.ft8vc.app.settings.DecodeColorScheme` at the top of the file instead of the qualified name if there is no name clash — there isn't.)

In `OperateViewModel.kt`, inside the `combine` block that builds `OperateUiState`, after `cq73OnlyFilter = settings.cq73OnlyFilter,` add:

```kotlin
                decodeColors = settings.decodeColors,
```

In `OperateScreen.kt`, in the `DecodeListPanel(...)` call, after `qsoActive = state.qsoActive,` add:

```kotlin
                decodeColors = state.decodeColors,
```

- [ ] **Step 2: Rewrite `DecodeRowItem` around the resolver**

In `DecodeListPanel.kt`:

1. Add imports:

```kotlin
import androidx.compose.ui.platform.testTag
import net.ft8vc.app.settings.DecodeColorScheme
import net.ft8vc.core.DecodeCategory
import net.ft8vc.core.DecodeCategoryResolver
```

2. Add the parameter to `DecodeListPanel` (after `qsoActive: Boolean,`):

```kotlin
    decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
```

(The default keeps the existing androidTest call sites compiling unchanged.)

3. In the `items(...)` block, pass it down: `decodeColors = decodeColors,` in the `DecodeRowItem(...)` call.

4. Replace the whole `DecodeRowItem` composable with:

```kotlin
@Composable
private fun DecodeRowItem(
    row: DecodeRow,
    qsoDx: String?,
    qsoActive: Boolean,
    decodeColors: DecodeColorScheme,
    onClick: (() -> Unit)?,
) {
    val isTx = row.source is DecodeRowSource.Tx
    val category = DecodeCategoryResolver.resolve(
        isTx = isTx,
        isCq = row.isCq,
        isToMe = row.isToMe,
        workedBefore = row.workedBefore,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
        message = row.message,
    )
    // Fill categories carry the color as a row background; the rest as text color.
    val filled = when (category) {
        DecodeCategory.OWN_TX, DecodeCategory.PARTNER, DecodeCategory.MY_CALL -> true
        else -> false
    }
    val categoryColor = decodeColors.colorFor(category)?.let { Color(it) }
    val rowBackground = if (filled && categoryColor != null) {
        categoryColor.copy(alpha = FILL_ALPHA)
    } else {
        Color.Transparent
    }
    val dimmed = category == DecodeCategory.OTHER && qsoActive
    val textColor = when {
        filled -> MaterialTheme.colorScheme.onSurface
        categoryColor != null -> categoryColor
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = DecodePrefix.glyphFor(category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(rowBackground)
            .testTag("decodeRow_${category.name}")
            .then(if (onClick != null && !isTx) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "   " else "%+3d".format(row.snr),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "    " else DecodeDistance.label(row.distanceKm),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "%4d".format(row.freqHz),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$prefix${row.message}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Fixed background alpha for filled categories (OWN_TX / PARTNER / MY_CALL). */
private const val FILL_ALPHA = 0.16f
```

5. Delete the now-unused imports if the IDE flags them: `Ft8Amber`, `Ft8Green`, `WorkedBefore` (check `MonitorDecodeFilter` and the chip code first — remove only what is genuinely unused; `DecodePrefix` stays imported for `glyphFor`).

- [ ] **Step 3: Compile and run the app unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Compile the androidTest sources (existing panel tests use the default param)**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL — `DecodeListPanelEarlyParityTest`, `DecodeListPanelScrollOrderTest`, and `DecodeListPanelFollowTest` compile against the new signature via the `decodeColors` default.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt \
        app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt
git commit -m "feat(app): category-driven decode row colors

To-me and partner rows now carry a red background fill that stays on
during an active QSO (WSJT-X 'my call' convention); worked-before
dimming becomes explicit CQ categories scoped to CQ rows."
```

---

### Task 5: Settings UI — collapsible "Decode colors" card

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/DecodeColorSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (add card to Display section)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (two setter functions)

**Interfaces:**
- Consumes: `DecodeColorScheme` (+ `colorFor`), `DecodeCategory` (Tasks 1, 3); `SettingsRepository.setDecodeColor` / `resetDecodeColors` (Task 3).
- Produces: `@Composable fun DecodeColorsSection(scheme: DecodeColorScheme, onPickColor: (DecodeCategory, Int) -> Unit, onReset: () -> Unit)`; `OperateViewModel.setDecodeColor(category, argb)` and `OperateViewModel.resetDecodeColors()`.

- [ ] **Step 1: Create the section composable**

`app/src/main/java/net/ft8vc/app/settings/DecodeColorSettings.kt`:

```kotlin
package net.ft8vc.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.ft8vc.core.DecodeCategory

/**
 * Curated palette for decode-row colors. Chosen for legibility on the dark
 * theme; a fixed set (no free picker) so the operator can't select an
 * unreadable color in the field.
 */
val DECODE_COLOR_PALETTE: List<Int> = listOf(
    0xFFE63946.toInt(), // red (Ft8Red)
    0xFFFF6B6B.toInt(), // coral
    0xFFFFB347.toInt(), // amber (Ft8Amber)
    0xFFFFD166.toInt(), // yellow
    0xFF3DDC97.toInt(), // green (Ft8Green)
    0xFF2EC4B6.toInt(), // teal
    0xFF4CC9F0.toInt(), // cyan
    0xFF6C9DFF.toInt(), // blue
    0xFF9B8CFF.toInt(), // violet
    0xFFE07BE0.toInt(), // magenta
    0xFFFF8FA3.toInt(), // pink
    0xFF9AA0A6.toInt(), // gray
)

private data class CategoryRow(
    val category: DecodeCategory,
    val label: String,
    val description: String,
)

private val CATEGORY_ROWS = listOf(
    CategoryRow(DecodeCategory.MY_CALL, "My call", "Messages directed at your callsign"),
    CategoryRow(DecodeCategory.PARTNER, "QSO partner", "Messages involving your current QSO partner"),
    CategoryRow(DecodeCategory.OWN_TX, "My TX", "Rows you transmitted"),
    CategoryRow(DecodeCategory.CQ_NEW, "CQ — new", "CQ from a station not yet worked"),
    CategoryRow(DecodeCategory.CQ_WORKED_OTHER_BAND, "CQ — worked other band", "CQ from a call worked, but not on this band"),
    CategoryRow(DecodeCategory.CQ_WORKED_THIS_BAND, "CQ — worked this band", "CQ from a call already in the log on this band"),
)

/**
 * Collapsible decode-color editor (collapsed by default). Pattern mirrors the
 * USB diagnostics toggle row in SettingsScreen.
 */
@Composable
fun DecodeColorsSection(
    scheme: DecodeColorScheme,
    onPickColor: (DecodeCategory, Int) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DecodeCategory?>(null) }

    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (expanded) "Hide decode colors" else "Show decode colors",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = if (expanded) " ▴" else " ▾", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            CATEGORY_ROWS.forEach { rowSpec ->
                val argb = scheme.colorFor(rowSpec.category) ?: return@forEach
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = rowSpec.category }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rowSpec.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            rowSpec.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Swatch(color = Color(argb))
                }
            }
            TextButton(onClick = onReset) {
                Text("Reset to defaults", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    editing?.let { category ->
        val label = CATEGORY_ROWS.first { it.category == category }.label
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Color for $label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DECODE_COLOR_PALETTE.chunked(4).forEach { paletteRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            paletteRow.forEach { argb ->
                                Swatch(
                                    color = Color(argb),
                                    size = 36.dp,
                                    onClick = {
                                        onPickColor(category, argb)
                                        editing = null
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Swatch(
    color: Color,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    onClick: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}
```

(Move the `Box` and `Dp` imports to the top of the file per house style — no inline qualified names — when writing the final file: `import androidx.compose.foundation.layout.Box` and `import androidx.compose.ui.unit.Dp`.)

- [ ] **Step 2: Add ViewModel setters**

In `OperateViewModel.kt`, next to the other settings setters (near `setDecodeViewMode`), add (import `net.ft8vc.core.DecodeCategory`):

```kotlin
    fun setDecodeColor(category: DecodeCategory, argb: Int) {
        viewModelScope.launch { settingsRepo.setDecodeColor(category, argb) }
    }

    fun resetDecodeColors() {
        viewModelScope.launch { settingsRepo.resetDecodeColors() }
    }
```

- [ ] **Step 3: Mount in SettingsScreen**

In `SettingsScreen.kt`, inside the `SettingsSection("Display") { ... }` block, after the dark-mode toggle row, add:

```kotlin
                DecodeColorsSection(
                    scheme = state.decodeColors,
                    onPickColor = vm::setDecodeColor,
                    onReset = vm::resetDecodeColors,
                )
```

- [ ] **Step 4: Compile and run the app unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/DecodeColorSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): collapsible decode-color settings with curated palette"
```

---

### Task 6: Compose UI test — the original bug, pinned

**Files:**
- Create: `app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelCategoryColorTest.kt`

**Interfaces:**
- Consumes: `DecodeListPanel` with `decodeColors` default (Task 4), row test tags `decodeRow_<CATEGORY>` (Task 4), `DecodeRow` (existing).

- [ ] **Step 1: Write the test**

Style mirrors `DecodeListPanelEarlyParityTest` (camelCase names, `createComposeRule`):

```kotlin
package net.ft8vc.app.ui.operate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeViewMode
import org.junit.Rule
import org.junit.Test

/**
 * Pins the decode-colorscheme spec's core fix: messages directed at my call
 * keep the strong (filled) treatment DURING an active QSO — partner replies
 * classify PARTNER, tail-enders classify MY_CALL, and neither renders as
 * plain CQ/chatter styling.
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class DecodeListPanelCategoryColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setPanel(rows: List<DecodeRow>) {
        composeTestRule.setContent {
            DecodeListPanel(
                decodes = rows,
                myCall = "W0DEV",
                txToneHz = 1500,
                decodeViewMode = DecodeViewMode.ALL,
                onDecodeViewModeChange = {},
                cq73OnlyFilter = false,
                onCq73OnlyFilterChange = {},
                qsoDx = "K1ABC",
                qsoActive = true,
                canAnswer = false,
                canResume = false,
                onClear = {},
                onAnswerCq = {},
                onResume = {},
            )
        }
    }

    @Test
    fun partnerReplyMidQsoRendersAsPartnerCategory() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 1L, timeUtc = "120015", snr = -5, dtSeconds = 0.2f,
                    freqHz = 1500, message = "W0DEV K1ABC -05",
                    isCq = false, isToMe = true,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_PARTNER", useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("decodeRow_OTHER", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun tailEnderMidQsoRendersAsMyCallCategory() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 2L, timeUtc = "120015", snr = -12, dtSeconds = 0.1f,
                    freqHz = 900, message = "W0DEV N5XYZ EM10",
                    isCq = false, isToMe = true,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_MY_CALL", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun unrelatedChatterMidQsoStaysOther() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 3L, timeUtc = "120015", snr = -3, dtSeconds = 0.0f,
                    freqHz = 2200, message = "N5XYZ W1ABC RR73",
                    isCq = false, isToMe = false,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_OTHER", useUnmergedTree = true)
            .assertCountEquals(1)
    }
}
```

- [ ] **Step 2: Compile; run on device if one is attached**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

Then check for a device: `adb devices` — if a device/emulator is listed, run
`./gradlew :app:connectedDebugAndroidTest --tests "net.ft8vc.app.ui.operate.DecodeListPanelCategoryColorTest"` (if the AGP version rejects `--tests` for connected tests, use `-Pandroid.testInstrumentationRunnerArguments.class=net.ft8vc.app.ui.operate.DecodeListPanelCategoryColorTest`).
Expected: 3 tests PASS. If no device is attached, note the deferral in the commit message and move on — do not claim the tests ran.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelCategoryColorTest.kt
git commit -m "test(app): pin category classification of decode rows mid-QSO"
```

---

### Task 7: Full verification pass

- [ ] **Step 1: Run every JVM test suite**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :data:testDebugUnitTest :rig:testDebugUnitTest :audio:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, zero failures. (Some modules may have no tests — that's fine.)

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke check (device attached only)**

Install and open Settings → Display → "Show decode colors": six rows with swatches; tap one → 12-swatch dialog; pick a color → swatch updates; "Reset to defaults" restores. On the Operate tab with saved decodes (or during live RX), confirm: to-me rows show a red fill, TX rows amber fill, CQ rows green text. If no device is available, record this as an outstanding verification item in the final report — field verification on the FT-891 + Digirig is the milestone bar.

- [ ] **Step 4: Report**

Summarize what ran, what passed, and anything deferred (androidTest without device, manual smoke check). Do not claim verified-on-device unless it was.
