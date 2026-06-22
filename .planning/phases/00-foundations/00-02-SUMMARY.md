---
phase: 00-foundations
plan: 02
subsystem: build-tooling
status: complete
tags: [gradle, test-classpath, version-catalog, foundations]
requirements_completed: [FOUND-03, FOUND-04, FOUND-05]
dependency_graph:
  requires: []
  provides:
    - "test-classpath wiring for Turbine 1.2.1 + MockK 1.14.7 in rig/, audio/, ft8-native/, app/"
    - "catalog pin for kotlinx-collections-immutable 0.4.0 (Phase 3 wires as implementation)"
    - "working gradlew wrapper (was pre-existing-broken; fixed inline as Rule 3 deviation)"
  affects:
    - "Plan 00-03 (FakeRigBackend + Ft8DecoderFake + FakeUsbAudioCapture) — now has test deps"
    - "Plan 00-04 (golden-trace harness) — now has Turbine for Flow assertions"
tech_stack:
  added:
    - "app.cash.turbine:turbine:1.2.1 (testImplementation)"
    - "io.mockk:mockk:1.14.7 (testImplementation)"
    - "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0 (catalog-only — NOT wired anywhere)"
  patterns: []
key_files:
  created:
    - ".planning/phases/00-foundations/00-02-SUMMARY.md"
  modified:
    - "gradle/libs.versions.toml"
    - "rig/build.gradle.kts"
    - "audio/build.gradle.kts"
    - "ft8-native/build.gradle.kts"
    - "app/build.gradle.kts"
    - "gradlew (Rule 3 deviation — pre-existing wrapper bug)"
decisions:
  - "Pin kotlinx-collections-immutable in catalog but do NOT wire as implementation; Phase 3 owns the production-classpath landing."
  - "Wire kotlinx-coroutines-test via testImplementation (Turbine hard-depends on it; explicit pin keeps version under our control)."
  - "Leave core/build.gradle.kts unchanged — no controller code under test in Phase 0; existing JUnit-only setup stays."
  - "Auto-fix the pre-existing broken gradlew (Rule 3): without it no plan verification that shells to ./gradlew could run."
metrics:
  duration_minutes: 24
  completed_at: "2026-06-22T05:09:55Z"
  tasks_total: 2
  tasks_completed: 2
  commits: 3
  files_modified: 6
---

# Phase 0 Plan 02: Test-Classpath Foundation Summary

Wired Turbine 1.2.1 + MockK 1.14.7 (+ explicit kotlinx-coroutines-test 1.10.2) into the test classpath of `rig/`, `audio/`, `ft8-native/`, and `app/` via the Gradle version catalog; pre-staged kotlinx-collections-immutable 0.4.0 in the catalog only (Phase 3 wires it as `implementation`).

## What Was Built

1. **gradle/libs.versions.toml** — three new `[versions]` pins (`turbine = "1.2.1"`, `mockk = "1.14.7"`, `kotlinxCollectionsImmutable = "0.4.0"`) and three new `[libraries]` aliases (`turbine`, `mockk`, `kotlinx-collections-immutable`). The existing `coroutines = "1.10.2"` pin is untouched (Kotlin 2.3.21 compat per STACK.md).
2. **rig/build.gradle.kts**, **audio/build.gradle.kts**, **ft8-native/build.gradle.kts**, **app/build.gradle.kts** — each gains exactly three new `testImplementation` lines (12 new lines total across the 4 modules):
   ```kotlin
   testImplementation(libs.turbine)
   testImplementation(libs.mockk)
   testImplementation(libs.kotlinx.coroutines.test)
   ```
3. **core/build.gradle.kts** — intentionally unchanged. (No controller code under test in Phase 0; existing JUnit-only setup stays per the "no production code changes" constraint.)
4. **kotlinx-collections-immutable** is wired ONLY in the catalog. No module's `dependencies { ... }` block references it. Phase 3 will add the `implementation(libs.kotlinx.collections.immutable)` line when DecodeRow gains stable keys + `ImmutableList<Int>` columns.

## Catalog Deltas (gradle/libs.versions.toml)

| Section     | Key                              | Value                                                                          |
| ----------- | -------------------------------- | ------------------------------------------------------------------------------ |
| `[versions]` | `kotlinxCollectionsImmutable`    | `"0.4.0"`                                                                      |
| `[versions]` | `turbine`                        | `"1.2.1"`                                                                      |
| `[versions]` | `mockk`                          | `"1.14.7"`                                                                     |
| `[libraries]` | `turbine`                       | `{ group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }`    |
| `[libraries]` | `mockk`                         | `{ group = "io.mockk", name = "mockk", version.ref = "mockk" }`                |
| `[libraries]` | `kotlinx-collections-immutable` | `{ group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version.ref = "kotlinxCollectionsImmutable" }` |

## Per-Module testImplementation Additions

| Module        | Lines added                                                                                                                                          |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rig/`        | `testImplementation(libs.turbine)`, `testImplementation(libs.mockk)`, `testImplementation(libs.kotlinx.coroutines.test)`                             |
| `audio/`      | `testImplementation(libs.turbine)`, `testImplementation(libs.mockk)`, `testImplementation(libs.kotlinx.coroutines.test)`                             |
| `ft8-native/` | `testImplementation(libs.turbine)`, `testImplementation(libs.mockk)`, `testImplementation(libs.kotlinx.coroutines.test)` (androidTestImplementation block preserved) |
| `app/`        | `testImplementation(libs.turbine)`, `testImplementation(libs.mockk)`, `testImplementation(libs.kotlinx.coroutines.test)` (placed before existing androidTestImplementation lines) |
| `core/`       | **No change** (per phase constraint)                                                                                                                  |

## Gradle Resolution Proof

Configuration name mismatch note: the plan calls for `testCompileClasspath` / `testRuntimeClasspath`, but Android library modules expose variant-aware configurations: `debugUnitTestRuntimeClasspath` and `releaseUnitTestRuntimeClasspath`. Verified via the debug variant on all four modules.

`./gradlew :rig:dependencies --configuration debugUnitTestRuntimeClasspath` output (relevant excerpt):

```
debugUnitTestRuntimeClasspath - Runtime classpath of '/debugUnitTest'.
+--- project :rig (*)
+--- junit:junit:4.13.2
+--- app.cash.turbine:turbine:1.2.1
|    \--- app.cash.turbine:turbine-jvm:1.2.1
|         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
|         +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.21 -> 2.3.21 (*)
|         \--- org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2
+--- io.mockk:mockk:1.14.7
|    \--- io.mockk:mockk-jvm:1.14.7
|         +--- io.mockk:mockk-dsl:1.14.7
|         +--- io.mockk:mockk-agent:1.14.7
|         |    +--- net.bytebuddy:byte-buddy:1.15.11
|         |    +--- net.bytebuddy:byte-buddy-agent:1.15.11
|         |    \--- org.objenesis:objenesis:3.3
|         \--- io.mockk:mockk-core:1.14.7
+--- org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2 (*)
\--- project :core
```

**Resolution checks across all four modules** (`audio/`, `ft8-native/`, `app/` confirmed in parallel):
- `app.cash.turbine:turbine:1.2.1` resolves cleanly.
- `io.mockk:mockk:1.14.7` resolves cleanly (with byte-buddy 1.15.11 + objenesis 3.3 transitives).
- `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2` resolves cleanly.
- All transitives converge on **Kotlin 2.3.21** and **coroutines 1.10.2** (no version conflicts; constraints from `kotlinx-coroutines-bom:1.10.2` enforce the convergence).

**Negative check on `core/`**: `./gradlew :core:dependencies --configuration debugUnitTestRuntimeClasspath | grep -E '(turbine|mockk)'` returns no matches — `core/` is untouched as required.

## Production Classpath Audit

- `! grep -q 'implementation(libs.kotlinx.collections.immutable)' rig/build.gradle.kts audio/build.gradle.kts ft8-native/build.gradle.kts app/build.gradle.kts core/build.gradle.kts` → exits 0 (no module wires it as implementation).
- `! grep -q 'libs.turbine\|libs.mockk' core/build.gradle.kts` → exits 0 (core has no new test deps either).
- No module's `main/` source tree was touched. Zero production-classpath changes.

## Requirements Unblocked

| ID        | Description                                                              | Status                                         |
| --------- | ------------------------------------------------------------------------ | ---------------------------------------------- |
| FOUND-03  | Test classpath has Turbine wired for Flow assertions                     | done (rig, audio, ft8-native, app)             |
| FOUND-04  | Test classpath has MockK wired for fake/mock construction                | done (rig, audio, ft8-native, app)             |
| FOUND-05  | Catalog pin for kotlinx-collections-immutable staged for Phase 3 wiring  | done (catalog-only, per phase constraint)      |

## Phase 3 Deferral Note (intentional)

**kotlinx-collections-immutable is pinned in the catalog (`libs.kotlinx.collections.immutable` alias is live), but is NOT wired as `implementation` in any module's `build.gradle.kts`.** This is deliberate per Plan 00-02's `<phase_specific_constraints>`:

- Phase 0's mandate is "no production code changes" — adding `implementation(...)` to a module's production classpath is a production-classpath change.
- The library lands as `implementation` in **Phase 3** when DecodeRow gains stable keys and switches its `columns: List<Int>` field to `ImmutableList<Int>` (per RESEARCH.md Recommended Stack).
- Pre-staging the catalog pin in Phase 0 means Phase 3's wiring is a single one-line edit to the relevant module (likely `core/` or `app/`, TBD at Phase 3 planning) with no catalog churn.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed pre-existing broken `gradlew` wrapper script**

- **Found during:** Task 2 verification (`./gradlew :rig:dependencies --configuration testCompileClasspath`).
- **Issue:** The committed `gradlew` had a malformed `exec` line that passed Gradle CLI arguments as JVM options BEFORE the `GradleWrapperMain` classpath, causing every invocation to fail with `Error: Could not find or load main class :rig:dependencies` (the JVM was treating the first `:rig:dependencies` arg as a class name to load). This was pre-existing in the repo (present since `dbb5bc4` "init" and unchanged through `c53ef77` "Implement v1 readiness review action plan") — NOT caused by Plan 00-02's edits.
- **Fix:** Reverted the `exec` line to the canonical Gradle wrapper template by removing the spurious leading `"$@"`:
  - Before: `exec "$JAVACMD" "$@" "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"`
  - After: `exec "$JAVACMD" "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"`
- **Why this counts as Rule 3 (not out-of-scope):** Plan 00-02's `<verify>` block requires `./gradlew :rig:dependencies --configuration testCompileClasspath` to succeed. With the broken wrapper, this verification (and every other plan that shells to `./gradlew`) could not run. Fixing it was the minimum cost to unblock this task's acceptance criteria. The fix is the canonical Gradle wrapper template (a well-defined revert, not a judgment call).
- **Files modified:** `gradlew`
- **Commit:** `7929704`
- **Note for orchestrator:** `gradlew` is NOT in `files_modified` for Plan 00-02. Surfacing this here so the wave-aggregation step can decide whether to (a) absorb the fix into Plan 02 retroactively, or (b) note it as an out-of-band foundational fix for the milestone audit. Either way the fix is required for any later phase that runs Gradle.

### Other Deviations

- Used `debugUnitTestRuntimeClasspath` instead of the plan's literal `testCompileClasspath` / `testRuntimeClasspath`. Android library modules expose variant-aware configurations (`debugUnitTest…` / `releaseUnitTest…`), not the plain JVM names. This is a Gradle-Android conventional naming difference, not a deviation from intent. The verification outcome (Maven resolution of Turbine/MockK/kotlinx-coroutines-test) is what the plan actually requires, and it passes.

## Authentication Gates

None. All Maven Central deps resolved from existing repository configuration (no new credentials, no new repositories).

## Self-Check: PASSED

- `gradle/libs.versions.toml` contains all three new pins and aliases — verified by `grep` on the committed file.
- `rig/`, `audio/`, `ft8-native/`, `app/` `build.gradle.kts` each contain the three new `testImplementation` lines — verified via `grep -c` (each returns 1).
- `core/build.gradle.kts` contains no `turbine` or `mockk` references — verified via `grep`.
- No module wires `implementation(libs.kotlinx.collections.immutable)` — verified via `grep` (exit 0).
- `gradlew --version` reports `Gradle 9.4.1` cleanly.
- `./gradlew :rig:dependencies --configuration debugUnitTestRuntimeClasspath` resolves Turbine 1.2.1, MockK 1.14.7, kotlinx-coroutines-test 1.10.2 with no errors.
- Commits exist on this worktree branch:
  - `adfd2e7` `build(00): add Turbine, MockK, kotlinx-collections-immutable to version catalog`
  - `d0e23e8` `build(00): wire testImplementation Turbine + MockK + coroutines-test into 4 modules`
  - `7929704` `fix(00): remove duplicate "$@" from gradlew wrapper exec line`
- `git diff --stat f94193b..HEAD` reports exactly 6 files changed (5 in `files_modified` + `gradlew` Rule-3 fix). No deletions, no untracked files.

## Threat Flags

No new security-relevant surface introduced by this plan. All changes are test-classpath-only (`testImplementation` configuration); nothing flows into release APK builds. The pre-existing `gradlew` wrapper fix is a build-tooling correctness change with no runtime surface.

## TDD Gate Compliance

N/A — Plan 00-02 is `type: execute` (build-tooling wiring), not `type: tdd`. No RED/GREEN/REFACTOR gates required.
