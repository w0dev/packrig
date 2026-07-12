# Decode Country Code Column Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a two-letter ISO 3166 country code for each decode row's sender callsign, derived offline from a committed lookup table.

**Architecture:** A dev-time Python script collapses AD1C `cty.dat` to ISO granularity and emits a generated Kotlin table in `core/`. A pure `CallsignCountry` object does exact-call + longest-prefix lookup. `DecodeController` stamps `DecodeRow.countryCode` at row creation; `DecodeListPanel` renders a 2-char column between DIST and Hz.

**Tech Stack:** Kotlin (core + app modules), Python 3 (dev-time generator only, not shipped), JUnit4 unit tests.

**Spec:** `docs/superpowers/specs/2026-07-05-decode-country-code-design.md`

## Global Constraints

- No new runtime dependencies; the generator is dev-time only and never runs on device.
- `core/` stays pure Kotlin — no Android imports, no I/O in `CallsignCountry`.
- Behavior parity: no change to decode, QSO, TX, CAT, or logging paths.
- Unknown/unresolvable country renders ` —`; TX rows render blank — never a wrong code.
- Kotlin style: 4-space indent, no wildcard imports, one top-level type per file.
- Branch: `multi-rig`. Commit after every green test cycle.

---

### Task 1: Generator script + generated table

**Files:**
- Create: `tools/gen_country_table.py`
- Create (generated): `core/src/main/java/net/ft8vc/core/CallsignCountryTable.kt`

**Interfaces:**
- Produces: `internal object CallsignCountryTable` with `const val MAX_PREFIX_LEN: Int`, `val PREFIX_ENTRIES: List<String>`, `val EXACT_ENTRIES: List<String>`. Entry format: `"PREFIX=CC;PREFIX=CC;…"` per list element (≤200 entries each). Task 2 parses these.

- [ ] **Step 1: Write the generator script**

Create `tools/gen_country_table.py`:

```python
#!/usr/bin/env python3
"""Generate CallsignCountryTable.kt from the AD1C cty.dat prefix list.

Usage:
    python3 tools/gen_country_table.py /path/to/cty.dat

cty.dat source: https://www.country-files.com/cty/cty.dat
Dev-time only; the output file is committed. Regenerate when prefix->country
allocations meaningfully change (rare). Stale data degrades to a blank
decode-list cell, never a wrong QSO.
"""

import datetime
import re
import sys

# DXCC entity name (exact cty.dat spelling) -> ISO 3166 alpha-2.
# Ham-only distinctions collapse (Asiatic Russia -> RU, Alaska -> US).
ENTITY_ISO = {
    "Monaco": "MC", "Agalega & St. Brandon": "MU", "Mauritius": "MU",
    "Rodriguez Island": "MU", "Equatorial Guinea": "GQ", "Annobon Island": "GQ",
    "Fiji": "FJ", "Conway Reef": "FJ", "Rotuma Island": "FJ",
    "Kingdom of Eswatini": "SZ", "Tunisia": "TN", "Vietnam": "VN",
    "Guinea": "GN", "Bouvet": "BV", "Peter 1 Island": "AQ",
    "Azerbaijan": "AZ", "Georgia": "GE", "Montenegro": "ME",
    "Sri Lanka": "LK", "ITU HQ": "CH", "United Nations HQ": "US",
    "Vienna Intl Ctr": "AT", "Timor - Leste": "TL", "Israel": "IL",
    "Libya": "LY", "Cyprus": "CY", "Tanzania": "TZ", "Nigeria": "NG",
    "Madagascar": "MG", "Mauritania": "MR", "Niger": "NE", "Togo": "TG",
    "Samoa": "WS", "Uganda": "UG", "Kenya": "KE", "Senegal": "SN",
    "Jamaica": "JM", "Yemen": "YE", "Lesotho": "LS", "Malawi": "MW",
    "Algeria": "DZ", "Barbados": "BB", "Maldives": "MV", "Guyana": "GY",
    "Croatia": "HR", "Ghana": "GH", "Malta": "MT", "Zambia": "ZM",
    "Kuwait": "KW", "Sierra Leone": "SL", "West Malaysia": "MY",
    "East Malaysia": "MY", "Nepal": "NP", "Dem. Rep. of the Congo": "CD",
    "Burundi": "BI", "Singapore": "SG", "Rwanda": "RW",
    "Trinidad & Tobago": "TT", "Botswana": "BW", "Tonga": "TO", "Oman": "OM",
    "Bhutan": "BT", "United Arab Emirates": "AE", "Qatar": "QA",
    "Bahrain": "BH", "Pakistan": "PK", "Taiwan": "TW", "Pratas Island": "TW",
    "China": "CN", "Nauru": "NR", "Andorra": "AD", "The Gambia": "GM",
    "Bahamas": "BS", "Mozambique": "MZ", "Chile": "CL",
    "San Felix & San Ambrosio": "CL", "Easter Island": "CL",
    "Juan Fernandez Islands": "CL", "Antarctica": "AQ", "Cuba": "CU",
    "Morocco": "MA", "Bolivia": "BO", "Portugal": "PT",
    "Madeira Islands": "PT", "Azores": "PT", "Uruguay": "UY",
    "Sable Island": "CA", "St. Paul Island": "CA", "Angola": "AO",
    "Cape Verde": "CV", "Comoros": "KM", "Fed. Rep. of Germany": "DE",
    "Philippines": "PH", "Eritrea": "ER", "Palestine": "PS",
    "North Cook Islands": "CK", "South Cook Islands": "CK", "Niue": "NU",
    "Bosnia-Herzegovina": "BA", "Spain": "ES", "Balearic Islands": "ES",
    "Canary Islands": "ES", "Ceuta & Melilla": "ES", "Ireland": "IE",
    "Armenia": "AM", "Liberia": "LR", "Iran": "IR", "Moldova": "MD",
    "Estonia": "EE", "Ethiopia": "ET", "Belarus": "BY", "Kyrgyzstan": "KG",
    "Tajikistan": "TJ", "Turkmenistan": "TM", "France": "FR",
    "Guadeloupe": "GP", "Mayotte": "YT", "St. Barthelemy": "BL",
    "New Caledonia": "NC", "Chesterfield Islands": "NC", "Martinique": "MQ",
    "French Polynesia": "PF", "Austral Islands": "PF",
    "Clipperton Island": "FR", "Marquesas Islands": "PF",
    "St. Pierre & Miquelon": "PM", "Reunion Island": "RE", "St. Martin": "MF",
    "Glorioso Islands": "TF", "Juan de Nova, Europa": "TF",
    "Tromelin Island": "TF", "Crozet Island": "TF", "Kerguelen Islands": "TF",
    "Amsterdam & St. Paul Is.": "TF", "Wallis & Futuna Islands": "WF",
    "French Guiana": "GF", "England": "GB", "Isle of Man": "IM",
    "Northern Ireland": "GB", "Jersey": "JE", "Scotland": "GB",
    "Shetland Islands": "GB", "Guernsey": "GG", "Wales": "GB",
    "Solomon Islands": "SB", "Temotu Province": "SB", "Hungary": "HU",
    "Switzerland": "CH", "Liechtenstein": "LI", "Ecuador": "EC",
    "Galapagos Islands": "EC", "Haiti": "HT", "Dominican Republic": "DO",
    "Colombia": "CO", "San Andres & Providencia": "CO",
    "Malpelo Island": "CO", "Republic of Korea": "KR", "Panama": "PA",
    "Honduras": "HN", "Thailand": "TH", "Vatican City": "VA",
    "Saudi Arabia": "SA", "Italy": "IT", "African Italy": "IT",
    "Sardinia": "IT", "Sicily": "IT", "Djibouti": "DJ", "Grenada": "GD",
    "Guinea-Bissau": "GW", "St. Lucia": "LC", "Dominica": "DM",
    "St. Vincent": "VC", "Japan": "JP", "Minami Torishima": "JP",
    "Ogasawara": "JP", "Mongolia": "MN", "Svalbard": "SJ",
    "Bear Island": "SJ", "Jan Mayen": "SJ", "Jordan": "JO",
    "United States": "US", "Guantanamo Bay": "US", "Mariana Islands": "MP",
    "Baker & Howland Islands": "UM", "Guam": "GU", "Johnston Island": "UM",
    "Midway Island": "UM", "Palmyra & Jarvis Islands": "UM", "Hawaii": "US",
    "Kure Island": "US", "American Samoa": "AS", "Swains Island": "AS",
    "Wake Island": "UM", "Alaska": "US", "Navassa Island": "UM",
    "US Virgin Islands": "VI", "Puerto Rico": "PR", "Desecheo Island": "PR",
    "Norway": "NO", "Argentina": "AR", "Luxembourg": "LU", "Lithuania": "LT",
    "Bulgaria": "BG", "Peru": "PE", "Lebanon": "LB", "Austria": "AT",
    "Finland": "FI", "Aland Islands": "AX", "Market Reef": "AX",
    "Czech Republic": "CZ", "Slovak Republic": "SK", "Belgium": "BE",
    "Greenland": "GL", "Faroe Islands": "FO", "Denmark": "DK",
    "Papua New Guinea": "PG", "Aruba": "AW", "DPR of Korea": "KP",
    "Netherlands": "NL", "Curacao": "CW", "Bonaire": "BQ",
    "Saba & St. Eustatius": "BQ", "Sint Maarten": "SX", "Brazil": "BR",
    "Fernando de Noronha": "BR", "St. Peter & St. Paul": "BR",
    "Trindade & Martim Vaz": "BR", "Suriname": "SR",
    "Franz Josef Land": "RU", "Western Sahara": "EH", "Bangladesh": "BD",
    "Slovenia": "SI", "Seychelles": "SC", "Sao Tome & Principe": "ST",
    "Sweden": "SE", "Poland": "PL", "Sudan": "SD", "Egypt": "EG",
    "Greece": "GR", "Mount Athos": "GR", "Dodecanese": "GR", "Crete": "GR",
    "Tuvalu": "TV", "Western Kiribati": "KI", "Central Kiribati": "KI",
    "Eastern Kiribati": "KI", "Banaba Island": "KI", "Somalia": "SO",
    "San Marino": "SM", "Palau": "PW", "Asiatic Turkey": "TR",
    "European Turkey": "TR", "Iceland": "IS", "Guatemala": "GT",
    "Costa Rica": "CR", "Cocos Island": "CR", "Cameroon": "CM",
    "Corsica": "FR", "Central African Republic": "CF",
    "Republic of the Congo": "CG", "Gabon": "GA", "Chad": "TD",
    "Cote d'Ivoire": "CI", "Benin": "BJ", "Mali": "ML",
    "European Russia": "RU", "Kaliningrad": "RU", "Asiatic Russia": "RU",
    "Uzbekistan": "UZ", "Kazakhstan": "KZ", "Ukraine": "UA",
    "Antigua & Barbuda": "AG", "Belize": "BZ", "St. Kitts & Nevis": "KN",
    "Namibia": "NA", "Micronesia": "FM", "Marshall Islands": "MH",
    "Brunei Darussalam": "BN", "Canada": "CA", "Australia": "AU",
    "Heard Island": "HM", "Macquarie Island": "AU",
    "Cocos (Keeling) Islands": "CC", "Lord Howe Island": "AU",
    "Mellish Reef": "AU", "Norfolk Island": "NF", "Willis Island": "AU",
    "Christmas Island": "CX", "Anguilla": "AI", "Montserrat": "MS",
    "British Virgin Islands": "VG", "Turks & Caicos Islands": "TC",
    "Pitcairn Island": "PN", "Ducie Island": "PN", "Falkland Islands": "FK",
    "South Georgia Island": "GS", "South Shetland Islands": "AQ",
    "South Orkney Islands": "AQ", "South Sandwich Islands": "GS",
    "Bermuda": "BM", "Chagos Islands": "IO", "Hong Kong": "HK",
    "India": "IN", "Andaman & Nicobar Is.": "IN",
    "Lakshadweep Islands": "IN", "Mexico": "MX", "Revillagigedo": "MX",
    "Burkina Faso": "BF", "Cambodia": "KH", "Laos": "LA", "Macao": "MO",
    "Myanmar": "MM", "Afghanistan": "AF", "Indonesia": "ID", "Iraq": "IQ",
    "Vanuatu": "VU", "Syria": "SY", "Latvia": "LV", "Nicaragua": "NI",
    "Romania": "RO", "El Salvador": "SV", "Serbia": "RS", "Venezuela": "VE",
    "Aves Island": "VE", "Zimbabwe": "ZW", "North Macedonia": "MK",
    "Republic of Kosovo": "XK", "Republic of South Sudan": "SS",
    "Albania": "AL", "Gibraltar": "GI", "UK Base Areas on Cyprus": "CY",
    "St. Helena": "SH", "Ascension Island": "SH",
    "Tristan da Cunha & Gough": "SH", "Cayman Islands": "KY",
    "Tokelau Islands": "TK", "New Zealand": "NZ", "Chatham Islands": "NZ",
    "Kermadec Islands": "NZ", "N.Z. Subantarctic Is.": "NZ",
    "Paraguay": "PY", "South Africa": "ZA", "Pr. Edward & Marion Is.": "ZA",
}

# Entities with no defensible ISO mapping: render as blank cell.
NO_ISO = {
    "Sov Mil Order of Malta",
    "Spratly Islands",
    "Scarborough Reef",
}

OUT_PATH = "core/src/main/java/net/ft8vc/core/CallsignCountryTable.kt"
CHUNK = 200  # entries per Kotlin string literal (stays far below 64K)


def parse_cty(path):
    """Yield (name, aliases) per entity record."""
    text = open(path, encoding="utf-8", errors="replace").read()
    entities = []
    for rec in text.split(";"):
        rec = rec.strip()
        if not rec:
            continue
        fields = rec.split(":", 8)
        if len(fields) < 9:
            continue
        name = fields[0].strip()
        aliases = [a.strip() for a in fields[8].split(",") if a.strip()]
        entities.append((name, aliases))
    version = re.search(r"VER(\d{8})", text)
    return entities, (version.group(1) if version else "unknown")


def build_tables(entities):
    prefix_iso, exact_iso, unknown = {}, {}, []
    for name, aliases in entities:
        iso = ENTITY_ISO.get(name)
        if iso is None:
            if name not in NO_ISO:
                unknown.append(name)
            continue
        for alias in aliases:
            # Strip CQ/ITU-zone and continent override suffixes: (…) […] <…> {…} ~…
            base = re.split(r"[(\[<{~]", alias)[0].strip().upper()
            if not base:
                continue
            if base.startswith("="):
                exact_iso.setdefault(base[1:], iso)
            else:
                prefix_iso.setdefault(base, iso)
    return prefix_iso, exact_iso, unknown


def prune_prefixes(prefix_iso):
    """Drop a prefix when its longest shorter ancestor resolves to the same ISO."""
    kept = {}
    for p, iso in prefix_iso.items():
        parent = None
        for ln in range(len(p) - 1, 0, -1):
            if p[:ln] in prefix_iso:
                parent = prefix_iso[p[:ln]]
                break
        if parent != iso:
            kept[p] = iso
    return kept


def prune_exacts(exact_iso, prefixes, max_len):
    def resolve(call):
        for ln in range(min(len(call), max_len), 0, -1):
            if call[:ln] in prefixes:
                return prefixes[call[:ln]]
        return None
    return {c: iso for c, iso in exact_iso.items() if resolve(c) != iso}


def emit(prefixes, exacts, version):
    max_len = max(len(k) for k in prefixes)
    def chunks(d):
        items = sorted(f"{k}={v}" for k, v in d.items())
        return [";".join(items[i:i + CHUNK]) for i in range(0, len(items), CHUNK)]
    def kotlin_list(cs):
        if not cs:
            return "emptyList()"
        body = "\n".join(f'        "{c}",' for c in cs)
        return "listOf(\n" + body + "\n    )"
    today = datetime.date.today().isoformat()
    return f"""package net.ft8vc.core

/**
 * GENERATED FILE — do not edit by hand.
 * Source: AD1C cty.dat (VER{version}) via tools/gen_country_table.py on {today}.
 * Callsign prefix / exact call → ISO 3166 alpha-2, collapsed to ISO granularity
 * and pruned (a prefix is omitted when a shorter prefix yields the same code).
 */
internal object CallsignCountryTable {{
    const val MAX_PREFIX_LEN = {max_len}

    val PREFIX_ENTRIES: List<String> = {kotlin_list(chunks(prefixes))}

    val EXACT_ENTRIES: List<String> = {kotlin_list(chunks(exacts))}
}}
"""


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    entities, version = parse_cty(sys.argv[1])
    prefix_iso, exact_iso, unknown = build_tables(entities)
    prefixes = prune_prefixes(prefix_iso)
    exacts = prune_exacts(exact_iso, prefixes,
                          max(len(k) for k in prefixes))
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write(emit(prefixes, exacts, version))
    print(f"entities: {len(entities)}  mapped: "
          f"{sum(1 for n, _ in entities if n in ENTITY_ISO)}  "
          f"no-iso: {sum(1 for n, _ in entities if n in NO_ISO)}  "
          f"UNKNOWN: {len(unknown)}")
    for n in unknown:
        print(f"  unmapped entity: {n}")
    print(f"prefixes: {len(prefix_iso)} -> {len(prefixes)} after pruning; "
          f"exact calls: {len(exact_iso)} -> {len(exacts)}")
    print(f"wrote {OUT_PATH}")
    if unknown:
        sys.exit("FAIL: add the entities above to ENTITY_ISO or NO_ISO")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the generator**

Run (from repo root; cty.dat already fetched to the session scratchpad, else
`curl -sL -o <scratchpad>/cty.dat https://www.country-files.com/cty/cty.dat`):

```bash
python3 tools/gen_country_table.py <scratchpad>/cty.dat
```

Expected: `entities: 346  mapped: 343  no-iso: 3  UNKNOWN: 0`, prefix/exact
counts printed, `wrote core/src/main/java/net/ft8vc/core/CallsignCountryTable.kt`,
exit 0. If any `unmapped entity` lines appear, add them to `ENTITY_ISO` (or
`NO_ISO`) and re-run until UNKNOWN is 0.

- [ ] **Step 3: Verify the generated file compiles**

Run: `./gradlew :core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add tools/gen_country_table.py core/src/main/java/net/ft8vc/core/CallsignCountryTable.kt
git commit -m "feat(core): generated callsign->ISO country table + generator script"
```

---

### Task 2: CallsignCountry lookup (core, TDD)

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/CallsignCountry.kt`
- Test: `core/src/test/java/net/ft8vc/core/CallsignCountryTest.kt`

**Interfaces:**
- Consumes: `CallsignCountryTable.MAX_PREFIX_LEN / PREFIX_ENTRIES / EXACT_ENTRIES` (Task 1).
- Produces: `object CallsignCountry { fun isoFor(call: String): String? }` — Task 3 calls this.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/ft8vc/core/CallsignCountryTest.kt`:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignCountryTest {

    @Test
    fun `plain prefixes resolve to iso codes`() {
        assertEquals("US", CallsignCountry.isoFor("K1ABC"))
        assertEquals("JP", CallsignCountry.isoFor("JA1XYZ"))
        assertEquals("DE", CallsignCountry.isoFor("DL1ABC"))
        assertEquals("GB", CallsignCountry.isoFor("G4ABC"))
        assertEquals("BR", CallsignCountry.isoFor("PY2XYZ"))
    }

    @Test
    fun `ham-only dxcc distinctions collapse to iso`() {
        assertEquals("US", CallsignCountry.isoFor("KL7AA"))   // Alaska
        assertEquals("US", CallsignCountry.isoFor("KH6AA"))   // Hawaii
        assertEquals("RU", CallsignCountry.isoFor("RA9AA"))   // Asiatic Russia
        assertEquals("IT", CallsignCountry.isoFor("IS0ABC"))  // Sardinia
        assertEquals("FK", CallsignCountry.isoFor("VP8ABC"))  // Falklands default
    }

    @Test
    fun `portable designators and suffixes`() {
        assertEquals("FR", CallsignCountry.isoFor("F/DL1ABC"))
        assertEquals("DE", CallsignCountry.isoFor("DL1ABC/P"))
        assertEquals("US", CallsignCountry.isoFor("W1ABC/7"))
        assertNull(CallsignCountry.isoFor("W1ABC/MM"))
    }

    @Test
    fun `unresolvable calls return null`() {
        assertNull(CallsignCountry.isoFor("<PJ4/K1ABC>"))
        assertNull(CallsignCountry.isoFor(""))
        assertNull(CallsignCountry.isoFor("QAA1AA")) // Q block unallocated
    }

    @Test
    fun `lookup is case and whitespace tolerant`() {
        assertEquals("JP", CallsignCountry.isoFor(" ja1xyz "))
    }

    @Test
    fun `generated table is well formed`() {
        val prefixEntries = CallsignCountryTable.PREFIX_ENTRIES
            .flatMap { it.split(';') }.filter { it.isNotEmpty() }
        val exactEntries = CallsignCountryTable.EXACT_ENTRIES
            .flatMap { it.split(';') }.filter { it.isNotEmpty() }
        assertTrue("table must not be empty", prefixEntries.size > 300)
        var maxPrefixLen = 0
        for (e in prefixEntries + exactEntries) {
            val parts = e.split('=')
            assertEquals("entry '$e' must be KEY=CC", 2, parts.size)
            assertTrue("code '${parts[1]}' in '$e' must be 2 uppercase letters",
                parts[1].matches(Regex("[A-Z]{2}")))
        }
        for (e in prefixEntries) {
            maxPrefixLen = maxOf(maxPrefixLen, e.substringBefore('=').length)
        }
        assertEquals(CallsignCountryTable.MAX_PREFIX_LEN, maxPrefixLen)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallsignCountryTest"`
Expected: FAIL to compile — `Unresolved reference 'CallsignCountry'`

- [ ] **Step 3: Write the implementation**

Create `core/src/main/java/net/ft8vc/core/CallsignCountry.kt`:

```kotlin
package net.ft8vc.core

/**
 * ISO 3166 alpha-2 country code lookup for decoded FT8 callsigns.
 *
 * Backed by the generated [CallsignCountryTable] (see tools/gen_country_table.py).
 * Resolution order: exact call, then portable-suffix strip, then prefix
 * designator of a compound call, then longest-prefix match. Every failure
 * mode returns null (rendered as a blank cell) — never a guessed code.
 */
object CallsignCountry {

    private val exact: Map<String, String> by lazy { parseEntries(CallsignCountryTable.EXACT_ENTRIES) }
    private val prefixes: Map<String, String> by lazy { parseEntries(CallsignCountryTable.PREFIX_ENTRIES) }

    /** ISO alpha-2 code for [call], or null when unresolvable. Never throws. */
    fun isoFor(call: String): String? {
        val c = call.trim().uppercase()
        if (c.isEmpty() || c.startsWith("<")) return null
        exact[c]?.let { return it }
        val base = stripPortableSuffix(c) ?: return null
        exact[base]?.let { return it }
        val target = designatorOf(base)
        for (len in minOf(target.length, CallsignCountryTable.MAX_PREFIX_LEN) downTo 1) {
            prefixes[target.substring(0, len)]?.let { return it }
        }
        return null
    }

    /**
     * Drops one trailing portable suffix (`/P`, `/M`, `/QRP`, `/A`, `/<digit>`).
     * Maritime/aeronautical mobile (`/MM`, `/AM`) has no DXCC country: null.
     */
    private fun stripPortableSuffix(call: String): String? {
        val slash = call.lastIndexOf('/')
        if (slash < 0) return call
        val tail = call.substring(slash + 1)
        return when {
            tail == "MM" || tail == "AM" -> null
            tail == "P" || tail == "M" || tail == "QRP" || tail == "A" ||
                (tail.length == 1 && tail[0].isDigit()) ->
                call.substring(0, slash).ifEmpty { null }
            else -> call
        }
    }

    /** For compound calls (`F/DL1ABC`), the shorter segment names the country; ties go first. */
    private fun designatorOf(call: String): String {
        val slash = call.indexOf('/')
        if (slash < 0) return call
        val head = call.substring(0, slash)
        val tail = call.substring(slash + 1)
        return if (tail.length < head.length) tail else head
    }

    private fun parseEntries(chunks: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (chunk in chunks) {
            for (entry in chunk.split(';')) {
                if (entry.isEmpty()) continue
                val eq = entry.indexOf('=')
                map[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        }
        return map
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallsignCountryTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/CallsignCountry.kt core/src/test/java/net/ft8vc/core/CallsignCountryTest.kt
git commit -m "feat(core): CallsignCountry ISO lookup over generated table"
```

---

### Task 3: DecodeRow.countryCode + controller wiring (TDD)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (DecodeRow, ~line 30)
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` (~line 310 row creation)
- Test: `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerCountryTest.kt`

**Interfaces:**
- Consumes: `CallsignCountry.isoFor(call: String): String?` (Task 2).
- Produces: `DecodeRow.countryCode: String?` (default null) — Task 4 renders it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerCountryTest.kt`
(fixture mirrors `DecodeControllerWorkedBeforeTest`):

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeControllerCountryTest {
    private val slotStart = 1_700_000_000_000L

    private fun result(message: String, freqHz: Double, snr: Int) =
        Ft8DecodeResult(message = message, snr = snr, dtSeconds = 0f, freqHz = freqHz.toFloat(), score = snr)

    private class SingleShotFake(private val results: List<Ft8DecodeResult>) : Ft8DecoderApi {
        override fun isAvailable(): Boolean = true
        override fun version(): String = "single-shot-fake"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
            results.toTypedArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(0)
    }

    @Test
    fun `new rows carry the iso country of the sender call`() = runTest {
        val fake = SingleShotFake(
            listOf(
                result("CQ JA1XYZ PM95", 1500.0, snr = -10),
                result("K1ABC DL1ABC -07", 1700.0, snr = -12),
            )
        )
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)

        val byMessage = controller.slice.value.decodes.associateBy { it.message }
        assertEquals("JP", byMessage.getValue("CQ JA1XYZ PM95").countryCode)
        assertEquals("DE", byMessage.getValue("K1ABC DL1ABC -07").countryCode)
        controller.close()
    }

    @Test
    fun `rows without a resolvable sender have null country`() = runTest {
        val fake = SingleShotFake(listOf(result("W2XYZ <PJ4/K1ABC> -05", 1500.0, snr = -10)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)

        assertNull(controller.slice.value.decodes.single().countryCode)
        controller.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerCountryTest"`
Expected: FAIL to compile — `Unresolved reference 'countryCode'`

- [ ] **Step 3: Add the field and wiring**

In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, after `distanceKm`:

```kotlin
    /** ISO 3166 alpha-2 code for the sender callsign; null when unresolvable. */
    val countryCode: String? = null,
```

In `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`, add the import:

```kotlin
import net.ft8vc.core.CallsignCountry
```

and in the `newRows += DecodeRow(...)` construction, after the `distanceKm =` line:

```kotlin
                    countryCode = sender?.let { CallsignCountry.isoFor(it) },
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerCountryTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt app/src/test/java/net/ft8vc/app/controllers/DecodeControllerCountryTest.kt
git commit -m "feat(app): stamp ISO country code on decode rows"
```

---

### Task 4: UI column + full verification

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` (header ~line 164, row ~line 333)

**Interfaces:**
- Consumes: `DecodeRow.countryCode: String?` (Task 3).
- Produces: user-visible `CC` column. No downstream consumers.

- [ ] **Step 1: Add the header cell**

In the header `Row`, after `DecodeHeaderCell("DIST")` and before `DecodeHeaderCell("Hz")`:

```kotlin
                    DecodeHeaderCell("CC")
```

- [ ] **Step 2: Add the row cell**

In the decode row `Row`, between the distance `Text` and the freq `Text`
(after the `DecodeDistance.label` text block, before `text = "%4d".format(row.freqHz)`):

```kotlin
        Text(
            text = if (isTx) "  " else (row.countryCode ?: " —"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
```

- [ ] **Step 3: Compile and run the full unit suites**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures (pre-existing suites stay green).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt
git commit -m "feat(ui): two-letter country code column in decode list"
```
