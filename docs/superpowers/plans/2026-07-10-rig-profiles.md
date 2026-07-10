# Rig Profiles (multi-rig Phase 2.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Named, user-saved rig profiles (max 5) replace the "Radio model" dropdown; registry models become presets; three setup-named generic presets (`generic-digirig`, `generic-cat`, `generic-rts`) plus a Test CAT diagnostic let operators run unlisted hardware without an app release.

**Architecture:** `RigRegistry` becomes a preset table (six named models + three generics). A `RigProfile` (rig module, pure data) stores a name, a `presetId`, and nullable knob overrides; profile knobs flow into the app through the *existing* `StationSettings` fields (`radioModelId`, `catBaud`, `catPortOverride`, `pttPreference`) via a derivation step in `SettingsRepository`, so the OperateViewModel settings mirrors and `RigController` stay byte-equivalent for the FT-891 path. `RigProfiles.resolve()` synthesizes a full `RigDescriptor` from a profile — used by the Test CAT probe (draft profiles) and by parity tests. No-CAT support = nullable `protocolFactory` threaded through `SerialRigBackend`/`RigController`.

**Tech Stack:** Kotlin 2.3, Jetpack Compose (Material 3), DataStore Preferences, org.json (Android platform API; `org.json:json` artifact test-only), JUnit4 JVM tests.

**Spec:** `docs/superpowers/specs/2026-07-10-rig-profiles-design.md`

## Global Constraints

- **Behavior parity:** RX/TX/CAT/QSO behavior must be byte-equivalent to the current build for an FT-891 profile created by migration. Never change `SerialRigBackend.readReply()`, the PTT mirror logic, or the descriptor/baud/port mirror structure in `OperateViewModel`.
- **No new top-level dependencies.** `org.json` is an Android platform API at runtime; only `testImplementation("org.json:json:20240303")` may be added (test classpath only).
- **License gate untouched:** nothing in these tasks may touch `LICENSE_ACK`, `TX_ENABLED`, or the receive-only default.
- **UI copy is plain language** (match existing "Enhanced port — CAT (default)" style). No protocol jargon in labels; generic preset display names are exactly: `Digirig with CAT (generic)`, `USB CAT cable / built-in USB (generic)`, `Audio only — no CAT (generic)`.
- **Settings exposure is exactly the spec's form-field table.** Do not add frequency-range, data-mode, or port-index fields beyond it.
- **Threading:** anything that calls `RigController.setDescriptor`/`rebind`/`probe` runs on the CAT dispatcher, never Main (field ANR class, 2026-07-03).
- **Stable ids are load-bearing:** preset ids (`ft891`, …, `generic-digirig`, `generic-cat`, `generic-rts`) and protocol id `yaesu-newcat` are persisted — never rename after this ships.
- Branch: work lands on `unstable`. Kotlin official style, 4-space indent, no semicolons, one top-level type per file.
- Test commands: `./gradlew :rig:test` and `./gradlew :app:testDebugUnitTest` (full sweep: `./gradlew test`).

---

### Task 1: No-CAT seam — nullable `protocolFactory`

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/RigDescriptor.kt`
- Modify: `rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt`
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt:169-172`
- Test: `rig/src/test/java/net/ft8vc/rig/SerialRigBackendNoCatTest.kt`

**Interfaces:**
- Consumes: existing `SerialTransport`, `FakeSerialTransport` (in `rig/src/test/.../fakes/`), `RtsPttStrategy`.
- Produces: `RigDescriptor.protocolFactory: (() -> CatProtocol)?`; `SerialRigBackend(transport, protocol: CatProtocol?, nowMs)` where all CAT methods are null/false-safe and RTS PTT still works with `protocol = null`.

- [ ] **Step 1: Write the failing test**

Create `rig/src/test/java/net/ft8vc/rig/SerialRigBackendNoCatTest.kt`. Look at `SerialRigBackendTest.kt` first and construct `FakeSerialTransport` exactly the way it does (constructor/params may differ from this sketch — keep the assertions):

```kotlin
package net.ft8vc.rig

import net.ft8vc.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backend with no CAT protocol (generic-rts preset) must still key RTS PTT
 *  and answer every CAT call with a fast null/false — no reads, no timeouts. */
class SerialRigBackendNoCatTest {

    private fun noCatBackend(transport: FakeSerialTransport) =
        SerialRigBackend(transport = transport, protocol = null)

    @Test
    fun rtsPttStillWorksWithoutProtocol() {
        val transport = FakeSerialTransport()
        val backend = noCatBackend(transport)
        assertTrue(backend.open())
        backend.keyPtt()
        assertTrue(transport.rtsAsserted)
        backend.releasePtt()
        assertFalse(transport.rtsAsserted)
    }

    @Test
    fun catCallsAreNullSafeAndWriteNothing() {
        val transport = FakeSerialTransport()
        val backend = noCatBackend(transport)
        assertTrue(backend.open())
        assertNull(backend.frequencyHz())
        assertNull(backend.modeLabel())
        assertFalse(backend.setFrequencyHz(14_074_000L))
        assertFalse(backend.setDataMode())
        assertFalse(backend.catPtt(true))
        assertEquals("No CAT", backend.dataModeLabel())
        assertEquals(0, transport.writtenBytes.size)
    }
}
```

(If `FakeSerialTransport` exposes written data / RTS state under different names, use its real API — the self-test file `FakeSerialTransportSelfTest.kt` documents it.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rig:test --tests "net.ft8vc.rig.SerialRigBackendNoCatTest"`
Expected: COMPILE FAILURE — `SerialRigBackend` does not accept `protocol: CatProtocol?`.

- [ ] **Step 3: Implement**

In `RigDescriptor.kt` make the factory nullable and document it:

```kotlin
    /** Builds the CAT protocol, or null for CAT-less presets (generic-rts):
     *  PTT keys via RTS, every CAT read/write is a fast no-op. */
    val protocolFactory: (() -> CatProtocol)?,
```

In `SerialRigBackend.kt` change the constructor parameter to `private val protocol: CatProtocol?` and null-guard every CAT entry point (RTS PTT and open/close are untouched):

```kotlin
    override fun frequencyHz(): Long? {
        val p = protocol ?: return null
        return catExchange(p.readFrequencyCommand(), p.replyTerminator)?.let(p::parseFrequency)
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        val command = protocol?.setFrequencyCommand(hz) ?: return false
        return catWrite(command)
    }

    override fun modeLabel(): String? {
        val p = protocol ?: return null
        return catExchange(p.readModeCommand(), p.replyTerminator)?.let(p::parseModeLabel)
    }

    override fun setDataMode(): Boolean {
        val command = protocol?.setDataModeCommand() ?: return false
        return catWrite(command)
    }

    override fun dataModeLabel(): String = protocol?.dataModeLabel ?: "No CAT"

    override fun catPtt(on: Boolean): Boolean {
        val command = protocol?.pttCommand(on) ?: return false
        val ok = catWrite(command)
        Log.i(TAG, "catPtt(on=$on) sent=$ok")
        return ok
    }
```

`catExchange`/`readReply` currently read `protocol.replyTerminator` directly; pass the terminator in as a parameter instead (as shown above) so the null guard stays at the entry points — do not otherwise change the read loop:

```kotlin
    private fun catExchange(command: ByteArray, terminator: Byte): ByteArray? = synchronized(catLock) { /* body unchanged, readReply(terminator) */ }
    private fun readReply(terminator: Byte): ByteArray? { /* body unchanged, compare against terminator */ }
```

In `RigController.bindIfPermitted()` (line ~171): `protocol = d.protocolFactory?.invoke(),`. The existing call sites `descriptor?.protocolFactory?.invoke()?.dataModeLabel` (RigController:296) and `RigRegistry.byId(it)?.protocolFactory?.invoke()` (app `Ft8Bands.kt:47`) compile unchanged with a nullable factory.

- [ ] **Step 4: Run the rig test suite**

Run: `./gradlew :rig:test`
Expected: ALL PASS (new test + all existing — existing descriptor tests construct non-null factories and must not change).

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/ft8vc/rig/RigDescriptor.kt rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt rig/src/main/java/net/ft8vc/rig/RigController.kt rig/src/test/java/net/ft8vc/rig/SerialRigBackendNoCatTest.kt
git commit -m "feat(rig): nullable CAT protocol — RTS-only backends for CAT-less presets"
```

---

### Task 2: Generic presets + CAT protocol registry

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/YaesuModels.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/CatProtocols.kt`
- Modify: `rig/src/main/java/net/ft8vc/rig/RigRegistry.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/GenericPresetsTest.kt`

**Interfaces:**
- Consumes: `YaesuCat`, `YaesuModelSpec`, `RigDescriptor` (nullable factory from Task 1), `PttMethod`.
- Produces: `YaesuModels.GENERIC: YaesuModelSpec`; `CatProtocols.Entry(id, displayName, factory)`, `CatProtocols.all: List<Entry>`, `CatProtocols.byId(id): Entry?`, id constant `CatProtocols.YAESU_NEWCAT = "yaesu-newcat"`; `RigRegistry` constants `GENERIC_DIGIRIG = "generic-digirig"`, `GENERIC_CAT = "generic-cat"`, `GENERIC_RTS = "generic-rts"`, list `RigRegistry.generics`, helper `RigRegistry.isGeneric(id)`, `RigRegistry.isCatGeneric(id)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPresetsTest {

    @Test
    fun genericDigirigSpeaksYaesuWithRtsPttOnPortZero() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_DIGIRIG)!!
        assertEquals("Digirig with CAT (generic)", d.displayName)
        assertEquals(38_400, d.defaultBaud)
        assertEquals(0, d.catPortIndex)
        assertEquals(PttMethod.RTS, d.defaultPtt)
        assertNotNull(d.protocolFactory)
    }

    @Test
    fun genericCatDefaultsToCatPtt() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_CAT)!!
        assertEquals("USB CAT cable / built-in USB (generic)", d.displayName)
        assertEquals(PttMethod.CAT, d.defaultPtt)
        assertNotNull(d.protocolFactory)
    }

    @Test
    fun genericRtsHasNoCat() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_RTS)!!
        assertEquals("Audio only — no CAT (generic)", d.displayName)
        assertEquals(PttMethod.RTS, d.defaultPtt)
        assertNull(d.protocolFactory)
    }

    @Test
    fun genericYaesuSpecCoversEveryFt8DialPreset() {
        // Wide bounds: 70 cm (432.174 MHz) must be settable so generics offer the full band table.
        val protocol = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory()
        assertNotNull(protocol.setFrequencyCommand(432_174_000L))
        assertNotNull(protocol.setFrequencyCommand(1_840_000L))
    }

    @Test
    fun genericsComeAfterNamedModelsInRegistryOrder() {
        val ids = RigRegistry.all.map { it.id }
        assertTrue(ids.indexOf(RigRegistry.GENERIC_DIGIRIG) > ids.indexOf("ftx1"))
        assertEquals(ids.size - 3, ids.indexOf(RigRegistry.GENERIC_DIGIRIG))
    }

    @Test
    fun isGenericHelpers() {
        assertTrue(RigRegistry.isGeneric(RigRegistry.GENERIC_RTS))
        assertTrue(RigRegistry.isCatGeneric(RigRegistry.GENERIC_CAT))
        assertTrue(RigRegistry.isCatGeneric(RigRegistry.GENERIC_DIGIRIG))
        org.junit.Assert.assertFalse(RigRegistry.isCatGeneric(RigRegistry.GENERIC_RTS))
        org.junit.Assert.assertFalse(RigRegistry.isGeneric("ft891"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rig:test --tests "net.ft8vc.rig.GenericPresetsTest"`
Expected: COMPILE FAILURE — `GENERIC_DIGIRIG`, `CatProtocols` undefined.

- [ ] **Step 3: Implement**

Append to `YaesuModels.kt`:

```kotlin
    /**
     * Generic new-CAT spec for user-configured rigs (the generic presets):
     * widest published family bounds so the band list never filters a preset.
     * Data mode and mode table are family-uniform — never user-edited.
     */
    val GENERIC = YaesuModelSpec(
        name = "Generic Yaesu (new CAT)",
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )
```

(`NEW_CAT_MODES` is currently `private` in that object — it stays private; `GENERIC` lives beside the other specs.)

Create `CatProtocols.kt`:

```kotlin
package net.ft8vc.rig

/**
 * CAT protocol families a generic preset can speak. One entry today; Phase 3
 * (Kenwood) and Phase 4 (Icom CI-V) add entries here — never new presets.
 * Ids are persisted in [RigProfile.catProtocolId]; never rename.
 */
object CatProtocols {

    const val YAESU_NEWCAT = "yaesu-newcat"

    data class Entry(
        val id: String,
        val displayName: String,
        val factory: () -> CatProtocol,
    )

    val all: List<Entry> = listOf(
        Entry(
            id = YAESU_NEWCAT,
            displayName = "Yaesu (modern USB CAT)",
            factory = { YaesuCat(YaesuModels.GENERIC) },
        ),
    )

    fun byId(id: String): Entry? = all.firstOrNull { it.id == id }
}
```

In `RigRegistry.kt`, append the three generics to `all` (after `ftx1`) and add the constants/helpers:

```kotlin
        RigDescriptor(
            id = GENERIC_DIGIRIG,
            displayName = "Digirig with CAT (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_CAT,
            displayName = "USB CAT cable / built-in USB (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_RTS,
            displayName = "Audio only — no CAT (generic)",
            protocolFactory = null,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            transportVerified = false,
        ),
    )

    /** Preset ids for the user-configured generics. Persisted — never rename. */
    const val GENERIC_DIGIRIG = "generic-digirig"
    const val GENERIC_CAT = "generic-cat"
    const val GENERIC_RTS = "generic-rts"

    val generics: List<RigDescriptor> get() = all.filter { isGeneric(it.id) }

    fun isGeneric(id: String): Boolean =
        id == GENERIC_DIGIRIG || id == GENERIC_CAT || id == GENERIC_RTS

    /** Generics whose CAT protocol is a user-facing choice. */
    fun isCatGeneric(id: String): Boolean = id == GENERIC_DIGIRIG || id == GENERIC_CAT

    fun byId(id: String): RigDescriptor? = all.firstOrNull { it.id == id }
```

Also update the `RigRegistry` KDoc first line to mention it is now the preset table for rig profiles (spec 2026-07-10-rig-profiles-design).

- [ ] **Step 4: Run the rig test suite**

Run: `./gradlew :rig:test`
Expected: ALL PASS. If `RigRegistryTest` asserts an exact registry size or id set, extend those assertions for the three generics (that test's intent is "registry entries are well-formed", not "exactly six rigs").

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/ft8vc/rig/YaesuModels.kt rig/src/main/java/net/ft8vc/rig/CatProtocols.kt rig/src/main/java/net/ft8vc/rig/RigRegistry.kt rig/src/test/java/net/ft8vc/rig/GenericPresetsTest.kt rig/src/test/java/net/ft8vc/rig/RigRegistryTest.kt
git commit -m "feat(rig): three setup-named generic presets + CAT protocol registry"
```

---

### Task 3: `RigProfile` + `RigProfiles.resolve()`

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/RigProfile.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/RigProfiles.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/RigProfilesTest.kt`

**Interfaces:**
- Consumes: `RigRegistry` (incl. Task 2 constants), `CatProtocols`, `PttMethod`, `RigDescriptor`.
- Produces:

```kotlin
data class RigProfile(
    val id: String,
    val name: String,
    val presetId: String,
    val catProtocolId: String? = null,
    val baud: Int? = null,
    val catPortIndex: Int? = null,
    val pttMethod: PttMethod? = null,
)

object RigProfiles {
    fun resolve(profile: RigProfile): RigDescriptor?
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RigProfilesTest {

    private fun profile(
        presetId: String,
        catProtocolId: String? = null,
        baud: Int? = null,
        catPortIndex: Int? = null,
        pttMethod: PttMethod? = null,
    ) = RigProfile(
        id = "test-uuid",
        name = "My Rig",
        presetId = presetId,
        catProtocolId = catProtocolId,
        baud = baud,
        catPortIndex = catPortIndex,
        pttMethod = pttMethod,
    )

    @Test
    fun ft891ParityAllNullKnobsEqualsRegistryEntry() {
        // A migrated FT-891 profile must be byte-equivalent to today's registry
        // entry — only identity fields (id, displayName) may differ.
        val resolved = RigProfiles.resolve(profile("ft891"))!!
        val preset = RigRegistry.byId("ft891")!!
        assertEquals(preset.copy(id = "test-uuid", displayName = "My Rig"), resolved)
        assertSame(preset.protocolFactory, resolved.protocolFactory)
    }

    @Test
    fun nonNullKnobsOverridePresetDefaults() {
        val resolved = RigProfiles.resolve(
            profile("ft891", baud = 4_800, catPortIndex = 1, pttMethod = PttMethod.CAT),
        )!!
        assertEquals(4_800, resolved.defaultBaud)
        assertEquals(1, resolved.catPortIndex)
        assertEquals(PttMethod.CAT, resolved.defaultPtt)
    }

    @Test
    fun unknownPresetResolvesNull() {
        assertNull(RigProfiles.resolve(profile("kenwood-ts590")))
    }

    @Test
    fun protocolKnobHonoredOnlyForCatGenerics() {
        val named = RigProfiles.resolve(profile("ft891", catProtocolId = CatProtocols.YAESU_NEWCAT))!!
        assertSame(RigRegistry.byId("ft891")!!.protocolFactory, named.protocolFactory)

        val generic = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_CAT, catProtocolId = CatProtocols.YAESU_NEWCAT),
        )!!
        assertSame(CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory, generic.protocolFactory)
    }

    @Test
    fun genericRtsResolvesWithNoProtocolRegardlessOfKnob() {
        val resolved = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_RTS, catProtocolId = CatProtocols.YAESU_NEWCAT),
        )!!
        assertNull(resolved.protocolFactory)
        assertEquals(PttMethod.RTS, resolved.defaultPtt)
    }

    @Test
    fun unknownProtocolIdFallsBackToPresetFactory() {
        val resolved = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_DIGIRIG, catProtocolId = "icom-civ"),
        )!!
        assertSame(RigRegistry.byId(RigRegistry.GENERIC_DIGIRIG)!!.protocolFactory, resolved.protocolFactory)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rig:test --tests "net.ft8vc.rig.RigProfilesTest"`
Expected: COMPILE FAILURE — `RigProfile`, `RigProfiles` undefined.

- [ ] **Step 3: Implement**

`RigProfile.kt`:

```kotlin
package net.ft8vc.rig

/**
 * One user-saved rig configuration (spec 2026-07-10-rig-profiles-design):
 * a preset choice plus nullable knob overrides. Null = use the preset default.
 * Persisted as JSON in app settings; field names there mirror these.
 *
 * @param id stable UUID string (selection + editing key; never reused).
 * @param presetId a [RigRegistry] id — named model or generic.
 * @param catProtocolId a [CatProtocols] id; honored only for CAT generics.
 */
data class RigProfile(
    val id: String,
    val name: String,
    val presetId: String,
    val catProtocolId: String? = null,
    val baud: Int? = null,
    val catPortIndex: Int? = null,
    val pttMethod: PttMethod? = null,
)
```

`RigProfiles.kt`:

```kotlin
package net.ft8vc.rig

/** Profile → descriptor resolution: preset defaults with profile knobs on top. */
object RigProfiles {

    /**
     * Synthesize the [RigDescriptor] a profile describes, or null when the
     * preset is unknown (e.g. profile written by a newer app version). The
     * result carries the profile's id/name; all other fields come from the
     * preset unless the profile overrides them. [RigProfile.catProtocolId]
     * is honored only for CAT generics ([RigRegistry.isCatGeneric]).
     */
    fun resolve(profile: RigProfile): RigDescriptor? {
        val preset = RigRegistry.byId(profile.presetId) ?: return null
        val protocolOverride =
            if (RigRegistry.isCatGeneric(preset.id)) {
                profile.catProtocolId?.let { CatProtocols.byId(it)?.factory }
            } else {
                null
            }
        return preset.copy(
            id = profile.id,
            displayName = profile.name,
            protocolFactory = protocolOverride ?: preset.protocolFactory,
            defaultBaud = profile.baud ?: preset.defaultBaud,
            catPortIndex = profile.catPortIndex ?: preset.catPortIndex,
            defaultPtt = profile.pttMethod ?: preset.defaultPtt,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :rig:test`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/ft8vc/rig/RigProfile.kt rig/src/main/java/net/ft8vc/rig/RigProfiles.kt rig/src/test/java/net/ft8vc/rig/RigProfilesTest.kt
git commit -m "feat(rig): RigProfile + resolve() — presets-over-knobs core"
```

---

### Task 4: Profile JSON codec + list operations

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/RigProfileJson.kt`
- Create: `app/src/main/java/net/ft8vc/app/settings/RigProfileList.kt`
- Modify: `app/build.gradle.kts:107-113` (test dependency)
- Test: `app/src/test/java/net/ft8vc/app/settings/RigProfileJsonTest.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/RigProfileListTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.rig.RigProfile`, `net.ft8vc.rig.PttMethod`.
- Produces:

```kotlin
object RigProfileJson {
    fun encode(profiles: List<RigProfile>): String  // {"v":1,"profiles":[...]}
    fun decode(raw: String?): List<RigProfile>      // emptyList on null/corrupt
}

object RigProfileList {
    const val MAX = 5
    fun nameError(name: String, profiles: List<RigProfile>, selfId: String?): String?
    fun upsert(profiles: List<RigProfile>, profile: RigProfile): List<RigProfile>?  // null = rejected
    fun delete(profiles: List<RigProfile>, id: String): List<RigProfile>
    fun selectionAfterDelete(remaining: List<RigProfile>, deletedId: String, currentSelection: String?): String?
}
```

- [ ] **Step 1: Add the test-only org.json artifact**

In `app/build.gradle.kts` after line 110 (`testImplementation(libs.kotlinx.coroutines.test)`):

```kotlin
    // Real org.json for JVM unit tests (android.jar ships throw-only stubs).
    // Runtime uses the Android platform copy — this is test classpath only.
    testImplementation("org.json:json:20240303")
```

- [ ] **Step 2: Write the failing tests**

`RigProfileJsonTest.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RigProfileJsonTest {

    private val full = RigProfile(
        id = "a1b2",
        name = "Home FT-891",
        presetId = "ft891",
        catProtocolId = "yaesu-newcat",
        baud = 4_800,
        catPortIndex = 1,
        pttMethod = PttMethod.CAT,
    )
    private val sparse = RigProfile(id = "c3d4", name = "SOTA — no CAT", presetId = "generic-rts")

    @Test
    fun roundTripPreservesEveryFieldAndOrder() {
        assertEquals(listOf(full, sparse), RigProfileJson.decode(RigProfileJson.encode(listOf(full, sparse))))
    }

    @Test
    fun nullFieldsSurviveRoundTrip() {
        val decoded = RigProfileJson.decode(RigProfileJson.encode(listOf(sparse))).single()
        assertEquals(null, decoded.catProtocolId)
        assertEquals(null, decoded.baud)
        assertEquals(null, decoded.catPortIndex)
        assertEquals(null, decoded.pttMethod)
    }

    @Test
    fun corruptInputDecodesToEmpty() {
        assertTrue(RigProfileJson.decode(null).isEmpty())
        assertTrue(RigProfileJson.decode("").isEmpty())
        assertTrue(RigProfileJson.decode("not json").isEmpty())
        assertTrue(RigProfileJson.decode("""{"v":1,"profiles":[{"name":"missing id"}]}""").isEmpty())
        assertTrue(RigProfileJson.decode("""{"v":99,"profiles":[]}""").isEmpty())
    }

    @Test
    fun unknownPttMethodStringDecodesAsNullNotCrash() {
        val raw = """{"v":1,"profiles":[{"id":"x","name":"n","preset":"ft891","ptt":"LASER"}]}"""
        assertEquals(null, RigProfileJson.decode(raw).single().pttMethod)
    }

    @Test
    fun namesWithQuotesAndUnicodeRoundTrip() {
        val tricky = sparse.copy(name = """Bob's "portable" — 891 ✓""")
        assertEquals(tricky, RigProfileJson.decode(RigProfileJson.encode(listOf(tricky))).single())
    }
}
```

`RigProfileListTest.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.RigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RigProfileListTest {

    private fun p(id: String, name: String) = RigProfile(id = id, name = name, presetId = "ft891")
    private val four = listOf(p("1", "Alpha"), p("2", "Bravo"), p("3", "Charlie"), p("4", "Delta"))

    @Test
    fun upsertAddsUntilCapThenRejects() {
        val five = RigProfileList.upsert(four, p("5", "Echo"))!!
        assertEquals(5, five.size)
        assertNull(RigProfileList.upsert(five, p("6", "Foxtrot")))
    }

    @Test
    fun upsertReplacesExistingIdEvenAtCap() {
        val five = four + p("5", "Echo")
        val edited = RigProfileList.upsert(five, p("5", "Echo II"))!!
        assertEquals(5, edited.size)
        assertEquals("Echo II", edited.last().name)
    }

    @Test
    fun duplicateNameRejectedCaseInsensitiveExceptSelf() {
        assertNotNull(RigProfileList.nameError("alpha", four, selfId = "9"))
        assertNull(RigProfileList.nameError("alpha", four, selfId = "1"))
        assertNotNull(RigProfileList.nameError("  ", four, selfId = null))
        assertNull(RigProfileList.upsert(four, p("9", "ALPHA")))
    }

    @Test
    fun deleteRemovesById() {
        assertEquals(listOf("1", "3", "4"), RigProfileList.delete(four, "2").map { it.id })
    }

    @Test
    fun selectionFallsBackToFirstRemainingThenNull() {
        val remaining = RigProfileList.delete(four, "1")
        assertEquals("2", RigProfileList.selectionAfterDelete(remaining, deletedId = "1", currentSelection = "1"))
        assertEquals("3", RigProfileList.selectionAfterDelete(remaining, deletedId = "9", currentSelection = "3"))
        assertNull(RigProfileList.selectionAfterDelete(emptyList(), deletedId = "1", currentSelection = "1"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigProfileJsonTest" --tests "net.ft8vc.app.settings.RigProfileListTest"`
Expected: COMPILE FAILURE — `RigProfileJson`, `RigProfileList` undefined.

- [ ] **Step 4: Implement**

`RigProfileJson.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for the RIG_PROFILES DataStore value: `{"v":1,"profiles":[...]}`.
 * Decode is fail-closed to an empty list — a corrupt value must never crash
 * settings; the operator just re-adds their rig. Field names are persisted;
 * never rename ("id","name","preset","protocol","baud","port","ptt").
 */
object RigProfileJson {

    private const val VERSION = 1

    fun encode(profiles: List<RigProfile>): String {
        val array = JSONArray()
        profiles.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("preset", p.presetId)
            p.catProtocolId?.let { o.put("protocol", it) }
            p.baud?.let { o.put("baud", it) }
            p.catPortIndex?.let { o.put("port", it) }
            p.pttMethod?.let { o.put("ptt", it.name) }
            array.put(o)
        }
        return JSONObject().put("v", VERSION).put("profiles", array).toString()
    }

    fun decode(raw: String?): List<RigProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.getInt("v") != VERSION) return emptyList()
            val array = root.getJSONArray("profiles")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RigProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    presetId = o.getString("preset"),
                    catProtocolId = o.optStringOrNull("protocol"),
                    baud = if (o.has("baud")) o.getInt("baud") else null,
                    catPortIndex = if (o.has("port")) o.getInt("port") else null,
                    pttMethod = o.optStringOrNull("ptt")?.let { name ->
                        PttMethod.entries.firstOrNull { it.name == name }
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
```

`RigProfileList.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.RigProfile

/** Pure list rules for saved rig profiles: cap of 5, unique names, deletion fallback. */
object RigProfileList {

    const val MAX = 5

    /** Save-blocking validation message for [name], or null when acceptable. */
    fun nameError(name: String, profiles: List<RigProfile>, selfId: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name is required"
        val clash = profiles.any { it.id != selfId && it.name.equals(trimmed, ignoreCase = true) }
        return if (clash) "A rig with this name already exists" else null
    }

    /** Add or replace by id. Null when the cap or name rule rejects the save. */
    fun upsert(profiles: List<RigProfile>, profile: RigProfile): List<RigProfile>? {
        if (nameError(profile.name, profiles, profile.id) != null) return null
        val index = profiles.indexOfFirst { it.id == profile.id }
        return when {
            index >= 0 -> profiles.toMutableList().also { it[index] = profile }
            profiles.size >= MAX -> null
            else -> profiles + profile
        }
    }

    fun delete(profiles: List<RigProfile>, id: String): List<RigProfile> =
        profiles.filterNot { it.id == id }

    /** New selection after a delete: keep current if it survives, else first remaining. */
    fun selectionAfterDelete(
        remaining: List<RigProfile>,
        deletedId: String,
        currentSelection: String?,
    ): String? =
        if (currentSelection != null && currentSelection != deletedId &&
            remaining.any { it.id == currentSelection }
        ) {
            currentSelection
        } else {
            remaining.firstOrNull()?.id
        }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigProfileJsonTest" --tests "net.ft8vc.app.settings.RigProfileListTest"`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/net/ft8vc/app/settings/RigProfileJson.kt app/src/main/java/net/ft8vc/app/settings/RigProfileList.kt app/src/test/java/net/ft8vc/app/settings/RigProfileJsonTest.kt app/src/test/java/net/ft8vc/app/settings/RigProfileListTest.kt
git commit -m "feat(app): rig profile JSON codec + list rules (cap 5, unique names)"
```

---

### Task 5: Settings plumbing — profiles in `StationSettings`, CRUD, migration

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/StationSettingsProfileTest.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/RigProfileMigrationTest.kt`

**Interfaces:**
- Consumes: Task 3 `RigProfile`, Task 4 `RigProfileJson`/`RigProfileList`, existing `PttPreference`, `PttMethod.toPreference()` (currently top-level in `OperateViewModel.kt:64-69` — **move it** into `StationSettings.kt`, same package, and add the reverse mapping).
- Produces:

```kotlin
// StationSettings.kt (same file as the data class)
fun PttMethod.toPreference(): PttPreference
fun PttPreference.toPttMethod(): PttMethod

// StationSettings gains:
val rigProfiles: List<RigProfile> = emptyList()
val selectedRigProfileId: String? = null
val selectedRigProfile: RigProfile? get()   // lookup by id
fun StationSettings.withRigProfileApplied(): StationSettings

// SettingsRepository gains:
suspend fun saveRigProfile(profile: RigProfile): Boolean
suspend fun deleteRigProfile(id: String)
suspend fun selectRigProfile(id: String)
suspend fun migrateLegacyRadioModel()
// companion:
fun buildMigratedProfile(modelId: String, baud: Int?, catPortOverride: Int?, pttPreference: PttPreference?): RigProfile
```

- [ ] **Step 1: Write the failing tests**

`StationSettingsProfileTest.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationSettingsProfileTest {

    private val profile = RigProfile(
        id = "u1", name = "Home 891", presetId = "ft891",
        baud = 4_800, catPortIndex = 1, pttMethod = PttMethod.CAT,
    )

    @Test
    fun noSelectionLeavesLegacyFieldsUntouched() {
        val base = StationSettings(radioModelId = "ftdx10", catBaud = 19_200)
        assertEquals(base, base.withRigProfileApplied())
    }

    @Test
    fun selectedProfileDrivesTheDerivedRigFields() {
        val s = StationSettings(
            rigProfiles = listOf(profile),
            selectedRigProfileId = "u1",
        ).withRigProfileApplied()
        assertEquals("ft891", s.radioModelId)
        assertEquals(4_800, s.catBaud)
        assertEquals(1, s.catPortOverride)
        assertEquals(PttPreference.CAT, s.pttPreference)
    }

    @Test
    fun nullKnobsFallToPresetDefaults() {
        val sparse = RigProfile(id = "u2", name = "Sparse", presetId = "ft891")
        val s = StationSettings(
            rigProfiles = listOf(sparse),
            selectedRigProfileId = "u2",
        ).withRigProfileApplied()
        assertEquals(RigRegistry.byId("ft891")!!.defaultBaud, s.catBaud)
        assertNull(s.catPortOverride)
        assertEquals(PttPreference.AUTO, s.pttPreference) // FT-891 defaultPtt = AUTO
    }

    @Test
    fun unknownPresetStillExposesPresetIdSoUiCanFlagIt() {
        val orphan = RigProfile(id = "u3", name = "Future rig", presetId = "kenwood-ts590")
        val s = StationSettings(
            rigProfiles = listOf(orphan),
            selectedRigProfileId = "u3",
        ).withRigProfileApplied()
        assertEquals("kenwood-ts590", s.radioModelId) // RigRegistry.byId → null → NoModel downstream
    }

    @Test
    fun pttMappingsAreInverse() {
        PttMethod.entries.forEach { assertEquals(it, it.toPreference().toPttMethod()) }
    }
}
```

`RigProfileMigrationTest.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigProfileMigrationTest {

    @Test
    fun ft891MigrationCarriesLegacyKnobs() {
        val p = SettingsRepository.buildMigratedProfile(
            modelId = "ft891", baud = 4_800, catPortOverride = 1, pttPreference = PttPreference.RTS,
        )
        assertEquals("Yaesu FT-891", p.name)
        assertEquals("ft891", p.presetId)
        assertEquals(4_800, p.baud)
        assertEquals(1, p.catPortIndex)
        assertEquals(PttMethod.RTS, p.pttMethod)
        assertNull(p.catProtocolId)
        assertTrue(p.id.isNotBlank())
    }

    @Test
    fun freshDefaultsMigrateToAllNullKnobs() {
        // Parity path: legacy install that never touched baud/PTT → profile with
        // null knobs → RigProfiles.resolve() == the registry entry (RigProfilesTest).
        val p = SettingsRepository.buildMigratedProfile("ft891", null, null, null)
        assertNull(p.baud)
        assertNull(p.catPortIndex)
        assertNull(p.pttMethod)
    }

    @Test
    fun unknownLegacyModelKeepsIdAsNameAndPreset() {
        val p = SettingsRepository.buildMigratedProfile("mystery9000", null, null, null)
        assertEquals("mystery9000", p.name)
        assertEquals("mystery9000", p.presetId)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.StationSettingsProfileTest" --tests "net.ft8vc.app.settings.RigProfileMigrationTest"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

In `StationSettings.kt`:

1. Move `PttMethod.toPreference()` here from `OperateViewModel.kt:64-69` (delete it there; `OperateViewModel` already imports this package’s types — fix imports as needed) and add the inverse:

```kotlin
/** Map a descriptor's default PTT method onto the app's PTT preference. */
fun PttMethod.toPreference(): PttPreference = when (this) {
    PttMethod.AUTO -> PttPreference.AUTO
    PttMethod.CAT -> PttPreference.CAT
    PttMethod.RTS -> PttPreference.RTS
}

fun PttPreference.toPttMethod(): PttMethod = when (this) {
    PttPreference.AUTO -> PttMethod.AUTO
    PttPreference.CAT -> PttMethod.CAT
    PttPreference.RTS -> PttMethod.RTS
}
```

2. Add to the `StationSettings` data class (after `catPortOverride`):

```kotlin
    /** Saved rig profiles (spec 2026-07-10-rig-profiles-design). Max 5 — see RigProfileList. */
    val rigProfiles: List<RigProfile> = emptyList(),
    /** Selected profile id; null until the operator adds/selects a rig. */
    val selectedRigProfileId: String? = null,
```

plus, inside the class body:

```kotlin
    val selectedRigProfile: RigProfile? get() = rigProfiles.firstOrNull { it.id == selectedRigProfileId }
```

3. Add the derivation (top-level in the same file):

```kotlin
/**
 * Project the selected rig profile onto the legacy rig fields so every
 * downstream consumer (OperateViewModel mirrors, RigController) is untouched:
 * radioModelId = the profile's preset, catBaud/catPortOverride/pttPreference =
 * profile knobs with preset-default fallback. No selection = passthrough
 * (pre-migration behavior).
 */
fun StationSettings.withRigProfileApplied(): StationSettings {
    val profile = selectedRigProfile ?: return this
    val preset = RigRegistry.byId(profile.presetId)
    return copy(
        radioModelId = profile.presetId,
        catBaud = SettingsRepository.coerceCatBaud(
            profile.baud ?: preset?.defaultBaud ?: catBaud,
        ),
        catPortOverride = profile.catPortIndex,
        pttPreference = (profile.pttMethod ?: preset?.defaultPtt)?.toPreference() ?: pttPreference,
    )
}
```

(Imports: `net.ft8vc.rig.PttMethod`, `net.ft8vc.rig.RigProfile`, `net.ft8vc.rig.RigRegistry`.)

In `SettingsRepository.kt`:

1. Keys (in `private object Keys`):

```kotlin
        val RIG_PROFILES = stringPreferencesKey("rig_profiles")
        val SELECTED_RIG_PROFILE = stringPreferencesKey("selected_rig_profile")
```

2. In the `settings` flow map, populate the new fields and apply the derivation — change the tail of the `StationSettings(...)` construction to:

```kotlin
            rigProfiles = RigProfileJson.decode(prefs[Keys.RIG_PROFILES]),
            selectedRigProfileId = prefs[Keys.SELECTED_RIG_PROFILE],
        ).withRigProfileApplied()
```

3. CRUD + migration:

```kotlin
    /** Add or update a profile. First saved profile auto-selects. False = rejected (cap/name). */
    suspend fun saveRigProfile(profile: RigProfile): Boolean {
        var saved = false
        appContext.settingsDataStore.edit { prefs ->
            val current = RigProfileJson.decode(prefs[Keys.RIG_PROFILES])
            val updated = RigProfileList.upsert(current, profile) ?: return@edit
            prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(updated)
            if (prefs[Keys.SELECTED_RIG_PROFILE] == null) {
                prefs[Keys.SELECTED_RIG_PROFILE] = profile.id
            }
            saved = true
        }
        return saved
    }

    /** Delete a profile; selection falls back to the first remaining profile. */
    suspend fun deleteRigProfile(id: String) {
        appContext.settingsDataStore.edit { prefs ->
            val remaining = RigProfileList.delete(RigProfileJson.decode(prefs[Keys.RIG_PROFILES]), id)
            prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(remaining)
            val selection = RigProfileList.selectionAfterDelete(
                remaining, deletedId = id, currentSelection = prefs[Keys.SELECTED_RIG_PROFILE],
            )
            if (selection == null) {
                prefs.remove(Keys.SELECTED_RIG_PROFILE)
            } else {
                prefs[Keys.SELECTED_RIG_PROFILE] = selection
            }
        }
    }

    suspend fun selectRigProfile(id: String) {
        appContext.settingsDataStore.edit { prefs ->
            if (RigProfileJson.decode(prefs[Keys.RIG_PROFILES]).any { it.id == id }) {
                prefs[Keys.SELECTED_RIG_PROFILE] = id
            }
        }
    }

    /**
     * One-time upgrade: turn the legacy RADIO_MODEL selection into the first
     * saved profile, carrying the legacy baud/port/PTT knobs so behavior is
     * byte-identical (spec: parity regression + field gate 1). Legacy knob
     * keys stay as pre-migration fallback values; only RADIO_MODEL is cleared.
     */
    suspend fun migrateLegacyRadioModel() {
        appContext.settingsDataStore.edit { prefs ->
            val legacy = prefs[Keys.RADIO_MODEL] ?: return@edit
            if (prefs[Keys.RIG_PROFILES] == null) {
                val profile = buildMigratedProfile(
                    modelId = legacy,
                    baud = prefs[Keys.CAT_BAUD],
                    catPortOverride = prefs[Keys.CAT_PORT_OVERRIDE],
                    pttPreference = prefs[Keys.PTT_PREFERENCE]?.let { name ->
                        PttPreference.entries.firstOrNull { it.name == name }
                    },
                )
                prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(listOf(profile))
                prefs[Keys.SELECTED_RIG_PROFILE] = profile.id
            }
            prefs.remove(Keys.RADIO_MODEL)
        }
    }
```

and in `companion object`:

```kotlin
        /** Pure migration mapping (unit-tested; the DataStore edit is device-verified). */
        fun buildMigratedProfile(
            modelId: String,
            baud: Int?,
            catPortOverride: Int?,
            pttPreference: PttPreference?,
        ): RigProfile = RigProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = RigRegistry.byId(modelId)?.displayName ?: modelId,
            presetId = modelId,
            catProtocolId = null,
            baud = baud,
            catPortIndex = catPortOverride,
            pttMethod = pttPreference?.toPttMethod(),
        )
```

(Imports: `net.ft8vc.rig.RigProfile`, `net.ft8vc.rig.RigRegistry`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.*"`
Expected: ALL PASS, including the pre-existing settings tests (`StationSettingsDefaultsTest`, `PttMethodMappingTest` — if `PttMethodMappingTest` imported the mapping from the ViewModel file, its import now resolves to the same package; fix the import only, not the assertions).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/StationSettings.kt app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/test/java/net/ft8vc/app/settings/StationSettingsProfileTest.kt app/src/test/java/net/ft8vc/app/settings/RigProfileMigrationTest.kt
git commit -m "feat(app): rig profiles in settings — persistence, derivation, legacy migration"
```

---

### Task 6: OperateViewModel + UI state wiring

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (init block; settings→state mapping ~line 253-261; replace `setRadioModel` at ~line 607-615)
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (fields near `radioModelId` at line 131)
- Test: `app/src/test/java/net/ft8vc/app/OperateUiStateRigProfileTest.kt`

**Interfaces:**
- Consumes: Task 5 repository CRUD + `migrateLegacyRadioModel()`, `StationSettings.rigProfiles`/`selectedRigProfileId`, `RigRegistry`.
- Produces (used by Task 9 UI):

```kotlin
// OperateUiState:
val rigProfiles: List<RigProfile> = emptyList()
val selectedRigProfileId: String? = null
val rigHasCat: Boolean = true
val selectedRigProfileName: String? get()   // computed from the two above
// OperateViewModel:
fun saveRigProfile(profile: RigProfile, onRejected: () -> Unit = {})
fun deleteRigProfile(id: String)
fun selectRigProfile(id: String)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.app

import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperateUiStateRigProfileTest {

    @Test
    fun selectedProfileNameResolvesFromList() {
        val state = OperateUiState(
            rigProfiles = listOf(RigProfile(id = "u1", name = "Home 891", presetId = "ft891")),
            selectedRigProfileId = "u1",
        )
        assertEquals("Home 891", state.selectedRigProfileName)
    }

    @Test
    fun rigHasCatComputation() {
        assertTrue(OperateUiState(radioModelId = "ft891").computeRigHasCat())
        assertFalse(OperateUiState(radioModelId = RigRegistry.GENERIC_RTS).computeRigHasCat())
        assertTrue(OperateUiState(radioModelId = null).computeRigHasCat())
        assertTrue(OperateUiState(radioModelId = "unknown-model").computeRigHasCat())
    }
}
```

(`computeRigHasCat()` is the helper the state-mapping block uses to populate `rigHasCat`; keeping it on the state class makes it JVM-testable.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.OperateUiStateRigProfileTest"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

`OperateUiState.kt` — next to `radioModelId` (line ~131):

```kotlin
    /** Saved rig profiles + selection (settings mirror; drives the My rigs UI). */
    val rigProfiles: List<net.ft8vc.rig.RigProfile> = emptyList(),
    val selectedRigProfileId: String? = null,
    /** False when the selected preset has no CAT (generic-rts): dial is manual. */
    val rigHasCat: Boolean = true,
```

and in the class body:

```kotlin
    val selectedRigProfileName: String?
        get() = rigProfiles.firstOrNull { it.id == selectedRigProfileId }?.name

    /** True unless the selected preset is CAT-less. Unknown/absent model = true (legacy behavior). */
    fun computeRigHasCat(): Boolean =
        radioModelId?.let { id ->
            net.ft8vc.rig.RigRegistry.byId(id)?.let { it.protocolFactory != null } ?: true
        } ?: true
```

(Use proper imports rather than fully-qualified names if `OperateUiState.kt` already imports from `net.ft8vc.rig` — check the file head.)

`OperateViewModel.kt`:

1. In `init` (before the settings collection launch):

```kotlin
        viewModelScope.launch { settingsRepo.migrateLegacyRadioModel() }
```

2. In the settings→state mapping block (where `radioModelId = settings.radioModelId` is set at ~line 260), add:

```kotlin
                rigProfiles = settings.rigProfiles,
                selectedRigProfileId = settings.selectedRigProfileId,
```

then, immediately after that state is built, populate `rigHasCat` via the helper — if the block constructs the state object directly, add `rigHasCat` by building the object and copying: `.let { it.copy(rigHasCat = it.computeRigHasCat()) }` (match the surrounding style; the mirrors below the mapping stay **untouched** — profile knobs arrive through the derived `radioModelId`/`catBaud`/`catPortOverride`/`pttPreference` exactly as before).

3. Replace `setRadioModel` (line ~607-615) with profile CRUD (delete `setRadioModel`, `setCatBaud`, `setPttPreference`, and `setCatPortOverride` remain — they still back the legacy fallback and tests; only `setRadioModel` goes):

```kotlin
    /** Persist a new/edited rig profile. [onRejected] fires when the cap or name rule blocks it. */
    fun saveRigProfile(profile: RigProfile, onRejected: () -> Unit = {}) {
        viewModelScope.launch {
            if (!settingsRepo.saveRigProfile(profile)) onRejected()
        }
    }

    fun deleteRigProfile(id: String) {
        viewModelScope.launch { settingsRepo.deleteRigProfile(id) }
    }

    fun selectRigProfile(id: String) {
        viewModelScope.launch { settingsRepo.selectRigProfile(id) }
    }
```

(Import `net.ft8vc.rig.RigProfile`. `SettingsScreen` still calls `vm::setRadioModel` — it won't compile until Task 9; if you need the tree green before then, leave a deprecated one-line shim `fun setRadioModel(id: String) {}` and remove it in Task 9. Prefer doing Task 9 in the same session instead of shipping the shim.)

- [ ] **Step 4: Run test + full app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: new test PASSES; pre-existing failures only from the `SettingsScreen`→`setRadioModel` reference if you removed it without the shim (fix per Step 3 note).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/OperateUiState.kt app/src/test/java/net/ft8vc/app/OperateUiStateRigProfileTest.kt
git commit -m "feat(app): profile CRUD + migration wiring in OperateViewModel, rig fields in UI state"
```

---

### Task 7: No-CAT operating behavior — manual dial feeds display and logging

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (`currentBandLabel` ~line 158, `onQsoComplete` ~line 894, new `setManualDialFrequency`)
- Test: `app/src/test/java/net/ft8vc/app/OperateUiStateRigProfileTest.kt` (extend)

**Interfaces:**
- Consumes: Task 6 `rigHasCat`, existing `lastDialFreqHz` state field, `settingsRepo.setLastDialFreqHz()`.
- Produces: `OperateUiState.effectiveDialFreqHz: Long?` (display/log frequency), `OperateViewModel.setManualDialFrequency(hz: Long)` (used by Task 9 UI).

- [ ] **Step 1: Write the failing test (extend `OperateUiStateRigProfileTest`)**

```kotlin
    @Test
    fun effectiveDialPrefersCatReadbackAndFallsToManualOnlyWithoutCat() {
        // CAT rig: readback wins; stale manual dial must NOT leak in when CAT is silent.
        assertEquals(
            14_074_000L,
            OperateUiState(rigFreqHz = 14_074_000L, lastDialFreqHz = 7_074_000L, rigHasCat = true).effectiveDialFreqHz,
        )
        assertEquals(
            null,
            OperateUiState(rigFreqHz = null, lastDialFreqHz = 7_074_000L, rigHasCat = true).effectiveDialFreqHz,
        )
        // No-CAT rig: the band-picker value is the dial.
        assertEquals(
            7_074_000L,
            OperateUiState(rigFreqHz = null, lastDialFreqHz = 7_074_000L, rigHasCat = false).effectiveDialFreqHz,
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.OperateUiStateRigProfileTest"`
Expected: COMPILE FAILURE — `effectiveDialFreqHz` undefined.

- [ ] **Step 3: Implement**

`OperateUiState.kt` class body:

```kotlin
    /**
     * The dial frequency for display and logging: CAT readback when the rig
     * has CAT; the operator's band-picker choice for a no-CAT (generic-rts)
     * rig. Never falls back to a stale manual value on a CAT rig — a silent
     * CAT stays "unknown", exactly as before profiles (parity).
     */
    val effectiveDialFreqHz: Long?
        get() = rigFreqHz ?: lastDialFreqHz.takeIf { !rigHasCat }
```

`OperateViewModel.kt`:

```kotlin
    private fun currentBandLabel(): String? =
        bandLabelForFreqLoose(state.value.effectiveDialFreqHz)
```

In `onQsoComplete` (~line 894): `val freq = state.value.effectiveDialFreqHz` (was `state.value.rigFreqHz`).

Add near `setRigFrequency`:

```kotlin
    /** No-CAT rigs: the operator picked a band — persist it as the manual dial. */
    fun setManualDialFrequency(hz: Long) {
        viewModelScope.launch { settingsRepo.setLastDialFreqHz(hz) }
    }
```

**Scope note:** do NOT touch `restoreLastBandIfNeeded`, `setRigFrequency`, or `readRig` — those are CAT-command paths and stay CAT-gated. Display-only call sites of `rigFreqHz` in `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt` and `WaterfallPanel.kt`: grep for `rigFreqHz` in those two files; where the value feeds `dialLabelText(...)`/band text rendering, switch to `state.effectiveDialFreqHz`; anything feeding CAT actions keeps `rigFreqHz`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt app/src/test/java/net/ft8vc/app/OperateUiStateRigProfileTest.kt
git commit -m "feat(app): manual dial frequency drives display + logging for no-CAT rigs"
```

---

### Task 8: Test CAT probe

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/ProbeResult.kt`
- Modify: `rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt` (add `probeFrequency()`)
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt` (add `probe()`)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (add `testCatProfile()` + `probeResultText()`)
- Test: `rig/src/test/java/net/ft8vc/rig/ProbeTest.kt`
- Test: `app/src/test/java/net/ft8vc/app/ProbeResultTextTest.kt`

**Interfaces:**
- Consumes: `RigProfiles.resolve()` (Task 3), `FakeSerialTransport`, `YaesuCat`, catDispatcher from the rig session controller (see how the descriptor mirror launches on `rigSession.catDispatcher` at `OperateViewModel.kt:365`).
- Produces:

```kotlin
sealed interface ProbeResult {
    data class Sync(val freqHz: Long) : ProbeResult
    data object Garbage : ProbeResult
    data object Silence : ProbeResult
    data object NoDevice : ProbeResult
    data object NoPermission : ProbeResult
    data object NoCat : ProbeResult
}
fun SerialRigBackend.probeFrequency(): ProbeResult          // member fun, Sync/Garbage/Silence only
fun RigController.probe(d: RigDescriptor, baud: Int): ProbeResult
fun OperateViewModel.testCatProfile(draft: RigProfile, onResult: (String) -> Unit)
// top-level in OperateViewModel.kt, package net.ft8vc.app:
fun probeResultText(result: ProbeResult): String
```

- [ ] **Step 1: Write the failing tests**

`rig/src/test/java/net/ft8vc/rig/ProbeTest.kt` — drive `SerialRigBackend.probeFrequency()` through `FakeSerialTransport` (use its real API for canned replies; see `SerialRigBackendTest.kt` for how replies are queued):

```kotlin
package net.ft8vc.rig

import net.ft8vc.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {

    private fun backend(transport: FakeSerialTransport) =
        SerialRigBackend(transport = transport, protocol = YaesuCat(YaesuCat.FT891))

    @Test
    fun validFrequencyReplyIsSync() {
        val transport = FakeSerialTransport()
        transport.queueReply("FA014074000;".toByteArray(Charsets.US_ASCII))
        val backend = backend(transport)
        assertTrue(backend.open())
        assertEquals(ProbeResult.Sync(14_074_000L), backend.probeFrequency())
    }

    @Test
    fun unparseableBytesAreGarbage() {
        val transport = FakeSerialTransport()
        transport.queueReply(byteArrayOf(0x00, 0x7F, 0x15, ';'.code.toByte()))
        val backend = backend(transport)
        assertTrue(backend.open())
        assertEquals(ProbeResult.Garbage, backend.probeFrequency())
    }

    @Test
    fun noBytesIsSilence() {
        val transport = FakeSerialTransport()
        val backend = backend(transport)
        assertTrue(backend.open())
        assertEquals(ProbeResult.Silence, backend.probeFrequency())
    }

    @Test
    fun nullProtocolIsNoCat() {
        val backend = SerialRigBackend(transport = FakeSerialTransport(), protocol = null)
        assertTrue(backend.open())
        assertEquals(ProbeResult.NoCat, backend.probeFrequency())
    }
}
```

(`queueReply` is illustrative — use `FakeSerialTransport`'s actual reply-priming API. If the silence test would spin the 1-second real-clock deadline, construct the backend with the injectable `nowMs` the way `SerialRigBackendTest` does.)

`app/src/test/java/net/ft8vc/app/ProbeResultTextTest.kt`:

```kotlin
package net.ft8vc.app

import net.ft8vc.rig.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeResultTextTest {
    @Test
    fun everyOutcomeHasPlainLanguageCopy() {
        assertEquals("Sync OK — rig reports 14.074 MHz", probeResultText(ProbeResult.Sync(14_074_000L)))
        assertEquals(
            "Received data but couldn't understand it — likely a wrong baud rate",
            probeResultText(ProbeResult.Garbage),
        )
        assertEquals("No response — check the CAT port, cable, and the rig's CAT menu", probeResultText(ProbeResult.Silence))
        assertEquals("No USB serial device attached", probeResultText(ProbeResult.NoDevice))
        assertEquals("USB permission not granted — connect the rig and allow access", probeResultText(ProbeResult.NoPermission))
        assertEquals("This rig setup has no CAT to test", probeResultText(ProbeResult.NoCat))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :rig:test --tests "net.ft8vc.rig.ProbeTest"` and `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ProbeResultTextTest"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

`ProbeResult.kt`:

```kotlin
package net.ft8vc.rig

/** Outcome of a one-shot Test CAT probe (spec 2026-07-10, Diagnostics). */
sealed interface ProbeResult {
    /** The rig answered a frequency query — CAT works at these settings. */
    data class Sync(val freqHz: Long) : ProbeResult

    /** Bytes arrived but didn't parse — classic wrong-baud symptom. */
    data object Garbage : ProbeResult

    /** Nothing arrived — wrong port, cable, or rig CAT menu off. */
    data object Silence : ProbeResult

    data object NoDevice : ProbeResult
    data object NoPermission : ProbeResult

    /** The profile has no CAT protocol (generic-rts). */
    data object NoCat : ProbeResult
}
```

`SerialRigBackend.kt` — add (its own read loop; `readReply` stays untouched for parity):

```kotlin
    /**
     * One-shot diagnostic: send a frequency query and classify what comes
     * back, keeping partial bytes (unlike [readReply]) so a wrong-baud rig
     * reads as [ProbeResult.Garbage] rather than a timeout.
     */
    fun probeFrequency(): ProbeResult = synchronized(catLock) {
        val p = protocol ?: return ProbeResult.NoCat
        if (!transport.write(p.readFrequencyCommand(), CAT_TIMEOUT_MS)) return ProbeResult.Silence
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var collected = ByteArray(0)
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) break
            if (n > 0) {
                collected += buffer.copyOfRange(0, n)
                val end = collected.indexOfFirst { it == p.replyTerminator }
                if (end >= 0) {
                    val frame = collected.copyOfRange(0, end + 1)
                    return p.parseFrequency(frame)?.let { ProbeResult.Sync(it) } ?: ProbeResult.Garbage
                }
            }
        }
        return if (collected.isEmpty()) ProbeResult.Silence else ProbeResult.Garbage
    }
```

`RigController.kt` — add (below `rebind()`):

```kotlin
    /**
     * Test CAT for a draft profile's descriptor without adopting it: close any
     * live backend, open the candidate at [baud], query once, close, and
     * re-bind the selected configuration. Blocking serial I/O — call on the
     * CAT dispatcher, and only when not transmitting (caller-guarded).
     */
    @Synchronized
    fun probe(d: RigDescriptor, baud: Int): ProbeResult {
        val factory = d.protocolFactory ?: return ProbeResult.NoCat
        val driver = findDriver() ?: return ProbeResult.NoDevice
        if (!usbManager.hasPermission(driver.device)) return ProbeResult.NoPermission
        val index = resolveCatPortIndex(driver.ports.size, null, d.catPortIndex)
            ?: return ProbeResult.NoDevice
        val hadBackend = backend != null
        backend?.close()
        backend = null
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, driver.ports[index], baud),
            protocol = factory(),
        )
        val result = if (!candidate.open()) {
            ProbeResult.Silence
        } else {
            try {
                candidate.probeFrequency()
            } finally {
                candidate.close()
            }
        }
        if (hadBackend) bindIfPermitted()
        return result
    }
```

`OperateViewModel.kt` — top-level function + VM method:

```kotlin
/** Plain-language copy for Test CAT outcomes (spec: Diagnostics section). */
fun probeResultText(result: ProbeResult): String = when (result) {
    is ProbeResult.Sync -> "Sync OK — rig reports %.3f MHz".format(Locale.ROOT, result.freqHz / 1_000_000.0)
    ProbeResult.Garbage -> "Received data but couldn't understand it — likely a wrong baud rate"
    ProbeResult.Silence -> "No response — check the CAT port, cable, and the rig's CAT menu"
    ProbeResult.NoDevice -> "No USB serial device attached"
    ProbeResult.NoPermission -> "USB permission not granted — connect the rig and allow access"
    ProbeResult.NoCat -> "This rig setup has no CAT to test"
}
```

```kotlin
    /** Test CAT from the profile editor. TX-guarded; runs on the CAT dispatcher. */
    fun testCatProfile(draft: RigProfile, onResult: (String) -> Unit) {
        if (state.value.isTransmitting) {
            onResult("Can't test while transmitting")
            return
        }
        viewModelScope.launch(rigSession.catDispatcher) {
            val descriptor = RigProfiles.resolve(draft)
            val result = if (descriptor == null) {
                ProbeResult.NoCat
            } else {
                rig.probe(descriptor, draft.baud ?: descriptor.defaultBaud)
            }
            withContext(Dispatchers.Main) { onResult(probeResultText(result)) }
        }
    }
```

(Imports: `net.ft8vc.rig.ProbeResult`, `net.ft8vc.rig.RigProfiles`. `rigSession.catDispatcher` is the same dispatcher the descriptor mirror uses at line ~365; `Locale` is already imported at line 62.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :rig:test && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/ft8vc/rig/ProbeResult.kt rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt rig/src/main/java/net/ft8vc/rig/RigController.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt rig/src/test/java/net/ft8vc/rig/ProbeTest.kt app/src/test/java/net/ft8vc/app/ProbeResultTextTest.kt
git commit -m "feat(rig,app): Test CAT probe — sync/garbage/silence classification with plain copy"
```

---

### Task 9: Settings UI — My rigs list + profile editor

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/RigProfileEditor.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:140-155`

**Interfaces:**
- Consumes: `OperateUiState.rigProfiles`/`selectedRigProfileId`/`selectedRigProfileName`/`rigHasCat`/`lastDialFreqHz`, VM functions `saveRigProfile`/`deleteRigProfile`/`selectRigProfile`/`testCatProfile`/`setManualDialFrequency` (Tasks 6-8), `RigProfileList.nameError`/`MAX`, `RigRegistry` presets, `CatProtocols.all`, existing private composables `CatBaudPicker`/`PttPreferencePicker`/`CatPortOverridePicker` (move them into the editor file or widen visibility), `DialFrequencyDropdownField`.
- Produces: `RigProfileEditorDialog(...)` composable; reworked `RadioSettingsSection` with new parameter list (below) — `SettingsScreen` call site updated to match.

- [ ] **Step 1: Implement the editor dialog**

Create `RigProfileEditor.kt`. Form fields follow the spec table exactly — show a field only for setups it applies to:

```kotlin
package net.ft8vc.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import net.ft8vc.rig.CatProtocols
import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry
import java.util.UUID

/**
 * Add/Edit dialog for one rig profile. Exposes exactly the spec's form-field
 * table: name always; CAT protocol + baud for CAT setups; CAT port only for
 * generic-cat; PTT for CAT setups (generic-rts is fixed RTS). Everything else
 * keeps preset defaults and never appears (spec 2026-07-10, Settings UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RigProfileEditorDialog(
    existing: RigProfile?,                 // null = Add rig
    allProfiles: List<RigProfile>,
    serialPortNames: List<String>,
    onTestCat: (RigProfile, (String) -> Unit) -> Unit,
    onSave: (RigProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var presetId by remember { mutableStateOf(existing?.presetId ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baud by remember { mutableStateOf(existing?.baud) }
    var catPortIndex by remember { mutableStateOf(existing?.catPortIndex) }
    var pttMethod by remember { mutableStateOf(existing?.pttMethod) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val preset = RigRegistry.byId(presetId)
    val isCatSetup = preset != null && preset.protocolFactory != null
    val nameError = RigProfileList.nameError(name, allProfiles, existing?.id)
    val canSave = preset != null && nameError == null

    fun draft() = RigProfile(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.trim(),
        presetId = presetId,
        catProtocolId = if (RigRegistry.isCatGeneric(presetId)) CatProtocols.YAESU_NEWCAT else null,
        baud = baud,
        catPortIndex = catPortIndex,
        pttMethod = pttMethod,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add rig" else "Edit rig") },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(draft()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                PresetPicker(
                    selectedId = presetId.ifEmpty { null },
                    onSelect = { id ->
                        presetId = id
                        val chosen = RigRegistry.byId(id)
                        if (name.isBlank() && chosen != null && !RigRegistry.isGeneric(id)) {
                            name = chosen.displayName
                        }
                        // Re-prefill knobs from the new preset (null = default).
                        baud = null
                        catPortIndex = null
                        pttMethod = null
                        testResult = null
                    },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = name.isNotEmpty() && nameError != null,
                    supportingText = { nameError?.takeIf { name.isNotEmpty() }?.let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCatSetup) {
                    if (RigRegistry.isCatGeneric(presetId)) {
                        CatProtocolPicker() // single option today, preselected + read-only
                    }
                    CatBaudPicker(
                        baud = baud ?: preset!!.defaultBaud,
                        enabled = true,
                        onSelect = { baud = it },
                    )
                    if (presetId == RigRegistry.GENERIC_CAT && serialPortNames.size > 1) {
                        CatPortOverridePicker(
                            override = catPortIndex,
                            portNames = serialPortNames,
                            enabled = true,
                            onSelect = { catPortIndex = it },
                        )
                    }
                    PttPreferencePicker(
                        preference = (pttMethod ?: preset!!.defaultPtt).toPreference(),
                        enabled = true,
                        onSelect = { pttMethod = it.toPttMethod() },
                    )
                    TextButton(onClick = { onTestCat(draft()) { testResult = it } }) {
                        Text("Test CAT")
                    }
                    testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (preset != null) {
                    Text(
                        "No CAT for this setup — PTT keys through the serial RTS line, " +
                            "and you set your dial frequency on the Radio settings screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedId?.let { RigRegistry.byId(it)?.displayName } ?: "Select your setup"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Radio / setup") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RigRegistry.all.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.displayName) },
                    onClick = { expanded = false; onSelect(d.id) },
                )
            }
        }
    }
}

/** One protocol today — visible so the operator knows which dialect is spoken. */
@Composable
private fun CatProtocolPicker() {
    OutlinedTextField(
        value = CatProtocols.all.single().displayName,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text("CAT protocol") },
        supportingText = { Text("More radio families arrive in later releases") },
        modifier = Modifier.fillMaxWidth(),
    )
}
```

Move `CatBaudPicker`, `PttPreferencePicker`, and `CatPortOverridePicker` from `RadioSettingsSection.kt` into this file unchanged except: visibility stays `private`-to-file → they now live here; generalize `CatBaudPicker`'s supporting text to `"Must match your rig's CAT rate menu setting"`.

- [ ] **Step 2: Rework `RadioSettingsSection`**

Replace `RadioModelPicker` + the three moved pickers with the My rigs block. New signature:

```kotlin
@Composable
fun RadioSettingsSection(
    state: OperateUiState,
    usbDiagnostics: String,
    serialPortNames: List<String>,
    onSelectRigProfile: (String) -> Unit,
    onSaveRigProfile: (RigProfile) -> Unit,
    onDeleteRigProfile: (String) -> Unit,
    onTestCat: (RigProfile, (String) -> Unit) -> Unit,
    onSelectDialFrequency: (Long) -> Unit,
    onSetManualDialFrequency: (Long) -> Unit,
    onReadRig: () -> Unit,
    onSetRigDataUsb: () -> Unit,
)
```

Body (keep the existing cat-ready block — dial dropdown, Read rig, DATA-U button, catStatus — and `UsbDiagnosticsExpandable` verbatim):

```kotlin
    var editorTarget by remember { mutableStateOf<RigProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RigProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MyRigsBlock(
            profiles = state.rigProfiles,
            selectedId = state.selectedRigProfileId,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSelectRigProfile,
            onAdd = { editorTarget = null; editorOpen = true },
            onEdit = { editorTarget = it; editorOpen = true },
            onDelete = { deleteTarget = it },
        )
        if (state.rigProfiles.isEmpty()) {
            Text("Add your rig to enable CAT and PTT.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.catReady) {
            // ...existing dial/mode/DATA-U/catStatus block, unchanged...
        } else if (state.selectedRigProfileId != null && !state.rigHasCat) {
            Text(
                "No CAT (manual) — keep the radio's dial on the selected frequency.",
                style = MaterialTheme.typography.bodySmall,
            )
            DialFrequencyDropdownField(
                rigFreqHz = state.lastDialFreqHz,
                enabled = true,
                onSelect = onSetManualDialFrequency,
                radioModelId = state.radioModelId,
            )
        } else if (state.selectedRigProfileId != null) {
            Text(
                "CAT unavailable — connect the radio's serial link and grant USB permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        UsbDiagnosticsExpandable(diagnostics = usbDiagnostics)
    }

    if (editorOpen) {
        RigProfileEditorDialog(
            existing = editorTarget,
            allProfiles = state.rigProfiles,
            serialPortNames = serialPortNames,
            onTestCat = onTestCat,
            onSave = { editorOpen = false; onSaveRigProfile(it) },
            onDismiss = { editorOpen = false },
        )
    }
    deleteTarget?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${doomed.name}?") },
            text = { Text("This removes the saved configuration. Your log is not affected.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteRigProfile(doomed.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
```

`MyRigsBlock` (same file, private):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyRigsBlock(
    profiles: List<RigProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (RigProfile) -> Unit,
    onDelete: (RigProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    if (profiles.isNotEmpty()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = selected?.name ?: "Select a rig",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("My rig") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { expanded = false; onSelect(p.id) },
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (profiles.size < RigProfileList.MAX) {
            TextButton(onClick = onAdd, enabled = enabled) { Text("Add rig") }
        }
        selected?.let {
            TextButton(onClick = { onEdit(it) }, enabled = enabled) { Text("Edit") }
            TextButton(onClick = { onDelete(it) }, enabled = enabled) { Text("Delete") }
        }
    }
}
```

- [ ] **Step 3: Update the `SettingsScreen` call site (lines ~140-155)**

```kotlin
            val rigTitle = state.selectedRigProfileName?.let { "Rig ($it)" } ?: "Radio"
            SettingsSection(rigTitle) {
                RadioSettingsSection(
                    state = state,
                    usbDiagnostics = usbDiagnostics,
                    serialPortNames = serialPortNames,
                    onSelectRigProfile = vm::selectRigProfile,
                    onSaveRigProfile = { p -> vm.saveRigProfile(p) },
                    onDeleteRigProfile = vm::deleteRigProfile,
                    onTestCat = vm::testCatProfile,
                    onSelectDialFrequency = vm::setRigFrequency,
                    onSetManualDialFrequency = vm::setManualDialFrequency,
                    onReadRig = vm::readRig,
                    onSetRigDataUsb = vm::setRigDataUsb,
                )
            }
```

(Keep the surrounding parameter names the file actually uses — `usbDiagnostics`/`serialPortNames` come from the same sources as today. Remove the now-dead `onSetCatBaud`/`onSetPttPreference`/`onSelectCatPort`/`onSelectRadioModel` plumbing and the Task 6 shim `setRadioModel` if it exists. The VM functions `setCatBaud`/`setPttPreference`/`setCatPortOverride` themselves stay — legacy fallback keys still exist.)

- [ ] **Step 4: Build + full unit sweep**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL; all module tests pass. (`DecodeControllerTest.reset_clearsLevelMeter` is a known pre-existing flake — re-run once before suspecting this change.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/RigProfileEditor.kt app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(ui): My rigs — profile list, add/edit dialog with Test CAT, delete confirm"
```

---

### Task 10: Docs, sweep, and field-gate checklist

**Files:**
- Modify: `docs/RIG_MODELS.md`
- Modify: `docs/superpowers/specs/2026-07-10-rig-profiles-design.md` (status line only)

- [ ] **Step 1: Update `docs/RIG_MODELS.md`**

Replace the "Phase 2.5 — Rig profiles" roadmap bullet with a short implemented section after the model table:

```markdown
## Rig profiles (Phase 2.5 — implemented)

Operators save up to 5 named profiles (Settings → Radio → Add rig); the
model dropdown is gone and registry entries are presets that prefill the
form. Generic presets for unlisted hardware: `generic-digirig` (CAT + RTS
PTT through a Digirig), `generic-cat` (rig's own USB CAT), `generic-rts`
(audio-only, RTS keying, dial frequency set from the band picker and used
for logging). CAT protocol is a knob inside the CAT generics
(`CatProtocols`, Yaesu new-CAT only today) — Kenwood/Icom join that
dropdown in Phases 3/4; no new generic presets are ever added per family.
The profile form's Test CAT button reports sync / wrong-baud garbage /
silence in plain language; `transportVerified` stays a preset-table
concept — user profiles are self-verified via Test CAT. Legacy
`RADIO_MODEL` installs migrate to a single selected profile on first run.
```

Also update the spec's `**Status:**` line to `Implemented on unstable (this document is the design of record).`

- [ ] **Step 2: Full sweep**

Run: `./gradlew test`
Expected: ALL PASS across `:core`, `:rig`, `:audio`, `:data`, `:app`, `:ft8-native`.

- [ ] **Step 3: Commit**

```bash
git add docs/RIG_MODELS.md docs/superpowers/specs/2026-07-10-rig-profiles-design.md
git commit -m "docs(rig): rig profiles implemented — RIG_MODELS section + spec status"
```

- [ ] **Step 4: Record the field gates (do not claim done)**

The code-complete branch is NOT promotable until the owner runs, on real hardware:
1. **FT-891 + Digirig through the migration path** — upgrade an install with `RADIO_MODEL=ft891` set; confirm the profile appears selected and named "Yaesu FT-891", CAT syncs, rig keys, one full QSO.
2. **FTX-1 direct USB** via a preset-created profile — CAT sync + TX.
3. **One real no-CAT QSO** on a `generic-rts` profile — RTS keys, decodes flow, logged frequency matches the band picker.
4. **Test CAT spot-checks** — correct baud → Sync OK; wrong baud → garbage message; wrong port → no response.

State these as pending in the completion report.

---

## Self-Review Notes (already applied)

- Spec coverage: presets-over-knobs (T2/T3), persistence+cap+unique names (T4/T5), migration (T5), dropdown replacement + form table (T9), no-CAT band-picker logging (T7), Test CAT (T8), error handling (corrupt JSON T4, unknown preset T3/T5, deletion fallback T4/T5), parity regression (T3 `ft891ParityAllNullKnobsEqualsRegistryEntry` + T5 null-knob migration test), docs (T10). License gate untouched throughout.
- Type consistency: `RigProfile` fields match across T3 (definition), T4 (codec), T5 (migration), T6-T9 (consumers); `probe(d, baud)` matches `testCatProfile`'s call; `toPreference`/`toPttMethod` live in `StationSettings.kt` after T5 (T9's editor imports them from there).
- Known intentional simplification: the OperateViewModel descriptor mirror keeps resolving by `radioModelId` (= selected profile's preset) — profile knobs arrive through the derived legacy settings fields, so switching between two profiles that share a preset rebinds via the baud/port/PTT mirrors rather than a descriptor swap. `RigProfiles.resolve()` is exercised by tests and Test CAT today and becomes the descriptor-mirror path when a second CAT protocol lands (Phase 3).
