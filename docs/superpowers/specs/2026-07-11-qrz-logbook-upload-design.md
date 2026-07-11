# QRZ Logbook Auto-Upload — Design

**Date:** 2026-07-11
**Status:** Approved
**Feature:** Optional automatic upload of logged QSOs to the operator's QRZ.com logbook.

## Goal

When enabled, every QSO logged by FT8VC is uploaded to QRZ.com via the QRZ
Logbook API. Failures are quiet and self-healing: nothing interrupts operating,
nothing is lost, and the backlog flushes automatically when connectivity
returns. This is the app's first network feature.

## Decisions (from brainstorming)

1. **Quiet failures.** Upload failures never produce snackbars or Operate-screen
   UI. The QRZ section in Settings shows a warning indicator (⚠) while uploads
   are failing; it clears on the next successful upload or connection test.
2. **Self-healing queue.** QSOs that fail to upload stay pending and are
   retried automatically. The QRZ logbook eventually matches the app logbook
   without user action.
3. **Only new QSOs.** Enabling QRZ upload affects QSOs logged from that moment
   on. Existing logbook rows are never uploaded by the app (the operator can
   upload an ADIF export to QRZ manually if desired).
4. **No new dependencies.** Plain `HttpURLConnection`; no WorkManager, no HTTP
   library. Uploads run while the app process is alive, which matches how the
   app is used (QSOs only happen while operating).

## Components

### Settings UI (`app` module — SettingsScreen / SettingsRepository)

- New **"QRZ Logbook"** section in Settings:
  - Toggle: *Upload QSOs to QRZ*.
  - Text field: *API key* (QRZ Logbook API key, format `XXXX-XXXX-XXXX-XXXX`).
  - Button: **Test connection** — calls `ACTION=STATUS`; on success shows the
    logbook callsign and QSO count QRZ returns, on failure shows QRZ's reason
    string inline. A successful test also triggers a queue flush.
- Section header shows ⚠ when: upload enabled **and** last attempt failed
  **and** pending QSOs exist. Cleared by the next successful upload or test.
- Persistence: `qrzUploadEnabled: Boolean`, `qrzApiKey: String`, and
  `qrzLastError: String?` in DataStore via `SettingsRepository` (last error
  persisted so the warning survives restart).

### Data layer (`data` module)

- `QsoEntity` gains `qrzUploadState: String` with values:
  - `NOT_QUEUED` — default; applied to all pre-existing rows by Room migration.
  - `PENDING` — set at insert time when QRZ upload is enabled.
  - `UPLOADED` — set after QRZ accepts the record (or reports duplicate).
- New DAO queries: pending QSOs oldest-first; mark a QSO uploaded.
- Room schema version bump + migration adding the column with default
  `NOT_QUEUED`.

### QRZ client + queue (`data` module, `net.ft8vc.data.qrz`)

- **`QrzLogbookClient`**
  - Endpoint: `https://logbook.qrz.com/api` (HTTPS only; cleartext remains
    disabled app-wide).
  - `HttpURLConnection`, form-encoded POST, 10 s connect/read timeouts, runs on
    `Dispatchers.IO`.
  - `status(key)` → `ACTION=STATUS`; parses callsign + count for the Test UI.
  - `insert(key, adifRecord)` → `ACTION=INSERT` with `ADIF=` payload.
  - Parses QRZ's `RESULT=OK|FAIL|AUTH ... REASON=...` ampersand-delimited
    responses. A `FAIL` whose reason indicates a duplicate is treated as
    success (the QSO exists in the QRZ logbook, which is the desired state).
- **`QrzUploadQueue`**
  - Mutex-serialized `flush()`: loads pending QSOs oldest-first, formats each
    with the existing `AdifWriter.record(contact)`, uploads sequentially.
  - Stops at the first failure (no retry storms), records the error.
  - Exposes `StateFlow` of queue status (idle / uploading / error + message)
    consumed by the Settings UI for the ⚠ indicator and error text.

### Triggers (`app` module)

`flush()` is attempted when:
1. A new QSO is logged (row inserted as `PENDING`, flush follows).
2. The app starts, if upload is enabled and pending rows exist.
3. The upload toggle is switched on.
4. A connection test succeeds.
5. Connectivity returns — `ConnectivityManager.registerDefaultNetworkCallback`
   `onAvailable`. This is what clears the warning without user action.

No behavior on the Operate screen changes; TX/RX/CAT paths are untouched.

### Manifest

- Add `android.permission.INTERNET` (first use of networking in the app).

## Error handling

| Failure | Behavior |
|---|---|
| No connectivity / timeout | QSO stays `PENDING`; error stored; ⚠ shown; retried on next trigger |
| Invalid/revoked API key | Same quiet path; Test button gives the explicit reason |
| QRZ outage / 5xx / malformed response | Same quiet path |
| Duplicate rejection from QRZ | Treated as success; QSO marked `UPLOADED` |

Nothing is ever dropped: any QSO marked `PENDING` remains queued until QRZ
accepts it or reports it duplicate.

## Testing

- JVM unit tests (no network): QRZ response parser (OK / FAIL / AUTH /
  duplicate / malformed), queue behavior with a fake client (oldest-first
  order, stop-on-first-failure, duplicate-as-success, state-flow transitions,
  mutex serialization of concurrent flush calls).
- Room migration test for the new column/default.
- Manual verification: real API key — Test button success + failure paths,
  QSO upload appears in QRZ logbook, airplane-mode QSO flushes on reconnect
  and the ⚠ clears.

## Out of scope

- Uploading pre-existing logbook history.
- Other services (LoTW, eQSL, Clublog).
- Background upload while the app process is dead (no WorkManager).
- Any Operate-screen UI for upload status.
