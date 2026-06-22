# Codebase Concerns

**Analysis Date:** 2026-06-21

## Architecture

### Monolithic OperateViewModel

**Issue:** `OperateViewModel` (1,135 lines) is a single orchestrator responsible for all screen state, QSO automation, audio capture/playback, CAT (rig) control, decode processing, and logging.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`

**Impact:** 
- Difficult to test individual concerns in isolation
- High cognitive load; difficult to modify without side effects
- Tight coupling between UI state, I/O threads, and domain logic
- Future feature additions will increase complexity beyond maintainability

**Roadmap recognized in code (lines 66-81):** The developer explicitly documents v1.1 refactoring into focused controllers:
- `SettingsBridge` — observes settings flow, maps to UI state
- `DecodeController` — owns decode executor, SlotCollector, Ft8Native calls
- `TxOrchestrator` — manages encode + UsbAudioPlayback + PTT + TX thread
- `QsoSessionController` — wraps QsoMachine, abandon counter, auto-seq
- `RigSession` — CAT operations, dial presets, busy flag

**Fix approach:** Follow the roadmap in a dedicated refactoring phase; incrementally extract controllers without breaking v1.0 behavior. Use constructor injection and interface boundaries to decouple.

---

## Threading and Synchronization

### Manual Thread Management with Executor Services

**Issue:** Uses `Executors.newSingleThreadExecutor()` for decode and CAT operations without explicit cleanup or timeout/retry logic.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 102-103)

**Current code:**
```kotlin
private val decodeExecutor = Executors.newSingleThreadExecutor()
private val catExecutor = Executors.newSingleThreadExecutor()
```

**Cleanup:** `onCleared()` calls `shutdownNow()`, which is correct but harsh (interrupts pending tasks).

**Impact:**
- If a decode or CAT task hangs, it will block subsequent operations indefinitely
- No timeout on blocking CAT operations; USB permission denial or device disconnect hangs the thread
- `Thread.sleep()` in CAT calls (e.g., `runCat()`) is not cancellable; interrupt may be ignored

**Fix approach:** 
- Replace Executor with coroutine `withTimeoutOrNull()` for all blocking operations
- Add explicit timeout constants for CAT reads/writes (e.g., 5s)
- Use `supervisorScope` to isolate decode/CAT failures from crashing the ViewModel

---

### Volatile + Synchronized Mixed Pattern

**Issue:** OperateViewModel uses both `@Volatile` fields and `synchronized(qsoLock)` blocks inconsistently.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 115, 124, 530-551, 602-609, 748, 854-887)

**Current code:**
```kotlin
@Volatile private var qsoRunning = false
private var qsoThread: Thread? = null
private var qso: QsoMachine? = null  // accessed via synchronized(qsoLock)
```

**Problem:** `qsoThread` is accessed from the main thread (cancel/stop) and the QSO thread itself (check interruption), but has no synchronization. Race condition if stop/start rapidly.

**Impact:** Potential null pointer or missed thread reference during rapid start-stop cycles.

**Fix approach:** 
- Use `AtomicReference<Thread?>` or wrap both `qsoThread` and `qsoRunning` in the same lock
- Document the locking strategy in a comment block at the top of the class

---

### Waterfall Bitmap Allocation

**Issue:** Waterfall allocates a full ARGB_8888 Bitmap (320×256 = ~327 KB).

**Files:** `app/src/main/java/net/ft8vc/app/ui/Waterfall.kt` (line 28)

**Current code:**
```kotlin
private val bitmap: Bitmap = Bitmap.createBitmap(bins, history, Bitmap.Config.ARGB_8888)
```

**Impact:** Long-lived large heap allocation on every instantiation; garbage pressure if waterfall is recreated (e.g., screen rotation).

**Fix approach:** 
- Cache the Bitmap in a holder or ViewModel to survive recompositions
- Use `onDestroy()` or `onCleared()` to explicitly release via `bitmap.recycle()`
- Consider smaller depth (e.g., 150 rows instead of 320) if memory is tight

---

## Resource Lifecycle

### Audio Capture/Playback Not Guaranteed Closed on Crash

**Issue:** If an exception occurs during `OperateViewModel` initialization or operation, `capture`/`playback`/`rig` resources may not be released before `onCleared()` is called.

**Files:** 
- `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 97-99, 1119-1129)
- `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt` (lines 25-79)

**Scenario:** Exception in a TX slot encode/transmit thread leaves PTT keyed or capture thread blocked on AudioRecord.read().

**Impact:** 
- RF might remain keyed if exception occurs between `rig.keyPtt()` and `rig.releasePtt()`
- Capture thread may hang if device disconnects during `AudioRecord.read()`

**Fix approach:**
- Wrap all TX paths in a try-finally block that unconditionally calls `rig.releasePtt()`
- Add a timeout to `capture.stop()` in case the capture thread is hung
- Use `Thread.interrupt()` followed by `Thread.join(timeout)` before declaring failure

---

## Error Handling & Recovery

### Silent Failures in Decode Loop

**Issue:** The decode executor in line 992 catches exceptions but only logs them implicitly via `Ft8Native.decode()`.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (line 992, line 1020-1038)

**Current code:**
```kotlin
decodeExecutor.execute { decodeSlot(samples, slotStart) }
```

**Inside `decodeSlot()`:**
```kotlin
private fun decodeSlot(samples: ShortArray, slotStart: Long) {
    try {
        val results = Ft8Native.decode(samples, AppInfo.SAMPLE_RATE_HZ)
        // ...
    } catch (t: Throwable) {
        // Error not visible to user
        Log.e(TAG, "Decode failed", t)
    }
}
```

**Impact:** If the native decoder crashes or hangs on malformed input, the user receives no feedback. The slot is silently lost.

**Fix approach:**
- Wrap decode in a try-finally that emits a telemetry event on failure
- Add a "decode dropped N" counter to the status bar if consecutive failures exceed threshold
- Log decode errors to a local file for post-session review

---

### CAT Timeout Only on Throwable

**Issue:** `runCat()` (line 484-495) catches `Throwable` and sets `catStatus`, but `rig.setFrequencyHz()` and `rig.frequencyHz()` can block indefinitely if the CAT port is broken.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 455-480, 484-495)

**Current code:**
```kotlin
private fun runCat(busyStatus: String, block: () -> Unit) {
    _state.update { it.copy(catBusy = true, catStatus = busyStatus) }
    catExecutor.execute {
        try {
            block()  // block() may hang forever
        } catch (t: Throwable) {
            _state.update { it.copy(catStatus = t.message ?: "CAT error") }
        } finally {
            _state.update { it.copy(catBusy = false) }
        }
    }
}
```

**Scenario:** USB device disconnects; CAT read hangs on blocked I/O; user cannot cancel and must force-stop the app.

**Impact:** UI becomes unresponsive; no timeout feedback.

**Fix approach:**
- Pass a timeout Duration to `runCat()` and wrap `block()` with a coroutine timeout
- Set `catStatus = "CAT timeout"` if block doesn't complete in time
- Add an explicit "CAT timeout" and "Retry CAT" button in Settings → Rig

---

## Test Coverage Gaps

### OperateViewModel Not Directly Testable

**Issue:** The ViewModel tightly couples domain logic (QsoMachine, messages) with platform I/O (audio, CAT, PTT). It cannot be unit-tested without mocking the entire Android runtime.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`

**Tests:** No tests exist for OperateViewModel. Core logic (QsoMachine, QsoMessages) has good coverage (see `core/src/test/`).

**Impact:** 
- Platform-specific bugs (threading, state leaks, resource lifecycle) are only caught in manual integration testing
- Refactoring risks regression in subtle state management

**Fix approach:**
- After refactoring OperateViewModel into controllers, create unit tests for each controller using mocked I/O
- Add integration tests for QSO sequences (CQ → answer → complete) with mock audio capture
- Add UI state snapshot tests for screen rotation and backgrounding

---

### Automated Audio Device Tests Missing

**Issue:** UsbAudioCapture and UsbAudioPlayback have complex device selection logic but no automated tests.

**Files:**
- `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt`
- `audio/src/main/java/net/ft8vc/audio/UsbAudioPlayback.kt`

**Tests:** FftTest, FirDecimatorTest exist for DSP, but not for capture/playback device fallback logic (lines 42-48 in UsbAudioCapture).

**Impact:** Device fallback (48k → 24k → 12k sample rate) may silently fail on newer Android versions or hardware variants.

**Fix approach:**
- Add instrumented tests (androidTest) that enumerate available devices and verify fallback rates
- Mock AudioRecord.getAudioFormat() to simulate unavailable rates

---

## Fragile Areas

### QSO State Machine Thread Safety

**Issue:** `QsoMachine` is accessed from two threads: the QSO loop thread (which calls `txMessage()`, `recordTransmitted()`) and the decode loop thread (which calls `onDecodes()`). Synchronization via `qsoLock` is correct but fragile.

**Files:** 
- `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 602-609, 768-777, 854-887)
- `core/src/main/java/net/ft8vc/core/QsoMachine.kt`

**Risk:** If a new thread-unsafe field is added to QsoMachine without synchronization, subtle race conditions will occur only under high-latency decode conditions (e.g., slow device or decode backlog).

**Fix approach:**
- Move QsoMachine off the shared lock; route all access through an actor or command queue
- Document the thread safety contract of QsoMachine in JSDoc

---

### Decode List Updates Not Atomic with State

**Issue:** Decode rows are appended to `_state.value.decodes` without atomicity guarantees. If capture/decode thread interleaves with UI render thread, partial updates may be visible.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 1008-1018, 1040-1043)

**Current code:**
```kotlin
_state.update {
    it.copy(decodes = it.decodes + row)
}
```

**This is safe** (StateFlow.update atomically replaces the state), but the list grows unbounded if not pruned.

**Fix approach:**
- Cap the decode list size (e.g., 200 rows) and drop oldest decodes when full
- Add a `lastSlotDecodeCount` to allow re-fetching if needed

---

## Scaling Limits

### Unbounded Decode List Growth

**Issue:** The `decodes` list in `OperateUiState` grows every slot (15 seconds) and is never pruned.

**Files:** `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt`

**Current behavior:** After 1 hour, the list could contain ~240 rows × 10 decodes per slot = 2,400+ items.

**Impact:** 
- Compose recomposition of DecodeListPanel becomes slower
- Memory pressure increases
- Scrolling may stutter

**Limit:** By default, Compose LazyColumn handles 2k-5k items without noticeable lag, so this is not critical yet.

**Fix approach:**
- Implement a sliding window (e.g., keep only the last 500 decodes)
- Add a "Clear decodes" button (exists in OperateViewModel.clearDecodes() but not wired to UI)
- Consider off-loading old decodes to a local database for review

---

## Dependencies at Risk

### Hardcoded Native Library Version

**Issue:** `Ft8Native` loads `libft8vc.so` with no version check or fallback mechanism.

**Files:** `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt` (lines 15-21)

**Current code:**
```kotlin
private val loaded: Boolean = try {
    System.loadLibrary("ft8vc")
    true
} catch (t: UnsatisfiedLinkError) {
    Log.e(TAG, "Failed to load libft8vc.so", t)
    false
}
```

**Risk:**
- ABI mismatch (e.g., native library compiled for arm64, but app runs on armv7) will fail silently
- Version mismatch between app and library (rare but possible in manual testing) not detected
- Library missing entirely is logged but app continues, then crashes on first encode/decode

**Impact:** User can enable TX and press "Start CQ," which silently fails to encode; confusing error state.

**Fix approach:**
- Add a version string to the native library and validate it at app startup
- Add a "DSP library" status to Settings → About showing the native version
- Return an error to the user (not just a log) if the library fails to load

---

### Android API Level Compatibility

**Issue:** Uses `AudioDeviceInfo.TYPE_USB_*` and `AudioRecord.setPreferredDevice()` which are API 23+, but `minSdk` is 28. Code is safe but not future-proof if minSdk is lowered.

**Files:** `audio/src/main/java/net/ft8vc/audio/AudioInputs.kt`, `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt`

**minSdk:** Set to 28 (Android 9), so no current issue, but no guard clauses exist.

**Fix approach:** If minSdk is ever lowered, add `@RequiresApi` annotations and runtime checks.

---

## Security Considerations

### INTERNET Permission Declared but Unused

**Issue:** AndroidManifest declares `android.permission.INTERNET` with a comment "used by the NTP clock service (added in a later phase)."

**Files:** `app/src/main/AndroidManifest.xml`

**Current code:**
```xml
<!-- INTERNET is used by the NTP clock service (added in a later phase). -->
<uses-permission android:name="android.permission.INTERNET" />
```

**Risk:** User grants INTERNET permission but the app does not use it; future code that uses network APIs may not prompt again.

**Fix approach:**
- Remove the permission until NTP/network features are implemented
- When NTP is added, declare the permission at that time with a clear purpose

---

### USB Device Descriptor Not Validated

**Issue:** App auto-responds to USB device attach (line in AndroidManifest: `<action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />`), but does not verify device identity before granting permission.

**Files:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/usb_device_filter.xml` (not read, but referenced)

**Risk:** If `usb_device_filter.xml` is too permissive, the app may claim unrelated USB devices and confuse the user.

**Fix approach:**
- Review `usb_device_filter.xml` to ensure it matches Digirig PID/VID only (CP2102 + FT240X)
- Log USB device attach events to help users debug connectivity issues

---

### No PIN/Biometric Protection for Log Export

**Issue:** ADIF/CSV export to external storage does not prompt for authentication; the logbook (contact history with dates/times/frequencies) is readable by any app with `READ_EXTERNAL_FILES`.

**Files:** `data/src/main/java/net/ft8vc/data/adif/AdifWriter.kt`

**Impact:** Privacy risk if logbook contains sensitive information (e.g., monitoring specific frequencies, activity during travel).

**Fix approach:**
- Optionally encrypt exported ADIF file with a user PIN or biometric
- Default to exporting to `getExternalFilesDir()` (app-private) instead of public Downloads
- Add a "Hide logbook" toggle in Settings

---

## Performance Bottlenecks

### Spectrum Bitmap Updates Every Frame

**Issue:** Waterfall.addColumn() is called for every FFT column (~30 Hz × 256 columns = ~7.6k/sec in addColumn, but UI updates throttled at 30ms).

**Files:** `app/src/main/java/net/ft8vc/app/ui/Waterfall.kt` (lines 38-64)

**Current code:**
```kotlin
fun addColumn(column: FloatArray) {
    synchronized(lock) {
        // ... copy, sort, compute noise floor, scroll pixels, paint ...
        System.arraycopy(pixels, bins, pixels, 0, bins * (history - 1))
        // ...
    }
}
```

**Impact:** CPU cost is acceptable (FFT + sorts happen at capture rate ~12 kHz = 1 column per 12k/12 = 1 column per 1000 samples, ~100 columns/sec). Main thread UI update throttled at 30ms (line 996).

**Current:** No issue; load is well-distributed.

**Future risk:** If history is increased to 640+ rows, pixel array becomes 200+ KB; sorting slows down. Use a circular buffer instead.

---

### Decode Filter Linear Scan

**Issue:** MonitorDecodeFilter uses a list of regexes and scans every decode against all patterns.

**Files:** `core/src/main/java/net/ft8vc/core/MonitorDecodeFilter.kt`

**Impact:** Negligible (typically <10 patterns, ~1ms per decode). Scales fine for v1.0.

---

## Missing Critical Features

### No Disconnect Notification

**Issue:** If the USB device disconnects mid-QSO, there is no explicit notification. The app will eventually timeout on the next CAT attempt or decode will stop arriving, but the user might not notice for 15+ seconds.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`

**Fix approach:** Register for USB intent broadcasts (`ACTION_USB_DEVICE_DETACHED`) and emit a snackbar "Digirig disconnected — RX only."

---

### No Local Backup of Logbook

**Issue:** Logbook is stored in Room database (SQLite) in app-private storage. If the app is uninstalled, the database is deleted. No automatic backup to cloud or encrypted export.

**Files:** `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt`

**Risk:** User loses all QSO history if they factory reset or switch devices.

**Fix approach:** 
- Auto-export logbook to ADIF in app-private storage daily
- Offer "Backup to cloud" (e.g., Google Drive) in Settings (future phase)
- Add "Restore from ADIF" import tool

---

## Anti-Patterns

### Stringly-Typed Slot Parity

**Issue:** Slot parity (0 or 1) is passed as `Int` throughout the codebase instead of using an enum.

**Files:** `core/src/main/java/net/ft8vc/core/TxSlotParity.kt` (enum exists but Int is used in many places)

**Example:** Line 184 in OperateViewModel:
```kotlin
val parity = qsoTxParity ?: _state.value.txSlotParity.bit
val isTx = SlotTiming.slotIndexInMinute(now) % 2 == parity
```

**Impact:** Subtle bugs if 0/1 are swapped; test coverage insufficient.

**Fix approach:** Always use `TxSlotParity` enum; convert to Int only at I/O boundaries.

---

### Direct Calls to Thread.sleep() in QSO Loop

**Issue:** TX slot timing uses `Thread.sleep()` instead of structured concurrency.

**Files:** `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (lines 753-758, 919)

**Current code:**
```kotlin
val wait = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
if (wait > 0) Thread.sleep(wait)
```

**Risk:** If thread is interrupted, `InterruptedException` is caught but the wake-up time is lost. Retrying the calculation may oversleep.

**Fix approach:** Use `Dispatchers.IO` with `delay()` instead of manual threads.

---

## Recommendations (Priority Order)

1. **HIGH:** Refactor OperateViewModel into focused controllers (roadmap item v1.1)
   - Reduces complexity, enables unit testing, improves maintainability

2. **HIGH:** Add CAT timeout with user feedback
   - Prevents hang if USB device is broken; improves field reliability

3. **MEDIUM:** Implement decode list sliding window (cap at 500 decodes)
   - Prevents memory pressure after long sessions

4. **MEDIUM:** Add USB device disconnect notification
   - Improves user experience during field troubleshooting

5. **MEDIUM:** Replace manual Thread + Executor with coroutines
   - Enables structured cancellation, timeouts, better error handling

6. **LOW:** Add local logbook backup to ADIF
   - Protects against data loss; helps with QSO review

7. **LOW:** Remove unused INTERNET permission until NTP feature is ready
   - Improves privacy posture

---

*Concerns audit: 2026-06-21*
