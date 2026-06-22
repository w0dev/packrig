# Stack Research — FT8VC v1.x Code Health Milestone

**Domain:** Android Kotlin/Compose refactor on a brownfield, RF-safety-sensitive, solo-maintained transceiver app (minSdk 28, Kotlin 2.3.21, AGP 9.2.1).
**Researched:** 2026-06-21
**Confidence:** HIGH for versions verified against Maven Central / project release notes today; MEDIUM where the recommendation is a judgment call for a solo maintainer that the upstream docs do not make for you.

This document does **not** re-survey what FT8VC already uses — see `.planning/codebase/STACK.md` for the as-shipping inventory. It names only what to **add** or **standardize on** to execute the milestone described in `.planning/PROJECT.md`, anchored to the PITFALLS.md prescriptions (dedicated dispatchers for JNI, FakeRigBackend + golden-trace tests, ImmutableList for stable Compose lists, 4-layer PTT defense).

The big-picture recommendation: **add the minimum that earns its keep.** This is a solo refactor of an app already in field use; every new dependency is one more thing to keep current. Two libraries (Turbine, kotlinx-collections-immutable) are non-negotiable because PITFALLS.md explicitly relies on them. One library (MockK) is strongly recommended because the alternative is hand-rolled fakes that nobody enjoys writing. Everything else is **stay where you are**.

---

## Recommended Stack

### Core Technologies (additions only — nothing to swap out)

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `app.cash.turbine:turbine` | **1.2.1** | Pull-based `StateFlow`/`Flow` assertions for controller unit tests | PITFALLS.md Pitfall 5 + 6 require asserting on StateFlow emissions per slot (golden-trace style). Hand-rolled `flow.toList()` collectors race with `viewModelScope`; Turbine `test { awaitItem() }` is the de-facto standard and removes that whole class of flakiness. One small dep, all in test scope. |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | **0.4.0** | `ImmutableList<DecodeRow>` for Compose stability | PITFALLS.md Pitfall 6 and 11 explicitly call for this. `List<T>` is not a Compose-stable type — every decode emission invalidates the whole `DecodeListPanel` and triggers waterfall recompose. `ImmutableList` is the Jetpack-Compose-team-blessed fix. Tiny library, zero transitive cost. |
| `io.mockk:mockk` | **1.14.7** | Mocking framework for platform boundary types (`Ft8Native`, `AudioRecord`-style facades, USB serial backends) | PITFALLS.md Pitfall 5 rule "mock only the platform boundary" still leaves you mocking 4–6 interfaces per controller test. MockK speaks Kotlin natively (final classes, coroutines, `every { } returns`, `coVerify { }`) — Mockito requires `mockito-kotlin` + `mockito-inline` + still fights you on `suspend`. For a solo maintainer who'll write these tests once, MockK is the smaller-friction choice. |

### Supporting Libraries (optional but reach for these first)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.arch.core:core-testing` | **2.2.0** | `InstantTaskExecutorRule` for forcing main-thread execution in JVM tests | Only if a controller test ever observes a LiveData or touches `MutableLiveData`. Current code uses `StateFlow` throughout, so this may be unnecessary — add only when the first test fails for "must be on main thread." |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **already at 1.10.2 — match coroutines-core version** | `runTest { }`, `TestDispatcher`, `advanceTimeBy()` for slot-timing tests | Already a dependency. **Just use it.** Specifically: every controller test that depends on `delay(slot)` or `withTimeoutOrNull` must run under `runTest` with a `TestScope` so coroutine virtual time replaces wall-clock. This is how the `SlotTimingJitter` helper from PITFALLS.md Pitfall 10 stays deterministic. |
| `androidx.test.ext:junit` | **already at 1.x — keep** | `@RunWith(AndroidJUnit4::class)` shim for any Robolectric-style test | Only if you add Robolectric (see below — don't, this milestone). |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Existing Gradle version catalog (`gradle/libs.versions.toml`) | Centralized version pinning for the three new libs above | Add: `turbine = "1.2.1"`, `kotlinx-collections-immutable = "0.4.0"`, `mockk = "1.14.7"`. Wire `testImplementation(libs.turbine)`, `implementation(libs.kotlinx.collections.immutable)`, `testImplementation(libs.mockk)`. Don't sprinkle versions across modules. |
| KSP (already at 2.3.7) | No change needed | Mentioned here only to call out: do **not** introduce Hilt — it would force a `kapt` or KSP processor onto every module that wants `@Inject`. KSP for Room is fine; we don't need a second annotation processor. |

---

## Installation

Add to `gradle/libs.versions.toml`:

```toml
[versions]
turbine = "1.2.1"
kotlinx-collections-immutable = "0.4.0"
mockk = "1.14.7"

[libraries]
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
kotlinx-collections-immutable = { module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "kotlinx-collections-immutable" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
```

In `:app/build.gradle.kts` (and any module that gains a controller test):

```kotlin
dependencies {
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // kotlinx-coroutines-test is already on the classpath via :core — confirm transitive, add if not.
}
```

That's the entire new-dependency footprint for the milestone. Three lines of `implementation` / `testImplementation`. No new plugins, no annotation processors, no app-init code, no manifest changes.

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| **Manual constructor injection** (no DI framework) | Hilt 2.5x | If the project grew to >5 engineers and >25 injectable classes — neither is true here. Hilt adds compile-time codegen, an annotation processor on every module, and a learning surface (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`, scopes) that costs more than a 1,135-line ViewModel split into 5 controllers wired in one `Application.onCreate()` or in `OperateViewModel.Factory`. Skip. |
| Manual constructor injection | Koin 4.x | If you wanted a tiny DI helper without codegen. Koin would let you write `single { DecodeController(get(), get()) }` instead of `DecodeController(rig, dispatcher)`. The savings for 5 controllers + ~6 collaborators is roughly zero, and you'd own a runtime DSL that can fail at first use rather than at compile time. Skip. |
| **MockK 1.14.7** | Mockito 5 + mockito-kotlin + mockito-inline | If the team had deep Mockito muscle memory from a Java background. Not the case here (`TESTING.md` says no mocking framework today, so there's no incumbent skill to honor). MockK is the Kotlin-native choice; pick it once. |
| **Turbine 1.2.1** | Plain `flow.toList()` in `runTest` | For one-off, one-emission cases where the assertion is trivial. Turbine pays off the moment you have to assert "exactly two emissions, in this order, within 1.5 s of virtual time" — which is every controller test in this milestone. Adopt across the board. |
| **kotlinx-collections-immutable 0.4.0** | `@Immutable` annotation on a wrapping data class | The annotation approach works but is opt-in per-call-site and requires the wrapper. The library version is one import (`persistentListOf`, `toImmutableList()`) and Compose's stability inference handles it automatically. Use the library. |
| **Pure JVM unit tests** (`src/test/`) | Robolectric | For testing Android `BroadcastReceiver` registration logic. We have *one* such surface in scope (USB-detached receiver). Test the controller around it with a fake instead — Robolectric brings a ~3 s startup tax per test class and 20 MB of dependencies that the rest of the milestone does not need. Defer Robolectric until it earns the slot. |
| **`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`** for JNI work | `Dispatchers.IO` | PITFALLS.md Pitfall 2 makes this non-optional: JNI calls go on a dedicated single-thread dispatcher (one for decode, one for CAT), `Dispatchers.IO` is reserved for non-JNI blocking work. Same dispatcher rule applies to the `qsoLock` replacement per Pitfall 7. |
| **`supervisorScope` + `withTimeoutOrNull` + `delay`** as the coroutine vocabulary | `actor { }` / `Channel`-based serializers | An `actor` looks elegant but PITFALLS.md Pitfall 7 specifically warns that channels can re-order operations that a coarse lock used to serialize. A single-thread coroutine dispatcher preserves total ordering with no surprises. Use channels only for genuine producer/consumer (e.g., decode-row stream into UI), never as a lock replacement. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| **Hilt** | Compile-time DI with KSP/kapt overhead, annotation surface, `@HiltAndroidApp` lifecycle entanglement. For 5 controllers wired once in one ViewModel, the codegen earns nothing. PROJECT.md constraint says "no new top-level dependencies … unless they enable a controller seam"; manual constructor injection enables every seam Hilt does, with less code and zero new build steps. | Manual constructor injection. `OperateViewModel.Factory` (you'll need a factory anyway to inject the dispatchers) constructs `SettingsBridge`, `RigSession`, `DecodeController`, `TxOrchestrator`, `QsoSessionController` and passes them to the ViewModel. ~30 lines, one file. |
| **Koin** | Runtime DSL adds a startup module-graph eval that can fail in production after passing tests; lighter than Hilt but still introduces a global state mechanism. Same "earns nothing for 5 controllers" calculus. | Manual constructor injection. |
| **`Dispatchers.IO` for `Ft8Native.decode()` or any JNI call** | PITFALLS.md Pitfall 2: the IO dispatcher has ~64 threads, surfaces the cancellation/race bug that the current single-thread executor accidentally hides. | `private val decodeDispatcher = Executors.newSingleThreadExecutor { Thread(it, "ft8-decode") }.asCoroutineDispatcher()` (with matching `.close()` in `onCleared`). Same shape for `catDispatcher` and `qsoDispatcher`. |
| **`actor { }` to replace `qsoLock`** | PITFALLS.md Pitfall 7 — channels do not preserve cross-access ordering invariants that the lock enforced. | Dedicated single-thread coroutine dispatcher (`qsoDispatcher`) wrapping all `QsoMachine` access via `withContext(qsoDispatcher) { … }`. |
| **`LocalBroadcastManager`** for any new in-app event bus | Deprecated (moved out of androidx years ago). Even if it weren't, it's the wrong tool for USB lifecycle. | `Context.registerReceiver(receiver, IntentFilter(ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_NOT_EXPORTED)` — system broadcast, must use the explicit `NOT_EXPORTED` flag on API 33+ (we're targetSdk 36). Then funnel the event into a `MutableSharedFlow` owned by `RigSession` (or a small `UsbEvents` singleton). Coroutines + StateFlow/SharedFlow IS the modern in-app event bus. |
| **`collectAsState()`** in any new Compose code | Continues collecting in the background; PITFALLS.md Pitfall 6 calls this out as a slot-timing fighter. | `collectAsStateWithLifecycle()` from `androidx.lifecycle:lifecycle-runtime-compose` (already on classpath at 2.10.0). Lint-enforce it via a Detekt/Konsist rule if you add one this milestone. |
| **JUnit 5 (Jupiter)** | Current tests use JUnit 4 (per `codebase/TESTING.md`). Migrating to JUnit 5 mid-refactor is a tax on a milestone whose deliverable is *unit tests for 5 new controllers*. JUnit 4 + `kotlinx-coroutines-test` + Turbine + MockK is a fully supported, current stack. | JUnit 4. Revisit JUnit 5 in a later milestone when it's the actual subject of work. |
| **Robolectric** | Brings Android-framework simulation, ~3 s/class startup, large transitive deps. Not needed for controller unit tests, which are pure-JVM by construction (the controllers own no Android types — only `Context` is passed in for USB, and that's mockable). | Pure JVM (`src/test/`) with `runTest { }`. Use **instrumented tests** (`androidTest/`) on a real device for the few things that genuinely need the Android USB stack — and per PITFALLS.md Pitfall 10, the real bar is field-rig testing anyway. |
| **`Thread.sleep()` anywhere in production code** (including the new controllers) | CONCERNS.md explicitly flags this and the milestone removes it. | `delay()` inside a coroutine, or `withTimeoutOrNull(d) { … }` when waiting on an external event. Never `Thread.sleep` even "temporarily." |
| **Auto-resume of TX state across crashes or USB reconnect** | PITFALLS.md Pitfall 4 — RF safety. Not a library decision but a stack-level prohibition: any state-restoration scheme (e.g., `SavedStateHandle` with `tx_enabled` persisted) must NOT silently re-enable TX. | Explicit user re-confirmation, full re-init through the license-ack flow. `SavedStateHandle` is fine for UI state (selected tab, scroll position); not for `tx_enabled` or `qsoRunning`. |
| **Introducing `viewModelScope.launch { … }` everywhere as the replacement for Executors** | `viewModelScope` is `Dispatchers.Main.immediate + SupervisorJob`. Launching JNI work directly there will hit the wrong dispatcher (Pitfall 2) and lose `supervisorScope` per-controller isolation. | Pattern: each controller takes a `CoroutineScope` and its own dispatcher in the constructor (`class DecodeController(scope: CoroutineScope, decodeDispatcher: CoroutineDispatcher)`). `OperateViewModel` constructs these scopes from `viewModelScope` (so cancellation cascades from `onCleared`). Each controller wraps its work in `supervisorScope { … }` so one failing job doesn't tear down its siblings. |

---

## Stack Patterns by Variant

**If a controller owns JNI (DecodeController, TxOrchestrator's encode path):**
- Inject a dedicated single-thread dispatcher via the constructor.
- Wrap every native call: `withContext(decodeDispatcher) { ensureActive(); Ft8Native.decode(…) }`.
- On `cancel()`, the caller must `join()` the launched job — never assume "cancelled" means "JNI returned." (PITFALLS.md Pitfall 2.)
- Add a `released: AtomicBoolean` and call `dispatcher.close()` in `onCleared` (`asCoroutineDispatcher()` does not auto-close the underlying executor).

**If a controller owns RF state (TxOrchestrator, RigSession):**
- All four PTT defenses present (PITFALLS.md Pitfall 3): `try-finally`, `AutoCloseable` session, `withTimeoutOrNull(SLOT_DURATION_MS + 500)`, watchdog coroutine.
- Constructor takes `RigBackend` interface, not the concrete `Digirig` driver — enables `FakeRigBackend` for tests and emergency-halt in production.
- `emergencyHalt()` is idempotent and callable from any thread.

**If a controller owns shared mutable domain state (QsoSessionController + `QsoMachine`):**
- All access serialized through one dispatcher (`qsoDispatcher = newSingleThreadContext("qso")` OR a single-thread executor wrapped as dispatcher).
- Document invariants in a block comment at the top of the class before removing `qsoLock` — reviewer sign-off required (PITFALLS.md Pitfall 7).
- `qsoTxParity` ownership moves into this controller; nobody else writes it.

**If a controller publishes UI state (every controller):**
- Expose `val state: StateFlow<X>` via `MutableStateFlow.asStateFlow()` — never the mutable field.
- ViewModel combines: `combine(s.state, r.state, d.state, t.state, q.state) { … OperateUiState(…) }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialOperateUiState)`. (PITFALLS.md Pitfall 6.)
- Lists in the combined state are `ImmutableList<T>`. Decode rows carry a stable `id: Long` for `LazyColumn(key = { it.id })`. (PITFALLS.md Pitfall 11.)

**If a controller registers a system broadcast (`RigSession` for USB-detached):**
- `context.registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_NOT_EXPORTED)` — explicit flag required on API 33+ (we're targetSdk 36).
- Unregister in `onCleared` / scope cancellation. Wrap in `try { unregisterReceiver(…) } catch (_: IllegalArgumentException) {}` because double-unregister throws.
- Funnel into a `MutableSharedFlow<UsbEvent>(extraBufferCapacity = 8)`. Do not block in the receiver itself — `onReceive` runs on main thread.

---

## Version Compatibility

| Package | Pin | Notes |
|---------|-----|-------|
| `kotlinx-coroutines-core` / `-android` | **stay on 1.10.2** (already pinned) | 1.11.0 is "companion to Kotlin 2.2.20"; the project pins Kotlin 2.3.21. 1.10.2 is the most recent release that does not advertise a different Kotlin companion than what we ship. Do not bump coroutines as part of this milestone — bump in a dedicated dependency-update phase after confirming a coroutines release with explicit Kotlin 2.3 support. (Risk: subtle behavior changes in `delay`/`select` that interact with slot timing.) |
| `kotlinx-coroutines-test` | **match coroutines-core (1.10.2)** | Version skew between `-core` and `-test` is unsupported. If you bump one, bump both. |
| `app.cash.turbine:turbine` 1.2.1 | Requires `kotlinx-coroutines-test`. Works with 1.7.x+ — fully compatible with 1.10.2. | — |
| `io.mockk:mockk` 1.14.7 | Kotlin 1.9+ supported; works with 2.x including 2.3. Use `mockk` artifact for JVM tests; `mockk-android` only for instrumented (`androidTest/`) tests. | Stay on `mockk` for this milestone — instrumented tests are out of scope (PITFALLS.md Pitfall 10 says the real gate is field-rig testing, not Robolectric/instrumented). |
| `kotlinx-collections-immutable` 0.4.0 | Targets Kotlin stdlib 2.0+, works fine on 2.3.21. Note this library is still pre-1.0 (`0.x`) — API has been stable for years but JetBrains has not promised semver. Acceptable risk for a list-stability helper that you can replace with `@Immutable`-wrapped lists if it ever breaks. | — |
| `androidx.arch.core:core-testing` 2.2.0 | Only if needed. Compatible with current AndroidX lifecycle 2.10.0. | — |
| Compose BOM 2026.05.01 (already pinned) | No change. `lifecycle-runtime-compose` already provides `collectAsStateWithLifecycle`. | — |

---

## "We Already Have It, Don't Add Y" — Quick Reference for the Roadmapper

| Need | Already on classpath | Do not add |
|------|----------------------|------------|
| Coroutine scope tied to ViewModel | `viewModelScope` (lifecycle 2.10.0) | A custom `CoroutineScope` field — use `viewModelScope` as the parent; derive child scopes for each controller. |
| Lifecycle-aware Flow collection in Compose | `collectAsStateWithLifecycle` (lifecycle-runtime-compose 2.10.0) | Anything else. |
| Coroutine virtual time for tests | `kotlinx-coroutines-test` 1.10.2 | Hamcrest, AssertJ — JUnit 4 + `assertEquals` + Turbine covers every assertion this milestone needs. |
| Room async access | `room-ktx` (Room 2.7.2) | A second persistence layer for "transient" controller state — use plain `StateFlow` in memory. |
| KSP for Room | KSP 2.3.7 | A second annotation processor (i.e., Hilt). |
| USB device detection | `UsbManager` + `usb_device_filter.xml` (existing) | A USB library wrapper — the broadcast + manager API is exactly the right surface for the snackbar + emergency-halt path. |
| In-app event bus | Coroutines `SharedFlow` / `StateFlow` | EventBus, RxJava, `LocalBroadcastManager`. |

---

## Sources

- [kotlinx.coroutines releases (GitHub)](https://github.com/Kotlin/kotlinx.coroutines/releases) — verified 1.11.0 is current stable, companion to Kotlin 2.2.20; explains why we stay on 1.10.2 with Kotlin 2.3.21. HIGH confidence.
- [kotlinx.coroutines 1.11.0 release announcement](https://www.linkedin.com/posts/satishnada_kotlin-androiddev-coroutines-activity-7458690156871544832-GcA0) — confirms Kotlin 2.2.20 companion-version statement. MEDIUM confidence (secondary source; aligns with primary).
- [MockK releases (GitHub)](https://github.com/mockk/mockk/releases) — verified 1.14.7 (JVM) and 1.14.2/1.14.6 (Android) as current stable. HIGH confidence.
- [Turbine on Maven Central](https://central.sonatype.com/artifact/app.cash.turbine/turbine) and [GitHub](https://github.com/cashapp/turbine) — verified 1.2.1 stable; 1.3.0 in snapshots. HIGH confidence.
- [kotlinx.collections.immutable releases](https://github.com/Kotlin/kotlinx.collections.immutable/releases) — verified 0.4.0 stable (May 2026), 0.5.0-beta01 available. HIGH confidence.
- [Android Developers — Fix stability issues in Compose](https://developer.android.com/develop/ui/compose/performance/stability/fix) — endorses kotlinx immutable collections as the canonical stable-list fix for Compose. HIGH confidence.
- [Android Developers — Arch Core releases](https://developer.android.com/jetpack/androidx/releases/arch-core) — verified `core-testing` 2.2.0 current stable. HIGH confidence.
- [Hilt vs Koin (ProAndroidDev)](https://proandroiddev.com/hilt-vs-koin-the-hidden-cost-of-runtime-injection-and-why-compile-time-di-wins-3d8c522a073b) and [Koin docs on Hilt comparison](https://insert-koin.io/docs/intro/koin-vs-hilt/) — informed the "no DI framework" recommendation for a 5-controller solo refactor. MEDIUM confidence (opinionated sources; recommendation is a judgment call, not a verified fact).
- [Android Developers — Broadcast intents & flags (RECEIVER_NOT_EXPORTED)](https://developer.android.com/develop/background-work/background-tasks/broadcasts) — required pattern for dynamic system-broadcast receivers on API 33+. HIGH confidence.
- `.planning/codebase/STACK.md`, `.planning/codebase/TESTING.md`, `.planning/codebase/CONCERNS.md` — current dependency pinning, JUnit 4 baseline, and the issues this stack is meant to address. HIGH confidence (in-tree, dated 2026-06-21).
- `.planning/research/PITFALLS.md` (sibling researcher) — anchors the dedicated-dispatcher rule (Pitfall 2/7), 4-layer PTT defense (Pitfall 3), FakeRigBackend + golden-trace test pattern (Pitfall 5/10), Compose stability requirements (Pitfall 6/11). HIGH confidence (this milestone's authoritative pitfall map).

---

*Stack research for: FT8VC v1.x code-health milestone — additions only, no swaps of in-flight v1.0 stack*
*Researched: 2026-06-21*
