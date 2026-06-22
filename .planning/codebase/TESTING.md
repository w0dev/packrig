# Testing Patterns

**Analysis Date:** 2026-06-21

## Test Framework

**Runner:**
- JUnit 4.13.2 (`junit` library)
- Android Instrumentation Tests: `androidx.test` runner + Espresso (not currently used in unit test structure)
- No Mockito, Robolectric, or mocking framework configured
- No coroutine-specific testing framework (mock coroutines via simple lambdas)

**Config:**
- Build config: `core/build.gradle.kts` declares `testImplementation(libs.junit)`
- No `junit.platform.gradle.plugin` (Jupiter not used; classic JUnit 4)
- Test runner set to: `androidx.test.runner.AndroidJUnitRunner` for instrumentation tests

**Run Commands:**
```bash
./gradlew test                  # Run all unit tests across modules
./gradlew :core:test            # Run tests for core module only
./gradlew :app:test             # Run app module tests
./gradlew :core:testDebug       # Debug-build variant tests
```

**Assertion Library:**
- `org.junit.Assert.*` from JUnit core
- Commonly used: `assertEquals()`, `assertTrue()`, `assertFalse()`, `assertNull()`, `assertNotNull()`
- No Hamcrest or custom assertion library

## Test File Organization

**Location:**
- Co-located with source: `src/test/java/net/ft8vc/core/` mirrors `src/main/java/net/ft8vc/core/`
- One test class per source class: `QsoMachine.kt` → `QsoMachineTest.kt`
- Pure unit tests (no Android framework required)

**Naming:**
- `{ClassName}Test` suffix (e.g., `AnswerSelectorTest`, `StationProfileValidatorTest`)
- Test methods named `{behavior}_{conditions}` (BDD-style): `initiatorRunsFullSequence()`, `selectGridReply_firstUsesSlotOrder()`
- No `@Before` or `@After` setup; tests self-contained with local helper methods

**Structure:**
```
core/src/test/java/net/ft8vc/core/
├── QsoMachineTest.kt
├── AnswerSelectorTest.kt
├── StationProfileValidatorTest.kt
├── MaidenheadGridTest.kt
├── QsoFormLogicTest.kt
├── WavIoTest.kt
├── SlotCollectorTest.kt
├── SlotTimingTest.kt
├── QsoMessagesTest.kt
├── QsoResumeTest.kt
├── TxSlotSelectionTest.kt
├── ActivationProfileTest.kt
├── AbandonedPartnersTest.kt
├── MonitorDecodeFilterTest.kt
├── OperateTxOptionsTest.kt
├── DecodeDistanceTest.kt
├── DecodePrefixTest.kt
└── QsoFormLogicTest.kt
```

## Test Structure

**Suite Organization:**
```kotlin
class QsoMachineTest {
    // All tests for one class in a single test class
    
    private fun decode(vararg messages: String, snr: Int = -10): List<QsoDecode> =
        messages.map { QsoDecode(it, snr) }
    
    @Test
    fun initiatorRunsFullSequence() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        
        assertEquals(QsoState.CallingCq, m.state)
        // ...
    }
}
```

**Patterns:**
- **Setup:** Local helper methods (e.g., `decode()`) instead of `@Before` fixtures
- **Teardown:** Not needed; objects are garbage-collected after each test
- **Assertion:** Inline assertions in test methods (no assertion batching)
- **Act/Assert:** Interleaved: action followed immediately by one or more assertions

**Example from `StationProfileValidatorTest.kt`:**
```kotlin
@Test
fun isValidCall_acceptsStandardCalls() {
    assertTrue(StationProfileValidator.isValidCall("W0DEV"))
    assertTrue(StationProfileValidator.isValidCall("K1ABC"))
    assertTrue(StationProfileValidator.isValidCall("VE2XYZ"))
    assertTrue(StationProfileValidator.isValidCall("w0dev"))
}
```

**Example from `AnswerSelectorTest.kt`:**
```kotlin
@Test
fun selectGridReply_bestSnrPicksStrongest() {
    val decodes = listOf(
        QsoDecode("W0DEV K1ABC FN42", -10),
        QsoDecode("W0DEV N0XYZ FN20", -3),
    )
    val picked = AnswerSelector.selectGridReply(myCall, myGrid, decodes, AnswerPolicy.BEST_SNR)
    assertEquals("W0DEV N0XYZ FN20", picked?.message)
}
```

## Mocking

**Framework:** No mocking framework used
- No Mockito, MockK, or manual mock objects
- Pure data structures tested directly

**Patterns:**
- **Simple lambdas/functions:** Functions are tested directly; lambdas are passed as needed
- **Test data objects:** Lightweight data classes like `QsoDecode(message, snr)` created inline

**Example (no mocking needed):**
```kotlin
fun onDecodes(
    decodes: List<QsoDecode>,  // Test data injected directly
    answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    excludedDx: Set<String> = emptySet(),
): Boolean { ... }

// Test calls directly:
assertTrue(m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8)))
```

**What to Mock:**
- Not applicable; this codebase avoids mocking via pure-function design
- Core module has no Android/IO dependencies
- External dependencies (Android framework, audio, CAT) tested in integration/instrumentation tests (not in unit test suite)

**What NOT to Mock:**
- Sealed interface hierarchies (e.g., `QsoRx`) — test against real types
- Data classes — use real instances
- Enums — use real enum values
- Utility objects — test stateless helpers directly

## Fixtures and Factories

**Test Data:**
- Local helper methods per test class:
  ```kotlin
  private fun decode(vararg messages: String, snr: Int = -10): List<QsoDecode> =
      messages.map { QsoDecode(it, snr) }
  ```
- Hard-coded test values at class level:
  ```kotlin
  private val myCall = "W0DEV"
  private val myGrid = "EM26"
  ```

**Location:**
- Test fixtures live in the test class itself
- No shared fixture library or factory classes
- Decodes, messages, and QSO forms created inline in each test

**Example from `MaidenheadGridTest.kt`:**
```kotlin
@Test
fun distanceKm_increasesWithSeparation() {
    val near = MaidenheadGrid.distanceKm("FN31", "FN32")
    val far = MaidenheadGrid.distanceKm("FN31", "EM26")
    assertNotNull(near)
    assertNotNull(far)
    assertTrue(far!! > near!!)
}
```

## Coverage

**Requirements:** Not enforced (no coverage reports configured)

**View Coverage:** 
```bash
# No built-in coverage reporting
# Manual inspection: check test existence for each module
find /Users/bsmirks/git/ft8vc -name "*Test.kt" | wc -l
# Result: 24 test classes across core, audio, rig, data modules
```

**Current coverage estimate (by module):**
- `core/`: ~18 test classes covering all major algorithms (QSO machine, validation, selection, messaging)
- `audio/`: Minimal; integration tested only
- `rig/`: Integration tested only
- `data/`: Integration tested only (Room/DataStore covered by Android test infrastructure)
- `app/`: ViewModel tested via integration tests; Compose UI tested manually

## Test Types

**Unit Tests:**
- Scope: Single class or pure function
- Approach: Direct instantiation, no mocks, no framework dependencies
- Location: `core/src/test/java/` (24 test classes)
- Examples: `QsoMachineTest`, `AnswerSelectorTest`, `StationProfileValidatorTest`
- Characteristics: Fast (<100ms total), fully deterministic, no I/O

**Integration Tests:**
- Scope: Cross-module or with Android framework (not yet present in test suite)
- Approach: Would use instrumentation runner (`androidx.test.runner.AndroidJUnitRunner`)
- Location: `**/androidTest/java/` directories (only one file present: `Ft8DecodeInstrumentedTest.kt`)
- Status: Minimal coverage; focus is on unit testing core logic

**E2E Tests:**
- Framework: Not used
- Current workflow: Manual testing via emulator/device
- No Espresso or UiAutomator tests in codebase

## Common Patterns

**Async Testing:**
- Not applicable to current tests (all unit tests are synchronous)
- Future ViewModel/coroutine tests would use `runTest` from `kotlinx-coroutines-test`

**Error Testing:**
```kotlin
@Test
fun isValidCall_rejectsEmptyOrPlaceholders() {
    assertFalse(StationProfileValidator.isValidCall(""))
    assertFalse(StationProfileValidator.isValidCall("  "))
    assertFalse(StationProfileValidator.isValidCall("TEST"))
}
```

**Null Handling:**
```kotlin
@Test
fun selectCq_skipsOwnCall() {
    assertNull(
        AnswerSelector.selectCq(
            myCall,
            myGrid,
            listOf(QsoDecode("CQ W0DEV EM26", -5)),
            AnswerPolicy.FIRST,
        ),
    )
}
```

**State Machine Testing:**
```kotlin
@Test
fun initiatorRunsFullSequence() {
    val m = QsoMachine("W0DEV", "EM26")
    m.startCq()
    assertEquals(QsoState.CallingCq, m.state)
    
    assertTrue(m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8)))
    assertEquals(QsoState.SendingReport, m.state)
    
    assertTrue(m.onDecodes(decode("W0DEV K1ABC R-15")))
    assertEquals(QsoState.SendingRoger, m.state)
}
```

## Test Dependencies

**From gradle/libs.versions.toml:**
- `junit = "4.13.2"` — core JUnit 4 framework
- `kotlinx-coroutines-test = "1.10.2"` — coroutine test utilities (not yet used in unit tests)
- `androidx-junit = "1.3.0"` — AndroidX Test extension for JUnit
- `androidx-test-runner = "1.7.0"` — Android instrumentation runner
- `androidx-espresso-core = "3.7.0"` — UI testing (not yet used)

---

*Testing analysis: 2026-06-21*
