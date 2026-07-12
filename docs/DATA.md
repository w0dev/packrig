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
    suspend fun exportAdif(context: AdifExportContext): String
    fun contactCount(): Flow<Int>
    suspend fun clearAll()
}
```

`RoomLogbook` is the v1 implementation. `QsoContact.fromSnapshot()` maps from
`core.QsoSnapshot` after a completed auto-QSO.

## ADIF export

Export targets **ADIF 3.1.4** and is validated before share (`AdifValidator`).

Pipeline:

```
QsoContact + AdifExportContext → AdifNormalizer → AdifWriter → AdifValidator
```

Header fields: `ADIF_VER`, `PROGRAMID`, `PROGRAMVERSION`, `CREATED_TIMESTAMP`, `<EOH>`.

Per-QSO fields: `QSO_DATE`, `TIME_ON`, `CALL`, `MODE` (`FT8` only — no `SUBMODE`),
`BAND` and/or `FREQ`, `GRIDSQUARE`, `MY_GRIDSQUARE`, `STATION_CALLSIGN`,
`RST_SENT`, `RST_RCVD` (3-char FT8 reports, e.g. `-08`).

When **POTA mode** is enabled in Settings at export time:

- `MY_SIG` = `POTA`
- `MY_SIG_INFO` = park reference (e.g. `US-3315`)

Export fails closed (`AdifExportException`) if validation fails or POTA mode is on
without a valid park reference.

JVM unit tests: `AdifWriterTest`, `AdifValidatorTest`.

## Auto-log wiring

`OperateViewModel.handleQsoComplete()` logs when `QsoMachine.state == Complete`,
using rig frequency for `freqHz`, `Ft8DialBands` for band label, and station callsign from settings.

## Related docs

- [APP.md](APP.md) — Log screen UI
- [CORE.md](CORE.md) — `QsoSnapshot`, `ActivationProfile`
