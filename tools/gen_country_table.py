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

OUT_PATH = "core/src/main/java/net/packset/core/CallsignCountryTable.kt"
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
    return f"""package net.packset.core

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
