# Coding Conventions

**Analysis Date:** 2026-06-21

## Naming Patterns

**Files:**
- PascalCase for class/object files: `QsoMachine.kt`, `AnswerSelector.kt`
- PascalCase for data classes: `QsoForm.kt`, `QsoSnapshot.kt`
- PascalCase for utility objects: `QsoMessages.kt`, `MaidenheadGrid.kt`, `SlotTiming.kt`
- Single top-level public type per file (one class/object/interface per file)

**Functions:**
- camelCase for public and private functions
- Single-word or compound descriptive names: `startCq()`, `answerCq()`, `onDecodes()`, `formatReport()`
- Verb-first for action methods: `applyForm()`, `markTransmitted()`, `setManualControl()`
- Boolean getters as properties: `val isActive: Boolean`, `val isComplete: Boolean`
- Predicate functions as `isX` or `hasX`: `isValidCall()`, `isValid4()`, `hasCustomOverride()`
- Private functions in companion objects use underscore prefix for internal helpers (rarely used; most are regular functions)

**Variables:**
- camelCase for all local and member variables
- Short names for iteration: `d` for decode, `it` for current item in lambdas
- Null-safe: postfix `?` in names when nullable: `dxCall: String?`, `dxGrid: String?`
- Private mutable state with leading underscore for backing fields in sealed types (rare in this codebase; prefer immutable data classes)

**Types:**
- PascalCase for all classes, interfaces, objects, enums, and sealed types
- Enum values in CONSTANT_CASE within Kotlin enums: `FIRST`, `BEST_SNR`, `FURTHEST` (for `AnswerPolicy`)
- Exception types end in `Exception`: never used in this codebase (see Error Handling below)
- Sealed interface for discriminated unions: `sealed interface QsoRx { ... }`
- Companion objects named implicitly (no explicit name): `companion object { ... }`

## Code Style

**Formatting:**
- Kotlin Official style (enforced via IDE: `JetCodeStyleSettings` in `.idea/codeStyles/Project.xml`)
- Indentation: 4 spaces (configurable; current project uses 4-space)
- Line length: no hard limit enforced; typical: 100-120 characters
- Bracket style: opening brace on same line (Kotlin convention)
- No semicolons

**Linting:**
- No explicit linter configured (no `.ktlint` or `detekt` config files)
- Relies on IDE inspections and Kotlin compiler warnings
- All source files compile without warnings at `jvmTarget = JVM_17`

**Imports:**
- Standard import organization by IDE (Android Studio)
- Wildcard imports not used
- No star imports in this codebase

## Import Organization

**Order:**
1. Android / AndroidX framework imports
2. Kotlin stdlib imports
3. Third-party library imports (androidx.*, kotlinx.*, java.*)
4. Project-local imports (net.ft8vc.*)

**Example from `OperateViewModel.kt`:**
```kotlin
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.audio.AudioInputs
import net.ft8vc.core.ActivationProfile
import kotlinx.coroutines.Dispatchers
```

**Path Aliases:**
- No path aliases (gradle `include` statements) used in imports
- Fully qualified imports required: `net.ft8vc.core.QsoMachine`, `net.ft8vc.app.OperateViewModel`

## Error Handling

**Patterns:**
- **Validation before state change:** Input validated before applying: `isValidCall()`, `isValidGrid()` called before use
- **Exceptions for internal contract violations:** `throw IllegalStateException("Encoder rejected: $message")` when internal preconditions fail
- **Graceful null coalescing:** `?.let { ... }` for optional chains, early returns for validation failures
- **Unchecked exceptions (not caught explicitly):** Errors bubble up to caller (typical Kotlin style)

**Example from `QsoMachine.kt`:**
```kotlin
fun applyForm(form: QsoForm) {
    dxCall = form.dxCall.trim().uppercase().takeIf { it.isNotEmpty() }
    dxGrid = form.dxGrid.trim().uppercase().takeIf { it.isNotEmpty() }
}
```

**Example from `StationProfileValidator.kt`:**
```kotlin
fun isValidCall(call: String): Boolean {
    val c = call.trim().uppercase()
    if (!CALLSIGN.matches(c)) return false
    val core = c.substringBefore('/')
    return core.any { it.isDigit() } && core.any { it.isLetter() }
}
```

**Common exceptions in UI layer:**
- `InterruptedException` caught and ignored when coroutines are cancelled: `catch (_: InterruptedException) { }`
- CAT/serial failures logged as strings: `_state.update { it.copy(catStatus = t.message ?: "CAT error") }`

## Logging

**Framework:** No centralized logging framework; uses:
- `throw IllegalStateException("message")` for logic errors
- State mutations captured in flow-based UI state (`_state.update { ... }`)
- Error messages stored as UI strings: `catStatus: String` in `OperateUiState`
- No console logging in production code

**Patterns:**
- Error messages included in state for UI display
- Throwable messages extracted as fallback: `t.message ?: "CAT error"`

## Comments

**When to Comment:**
- Function-level: Doc comments for public APIs (rare; most are self-documenting)
- High-level explanations for complex state machines
- Enum documentation for each value

**JSDoc/KDoc:**
- KDoc (Kotlin doc) used for public classes and functions
- Format: `/** ... */` above declarations
- Includes description, parameter docs, return value docs

**Example from `QsoMachine.kt`:**
```kotlin
/**
 * Feed decodes from a completed RX slot. Advances the state when the expected
 * reply from [dxCall] (addressed to [myCall]) is present. Returns true if the
 * state changed.
 */
fun onDecodes(
    decodes: List<QsoDecode>,
    answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    excludedDx: Set<String> = emptySet(),
): Boolean { ... }
```

**Inline comments:**
- Minimal; code is self-documenting
- Brief explanations of non-obvious logic (state machine transitions)
- No comment drift (comments stay in sync with code due to small, focused functions)

## Function Design

**Size:**
- Small and focused: most functions 10–50 lines
- Largest function: `OperateViewModel.kt` @ 1135 lines (noted as needing refactor into sub-controllers)
- Typical: 15–30 lines for business logic

**Parameters:**
- Limited count: max 5–6 for public functions (see `onDecodes()` with 3 params + defaults)
- Default values used for optional parameters: `answerPolicy: AnswerPolicy = AnswerPolicy.FIRST`
- No varargs in this codebase; lists used instead: `decodes: List<QsoDecode>`
- Named arguments supported but not required in call sites

**Return Values:**
- Single return type (no multiple returns)
- Sealed interfaces for discriminated unions: `sealed interface QsoRx { ... }`
- Nullable returns for optional results: `fun selectCq(...): QsoDecode?`
- Boolean for success/failure: `fun onDecodes(...): Boolean`
- No void functions; side effects via coroutines/flows

## Module Design

**Exports:**
- Top-level classes/objects exported as-is
- No barrel files (no `index.kt` that re-exports all module contents)
- Each module has focused public API surface

**Structure by module:**
- `core/`: Pure FT8 logic, no Android dependencies, fully testable
- `app/`: UI, ViewModels, screen composition
- `audio/`: Audio I/O (USB/device capture/playback)
- `rig/`: CAT controller (serial, Yaesu, Digirig)
- `data/`: Database and persistence (Room, DataStore)
- `ft8-native/`: JNI bindings to native FT8 encoder/decoder

**Visibility:**
- Internal implementation classes marked `private`
- Public APIs exposed at module boundary
- No `internal` keyword used (relies on package visibility)

## Constants

**Pattern:**
- Companion objects for module-level constants (rare)
- Regex patterns as private lazy properties: 
  ```kotlin
  private val GRID4 = Regex("^[A-R]{2}[0-9]{2}$", RegexOption.IGNORE_CASE)
  ```
- Enum values for options: `AnswerPolicy.FIRST`, `DecodeViewMode.OPERATE`

## Sealed Types and ADTs

**Sealed interfaces:** Used for parseable message results:
```kotlin
sealed interface QsoRx {
    data class Cq(val call: String, val grid: String?) : QsoRx
    data class GridReply(val target: String, val sender: String, val grid: String) : QsoRx
    data object Other : QsoRx
}
```

**When clause exhaustiveness:** All subtypes handled in when expressions (compiler checks)

---

*Convention analysis: 2026-06-21*
