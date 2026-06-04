# Data module (`data/`)

Room-backed QSO logbook and ADIF export.

## Schema

`QsoEntity` (table `qso_contacts`):

| Column | Type | Notes |
|--------|------|-------|
| id | Long | Auto-generated PK |
| utcMillis | Long | QSO completion time (UTC) |
| myCall, myGrid | String | Our station |
| dxCall, dxGrid | String? | Remote station |
| rstSent, rstRcvd | Int? | FT8 reports |
| freqHz | Long? | Dial frequency from CAT |
| mode | String | `FT8` |
| band | String? | e.g. `20m` |
| notes | String | Optional |

Unique index on `(dxCall, utcMillis)` prevents double-logging the same QSO.

## API

```kotlin
interface Logbook {
    suspend fun log(contact: QsoContact): Long
    fun contacts(): Flow<List<QsoContact>>
    suspend fun exportAdif(): String
    fun contactCount(): Flow<Int>
    suspend fun clearAll()
}
```

`RoomLogbook` is the v1 implementation. `QsoContact.fromSnapshot()` maps from
`core.QsoSnapshot` after a completed auto-QSO.

## ADIF export

`AdifWriter` produces standard ADIF 3.x records with `CALL`, `GRIDSQUARE`,
`MY_GRIDSQUARE`, `RST_SENT`, `RST_RCVD`, `FREQ`, `MODE`, `SUBMODE` (FT8),
`QSO_DATE`, `TIME_ON`.

JVM unit tests in `AdifWriterTest`.

## Auto-log wiring

`OperateViewModel.handleQsoComplete()` logs when `QsoMachine.state == Complete`,
using rig frequency for `freqHz` and `Ft8DialBands` for band label.

## Related docs

- [APP.md](APP.md) — Log screen UI
- [CORE.md](CORE.md) — `QsoSnapshot`
