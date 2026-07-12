# Multi-Rig Phase 2: Rig Registry + Radio Model Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Phase 1's hardcoded `YaesuCat(FT891)` with a user-selected radio model backed by a `RigDescriptor` registry, shipping the full Yaesu new-CAT family with a dual-UART port-selection path for built-in-USB rigs.

**Architecture:** A pure `RigDescriptor` data type + static `RigRegistry` list are composed onto the Phase 1 seams: `RigController` holds a nullable descriptor, builds `SerialRigBackend(UsbSerialTransport(driver.ports[index], baud), descriptor.protocolFactory())`, and reports a new `NoModel` state until the operator selects a radio. Settings gains a "Radio model" picker (no default) plus an advanced CAT-port override for multi-port rigs.

**Tech Stack:** Kotlin, Android USB Host, usb-serial-for-android 3.9.0, Jetpack Compose (Material3), DataStore Preferences, JUnit4 JVM tests.

**Spec:** `docs/superpowers/specs/2026-07-05-multi-rig-phase2-rig-registry-design.md`

## Global Constraints

- **No default rig.** `RADIO_MODEL` DataStore key defaults to unset; `RigRegistry` has no `default` member; `RigController` with a null descriptor binds nothing and reports `State.NoModel`.
- **Behavior parity:** with **FT-891 selected**, RX/TX/CAT wire behavior is byte-identical to Phase 1 — same protocol bytes, port index 0, baud, and PTT probe (`PttMethod.AUTO`). This is a test gate.
- **PTT safety:** unchanged from Phase 1 — RTS only asserted by `keyPtt()`; the trailing unconditional RTS de-assert in `RigController.releasePtt()` survives.
- **No fabricated rig data:** per-model frequency ranges come from each rig's CAT/operating manual; tests assert invariants (FT8 calling frequencies accepted, sub-30 kHz and malformed rejected, mode round-trips), not invented band edges. The shared new-CAT `MD0x` mode table and `dataModeCode = 'C'` (DATA-U) are consistent across the family.
- **No in-app "unverified" marker** (docs-only verification tracking). **Minimal port override** surfaced only when the bound device exposes >1 serial port.
- **Module rules:** `rig` module is flat `net.ft8vc.rig` package, one public top-level type per file, protocol code pure (no Android imports); `PttMethod` is a rig-module enum the app's `PttPreference` maps onto.
- Work on branch `multi-rig`. All commands run from repo root `/Users/bsmirks/git/ft8vc`.

---

### Task 1: Yaesu family model specs

Adds the five new-CAT family entries beside the existing `YaesuCat.FT891`. Pure data + protocol tests; no wiring yet.

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/YaesuModels.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/YaesuModelsTest.kt`

**Interfaces:**
- Consumes: `YaesuModelSpec` (Phase 1), `YaesuCat` (Phase 1).
- Produces (relied on by Task 3): `object YaesuModels` with `FT991A`, `FTDX10`, `FT710`, `FTDX101`, `FTX1` — each a `YaesuModelSpec`. All reuse the new-CAT mode table and `dataModeCode = 'C'`.

- [ ] **Step 1: Write the failing test**

`rig/src/test/java/net/ft8vc/rig/YaesuModelsTest.kt`:

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Family specs are validated by invariants that hold regardless of a model's
 * exact band edges: the FT8 calling frequencies on every HF/6 m band must be
 * accepted, clearly-out values rejected, and the DATA-U mode must round-trip.
 * Exact min/max come from each rig's CAT manual (see YaesuModels.kt comments);
 * do NOT assert fabricated boundary numbers here.
 */
class YaesuModelsTest {

    private val ft8CallingFreqs = longArrayOf(
        1_840_000, 3_573_000, 7_074_000, 10_136_000, 14_074_000,
        18_100_000, 21_074_000, 24_915_000, 28_074_000, 50_313_000,
    )

    private val allSpecs = listOf(
        YaesuModels.FT991A, YaesuModels.FTDX10, YaesuModels.FT710,
        YaesuModels.FTDX101, YaesuModels.FTX1,
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun everyModelAcceptsAllHfAndSixMeterFt8Frequencies() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            for (hz in ft8CallingFreqs) {
                assertNotNull(
                    "${spec.name} should accept $hz Hz",
                    cat.setFrequencyCommand(hz),
                )
            }
        }
    }

    @Test
    fun everyModelRejectsSubMinimumAndParsesGarbageAsNull() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            assertNull("${spec.name} should reject 0 Hz", cat.setFrequencyCommand(0))
            assertNull("${spec.name} garbage parse", cat.parseFrequency(ascii("MD0C;")))
        }
    }

    @Test
    fun everyModelUsesDataUsbForFt8() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            assertEquals("${spec.name} data mode", "DATA-U", cat.dataModeLabel)
            assertEquals("${spec.name} MD0C parse", "DATA-U", cat.parseModeLabel(ascii("MD0C;")))
        }
    }

    @Test
    fun specsAreDistinctByName() {
        val names = allSpecs.map { it.name } + YaesuCat.FT891.name
        assertEquals(names.size, names.toSet().size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.YaesuModelsTest"`
Expected: FAIL — `YaesuModels` unresolved.

- [ ] **Step 3: Write the specs**

`rig/src/main/java/net/ft8vc/rig/YaesuModels.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Yaesu "new CAT" family specs beside [YaesuCat.FT891]. Every current-gen Yaesu
 * shares the same `MD0x` mode table and DATA-U (`C`) data mode; only the tuning
 * range varies. Ranges below are the published general-coverage RX/TX spans from
 * each rig's operating manual — verify the exact min/max against the manual when
 * touching a spec. The min is the receiver's low edge; the max covers the rig's
 * top band (HF+6 m for the FTDX10/FT-710/FTDX101/FTX-1; +VHF/UHF for the FT-991A).
 */
object YaesuModels {

    /** Shared new-CAT MD0x → label map (identical across the current family). */
    private val NEW_CAT_MODES: Map<Char, String> = YaesuCat.FT891.modeLabels

    /** FT-991A: HF/50/144/430 MHz all-mode. Manual: general coverage RX to 470 MHz. */
    val FT991A = YaesuModelSpec(
        name = "Yaesu FT-991A",
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTDX10: HF + 50 MHz. */
    val FTDX10 = YaesuModelSpec(
        name = "Yaesu FTDX10",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FT-710: HF + 50 MHz. */
    val FT710 = YaesuModelSpec(
        name = "Yaesu FT-710",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTDX101 (D/MP): HF + 50 MHz. */
    val FTDX101 = YaesuModelSpec(
        name = "Yaesu FTDX101",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTX-1: HF + 50 MHz (portable SDR). Transport details unverified — see registry. */
    val FTX1 = YaesuModelSpec(
        name = "Yaesu FTX-1",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.YaesuModelsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src
git commit -m "feat(rig): Yaesu new-CAT family model specs (991A/FTDX10/FT-710/FTDX101/FTX-1)"
```

---

### Task 2: RigDescriptor types + RigRegistry

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/PttMethod.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/UsbId.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/RigDescriptor.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/RigRegistry.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/RigRegistryTest.kt`

**Interfaces:**
- Consumes: `CatProtocol`, `YaesuCat`, `YaesuModels` (Tasks 1, Phase 1).
- Produces (relied on by Tasks 3–5): `enum PttMethod { AUTO, RTS, CAT }`; `data class UsbId(vendorId: Int, productId: Int)`; `data class RigDescriptor(id, displayName, protocolFactory: () -> CatProtocol, defaultBaud: Int, catPortIndex: Int, defaultPtt: PttMethod, customProbePids: List<UsbId> = emptyList(), transportVerified: Boolean)`; `object RigRegistry { val all: List<RigDescriptor>; fun byId(id: String): RigDescriptor? }`. Registry ids: `ft891`, `ft991a`, `ftdx10`, `ft710`, `ftdx101`, `ftx1`.

- [ ] **Step 1: Write the failing test**

`rig/src/test/java/net/ft8vc/rig/RigRegistryTest.kt`:

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigRegistryTest {

    @Test
    fun idsAreUnique() {
        val ids = RigRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyDescriptorResolvesAProtocolAndHasNonNegativePort() {
        for (d in RigRegistry.all) {
            assertNotNull("${d.id} protocol", d.protocolFactory())
            assertTrue("${d.id} port index >= 0", d.catPortIndex >= 0)
            assertTrue("${d.id} baud > 0", d.defaultBaud > 0)
        }
    }

    @Test
    fun ft891IsPresentVerifiedAndAutoPtt() {
        val ft891 = RigRegistry.byId("ft891")
        assertNotNull(ft891)
        assertTrue(ft891!!.transportVerified)
        assertEquals(PttMethod.AUTO, ft891.defaultPtt)
        assertEquals(0, ft891.catPortIndex)
    }

    @Test
    fun builtInUsbRigsAreUnverifiedWithCatPtt() {
        for (id in listOf("ft991a", "ftdx10", "ft710", "ftdx101", "ftx1")) {
            val d = RigRegistry.byId(id)
            assertNotNull("$id present", d)
            assertFalse("$id transport unverified", d!!.transportVerified)
            assertEquals("$id CAT PTT", PttMethod.CAT, d.defaultPtt)
        }
    }

    @Test
    fun byIdReturnsNullForUnknown() {
        assertNull(RigRegistry.byId("nonexistent"))
    }

    @Test
    fun ft891ProtocolIsByteEquivalentToYaesuFt891() {
        val cat = RigRegistry.byId("ft891")!!.protocolFactory()
        assertEquals(
            "FA014074000;",
            cat.setFrequencyCommand(14_074_000)!!.toString(Charsets.US_ASCII),
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigRegistryTest"`
Expected: FAIL — `RigRegistry`/`RigDescriptor`/`PttMethod`/`UsbId` unresolved.

- [ ] **Step 3: Write the types**

`rig/src/main/java/net/ft8vc/rig/PttMethod.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Default PTT keying a rig descriptor prescribes. Maps one-to-one onto the app's
 * `PttPreference`. AUTO = probe CAT, fall back to RTS (the FT-891/Digirig path).
 */
enum class PttMethod { AUTO, RTS, CAT }
```

`rig/src/main/java/net/ft8vc/rig/UsbId.kt`:

```kotlin
package net.ft8vc.rig

/** A USB vendor/product id pair, for augmenting the serial prober per rig. */
data class UsbId(val vendorId: Int, val productId: Int)
```

`rig/src/main/java/net/ft8vc/rig/RigDescriptor.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Everything [RigController] needs to compose the Phase 1 seams for one radio:
 * how to talk to it ([protocolFactory]), how it connects ([defaultBaud],
 * [catPortIndex], [customProbePids]), and how to key it ([defaultPtt]).
 *
 * @param id stable key persisted in settings (never localize/rename).
 * @param catPortIndex which serial port carries CAT; 0 for single-port bridges,
 *   0 = Enhanced port on dual-UART rigs (CP2105).
 * @param customProbePids extra VID/PID entries when the stock CP210x prober
 *   table misses the rig's bridge.
 * @param transportVerified false when the transport fields are best-guess pending
 *   hardware (docs tracking only — no UI marker).
 */
data class RigDescriptor(
    val id: String,
    val displayName: String,
    val protocolFactory: () -> CatProtocol,
    val defaultBaud: Int,
    val catPortIndex: Int,
    val defaultPtt: PttMethod,
    val customProbePids: List<UsbId> = emptyList(),
    val transportVerified: Boolean,
)
```

`rig/src/main/java/net/ft8vc/rig/RigRegistry.kt`:

```kotlin
package net.ft8vc.rig

/**
 * The supported radios. No `default` — an unselected model is a real state the
 * app handles (see [RigController.State.NoModel]); the operator must choose.
 * The current family is all Yaesu new-CAT; Phase 3/4 append Kenwood/Icom.
 */
object RigRegistry {

    val all: List<RigDescriptor> = listOf(
        RigDescriptor(
            id = "ft891",
            displayName = "Yaesu FT-891",
            protocolFactory = { YaesuCat(YaesuCat.FT891) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.AUTO,
            transportVerified = true,
        ),
        RigDescriptor(
            id = "ft991a",
            displayName = "Yaesu FT-991A",
            protocolFactory = { YaesuCat(YaesuModels.FT991A) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx10",
            displayName = "Yaesu FTDX10",
            protocolFactory = { YaesuCat(YaesuModels.FTDX10) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ft710",
            displayName = "Yaesu FT-710",
            protocolFactory = { YaesuCat(YaesuModels.FT710) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx101",
            displayName = "Yaesu FTDX101",
            protocolFactory = { YaesuCat(YaesuModels.FTDX101) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftx1",
            displayName = "Yaesu FTX-1",
            protocolFactory = { YaesuCat(YaesuModels.FTX1) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
    )

    fun byId(id: String): RigDescriptor? = all.firstOrNull { it.id == id }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigRegistryTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src
git commit -m "feat(rig): RigDescriptor + RigRegistry (no default rig)"
```

---

### Task 3: RigController — descriptor composition, NoModel, port selection, CDC-ACM fallback

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/RigControllerDescriptorTest.kt`

**Interfaces:**
- Consumes: `RigDescriptor`, `RigRegistry`, `UsbId` (Task 2), `SerialRigBackend`, `UsbSerialTransport`, `CdcAcmSerialDriver` (library).
- Produces (relied on by Task 5): `RigController.State` gains `NoModel`; `var descriptor: RigDescriptor?`; `var catPortOverride: Int?`; `fun setDescriptor(d: RigDescriptor?)`; `fun availablePortCount(): Int`. `catBaud` and all Phase 1 members unchanged.

Because `bindIfPermitted` touches the Android USB stack, the pure port-selection math is extracted into an internal, unit-testable helper `resolveCatPortIndex(portCount, override, descriptorIndex): Int?` (returns null when out of range). The test targets that helper and the `state()`/descriptor logic; the USB binding itself is covered at the Task 7 hardware gate.

- [ ] **Step 1: Write the failing test**

`rig/src/test/java/net/ft8vc/rig/RigControllerDescriptorTest.kt`:

```kotlin
package net.ft8vc.rig

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RigControllerDescriptorTest {

    private fun controller(): RigController {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return RigController(context)
    }

    @Test
    fun stateIsNoModelUntilDescriptorSet() {
        val rig = controller()
        assertEquals(RigController.State.NoModel, rig.state())
    }

    @Test
    fun setDescriptorClearsNoModel() {
        val rig = controller()
        rig.setDescriptor(RigRegistry.byId("ft891"))
        // No USB device in a unit test, so state falls through to NoDevice — the
        // point is it is no longer NoModel once a model is chosen.
        assertEquals(RigController.State.NoDevice, rig.state())
    }

    @Test
    fun resolvePortIndex_prefersOverride_thenDescriptor_boundsChecked() {
        // override wins when in range
        assertEquals(1, RigController.resolveCatPortIndex(portCount = 2, override = 1, descriptorIndex = 0))
        // null override falls back to descriptor index
        assertEquals(0, RigController.resolveCatPortIndex(portCount = 2, override = null, descriptorIndex = 0))
        // descriptor index in range on a single-port device
        assertEquals(0, RigController.resolveCatPortIndex(portCount = 1, override = null, descriptorIndex = 0))
        // out-of-range override -> null (bind fails cleanly)
        assertNull(RigController.resolveCatPortIndex(portCount = 1, override = 3, descriptorIndex = 0))
        // out-of-range descriptor index -> null
        assertNull(RigController.resolveCatPortIndex(portCount = 1, override = null, descriptorIndex = 2))
        // no ports -> null
        assertNull(RigController.resolveCatPortIndex(portCount = 0, override = null, descriptorIndex = 0))
    }

    @Test
    fun catPortOverrideDefaultsToNull() {
        assertEquals(null, controller().catPortOverride)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigControllerDescriptorTest"`
Expected: FAIL — `State.NoModel`, `setDescriptor`, `resolveCatPortIndex`, `catPortOverride` unresolved.

- [ ] **Step 3: Modify RigController**

In `rig/src/main/java/net/ft8vc/rig/RigController.kt`:

Add `NoModel` to the `State` enum (first entry):

```kotlin
    enum class State {
        /** No radio model selected yet. */
        NoModel,

        /** Model selected, but no supported USB-serial device present. */
        NoDevice,

        /** Device present but USB permission not yet granted. */
        NeedsPermission,

        /** Device present, permission granted, PTT wired. */
        Ready,
    }
```

Add the imports at the top (alongside the existing library imports):

```kotlin
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
```

Add descriptor + override fields near `catBaud`:

```kotlin
    /**
     * Selected radio. Null until the operator picks a model — [bindIfPermitted]
     * is a no-op and [state] reports [State.NoModel]. Owner (OperateViewModel)
     * mirrors the persisted RADIO_MODEL setting here; call [rebind] to apply.
     */
    @Volatile
    var descriptor: RigDescriptor? = null

    /** Operator override for which serial port carries CAT; null = descriptor default. */
    @Volatile
    var catPortOverride: Int? = null

    /** Set the active model and drop any backend bound to the previous one. */
    @Synchronized
    fun setDescriptor(d: RigDescriptor?) {
        if (descriptor?.id == d?.id) return
        descriptor = d
        backend?.close()
        backend = null
    }
```

Replace `findDriver()` so it augments the prober with the descriptor's custom PIDs and falls back to CDC-ACM (FT8CN's pattern for built-in-USB rigs that enumerate as CDC):

```kotlin
    private fun findDriver(): UsbSerialDriver? {
        val fromStock = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (fromStock != null) return fromStock
        val custom = customProber().findAllDrivers(usbManager).firstOrNull()
        if (custom != null) return custom
        // Some built-in-USB rigs enumerate as CDC-ACM, which the stock prober
        // does not match blindly; try it directly against a present device.
        val device = usbManager.deviceList.values.firstOrNull() ?: return null
        return runCatching { CdcAcmSerialDriver(device) }.getOrNull()
    }

    /** Prober seeded with the current descriptor's custom PIDs plus the CP2102 dual variant. */
    private fun customProber(): UsbSerialProber {
        val table = ProbeTable().addProduct(0x10C4, 0xEA61, Cp21xxSerialDriver::class.java)
        descriptor?.customProbePids?.forEach { id ->
            table.addProduct(id.vendorId, id.productId, Cp21xxSerialDriver::class.java)
        }
        return UsbSerialProber(table)
    }
```

(Remove the old `customProber` val field from Phase 1 if present, replaced by the function above.)

Update `state()` to report `NoModel` first:

```kotlin
    fun state(): State {
        if (descriptor == null) return State.NoModel
        findDevice() ?: return State.NoDevice
        return if (backend != null) State.Ready else State.NeedsPermission
    }
```

Update `bindIfPermitted()` to require a descriptor and pick the port index:

```kotlin
    @Synchronized
    fun bindIfPermitted(): Boolean {
        if (backend != null) return true
        val d = descriptor ?: return false
        val driver = findDriver() ?: return false
        if (!usbManager.hasPermission(driver.device)) return false
        val index = resolveCatPortIndex(driver.ports.size, catPortOverride, d.catPortIndex)
        if (index == null) {
            Log.e(TAG, "No serial port at CAT index (ports=${driver.ports.size}, override=$catPortOverride, descriptor=${d.catPortIndex})")
            return false
        }
        val port = driver.ports[index]
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, port, catBaud),
            protocol = d.protocolFactory(),
        )
        return if (candidate.open()) {
            backend = candidate
            Log.i(TAG, "Serial rig backend bound: ${d.displayName} @ port $index (${driver.device.deviceName})")
            true
        } else {
            Log.e(TAG, "Backend open() failed after USB permission granted")
            false
        }
    }
```

Add the pure helper + the port-count accessor in the `companion object` / class:

```kotlin
    /** Serial ports the currently attached, matched driver exposes (0 if none). */
    fun availablePortCount(): Int = findDriver()?.ports?.size ?: 0
```

and in the companion object:

```kotlin
        /**
         * Pick the serial port index for CAT: operator [override] if set, else the
         * [descriptorIndex], validated against [portCount]. Null = no usable port.
         */
        fun resolveCatPortIndex(portCount: Int, override: Int?, descriptorIndex: Int): Int? {
            val wanted = override ?: descriptorIndex
            return if (wanted in 0 until portCount) wanted else null
        }
```

Update `dataModeLabel()`'s fallback (Phase 1 returned `YaesuCat(YaesuCat.FT891).dataModeLabel` when unbound) to use the descriptor when present:

```kotlin
    override fun dataModeLabel(): String =
        backend?.dataModeLabel()
            ?: descriptor?.protocolFactory()?.dataModeLabel
            ?: "DATA-U"
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigControllerDescriptorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the whole rig module (Phase 1 parity)**

Run: `./gradlew :rig:testDebugUnitTest`
Expected: PASS — including the Phase 1 `RigControllerCatBaudTest`, `SerialRigBackendTest`, byte-equivalence.

- [ ] **Step 6: Commit**

```bash
git add rig/src
git commit -m "feat(rig): compose RigController from selected descriptor; NoModel state + port selection + CDC-ACM fallback"
```

---

### Task 4: Settings persistence — radio model + port override

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/RadioModelSettingsTest.kt`

**Interfaces:**
- Consumes: DataStore key patterns (existing).
- Produces (relied on by Task 5): `StationSettings.radioModelId: String?` (default null), `StationSettings.catPortOverride: Int?` (default null); `SettingsRepository.setRadioModel(id: String)`, `SettingsRepository.setCatPortOverride(index: Int?)`; `SettingsSlice.radioModelId`, `SettingsSlice.catPortOverride`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/net/ft8vc/app/settings/RadioModelSettingsTest.kt`:

```kotlin
package net.ft8vc.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Radio-model persistence contract — no default model (null until set). */
class RadioModelSettingsTest {

    @Test
    fun radioModelDefaultsToNull() {
        assertNull(StationSettings().radioModelId)
    }

    @Test
    fun catPortOverrideDefaultsToNull() {
        assertNull(StationSettings().catPortOverride)
    }

    @Test
    fun fieldsAreAssignable() {
        val s = StationSettings(radioModelId = "ftdx10", catPortOverride = 1)
        assertEquals("ftdx10", s.radioModelId)
        assertEquals(1, s.catPortOverride)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RadioModelSettingsTest"`
Expected: FAIL — `radioModelId`/`catPortOverride` unresolved.

- [ ] **Step 3: Add the fields, keys, setters, and load**

In `StationSettings.kt`, add to the data class (after `catBaud`):

```kotlin
    /** Selected radio model id (see net.ft8vc.rig.RigRegistry). Null = none chosen. */
    val radioModelId: String? = null,
    /** Operator override for the CAT serial port index; null = descriptor default. */
    val catPortOverride: Int? = null,
```

In `SettingsRepository.kt`, add to the `Keys` object:

```kotlin
        val RADIO_MODEL = stringPreferencesKey("radio_model")
        val CAT_PORT_OVERRIDE = intPreferencesKey("cat_port_override")
```

Add to the `StationSettings(...)` construction in the load flow (next to `catBaud = ...`):

```kotlin
            radioModelId = prefs[Keys.RADIO_MODEL],
            catPortOverride = prefs[Keys.CAT_PORT_OVERRIDE],
```

Add setters (next to `setCatBaud`):

```kotlin
    suspend fun setRadioModel(id: String) {
        dataStore.edit { it[Keys.RADIO_MODEL] = id }
    }

    suspend fun setCatPortOverride(index: Int?) {
        dataStore.edit {
            if (index == null) it.remove(Keys.CAT_PORT_OVERRIDE) else it[Keys.CAT_PORT_OVERRIDE] = index
        }
    }
```

In `SettingsBridge.kt`, add to `SettingsSlice` (after `catBaud`):

```kotlin
    val radioModelId: String? = null,
    val catPortOverride: Int? = null,
```

and map them in the `StationSettings.toSlice()` (or equivalent mapping) alongside `catBaud = catBaud`:

```kotlin
        radioModelId = radioModelId,
        catPortOverride = catPortOverride,
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RadioModelSettingsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(settings): persist selected radio model + CAT port override (no default)"
```

---

### Task 5: OperateViewModel wiring — descriptor mirror, NoModel, apply-defaults

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/PttMethodMappingTest.kt`

**Interfaces:**
- Consumes: `RigRegistry`, `RigDescriptor`, `PttMethod`, `RigController.setDescriptor/availablePortCount/catPortOverride` (Tasks 2–3), `SettingsSlice.radioModelId/catPortOverride` (Task 4).
- Produces (relied on by Task 6): `OperateUiState.radioModelId: String?`; `OperateViewModel.setRadioModel(id: String)`, `setCatPortOverride(index: Int?)`, `availableSerialPortCount(): Int`; private `PttMethod.toPreference()` mapping. Selecting a model applies the descriptor's `defaultBaud` and `defaultPtt`.

- [ ] **Step 1: Write the failing test** (the one piece with pure logic worth pinning: the PttMethod → PttPreference mapping used when applying a model's default)

`app/src/test/java/net/ft8vc/app/settings/PttMethodMappingTest.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.app.toPreference
import net.ft8vc.rig.PttMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class PttMethodMappingTest {

    @Test
    fun everyMethodMapsToTheMatchingPreference() {
        assertEquals(PttPreference.AUTO, PttMethod.AUTO.toPreference())
        assertEquals(PttPreference.CAT, PttMethod.CAT.toPreference())
        assertEquals(PttPreference.RTS, PttMethod.RTS.toPreference())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.PttMethodMappingTest"`
Expected: FAIL — `toPreference` unresolved.

- [ ] **Step 3: Add the mapping (top-level fun in OperateViewModel.kt, above the class)**

Add the two new imports (`PttPreference` is already imported at line 18 — do
not re-add it):

```kotlin
import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigRegistry
```

Then the top-level mapping (above `class OperateViewModel`):

```kotlin
/** Map a descriptor's default PTT method onto the app's PTT preference. */
fun PttMethod.toPreference(): PttPreference = when (this) {
    PttMethod.AUTO -> PttPreference.AUTO
    PttMethod.CAT -> PttPreference.CAT
    PttMethod.RTS -> PttPreference.RTS
}
```

- [ ] **Step 4: Wire the descriptor mirror + model-selection actions**

In the settings-collect block (around the `catBaud` mirror at `OperateViewModel.kt:334`), add a descriptor mirror immediately after it (inside the same `collect { s -> ... }`):

```kotlin
                // Radio-model mirror: resolve the selected id to a descriptor and
                // apply it to the controller, rebinding like the CAT-baud mirror.
                val wantModel = s.radioModelId?.let { RigRegistry.byId(it) }
                if (rig.descriptor?.id != wantModel?.id || rig.catPortOverride != s.catPortOverride) {
                    rig.setDescriptor(wantModel)
                    rig.catPortOverride = s.catPortOverride
                    if (!state.value.isTransmitting) {
                        viewModelScope.launch(rigSession.catDispatcher) {
                            rig.rebind()
                            withContext(Dispatchers.Main) { prepareRig() }
                        }
                    }
                }
```

In the UI-state builder where `catBaud`/`pttPreference` are copied into `OperateUiState` (around `OperateViewModel.kt:250`), add:

```kotlin
                radioModelId = settings.radioModelId,
```

Add the `NoModel` branch to `prepareRig()`'s `when (rig.state())`:

```kotlin
            RigController.State.NoModel -> {
                _viewState.update {
                    it.copy(
                        pttReady = false,
                        catReady = false,
                        txStatus = "Select your radio model in Settings",
                    )
                }
            }
```

Add the public actions (near `setCatBaud`):

```kotlin
    /** Select the radio model; applies the model's default baud + PTT method. */
    fun setRadioModel(id: String) {
        val d = RigRegistry.byId(id) ?: return
        viewModelScope.launch {
            settingsRepo.setRadioModel(id)
            settingsRepo.setCatBaud(d.defaultBaud)
            settingsRepo.setPttPreference(d.defaultPtt.toPreference())
        }
    }

    fun setCatPortOverride(index: Int?) {
        viewModelScope.launch { settingsRepo.setCatPortOverride(index) }
    }

    fun availableSerialPortCount(): Int = rig.availablePortCount()
```

Place these beside the existing `setCatBaud`/`setPttPreference` methods (around
`OperateViewModel.kt:555-561`), which use the same
`viewModelScope.launch { settingsRepo.setX(...) }` pattern.

In `OperateUiState.kt`, add the field (near `catBaud`):

```kotlin
    /** Selected radio model id, or null if none chosen yet. */
    val radioModelId: String? = null,
```

- [ ] **Step 5: Run the mapping test + app suite**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.PttMethodMappingTest"`
Expected: PASS.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (full app suite — no regressions).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(app): wire radio-model selection into RigController (NoModel state, apply defaults)"
```

---

### Task 6: Settings UI — radio model picker + port override + dynamic title

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `OperateUiState.radioModelId` (Task 5), `vm.setRadioModel`, `vm.setCatPortOverride`, `vm.availableSerialPortCount` (Task 5), `RigRegistry` (Task 2).
- Produces: no downstream code consumers (leaf UI). Verified visually + build.

UI/Compose changes are validated by build + on-device visual check (this codebase unit-tests ViewModels, not composables). Keep the new pickers structurally identical to the existing `CatBaudPicker`.

- [ ] **Step 1: Add a RadioModelPicker and CatPortOverridePicker to RadioSettingsSection**

In `RadioSettingsSection.kt`, extend the parameter list and prepend the model picker. Change the function signature:

```kotlin
fun RadioSettingsSection(
    state: OperateUiState,
    usbDiagnostics: String,
    serialPortCount: Int,
    onSelectRadioModel: (String) -> Unit,
    onSelectCatPort: (Int?) -> Unit,
    onSelectDialFrequency: (Long) -> Unit,
    onReadRig: () -> Unit,
    onSetRigDataUsb: () -> Unit,
    onSetCatBaud: (Int) -> Unit,
    onSetPttPreference: (PttPreference) -> Unit,
) {
```

At the top of the `Column`, before the `if (state.catReady)` block, add:

```kotlin
        RadioModelPicker(
            selectedId = state.radioModelId,
            onSelect = onSelectRadioModel,
        )
        if (serialPortCount > 1) {
            CatPortOverridePicker(
                override = state.catPortOverride,
                portCount = serialPortCount,
                onSelect = onSelectCatPort,
            )
        }
        if (state.radioModelId == null) {
            Text(
                "Select your radio model to enable CAT and PTT.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
```

Change the existing `else` copy under `if (state.catReady)` from the Digirig-specific text to model-aware text:

```kotlin
        } else if (state.radioModelId != null) {
            Text(
                "CAT unavailable — connect the radio's serial link and grant USB permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
```

Add the two composables (mirroring `CatBaudPicker`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioModelPicker(
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = selectedId
        ?.let { id -> RigRegistry.byId(id)?.displayName }
        ?: "Select your radio model"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Radio model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RigRegistry.all.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(d.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatPortOverridePicker(
    override: Int?,
    portCount: Int,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = override?.let { "Port $it" } ?: "Auto (from model)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("CAT port") },
            supportingText = { Text("This radio exposes $portCount serial ports") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto (from model)") },
                onClick = { expanded = false; onSelect(null) },
            )
            (0 until portCount).forEach { i ->
                DropdownMenuItem(
                    text = { Text("Port $i") },
                    onClick = { expanded = false; onSelect(i) },
                )
            }
        }
    }
}
```

Add the import at the top of the file:

```kotlin
import net.ft8vc.rig.RigRegistry
```

- [ ] **Step 2: Update the call site + dynamic section title**

In `SettingsScreen.kt` (around line 140), replace the section:

```kotlin
            val rigTitle = state.radioModelId
                ?.let { net.ft8vc.rig.RigRegistry.byId(it)?.displayName }
                ?.let { "Rig ($it)" }
                ?: "Radio"
            SettingsSection(rigTitle) {
                RadioSettingsSection(
                    state = state,
                    usbDiagnostics = vm.usbDiagnostics(),
                    serialPortCount = vm.availableSerialPortCount(),
                    onSelectRadioModel = vm::setRadioModel,
                    onSelectCatPort = vm::setCatPortOverride,
                    onSelectDialFrequency = vm::setRigFrequency,
                    onReadRig = vm::readRig,
                    onSetRigDataUsb = vm::setRigDataUsb,
                    onSetCatBaud = vm::setCatBaud,
                    onSetPttPreference = vm::setPttPreference,
                )
            }
```

Update the hardcoded FT-891 footer line (`SettingsScreen.kt:378`, "Field setup: Yaesu FT-891 + Digirig Mobile…") to be model-neutral:

```kotlin
                    "Field reference: Yaesu FT-891 + Digirig Mobile. Other rigs: see docs/HARDWARE.md.",
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat(ui): radio model picker + CAT port override in Settings (no default)"
```

---

### Task 7: Docs, verification tracking, full sweep + hardware gate

**Files:**
- Modify: `docs/RIG.md`
- Modify: `.claude/CLAUDE.md`
- Create: `docs/RIG_MODELS.md`

**Interfaces:** none (docs + verification).

- [ ] **Step 1: Create the model verification tracker**

`docs/RIG_MODELS.md`:

```markdown
# Supported radios (multi-rig)

Registry: `rig/src/main/java/net/ft8vc/rig/RigRegistry.kt`. Protocol tables:
`YaesuCat.FT891` and `YaesuModels`. "Verified" = confirmed on real hardware
(CAT sync, TX keys, full QSO); "CAT from manual" = protocol authored from the
CAT manual + cross-checked against FT8CN, transport fields (PID/port) best-guess.

| Model    | id      | Connection            | Status            |
|----------|---------|-----------------------|-------------------|
| FT-891   | ft891   | Digirig (CP2102)      | Verified (reference rig) |
| FT-991A  | ft991a  | built-in USB          | CAT from manual   |
| FTDX10   | ftdx10  | built-in USB (CP2105) | CAT from manual   |
| FT-710   | ft710   | built-in USB          | CAT from manual   |
| FTDX101  | ftdx101 | built-in USB (CP2105) | CAT from manual   |
| FTX-1    | ftx1    | built-in USB (dual-UART) | CAT from manual — transport unverified |

## Adding / verifying a model

1. Add or confirm the `YaesuModelSpec` (tuning range from the CAT manual).
2. Add the `RigDescriptor` to `RigRegistry` (baud, `catPortIndex`, PTT, any
   `customProbePids`).
3. On real hardware: confirm CAT sync, the correct CAT port index (dual-UART),
   TX keying, and built-in-codec audio; flip the row to Verified and set
   `transportVerified = true`.
```

- [ ] **Step 2: Update RIG.md and CLAUDE.md**

In `docs/RIG.md`, under the Phase 1 seams section, add:

```markdown
## Radio model registry (multi-rig phase 2)

`RigDescriptor` (id, protocol factory, default baud, CAT port index, PTT method,
custom prober PIDs) + the static `RigRegistry` compose the Phase 1 seams per
model. There is **no default rig** — the operator selects one in Settings;
`RigController` reports `State.NoModel` until then. The CAT port index handles
dual-UART built-in-USB rigs (CP2105 Enhanced port); an advanced Settings
override appears only when a device exposes >1 port. See
[RIG_MODELS.md](RIG_MODELS.md) for the supported list and verification status.
```

In `.claude/CLAUDE.md`, add a row to the component table:

```markdown
| **RigRegistry** | Static `RigDescriptor` table of supported radios; no default (operator selects) | `rig/src/main/java/net/ft8vc/rig/RigRegistry.kt` |
```

- [ ] **Step 3: Full sweep**

Run: `./gradlew clean test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all module unit tests pass.

- [ ] **Step 4: Commit**

```bash
git add docs .claude/CLAUDE.md
git commit -m "docs(rig): document radio model registry + verification tracker"
```

- [ ] **Step 5: Hardware gate (owner — merge gate for the phase)**

On the `multi-rig` debug build:

1. **No-model state:** fresh selection empty → Settings shows "Select your radio model"; no CAT/PTT; no crash.
2. **FT-891 parity:** select **Yaesu FT-891**; with the Digirig attached, confirm identical Phase 1 behavior — "Rig in sync", frequency tracking, DATA-U, TX keys on slot, full QSO, PTT never keys on connect/permission. This is the parity gate.
3. **Model switch:** switching models applies that model's default baud + PTT; switching back to FT-891 restores 38400 + AUTO.
4. **FTX-1 (when hardware available):** select **Yaesu FTX-1** over built-in USB; confirm the CAT port index (use the override if port 0 is wrong), CAT sync, TX, and that the built-in codec appears as a selectable audio device. Update `RIG_MODELS.md` + `transportVerified`.
```
