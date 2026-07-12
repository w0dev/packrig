# Multi-Rig Phase 1: Transport + Protocol Seams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fused Digirig backend with composable `SerialTransport` / `CatProtocol` / `PttStrategy` seams over usb-serial-for-android, with zero user-visible change and FT-891 byte-equivalence.

**Architecture:** `RigController` composes `UsbSerialTransport` (wraps usb-serial-for-android's `UsbSerialPort`) + `YaesuCat` (pure protocol, parameterized by `YaesuModelSpec`, FT-891 first entry) + `RtsPttStrategy` into a `SerialRigBackend` that implements the existing `RigBackend`/`CatControl` interfaces. `CatControl` loses its `Ft891Cat.Mode` leak (`modeLabel()`/`setDataMode()` instead). `RigSession` and its timeout/unreachable policy do not change.

**Tech Stack:** Kotlin, Android USB Host, `com.github.mik3y:usb-serial-for-android:3.9.0` (JitPack, scoped), JUnit4 JVM tests.

**Spec:** `docs/superpowers/specs/2026-07-04-multi-rig-support-design.md` (Phase 1 section)

## Global Constraints

- **Behavior parity:** FT-891 + Digirig RX/TX/CAT behavior must be byte-equivalent to current `readiness`. No user-visible change in this phase.
- **PTT safety:** RTS is hardware PTT on the Digirig. RTS must never be asserted during open/close/init — only by `keyPtt()`.
- **Dependency rules:** JitPack repo only via `exclusiveContent` scoped to `com.github.mik3y`; exact pin `3.9.0`; checksum verification for that artifact (spec "Dependency Decision", all three mitigations required).
- **Module rules:** protocol code is pure (no Android imports); `rig` module classes live in the flat `net.ft8vc.rig` package (matching existing style); one public top-level type per file.
- **Field gate:** the phase does not merge to a release channel until the FT-891 + Digirig field regression in Task 10 passes. Code tasks can land on `multi-rig` before that.
- Work on branch `multi-rig`. All commands run from repo root `/Users/bsmirks/git/ft8vc`.

---

### Task 1: JitPack repo (scoped) + dependency pin

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `rig/build.gradle.kts`

**Interfaces:**
- Consumes: nothing.
- Produces: `libs.usb.serial.android` catalog accessor; classes `com.hoho.android.usbserial.driver.*` resolvable from the `rig` module (used by Tasks 8–9).

- [ ] **Step 1: Add the scoped JitPack repository**

In `settings.gradle.kts`, replace the `dependencyResolutionManagement` block with:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is JitPack-only. exclusiveContent means JitPack
        // can never resolve anything outside com.github.mik3y (supply-chain
        // guard rail — see docs/USB_SERIAL_LIB_UPGRADE.md).
        exclusiveContent {
            forRepository {
                maven { url = uri("https://jitpack.io") }
            }
            filter {
                includeGroup("com.github.mik3y")
            }
        }
    }
}
```

- [ ] **Step 2: Pin the version in the catalog**

In `gradle/libs.versions.toml`, under `[versions]` (after the `mockk` line), add:

```toml
# CAT serial transport (JitPack, scoped to com.github.mik3y in settings.gradle.kts).
# Upgrade runbook: docs/USB_SERIAL_LIB_UPGRADE.md
usbSerial = "3.9.0"
```

Under `[libraries]`, add:

```toml
usb-serial-android = { group = "com.github.mik3y", name = "usb-serial-for-android", version.ref = "usbSerial" }
```

- [ ] **Step 3: Add the dependency to the rig module**

In `rig/build.gradle.kts` `dependencies` block, after `implementation(project(":core"))`:

```kotlin
    implementation(libs.usb.serial.android)
```

- [ ] **Step 4: Verify resolution**

Run: `./gradlew :rig:dependencies --configuration debugCompileClasspath | grep usb-serial`
Expected: `+--- com.github.mik3y:usb-serial-for-android:3.9.0`

Run: `./gradlew :rig:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml rig/build.gradle.kts
git commit -m "build(rig): add usb-serial-for-android 3.9.0 via scoped JitPack repo"
```

---

### Task 2: Dependency verification metadata (scoped to the JitPack artifact)

**Files:**
- Create: `gradle/verification-metadata.xml`

**Interfaces:**
- Consumes: Task 1's dependency.
- Produces: build-time checksum enforcement for `com.github.mik3y:*`; everything else trusted (unchanged behavior).

- [ ] **Step 1: Generate checksums**

Run: `./gradlew --write-verification-metadata sha256 :rig:dependencies`
Expected: `BUILD SUCCESSFUL`; `gradle/verification-metadata.xml` now exists with `<component>` entries for many artifacts.

- [ ] **Step 2: Prune to the JitPack artifact only**

Edit `gradle/verification-metadata.xml`: delete every `<component>` **except** the `com.github.mik3y` ones (keep both the `.aar` and `.pom`/`.module` artifact entries with their generated `sha256` values — do not retype them), and make the `<configuration>` block exactly:

```xml
   <configuration>
      <verify-metadata>false</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <!-- Verify checksums ONLY for the JitPack-served group; everything else
              comes from Google/Maven Central and is trusted as before.
              See docs/USB_SERIAL_LIB_UPGRADE.md. -->
         <trust group="^(?!com\.github\.mik3y$).*$" regex="true"/>
      </trusted-artifacts>
   </configuration>
```

- [ ] **Step 3: Verify a clean build passes**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (no `Dependency verification failed` errors).

- [ ] **Step 4: Tamper check (proves verification is live)**

Edit one hex digit of the `.aar`'s `sha256` value, then run: `./gradlew :rig:dependencies --configuration debugCompileClasspath`
Expected: FAILURE containing `Dependency verification failed`.
Restore the correct digit, rerun, expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add gradle/verification-metadata.xml
git commit -m "build: checksum-verify the JitPack usb-serial artifact"
```

---

### Task 3: CatControl mode-leak fix (`modeLabel()` / `setDataMode()`)

Removes `Ft891Cat.Mode` from the app-facing interface while the old backend is still in place — everything keeps working, the seam just gets rig-agnostic.

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/CatControl.kt`
- Modify: `rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt:135-140`
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt:201-203`
- Modify: `rig/src/testFixtures/java/net/ft8vc/rig/fakes/FakeRigBackend.kt` **and** `rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackend.kt` (identical copies — a known AGP testFixtures workaround; keep them identical)
- Modify: `rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackendSelfTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/RigSession.kt:85-132`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:40,681`
- Test: `app/src/test/java/net/ft8vc/app/controllers/RigSessionTest.kt`

**Interfaces:**
- Consumes: existing `CatControl`, `Ft891Cat`.
- Produces (relied on by Tasks 7, 9): `CatControl.modeLabel(): String?`, `CatControl.setDataMode(): Boolean`, `CatControl.dataModeLabel(): String`; `RigSession.setDataMode(): Boolean`; `FakeRigBackend.currentModeLabel: String`, `FakeRigBackend.configureModeLabel(label: String)`, `FakeRigBackend.DATA_MODE_LABEL = "DATA-U"`.

- [ ] **Step 1: Update the failing tests first**

In `rig/src/test/java/net/ft8vc/rig/fakes/FakeRigBackendSelfTest.kt`, replace the `mode_defaultAndSetMode_roundTrip` test with:

```kotlin
    @Test
    fun modeLabel_defaultAndSetDataMode_roundTrip() {
        val fake = FakeRigBackend()
        assertEquals("DATA-U", fake.modeLabel())
        assertEquals("DATA-U", fake.dataModeLabel())

        fake.configureModeLabel("USB")
        assertEquals("USB", fake.modeLabel())

        assertTrue(fake.setDataMode())
        assertEquals("DATA-U", fake.modeLabel())
        assertEquals("DATA-U", fake.currentModeLabel)
    }
```

In the same file's `configureTimeoutHz_andTimeoutMode_returnNullOnce` test, change the two `fake.mode()` calls to `fake.modeLabel()`. In `simulateDetach_disablesEverything_reattachRecovers`, change `assertNull(fake.mode())` → `assertNull(fake.modeLabel())`, `assertFalse(fake.setMode(Ft891Cat.Mode.USB))` → `assertFalse(fake.setDataMode())`, and `assertNotNull(fake.mode())` → `assertNotNull(fake.modeLabel())`. Remove the `import net.ft8vc.rig.Ft891Cat` line.

In `app/src/test/java/net/ft8vc/app/controllers/RigSessionTest.kt`, remove `import net.ft8vc.rig.Ft891Cat` and replace the `setMode_dataUsb_appliesToFake_andSlice` test with:

```kotlin
    @Test
    fun setDataMode_appliesToFake_andSlice() = runTest {
        fake.configureModeLabel("LSB")
        val ok = session.setDataMode()
        assertTrue(ok)
        assertEquals("DATA-U", fake.currentModeLabel)
        assertEquals("DATA-U", session.slice.value.rigMode)
        assertEquals("Mode set", session.slice.value.catStatus)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :rig:testDebugUnitTest :app:testDebugUnitTest`
Expected: FAIL — compile errors (`configureModeLabel`, `setDataMode` unresolved).

- [ ] **Step 3: Change the interface**

Replace the mode members of `rig/src/main/java/net/ft8vc/rig/CatControl.kt` (keep `frequencyHz`, `setFrequencyHz`, `catPtt` as-is; update the class KDoc's Ft891 references to say "rig protocol" instead):

```kotlin
    /**
     * Read the current operating mode as a display label (e.g. "DATA-U"), or
     * null on failure. Labels are protocol-defined; the app treats them as
     * opaque strings.
     */
    fun modeLabel(): String?

    /** Put the rig in its FT8 data mode (e.g. DATA-U). Returns true if sent. */
    fun setDataMode(): Boolean

    /** Display label of the mode [setDataMode] selects (e.g. "DATA-U"). */
    fun dataModeLabel(): String
```

- [ ] **Step 4: Update the implementations**

`rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt` — replace `mode()`/`setMode(...)` overrides with:

```kotlin
    override fun modeLabel(): String? {
        val reply = catExchange(Ft891Cat.readModeCommand()) ?: return null
        return Ft891Cat.parseModeResponse(reply)?.label
    }

    override fun setDataMode(): Boolean =
        catWrite(Ft891Cat.setModeCommand(Ft891Cat.Mode.DATA_USB))

    override fun dataModeLabel(): String = Ft891Cat.Mode.DATA_USB.label
```

`rig/src/main/java/net/ft8vc/rig/RigController.kt` — replace the `mode()`/`setMode(...)` overrides with:

```kotlin
    override fun modeLabel(): String? = digirig?.modeLabel()

    override fun setDataMode(): Boolean = digirig?.setDataMode() ?: false

    override fun dataModeLabel(): String =
        digirig?.dataModeLabel() ?: Ft891Cat.Mode.DATA_USB.label
```

Both copies of `FakeRigBackend.kt` — replace the ctor param `initialMode: Ft891Cat.Mode = Ft891Cat.Mode.DATA_USB` with `initialModeLabel: String = DATA_MODE_LABEL`, the `modeValue` field with `@Volatile private var modeLabelValue: String = initialModeLabel`, the `currentMode` accessor with `val currentModeLabel: String get() = modeLabelValue`, and the `mode()`/`setMode` overrides with:

```kotlin
    override fun modeLabel(): String? {
        if (detached) return null
        if (timeoutMode) {
            timeoutMode = false
            return null
        }
        if (latencyMs > 0L) {
            Thread.sleep(latencyMs)
        }
        return modeLabelValue
    }

    override fun setDataMode(): Boolean {
        if (detached) return false
        modeLabelValue = DATA_MODE_LABEL
        return true
    }

    override fun dataModeLabel(): String = DATA_MODE_LABEL

    fun configureModeLabel(label: String) {
        modeLabelValue = label
    }
```

and add at the bottom of the class:

```kotlin
    companion object {
        /** Label of the FT8 data mode the fake selects (mirrors FT-891 DATA-U). */
        const val DATA_MODE_LABEL = "DATA-U"
    }
```

(`Ft891Cat` import stays for now — `setFrequencyHz` still uses `Ft891Cat.MIN_FREQ_HZ..MAX_FREQ_HZ`; Task 9 retargets it.)

- [ ] **Step 5: Update RigSession and OperateViewModel**

`app/src/main/java/net/ft8vc/app/controllers/RigSession.kt`:
- Remove `import net.ft8vc.rig.Ft891Cat`.
- In `readRigImpl()`: `val mode = catControl.mode()` → `val mode = catControl.modeLabel()`, and `rigMode = mode?.label ?: it.rigMode` → `rigMode = mode ?: it.rigMode`.
- Replace `setMode`/`setModeImpl` with:

```kotlin
    suspend fun setDataMode(): Boolean {
        if (_slice.value.catUnreachable) return false
        return setDataModeImpl()
    }

    private suspend fun setDataModeImpl(): Boolean = runCat("Setting mode…") {
        if (catControl.setDataMode()) {
            val actual = catControl.modeLabel()
            _slice.update {
                it.copy(rigMode = actual ?: catControl.dataModeLabel(), catStatus = "Mode set")
            }
            recordSuccess()
            true
        } else {
            _slice.update { it.copy(catStatus = "Mode set rejected") }
            false
        }
    } ?: false
```

`app/src/main/java/net/ft8vc/app/OperateViewModel.kt`:
- Line 681: `viewModelScope.launch { rigSession.setMode(Ft891Cat.Mode.DATA_USB) }` → `viewModelScope.launch { rigSession.setDataMode() }`
- Remove `import net.ft8vc.rig.Ft891Cat` (line 40).

- [ ] **Step 6: Run all unit tests**

Run: `./gradlew :rig:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (all).

- [ ] **Step 7: Commit**

```bash
git add rig/src app/src
git commit -m "refactor(rig): make CatControl rig-agnostic (modeLabel/setDataMode)"
```

---

### Task 4: SerialTransport interface + FakeSerialTransport

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/SerialTransport.kt`
- Create: `rig/src/test/java/net/ft8vc/rig/fakes/FakeSerialTransport.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/fakes/FakeSerialTransportSelfTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (relied on by Tasks 6–9): `SerialTransport` with `open(): Boolean`, `close()`, `write(bytes: ByteArray, timeoutMs: Int): Boolean`, `read(buffer: ByteArray, timeoutMs: Int): Int` (bytes read; 0 = timeout; -1 = error), `setRts(asserted: Boolean): Boolean`, `setDtr(asserted: Boolean): Boolean`. Fake extras: `enqueueReply(ascii: String)`, `writtenAscii(): List<String>`, `rtsEdges: MutableList<Boolean>`, `readChunkLimit: Int`, `failWrites: Boolean`, `openResult: Boolean`, `opened: Boolean`.

- [ ] **Step 1: Write the failing self-test**

`rig/src/test/java/net/ft8vc/rig/fakes/FakeSerialTransportSelfTest.kt`:

```kotlin
package net.ft8vc.rig.fakes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSerialTransportSelfTest {

    @Test
    fun writesAreRecordedAsAscii() {
        val t = FakeSerialTransport()
        assertTrue(t.write("FA;".toByteArray(Charsets.US_ASCII), 200))
        assertEquals(listOf("FA;"), t.writtenAscii())
    }

    @Test
    fun failWrites_makesWriteReturnFalse() {
        val t = FakeSerialTransport()
        t.failWrites = true
        assertFalse(t.write("FA;".toByteArray(Charsets.US_ASCII), 200))
    }

    @Test
    fun enqueuedReplyIsReadBack_respectingChunkLimit() {
        val t = FakeSerialTransport()
        t.enqueueReply("FA014074000;")
        t.readChunkLimit = 5
        val buf = ByteArray(64)

        assertEquals(5, t.read(buf, 200))
        assertEquals("FA014", String(buf, 0, 5, Charsets.US_ASCII))
        assertEquals(5, t.read(buf, 200))
        assertEquals(2, t.read(buf, 200))
        // Drained: further reads time out.
        assertEquals(0, t.read(buf, 200))
    }

    @Test
    fun rtsEdgesAreRecorded() {
        val t = FakeSerialTransport()
        t.setRts(true)
        t.setRts(false)
        assertEquals(listOf(true, false), t.rtsEdges)
    }

    @Test
    fun openHonoursOpenResult() {
        val t = FakeSerialTransport()
        assertTrue(t.open())
        assertTrue(t.opened)
        t.close()
        assertFalse(t.opened)
        t.openResult = false
        assertFalse(t.open())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.fakes.FakeSerialTransportSelfTest"`
Expected: FAIL — `FakeSerialTransport` unresolved.

- [ ] **Step 3: Write the interface and the fake**

`rig/src/main/java/net/ft8vc/rig/SerialTransport.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Byte-pipe seam between rig protocol code and the USB serial stack. The
 * production implementation wraps usb-serial-for-android ([UsbSerialTransport]);
 * tests use a scripted fake. All calls are blocking — use off the main thread.
 *
 * RTS/DTR matter beyond flow control here: on a Digirig, RTS is hardware PTT.
 * Implementations must never assert RTS except via [setRts].
 */
interface SerialTransport {

    /** Open and configure the port (baud/8N1 fixed at construction). */
    fun open(): Boolean

    /** Release the port. Safe to call when not open. */
    fun close()

    /** Write all of [bytes]. Returns true on success. */
    fun write(bytes: ByteArray, timeoutMs: Int): Boolean

    /** Read into [buffer]. Returns bytes read; 0 on timeout; -1 on error. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Drive the RTS modem line. Returns true if the line was set. */
    fun setRts(asserted: Boolean): Boolean

    /** Drive the DTR modem line. Returns true if the line was set. */
    fun setDtr(asserted: Boolean): Boolean
}
```

`rig/src/test/java/net/ft8vc/rig/fakes/FakeSerialTransport.kt`:

```kotlin
package net.ft8vc.rig.fakes

import net.ft8vc.rig.SerialTransport

/**
 * Scripted [SerialTransport] for JVM tests: records writes and RTS/DTR edges,
 * plays back enqueued reply bytes (optionally in small chunks to exercise
 * partial-read reassembly), and simulates write failures and timeouts.
 */
class FakeSerialTransport : SerialTransport {

    var openResult = true
    var opened = false
        private set
    var failWrites = false

    /** Max bytes returned per read() — lower to force partial reads. */
    var readChunkLimit = Int.MAX_VALUE

    val writes = mutableListOf<ByteArray>()
    val rtsEdges = mutableListOf<Boolean>()
    val dtrEdges = mutableListOf<Boolean>()

    private val pending = ArrayDeque<Byte>()

    fun enqueueReply(ascii: String) {
        ascii.toByteArray(Charsets.US_ASCII).forEach { pending.addLast(it) }
    }

    fun writtenAscii(): List<String> = writes.map { it.toString(Charsets.US_ASCII) }

    override fun open(): Boolean {
        opened = openResult
        return openResult
    }

    override fun close() {
        opened = false
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (failWrites) return false
        writes += bytes.copyOf()
        return true
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (pending.isEmpty()) return 0
        val n = minOf(buffer.size, readChunkLimit, pending.size)
        repeat(n) { buffer[it] = pending.removeFirst() }
        return n
    }

    override fun setRts(asserted: Boolean): Boolean {
        rtsEdges += asserted
        return true
    }

    override fun setDtr(asserted: Boolean): Boolean {
        dtrEdges += asserted
        return true
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.fakes.FakeSerialTransportSelfTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src
git commit -m "feat(rig): SerialTransport seam + scripted fake"
```

---

### Task 5: CatProtocol interface + YaesuCat with FT-891 byte-equivalence

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/CatProtocol.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/YaesuModelSpec.kt`
- Create: `rig/src/main/java/net/ft8vc/rig/YaesuCat.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/YaesuCatTest.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/Ft891EquivalenceTest.kt` (temporary — deleted in Task 9 together with `Ft891Cat`)

**Interfaces:**
- Consumes: nothing (pure).
- Produces (relied on by Tasks 6–9): `CatProtocol` (members below), `YaesuModelSpec(name, minFreqHz, maxFreqHz, dataModeCode, modeLabels)`, `class YaesuCat(val model: YaesuModelSpec) : CatProtocol`, `YaesuCat.FT891: YaesuModelSpec`, `YaesuCat.TERMINATOR = ';'`.

- [ ] **Step 1: Write the failing tests**

`rig/src/test/java/net/ft8vc/rig/YaesuCatTest.kt` (ports every `Ft891CatTest` case to the new API, asserting literal wire strings):

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YaesuCatTest {

    private val cat = YaesuCat(YaesuCat.FT891)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun readFrequencyCommandIsFaQuery() {
        assertEquals("FA;", cat.readFrequencyCommand().toString(Charsets.US_ASCII))
    }

    @Test
    fun setFrequencyPadsToNineDigits() {
        // 14.074 MHz, the FT8 calling frequency on 20 m.
        assertEquals(
            "FA014074000;",
            cat.setFrequencyCommand(14_074_000)!!.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun setFrequencyRejectsOutOfRange() {
        assertNull(cat.setFrequencyCommand(0))
        assertNull(cat.setFrequencyCommand(60_000_000))
    }

    @Test
    fun parseFrequencyRoundTrips() {
        val cmd = cat.setFrequencyCommand(7_074_000)!!
        // The radio echoes the same opcode+payload for a query reply.
        assertEquals(7_074_000L, cat.parseFrequency(cmd))
    }

    @Test
    fun parseFrequencyRejectsGarbage() {
        assertNull(cat.parseFrequency(ascii("MD0C;")))
        assertNull(cat.parseFrequency(ascii("FA12345;")))
        assertNull(cat.parseFrequency(ascii("FAabcdefghi;")))
        assertNull(cat.parseFrequency(ascii("")))
    }

    @Test
    fun parseFrequencyToleratesWhitespace() {
        assertEquals(14_074_000L, cat.parseFrequency(ascii("  FA014074000;\r\n")))
    }

    @Test
    fun setDataModeSelectsDataUsb() {
        assertEquals("MD0C;", cat.setDataModeCommand().toString(Charsets.US_ASCII))
        assertEquals("DATA-U", cat.dataModeLabel)
    }

    @Test
    fun parseModeReadsVfoDigitAndCode() {
        assertEquals("DATA-U", cat.parseModeLabel(ascii("MD0C;")))
        assertEquals("USB", cat.parseModeLabel(ascii("MD02;")))
        // Bare code without the VFO digit is tolerated (matches Ft891Cat).
        assertEquals("USB", cat.parseModeLabel(ascii("MD2;")))
    }

    @Test
    fun parseModeRejectsUnknownCode() {
        assertNull(cat.parseModeLabel(ascii("MD0Z;")))
        assertNull(cat.parseModeLabel(ascii("FA014074000;")))
    }

    @Test
    fun pttCommandsMatchYaesuTxOpcodes() {
        // YaesuCat narrows pttCommand's return to non-null ByteArray.
        assertEquals("TX1;", cat.pttCommand(true).toString(Charsets.US_ASCII))
        assertEquals("TX0;", cat.pttCommand(false).toString(Charsets.US_ASCII))
    }

    @Test
    fun replyTerminatorIsSemicolon() {
        assertEquals(';'.code.toByte(), cat.replyTerminator)
    }
}
```

`rig/src/test/java/net/ft8vc/rig/Ft891EquivalenceTest.kt`:

```kotlin
package net.ft8vc.rig

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-equivalence between the new parameterized YaesuCat(FT891) and the
 * legacy Ft891Cat it replaces. TEMPORARY: deleted together with Ft891Cat once
 * RigController is rewired (multi-rig phase 1, Task 9). Behavior parity on the
 * wire is the milestone bar.
 */
class Ft891EquivalenceTest {

    private val cat = YaesuCat(YaesuCat.FT891)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun commandsAreByteIdentical() {
        assertArrayEquals(ascii(Ft891Cat.readFrequencyCommand()), cat.readFrequencyCommand())
        assertArrayEquals(ascii(Ft891Cat.readModeCommand()), cat.readModeCommand())
        assertArrayEquals(ascii(Ft891Cat.txOnCommand()), cat.pttCommand(true))
        assertArrayEquals(ascii(Ft891Cat.txOffCommand()), cat.pttCommand(false))
        assertArrayEquals(
            ascii(Ft891Cat.setModeCommand(Ft891Cat.Mode.DATA_USB)),
            cat.setDataModeCommand(),
        )
        for (hz in longArrayOf(30_000, 1_840_000, 7_074_000, 14_074_000, 28_074_000, 56_000_000)) {
            assertArrayEquals(ascii(Ft891Cat.setFrequencyCommand(hz)), cat.setFrequencyCommand(hz))
        }
    }

    @Test
    fun frequencyRangeMatches() {
        assertEquals(Ft891Cat.MIN_FREQ_HZ, YaesuCat.FT891.minFreqHz)
        assertEquals(Ft891Cat.MAX_FREQ_HZ, YaesuCat.FT891.maxFreqHz)
    }

    @Test
    fun everyLegacyModeLabelParsesIdentically() {
        for (mode in Ft891Cat.Mode.entries) {
            val reply = ascii("MD0${mode.code};")
            assertEquals(mode.label, cat.parseModeLabel(reply))
        }
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.YaesuCatTest" --tests "net.ft8vc.rig.Ft891EquivalenceTest"`
Expected: FAIL — `CatProtocol`/`YaesuCat` unresolved.

- [ ] **Step 3: Write the implementation**

`rig/src/main/java/net/ft8vc/rig/CatProtocol.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Pure per-rig-family CAT protocol: builds command bytes and parses reply
 * bytes. No I/O, no Android — the transport lives behind [SerialTransport].
 * ByteArray (not String) so binary protocols (Icom CI-V) fit the same seam.
 */
interface CatProtocol {

    /** Byte that ends a reply frame (';' for ASCII CAT, 0xFD for CI-V). */
    val replyTerminator: Byte

    /** Display label of the mode [setDataModeCommand] selects (e.g. "DATA-U"). */
    val dataModeLabel: String

    /** Query the current VFO frequency. */
    fun readFrequencyCommand(): ByteArray

    /** Tune to [hz], or null if out of the model's range. */
    fun setFrequencyCommand(hz: Long): ByteArray?

    /** Parse a frequency reply into Hz, or null if malformed. */
    fun parseFrequency(reply: ByteArray): Long?

    /** Query the operating mode. */
    fun readModeCommand(): ByteArray

    /** Parse a mode reply into a display label, or null if unrecognized. */
    fun parseModeLabel(reply: ByteArray): String?

    /** Select the rig's FT8 data mode. */
    fun setDataModeCommand(): ByteArray

    /** Key/unkey via CAT, or null if this family has no CAT PTT. */
    fun pttCommand(on: Boolean): ByteArray?
}
```

`rig/src/main/java/net/ft8vc/rig/YaesuModelSpec.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Per-model parameters for the Yaesu "new CAT" ASCII dialect ([YaesuCat]).
 * Adding a rig in this family is a new spec value plus tests — no new parser.
 */
data class YaesuModelSpec(
    val name: String,
    /** Lowest/highest VFO frequencies the rig accepts, in Hz. */
    val minFreqHz: Long,
    val maxFreqHz: Long,
    /** `MD0x` code of the FT8 data mode (DATA-USB). */
    val dataModeCode: Char,
    /** `MD0x` code → display label, for mode readback. */
    val modeLabels: Map<Char, String>,
)
```

`rig/src/main/java/net/ft8vc/rig/YaesuCat.kt`:

```kotlin
package net.ft8vc.rig

/**
 * Yaesu "new CAT" ASCII protocol (FT-891 / FT-991A / FTDX10 / FT-710 family),
 * parameterized by a [YaesuModelSpec]. Commands are ASCII terminated by `;`;
 * a query is the bare opcode (`FA;`) and the radio answers with opcode + data
 * (`FA014074000;`). Frequencies are whole Hz zero-padded to 9 digits.
 */
class YaesuCat(val model: YaesuModelSpec) : CatProtocol {

    override val replyTerminator: Byte = TERMINATOR.code.toByte()

    override val dataModeLabel: String = model.modeLabels.getValue(model.dataModeCode)

    override fun readFrequencyCommand(): ByteArray = ascii("FA;")

    override fun setFrequencyCommand(hz: Long): ByteArray? {
        if (hz !in model.minFreqHz..model.maxFreqHz) return null
        return ascii("FA%09d;".format(hz))
    }

    override fun parseFrequency(reply: ByteArray): Long? {
        val body = opcodeBody(reply, "FA") ?: return null
        if (body.length != 9 || !body.all { it.isDigit() }) return null
        return body.toLongOrNull()
    }

    override fun readModeCommand(): ByteArray = ascii("MD0;")

    override fun parseModeLabel(reply: ByteArray): String? {
        val body = opcodeBody(reply, "MD") ?: return null
        // Reply form is "0x" (VFO digit + mode code); a bare "x" is tolerated.
        val code = when (body.length) {
            2 -> body[1]
            1 -> body[0]
            else -> return null
        }
        return model.modeLabels[code]
    }

    override fun setDataModeCommand(): ByteArray = ascii("MD0${model.dataModeCode};")

    override fun pttCommand(on: Boolean): ByteArray = ascii(if (on) "TX1;" else "TX0;")

    /** Strip whitespace, the trailing terminator, and a matching opcode prefix. */
    private fun opcodeBody(reply: ByteArray, opcode: String): String? {
        val trimmed = reply.toString(Charsets.US_ASCII).trim().removeSuffix(TERMINATOR.toString())
        if (!trimmed.startsWith(opcode)) return null
        return trimmed.substring(opcode.length)
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    companion object {
        const val TERMINATOR = ';'

        /** Yaesu FT-891 — the reference rig. Mirrors the legacy Ft891Cat table. */
        val FT891 = YaesuModelSpec(
            name = "Yaesu FT-891",
            minFreqHz = 30_000L,
            maxFreqHz = 56_000_000L,
            dataModeCode = 'C',
            modeLabels = mapOf(
                '1' to "LSB", '2' to "USB", '3' to "CW-U", '4' to "FM",
                '5' to "AM", '6' to "RTTY-L", '7' to "CW-L", '8' to "DATA-L",
                '9' to "RTTY-U", 'A' to "DATA-FM", 'B' to "FM-N", 'C' to "DATA-U",
                'D' to "AM-N", 'E' to "C4FM",
            ),
        )
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.YaesuCatTest" --tests "net.ft8vc.rig.Ft891EquivalenceTest"`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src
git commit -m "feat(rig): CatProtocol seam + parameterized YaesuCat with FT-891 byte-equivalence"
```

---

### Task 6: PttStrategy (RTS + CAT implementations)

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/PttStrategy.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/PttStrategyTest.kt`

**Interfaces:**
- Consumes: `SerialTransport` (Task 4), `CatControl` (Task 3).
- Produces (relied on by Task 7; descriptors wire `CatPttStrategy` in phase 2): `PttStrategy.key(): Boolean`, `PttStrategy.release(): Boolean`; `RtsPttStrategy(transport)`, `CatPttStrategy(cat)`.

- [ ] **Step 1: Write the failing test**

`rig/src/test/java/net/ft8vc/rig/PttStrategyTest.kt`:

```kotlin
package net.ft8vc.rig

import net.ft8vc.rig.fakes.FakeRigBackend
import net.ft8vc.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PttStrategyTest {

    @Test
    fun rtsStrategy_drivesTheRtsLine() {
        val transport = FakeSerialTransport()
        val ptt = RtsPttStrategy(transport)
        assertTrue(ptt.key())
        assertTrue(ptt.release())
        assertEquals(listOf(true, false), transport.rtsEdges)
    }

    @Test
    fun catStrategy_delegatesToCatPtt() {
        val cat = FakeRigBackend()
        val ptt = CatPttStrategy(cat)
        assertTrue(ptt.key())
        assertTrue(ptt.release())
        assertEquals(2, cat.catPttInvocations)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.PttStrategyTest"`
Expected: FAIL — `RtsPttStrategy` unresolved.

- [ ] **Step 3: Write the implementation**

`rig/src/main/java/net/ft8vc/rig/PttStrategy.kt`:

```kotlin
package net.ft8vc.rig

/**
 * How the transmitter gets keyed. Selected per rig configuration: Digirig-style
 * interfaces key a modem line ([RtsPttStrategy]); rigs whose CAT jack has no
 * hardware PTT line key over CAT ([CatPttStrategy]). Phase 2's rig descriptors
 * pick the strategy; until then [SerialRigBackend] uses RTS and RigController
 * keeps its CAT-vs-RTS probe.
 */
interface PttStrategy {

    /** Assert push-to-talk. Returns true if the underlying action succeeded. */
    fun key(): Boolean

    /** Release push-to-talk. Returns true if the underlying action succeeded. */
    fun release(): Boolean
}

/** Hardware PTT on the serial RTS line (Digirig Mobile). */
class RtsPttStrategy(private val transport: SerialTransport) : PttStrategy {
    override fun key(): Boolean = transport.setRts(true)
    override fun release(): Boolean = transport.setRts(false)
}

/** Software PTT over CAT (e.g. Yaesu `TX1;`/`TX0;`). */
class CatPttStrategy(private val cat: CatControl) : PttStrategy {
    override fun key(): Boolean = cat.catPtt(true)
    override fun release(): Boolean = cat.catPtt(false)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.PttStrategyTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add rig/src
git commit -m "feat(rig): PttStrategy seam with RTS and CAT implementations"
```

---

### Task 7: SerialRigBackend (transport + protocol composition)

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt`
- Modify: `rig/build.gradle.kts` (unit tests hit `android.util.Log`)
- Test: `rig/src/test/java/net/ft8vc/rig/SerialRigBackendTest.kt`

**Interfaces:**
- Consumes: `SerialTransport`, `CatProtocol`, `RtsPttStrategy`, `CatControl` (Tasks 3–6).
- Produces (relied on by Task 9): `class SerialRigBackend(transport, protocol, nowMs = System::currentTimeMillis) : RigBackend, CatControl` with `open(): Boolean` and `close()`.

- [ ] **Step 1: Let JVM tests tolerate android.util.Log**

In `rig/build.gradle.kts`, inside the `android { }` block, add:

```kotlin
    testOptions {
        // SerialRigBackend logs CAT exchanges via android.util.Log; JVM unit
        // tests get no-op Log calls instead of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }
```

- [ ] **Step 2: Write the failing tests**

`rig/src/test/java/net/ft8vc/rig/SerialRigBackendTest.kt`:

```kotlin
package net.ft8vc.rig

import net.ft8vc.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialRigBackendTest {

    private val transport = FakeSerialTransport()
    private val backend = SerialRigBackend(transport, YaesuCat(YaesuCat.FT891))

    @Test
    fun open_deassertsRtsSoPttCannotStartKeyed() {
        assertTrue(backend.open())
        assertTrue(transport.opened)
        assertEquals(listOf(false), transport.rtsEdges)
    }

    @Test
    fun open_failsWhenTransportFails() {
        transport.openResult = false
        assertFalse(backend.open())
        // No RTS activity on a port we never opened.
        assertEquals(emptyList<Boolean>(), transport.rtsEdges)
    }

    @Test
    fun frequencyQuery_writesFaAndParsesReply() {
        transport.enqueueReply("FA014074000;")
        assertEquals(14_074_000L, backend.frequencyHz())
        assertEquals(listOf("FA;"), transport.writtenAscii())
    }

    @Test
    fun frequencyQuery_reassemblesPartialReads() {
        transport.enqueueReply("FA007074000;")
        transport.readChunkLimit = 3
        assertEquals(7_074_000L, backend.frequencyHz())
    }

    @Test
    fun frequencyQuery_timesOutToNull() {
        // No reply enqueued; advance the injected clock 600 ms per look so the
        // 1 s reply deadline expires after a couple of loop iterations.
        var t = 0L
        val slow = SerialRigBackend(transport, YaesuCat(YaesuCat.FT891)) { t += 600; t }
        assertNull(slow.frequencyHz())
    }

    @Test
    fun frequencyQuery_writeFailureIsNull() {
        transport.failWrites = true
        assertNull(backend.frequencyHz())
    }

    @Test
    fun setFrequency_inRangeWrites_outOfRangeDoesNot()  {
        assertTrue(backend.setFrequencyHz(7_074_000L))
        assertEquals(listOf("FA007074000;"), transport.writtenAscii())
        assertFalse(backend.setFrequencyHz(60_000_000L))
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun modeLabel_roundTrip_andDataMode() {
        transport.enqueueReply("MD0C;")
        assertEquals("DATA-U", backend.modeLabel())
        assertTrue(backend.setDataMode())
        assertEquals(listOf("MD0;", "MD0C;"), transport.writtenAscii())
        assertEquals("DATA-U", backend.dataModeLabel())
    }

    @Test
    fun pttKeyAndRelease_driveRtsOnly() {
        backend.keyPtt()
        backend.releasePtt()
        assertEquals(listOf(true, false), transport.rtsEdges)
        assertEquals(0, transport.writes.size)
    }

    @Test
    fun catPtt_writesYaesuTxCommands() {
        assertTrue(backend.catPtt(true))
        assertTrue(backend.catPtt(false))
        assertEquals(listOf("TX1;", "TX0;"), transport.writtenAscii())
    }

    @Test
    fun close_releasesRtsBeforeClosingTransport() {
        backend.open()
        backend.close()
        assertEquals(listOf(false, false), transport.rtsEdges)
        assertFalse(transport.opened)
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.SerialRigBackendTest"`
Expected: FAIL — `SerialRigBackend` unresolved.

- [ ] **Step 4: Write the implementation**

`rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt`:

```kotlin
package net.ft8vc.rig

import android.util.Log

/**
 * [RigBackend] + [CatControl] composed from a [SerialTransport] (byte pipe)
 * and a [CatProtocol] (per-rig command builder/parser). Replaces the fused
 * DigirigRigBackend: hardware PTT is the transport's RTS line via
 * [RtsPttStrategy]; CAT PTT is the protocol's PTT command. CAT exchanges are
 * serialized on [catLock] and blocking — call off the main thread.
 *
 * @param nowMs injectable clock for the reply deadline (tests).
 */
class SerialRigBackend(
    private val transport: SerialTransport,
    private val protocol: CatProtocol,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RigBackend, CatControl {

    private val rtsPtt = RtsPttStrategy(transport)

    /** Serializes CAT exchanges so concurrent reads/writes don't interleave. */
    private val catLock = Any()

    /** Open the transport and start de-keyed (RTS must never idle asserted). */
    fun open(): Boolean {
        if (!transport.open()) return false
        if (!rtsPtt.release()) Log.e(TAG, "Initial RTS de-assert failed")
        return true
    }

    /** Release PTT and close the transport. */
    fun close() {
        runCatching { rtsPtt.release() }
        transport.close()
    }

    override fun keyPtt() {
        if (!rtsPtt.key()) Log.e(TAG, "keyPtt: RTS assert failed")
    }

    override fun releasePtt() {
        if (!rtsPtt.release()) Log.e(TAG, "releasePtt: RTS de-assert failed")
    }

    override fun frequencyHz(): Long? =
        catExchange(protocol.readFrequencyCommand())?.let(protocol::parseFrequency)

    override fun setFrequencyHz(hz: Long): Boolean {
        val command = protocol.setFrequencyCommand(hz) ?: return false
        return catWrite(command)
    }

    override fun modeLabel(): String? =
        catExchange(protocol.readModeCommand())?.let(protocol::parseModeLabel)

    override fun setDataMode(): Boolean = catWrite(protocol.setDataModeCommand())

    override fun dataModeLabel(): String = protocol.dataModeLabel

    override fun catPtt(on: Boolean): Boolean {
        val command = protocol.pttCommand(on) ?: return false
        val ok = catWrite(command)
        Log.i(TAG, "catPtt(on=$on) sent=$ok")
        return ok
    }

    /** Send a CAT command that expects no reply. */
    private fun catWrite(command: ByteArray): Boolean = synchronized(catLock) {
        val ok = transport.write(command, CAT_TIMEOUT_MS)
        Log.i(TAG, "CAT write \"${command.ascii()}\" ok=$ok")
        ok
    }

    /** Send a CAT query and read the terminated reply, or null on timeout. */
    private fun catExchange(command: ByteArray): ByteArray? = synchronized(catLock) {
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return null
        }
        val reply = readReply()
        Log.i(
            TAG,
            "CAT exchange \"${command.ascii()}\" -> " +
                (reply?.let { "\"${it.ascii()}\"" } ?: "<timeout>"),
        )
        reply
    }

    /** Accumulate reads until [CatProtocol.replyTerminator] or the deadline. */
    private fun readReply(): ByteArray? {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var collected = ByteArray(0)
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n > 0) {
                collected += buffer.copyOfRange(0, n)
                val end = collected.indexOfFirst { it == protocol.replyTerminator }
                if (end >= 0) return collected.copyOfRange(0, end + 1)
            }
        }
        Log.w(TAG, "CAT reply timed out (got \"${collected.ascii()}\")")
        return null
    }

    private fun ByteArray.ascii(): String = toString(Charsets.US_ASCII)

    companion object {
        private const val TAG = "SerialRigBackend"
        private const val READ_BUFFER_SIZE = 64

        /** Per-transfer timeout for CAT reads/writes. */
        private const val CAT_TIMEOUT_MS = 200

        /** Overall budget for collecting a complete terminated CAT reply. */
        private const val CAT_REPLY_DEADLINE_MS = 1000L
    }
}
```

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.SerialRigBackendTest"`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add rig/build.gradle.kts rig/src
git commit -m "feat(rig): SerialRigBackend composing transport + protocol + RTS PTT"
```

---

### Task 8: UsbSerialTransport (usb-serial-for-android adapter)

Thin adapter, no JVM test possible (Android USB stack); it is exercised at the Task 10 bench/field gate. Keep it free of logic beyond error mapping.

**Files:**
- Create: `rig/src/main/java/net/ft8vc/rig/UsbSerialTransport.kt`

**Interfaces:**
- Consumes: `SerialTransport` (Task 4), library `UsbSerialPort`.
- Produces (relied on by Task 9): `class UsbSerialTransport(usbManager: UsbManager, port: UsbSerialPort, baud: Int) : SerialTransport`.

- [ ] **Step 1: Write the implementation**

`rig/src/main/java/net/ft8vc/rig/UsbSerialTransport.kt`:

```kotlin
package net.ft8vc.rig

import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * [SerialTransport] over a usb-serial-for-android [UsbSerialPort]. Configures
 * 8N1 at [baud] with flow control off on open, and never asserts RTS during
 * open/close — on a Digirig RTS is hardware PTT, and an RTS blip on connect
 * would key the transmitter (docs/USB_SERIAL_LIB_UPGRADE.md, coupling pt. 4).
 *
 * @param baud must match the rig's CAT RATE menu (FT-891 menu 05-06).
 */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val port: UsbSerialPort,
    private val baud: Int,
) : SerialTransport {

    override fun open(): Boolean {
        val device = port.driver.device
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openDevice returned null (permission revoked?)")
            return false
        }
        return runCatching {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Not every driver supports these; failures are non-fatal.
            runCatching { port.setFlowControl(UsbSerialPort.FlowControl.NONE) }
            runCatching { port.setRTS(false) }
            Log.i(TAG, "Serial port opened: ${device.deviceName} 8N1 @ $baud baud")
            true
        }.getOrElse { t ->
            Log.e(TAG, "Port open/config failed: ${t.message}")
            runCatching { port.close() }
            connection.close()
            false
        }
    }

    override fun close() {
        runCatching { port.setRTS(false) }
        runCatching { port.close() } // also closes the UsbDeviceConnection
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
        runCatching {
            port.write(bytes, timeoutMs)
            true
        }.getOrElse { t ->
            Log.e(TAG, "Serial write failed: ${t.message}")
            false
        }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        runCatching { port.read(buffer, timeoutMs) }.getOrElse { t ->
            Log.e(TAG, "Serial read failed: ${t.message}")
            -1
        }

    override fun setRts(asserted: Boolean): Boolean =
        runCatching {
            port.setRTS(asserted)
            true
        }.getOrElse { t ->
            Log.e(TAG, "setRTS($asserted) failed: ${t.message}")
            false
        }

    override fun setDtr(asserted: Boolean): Boolean =
        runCatching {
            port.setDTR(asserted)
            true
        }.getOrElse { t ->
            Log.e(TAG, "setDTR($asserted) failed: ${t.message}")
            false
        }

    companion object {
        private const val TAG = "UsbSerialTransport"
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :rig:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. If `setFlowControl`/`FlowControl` does not resolve, the library version predates it — delete that line (CP210x defaults to no flow control; the old backend's explicit disable was belt-and-braces).

- [ ] **Step 3: Commit**

```bash
git add rig/src
git commit -m "feat(rig): UsbSerialTransport adapter over usb-serial-for-android"
```

---

### Task 9: Rewire RigController; delete DigirigRigBackend, Cp210x, Ft891Cat

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt` (full replacement below)
- Modify: `rig/src/main/java/net/ft8vc/rig/RigBackend.kt:17-19` (stale `DESCRIPTION` const)
- Modify: both `FakeRigBackend.kt` copies (frequency range source)
- Modify: `rig/src/test/java/net/ft8vc/rig/RigControllerCatBaudTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:19,37,256`
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt:6,23`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt:16,127`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt:10,92`
- Modify: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryCatBaudTest.kt:13,45`
- Delete: `rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt`, `rig/src/main/java/net/ft8vc/rig/Cp210x.kt`, `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt`
- Delete: `rig/src/test/java/net/ft8vc/rig/Cp210xTest.kt`, `rig/src/test/java/net/ft8vc/rig/Ft891CatTest.kt`, `rig/src/test/java/net/ft8vc/rig/Ft891EquivalenceTest.kt`

**Interfaces:**
- Consumes: `SerialRigBackend`, `UsbSerialTransport`, `YaesuCat` (Tasks 5, 7, 8).
- Produces: `RigController` public API unchanged for the app (`state()`, `bindIfPermitted()`, `rebind()`, `ensureReady()`, `configurePttFromCatProbe()`, `useCatPtt`, `catBaud`, `isDigirigReady`, `isCatReady`, `usbDeviceSummary()`, `findDevice()`, `close()`) **except** the baud constant moves: `DigirigRigBackend.DEFAULT_CAT_BAUD` → `RigController.DEFAULT_CAT_BAUD` (still `38_400`).

- [ ] **Step 1: Update the baud-constant test first**

In `rig/src/test/java/net/ft8vc/rig/RigControllerCatBaudTest.kt`, change the assertion to:

```kotlin
        assertEquals(RigController.DEFAULT_CAT_BAUD, controller().catBaud)
```

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigControllerCatBaudTest"`
Expected: FAIL — `RigController.DEFAULT_CAT_BAUD` unresolved.

- [ ] **Step 2: Replace RigController**

Replace the entire contents of `rig/src/main/java/net/ft8vc/rig/RigController.kt` with:

```kotlin
package net.ft8vc.rig

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Discovers a supported USB-serial rig interface (Digirig CP210x today; FTDI /
 * CH340 / PL2303 / CDC-ACM come with the library), manages USB permission, and
 * routes PTT/CAT to a [SerialRigBackend] when available, falling back to a
 * no-op. Phase 1 hardcodes the FT-891 protocol table; phase 2's RigDescriptor
 * registry makes the model selectable.
 *
 * Implements [RigBackend] so callers can key/unkey PTT without caring whether a
 * radio is actually attached (e.g. on the emulator).
 */
class RigController(private val context: Context) : RigBackend, CatControl {

    enum class State {
        /** No supported USB-serial device present. */
        NoDevice,

        /** Device present but USB permission not yet granted. */
        NeedsPermission,

        /** Device present, permission granted, PTT wired. */
        Ready,
    }

    private val appContext = context.applicationContext
    private val usbManager get() = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var backend: SerialRigBackend? = null
    private val fallback = NoOpRigBackend()

    /**
     * CAT baud for the next bind/rebind — must match FT-891 menu 05-06 (CAT RATE).
     * Owner (OperateViewModel) mirrors the persisted setting here; changing it does
     * NOT reconfigure a live connection — call [rebind] to apply.
     */
    @Volatile
    var catBaud: Int = DEFAULT_CAT_BAUD

    /** True once a real serial PTT backend is open. (Name kept for app parity.) */
    val isDigirigReady: Boolean get() = backend != null

    /** Silicon Labs PIDs the stock probe table may lack (CP2102 dual variant). */
    private val customProber = UsbSerialProber(
        ProbeTable().addProduct(0x10C4, 0xEA61, Cp21xxSerialDriver::class.java),
    )

    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
            ?: customProber.findAllDrivers(usbManager).firstOrNull()

    fun findDevice(): UsbDevice? = findDriver()?.device

    /** Short summary of USB devices Android reports (for UI diagnostics). */
    fun usbDeviceSummary(): String {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return "Android sees no USB devices"
        return devices.joinToString("; ") { dev ->
            val vid = dev.vendorId.toString(16).padStart(4, '0')
            val pid = dev.productId.toString(16).padStart(4, '0')
            val name = dev.productName?.toString()?.ifBlank { null } ?: dev.deviceName
            "$vid:$pid $name"
        }
    }

    /** Current discovery/permission state (for UI status). */
    fun state(): State {
        findDevice() ?: return State.NoDevice
        return if (backend != null) State.Ready else State.NeedsPermission
    }

    /**
     * Bind to a connected, permitted serial device. Returns true if PTT is now
     * wired. No-op (returns current readiness) if no device or no permission.
     */
    @Synchronized
    fun bindIfPermitted(): Boolean {
        if (backend != null) return true
        val driver = findDriver() ?: return false
        if (!usbManager.hasPermission(driver.device)) return false
        val port = driver.ports.firstOrNull() ?: return false
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, port, catBaud),
            protocol = YaesuCat(YaesuCat.FT891),
        )
        return if (candidate.open()) {
            backend = candidate
            Log.i(TAG, "Serial rig backend bound for PTT/CAT (${driver.device.deviceName})")
            true
        } else {
            Log.e(TAG, "Backend open() failed after USB permission granted")
            false
        }
    }

    /**
     * Drop any backend left over from a previous USB enumeration and bind the
     * currently attached device. A detach/reattach cycle re-enumerates the
     * device, so a held backend points at dead file descriptors while [state]
     * still reports Ready — call this on USB_DEVICE_ATTACHED before re-probing.
     * Returns true if PTT is wired to the fresh device.
     */
    @Synchronized
    fun rebind(): Boolean {
        backend?.close()
        backend = null
        return bindIfPermitted()
    }

    /**
     * Ensure PTT is wired. If a device is present but unpermitted, prompts the
     * user for USB permission and binds on grant. [onResult] reports readiness.
     */
    fun ensureReady(onResult: (Boolean) -> Unit) {
        if (bindIfPermitted()) {
            onResult(true)
            return
        }
        val device = findDevice()
        if (device == null) {
            onResult(false)
            return
        }
        requestPermission(device, onResult)
    }

    private fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { appContext.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: granted=$granted")
                if (granted) bindIfPermitted()
                onResult(backend != null)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        val flags = PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun active(): RigBackend = backend ?: fallback

    /**
     * When true, PTT uses CAT `TX1;`/`TX0;`. When false (Digirig default), PTT
     * uses the serial **RTS** line — the hardware PTT path on Digirig Mobile.
     *
     * [configurePttFromCatProbe] sets this from a live `FA;` query. Until then
     * we default to RTS so TX works even when CAT readback is broken on the phone.
     */
    @Volatile
    var useCatPtt: Boolean = false

    /**
     * Pick CAT vs RTS PTT from whether the rig answers a frequency query.
     * Call off the main thread (can block ~1 s on timeout).
     */
    fun configurePttFromCatProbe(): String {
        val rig = backend ?: return "no-op"
        useCatPtt = rig.frequencyHz() != null
        val method = if (useCatPtt) "CAT" else "RTS"
        Log.i(TAG, "PTT method: $method (CAT probe reply=${useCatPtt})")
        return method
    }

    override fun keyPtt() {
        val rig = backend
        if (useCatPtt && rig != null) {
            // CAT only — do not assert RTS (Menu 05-08 CAT RTS can latch TX).
            rig.catPtt(true)
        } else {
            active().keyPtt()
        }
    }

    override fun releasePtt() {
        val rig = backend
        if (useCatPtt && rig != null) {
            rig.catPtt(false)
        } else {
            active().releasePtt()
        }
        // Always de-assert RTS after any TX path so hardware PTT cannot stick.
        rig?.releasePtt()
    }

    /** True once CAT can talk to a real rig (serial backend open). */
    val isCatReady: Boolean get() = backend != null

    override fun frequencyHz(): Long? = backend?.frequencyHz()

    override fun setFrequencyHz(hz: Long): Boolean = backend?.setFrequencyHz(hz) ?: false

    override fun modeLabel(): String? = backend?.modeLabel()

    override fun setDataMode(): Boolean = backend?.setDataMode() ?: false

    override fun dataModeLabel(): String =
        backend?.dataModeLabel() ?: YaesuCat(YaesuCat.FT891).dataModeLabel

    override fun catPtt(on: Boolean): Boolean = backend?.catPtt(on) ?: false

    /** Release the USB connection (call from owner's teardown). */
    @Synchronized
    fun close() {
        backend?.close()
        backend = null
    }

    companion object {
        private const val TAG = "RigController"
        private const val ACTION_USB_PERMISSION = "net.ft8vc.rig.USB_PERMISSION"

        /**
         * Default CAT baud. The FT-891 ships at 4800 (menu 05-06); FT8 setups
         * commonly raise it to 38400 for snappier polling. Must match the rig.
         */
        const val DEFAULT_CAT_BAUD = 38_400
    }
}
```

- [ ] **Step 3: Retarget remaining Ft891Cat references**

In **both** `FakeRigBackend.kt` copies: replace `import net.ft8vc.rig.Ft891Cat` with `import net.ft8vc.rig.YaesuCat`, and in `setFrequencyHz` change the range check to:

```kotlin
        if (hz !in YaesuCat.FT891.minFreqHz..YaesuCat.FT891.maxFreqHz) return false
```

In `rig/src/main/java/net/ft8vc/rig/RigBackend.kt`, delete the now-stale companion (`const val DESCRIPTION = "Digirig RTS PTT + FT-891 CAT"`) and update the class KDoc's Digirig/CP2102 sentence to reference `SerialRigBackend`. Verify no one uses it first:

Run: `grep -rn "DESCRIPTION" app/ rig/ core/ audio/ data/ --include="*.kt"`
Expected: only the `RigBackend.kt` definition. (If a consumer appears, leave the constant and skip this deletion.)

- [ ] **Step 4: Move the baud-constant references in the app module**

In each of `SettingsRepository.kt`, `StationSettings.kt`, `OperateUiState.kt`, `SettingsBridge.kt`, `SettingsRepositoryCatBaudTest.kt`: change `import net.ft8vc.rig.DigirigRigBackend` → `import net.ft8vc.rig.RigController` and every `DigirigRigBackend.DEFAULT_CAT_BAUD` → `RigController.DEFAULT_CAT_BAUD`.

- [ ] **Step 5: Delete the legacy files**

```bash
git rm rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt \
       rig/src/main/java/net/ft8vc/rig/Cp210x.kt \
       rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt \
       rig/src/test/java/net/ft8vc/rig/Cp210xTest.kt \
       rig/src/test/java/net/ft8vc/rig/Ft891CatTest.kt \
       rig/src/test/java/net/ft8vc/rig/Ft891EquivalenceTest.kt
```

- [ ] **Step 6: Verify nothing references the deleted types, then run everything**

Run: `grep -rn "Ft891Cat\|DigirigRigBackend\|Cp210x" app/ rig/ core/ audio/ data/ --include="*.kt"`
Expected: no output.

Run: `./gradlew :rig:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(rig): compose RigController from transport/protocol seams; drop fused Digirig backend"
```

---

### Task 10: Docs + full verification + field gate

**Files:**
- Modify: `docs/RIG.md`
- Modify: `.claude/CLAUDE.md` (Key Dependencies)
- Modify: `docs/USB_SERIAL_LIB_UPGRADE.md` (Current state section)

**Interfaces:** none (docs + verification).

- [ ] **Step 1: Update docs**

`docs/RIG.md`: read the file, then update its architecture description to the new shape and add this section (placed after the module-overview material, adjusted to the file's heading style):

```markdown
## Serial transport & protocol seams (multi-rig phase 1)

The rig module is layered so radios and interfaces vary independently:

- `SerialTransport` — byte pipe. Production impl `UsbSerialTransport` wraps
  [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
  **3.9.0** (JitPack, scoped to `com.github.mik3y` via `exclusiveContent`;
  checksum-pinned in `gradle/verification-metadata.xml`). Upgrade runbook:
  [USB_SERIAL_LIB_UPGRADE.md](USB_SERIAL_LIB_UPGRADE.md).
- `CatProtocol` — pure per-family command builder/parser. `YaesuCat` +
  `YaesuModelSpec` cover the Yaesu new-CAT ASCII family; the FT-891 table is
  byte-equivalent to the retired `Ft891Cat`.
- `PttStrategy` — `RtsPttStrategy` (Digirig hardware PTT) or `CatPttStrategy`
  (`TX1;`/`TX0;`). `RigController` still probes CAT reachability to pick the
  method at bind time.
- `SerialRigBackend` — composes the three; `RigController` builds it for the
  FT-891 (phase 2 adds the RigDescriptor registry and a Radio model setting).

Safety invariant: RTS is hardware PTT on the Digirig — nothing may assert RTS
except `keyPtt()`; open/close paths explicitly de-assert it.
```

`.claude/CLAUDE.md` — in "Key Dependencies", after the `ft8_lib` line, add:

```markdown
- `usb-serial-for-android` 3.9.0 (mik3y) - CAT serial transport; JitPack scoped to `com.github.mik3y`, checksum-verified (see docs/USB_SERIAL_LIB_UPGRADE.md)
```

`docs/USB_SERIAL_LIB_UPGRADE.md` — in "Current state", replace the "Introduced by the multi-rig milestone…" bullet with: "In production since multi-rig phase 1 (see `docs/superpowers/plans/2026-07-04-multi-rig-phase1-transport-protocol-seams.md`)."

- [ ] **Step 2: Full build + test sweep**

Run: `./gradlew clean test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all module unit tests pass.

- [ ] **Step 3: Commit**

```bash
git add docs .claude/CLAUDE.md
git commit -m "docs(rig): document transport/protocol seams and usb-serial pin"
```

- [ ] **Step 4: Bench + field gate (owner, real hardware — merge gate for the phase)**

Install the `multi-rig` debug build on the field phone, then verify in order:

1. **PTT safety watch:** with the FT-891 + Digirig attached and the rig's TX indicator visible — connect USB, grant permission, start and stop monitoring. The transmitter must never key during any of it.
2. **CAT parity:** Settings shows the Digirig; Operate reaches "Rig in sync"; frequency readback tracks the dial; band change from the app tunes the rig; mode set lands on DATA-U.
3. **TX/QSO parity:** TX keys on slot start and releases on slot end (watch RTS behavior on Stop QSO / Halt TX too); complete one full QSO.
4. **Baud regression:** switch CAT RATE on the rig and the app (per `radio-settings-cat-baud` flow) and confirm recovery — same behavior as before the swap.

Record the result in the plan/PR notes. This gate must pass before phase 2 starts or anything promotes.
```
