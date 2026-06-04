# AGENTS.md — FT8VC

Guidance for AI coding agents working in this repository.

## Project summary

**FT8VC** is an open-source Android FT8 transceiver app (Kotlin + Jetpack Compose + NDK).
Target field setup: **Yaesu FT-891 + Digirig Mobile** over USB-C OTG.

| Doc | Purpose |
|-----|---------|
| [README.md](README.md) | Overview, roadmap, legal |
| [docs/README.md](docs/README.md) | Component doc index |
| [docs/TESTING.md](docs/TESTING.md) | Unit + instrumented test guide |
| [docs/SDK_SETUP.md](docs/SDK_SETUP.md) | SDK / NDK / CMake pins |

## Architecture

```
app/          Compose UI (Operate / Log / Settings), ViewModels
core/         Slot scheduler, FT8 message models, QSO state machine (pure Kotlin)
audio/        12 kHz USB audio capture/playback, DSP, waterfall
rig/          PTT + CAT backends (Digirig first)
data/         Room logbook + ADIF export
ft8-native/   NDK JNI bridge → kgoba/ft8_lib
```

**Dependency rule:** `app` wires modules together in `OperateViewModel`. Put **testable logic** in
`core`, `rig` (protocol helpers), or `data` (ADIF/formatting). Keep Android I/O in `app`, `audio`,
and `rig` backends.

**Naming note:** Older docs may still say `MonitorViewModel` / `MonitorScreen`. Current code uses
`OperateViewModel` and `OperateScreen`. Update stale references when you touch those docs.

## Toolchain (do not bump casually)

Pins live in `gradle/libs.versions.toml`:

| Pin | Value |
|-----|-------|
| JDK | 17 |
| AGP | 9.2.1 |
| Kotlin | 2.3.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 28 |
| NDK | 29.0.14206865 (stable r29 only — not r30 RC) |
| CMake | 4.1.2 |

After NDK or native changes, do a clean native rebuild (see [docs/SDK_SETUP.md](docs/SDK_SETUP.md)).

## Commands

```powershell
# Full unit test suite (CI gate)
.\gradlew.bat testDebugUnitTest

# Debug APK
.\gradlew.bat assembleDebug

# Single module
.\gradlew.bat :core:testDebugUnitTest

# Native decoder (device/emulator required)
.\gradlew.bat :ft8-native:connectedDebugAndroidTest
```

CI (`.github/workflows/build.yml`) runs `testDebugUnitTest` then `assembleDebug` on every PR.

## Code conventions

- **Kotlin 17**, functional Compose UI, ViewModels for screen state
- **Minimal diffs** — match surrounding style; no drive-by refactors
- **Comments** only for non-obvious protocol/timing behavior
- **Pure logic first** — if you can avoid Android APIs, put code in `core` (or pure helpers in `rig` / `data`)
- **Inject time** — use epoch millis parameters (see `SlotCollector`, `SlotTiming`) so tests stay deterministic
- **JUnit 4** — `org.junit.Test`, descriptive method names (`initiatorRunsFullSequence`, not `test1`)

## Definition of done (mandatory)

Every change that touches product behavior, public APIs, or module boundaries is **incomplete**
until tests and docs are updated. This is not optional.

### 1. Unit tests — required when applicable

| Change type | Required test action |
|-------------|---------------------|
| New/changed pure Kotlin logic (`core`, `rig` helpers, `data` formatting) | Add or update tests in that module's `src/test/java/` |
| Logic extracted from `app` / ViewModel for testability | Tests in `core` (preferred) or module where logic lives |
| Bug fix | Regression test that fails without the fix |
| JNI / native codec (`ft8-native`) | Instrumented test in `src/androidTest/` |
| Compose-only / layout-only UI tweak | No unit test required unless behavior changes |
| USB / audio hardware integration | Manual checklist in [docs/HARDWARE.md](docs/HARDWARE.md) if behavior changes; no automated test yet |

Before finishing:

1. Run `.\gradlew.bat testDebugUnitTest` (or the relevant `:module:testDebugUnitTest`).
2. Fix failures. Do not skip or disable tests to green the build.
3. Confirm new/changed behavior has assertions — not just compilation.

**Coverage expectation:** 100% of *applicable* changes get tests. "Applicable" means any logic
that can run on the JVM without a device. If you add a function with branching or protocol rules,
it needs a test. If you only rename a string resource, it does not.

### 2. Documentation — required when applicable

| Change type | Update |
|-------------|--------|
| New module API, type, or workflow | Matching `docs/<MODULE>.md` |
| User-visible UI or settings | [docs/APP.md](docs/APP.md) and/or [README.md](README.md) roadmap if scope shifts |
| Test conventions or CI | [docs/TESTING.md](docs/TESTING.md) |
| Build / SDK pins | [docs/SDK_SETUP.md](docs/SDK_SETUP.md), `gradle/libs.versions.toml`, CI workflow |
| Hardware or field setup | [docs/HARDWARE.md](docs/HARDWARE.md) |
| Release process | [docs/RELEASE.md](docs/RELEASE.md) |

Before finishing:

1. Identify which doc(s) describe what you changed.
2. Update them in the **same PR** as the code.
3. If you add a test class, add a row to the module table in [docs/TESTING.md](docs/TESTING.md).

### 3. Agent checklist (every task)

```
[ ] Logic in the right module (core/rig/data vs app)?
[ ] Unit or instrumented tests added/updated for applicable behavior?
[ ] .\gradlew.bat testDebugUnitTest passes?
[ ] Component docs updated (docs/*.md)?
[ ] Stale MonitorViewModel references fixed if touched?
[ ] No secrets, keystores, or credentials committed?
[ ] TX defaults remain receive-only unless explicitly requested?
```

## Where to add tests (by module)

| Module | Test location | Framework |
|--------|---------------|-----------|
| `core` | `core/src/test/java/net/ft8vc/core/` | JUnit 4 |
| `audio` | `audio/src/test/java/net/ft8vc/audio/` | JUnit 4 |
| `rig` | `rig/src/test/java/net/ft8vc/rig/` | JUnit 4 |
| `data` | `data/src/test/java/net/ft8vc/data/` | JUnit 4 |
| `ft8-native` | `ft8-native/src/androidTest/` | AndroidJUnit4 |
| `app` | Prefer extracting logic to `core`; ViewModel tests use coroutines test when added |

Example pure-logic test pattern (from `QsoMachineTest`):

```kotlin
@Test
fun initiatorRunsFullSequence() {
    val m = QsoMachine("W0DEV", "EM26")
    m.startCq()
    assertEquals(QsoState.CallingCq, m.state)
    assertTrue(m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8)))
    // ...
}
```

## Safety and product constraints

- **Amateur radio:** TX requires a valid license. App defaults to **receive-only**; TX is opt-in in Settings.
- **Do not** weaken license gating or TX safeguards without explicit user request.
- **Do not** commit signing keys, keystores, or `.env` secrets.
- **Do not** force-push to `main`, skip git hooks, or amend others' commits.

## Git

- Commit only when the user asks.
- Follow existing commit message style (short imperative subject, focus on *why*).

## Out of scope for agents unless asked

- Bumping compileSdk, NDK r30+, or AGP without user approval
- Large UI redesigns unrelated to the task
- Adding dependencies not required by the task
- Creating markdown files the user did not request (except doc updates required by Definition of Done)
