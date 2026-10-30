#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin-tarkistin ilman kääntäjää.

Tässä ympäristössä ei ole Kotlin-kääntäjää (Maven Central ja Gradle-portaali
ovat estettyjä), joten nämä tarkistukset korvaavat ne virheluokat jotka kääntäjä
olisi napannut ensimmäisenä:

  1. Kutsu nimetyllä argumentilla jota funktiossa ei ole  → "no parameter named"
  2. Pakollinen parametri jää antamatta                   → "no value passed for"
  3. Viittaus tyyppiin jota ei ole määritelty missään     → "unresolved reference"
  4. Sulkeiden / aaltosulkeiden epätasapaino

Tämä ei ole tyyppitarkistus eikä korvaa Android Studion käännöstä — mutta se
nappaa juuri ne virheet jotka syntyvät kun koodia kirjoitetaan monessa
tiedostossa ilman että kääntäjä sanoo mitään.
"""
import io
import os
import re
import sys

def _find_src():
    """Toimii sekä projektin juuresta että sen yläpuolelta."""
    import glob
    for pat in ("app/src/main/java/fi/kasvila/reko",
                "KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko",
                "../KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko"):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    raise SystemExit("Kotlin-lähteitä ei löytynyt.")

SRC = _find_src()

PROBLEMS = []
NOTES = []


def strip_noise(src):
    """Poista merkkijonot ja kommentit, jotta niiden sisällöt eivät sotke jäsennystä."""
    src = re.sub(r'"""[\s\S]*?"""', '""', src)
    src = re.sub(r'(?<!\\)"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def split_params(text):
    """Jaa parametrilista pilkuilla ylimmällä tasolla."""
    # HUOM: '<' ja '>' EIVÄT ole sulkuja tässä. Kotlinin lambda-tyyppi
    # "() -> Unit" sisältää '>':n, ja sen laskeminen suluksi katkaisi
    # parametrilistan jäsennyksen kesken — juuri siitä syntyivät ensimmäisen
    # ajon 57 väärää osumaa.
    out, depth, cur = [], 0, ""
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def find_matching(src, i):
    """i osoittaa '('. Palauta vastaavan ')' indeksi."""
    depth = 0
    while i < len(src):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


# ── 1. Kerää funktiomäärittelyt ─────────────────────────────────────────────

funcs = {}          # nimi -> {"params":[(nimi, onko_oletus)], "file":..., "line":...}
declared_types = set()

for fn in sorted(os.listdir(SRC)):
    if not fn.endswith(".kt"):
        continue
    raw = io.open(os.path.join(SRC, fn), encoding="utf-8").read()
    src = strip_noise(raw)

    for m in re.finditer(r"\b(?:data\s+|sealed\s+|enum\s+|abstract\s+|open\s+)*"
                         r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)", src):
        declared_types.add(m.group(1))

    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", src):
        name = m.group(1)
        open_paren = src.index("(", m.end() - 1)
        close = find_matching(src, open_paren)
        if close < 0:
            continue
        body = src[open_paren + 1:close]
        params = []
        for p in split_params(body):
            p = p.strip()
            if not p:
                continue
            pm = re.match(r"(?:vararg\s+)?(?:val\s+|var\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:", p)
            if pm:
                params.append((pm.group(1), "=" in p))
        line = raw[:m.start()].count("\n") + 1
        # Ylikuormitukset: merkitään, ettei niistä raportoida vääriä osumia.
        if name in funcs:
            funcs[name]["overloaded"] = True
        else:
            funcs[name] = {"params": params, "file": fn, "line": line, "overloaded": False}

# Composable-funktioiden kutsut, jotka kirjoitin itse. Vain nämä tarkistetaan:
# Compose- ja Kotlin-kirjastojen funktioista meillä ei ole määrittelyjä.
MINE = {n for n in funcs}


# ── 2. Tarkista kutsujen nimetyt argumentit ────────────────────────────────

for fn in sorted(os.listdir(SRC)):
    if not fn.endswith(".kt"):
        continue
    raw = io.open(os.path.join(SRC, fn), encoding="utf-8").read()
    src = strip_noise(raw)

    for m in re.finditer(r"(?<![\w.])([A-Z][A-Za-z0-9_]*|[a-z][A-Za-z0-9_]*)\s*\(", src):
        name = m.group(1)
        if name not in MINE:
            continue
        info = funcs[name]
        if info["overloaded"]:
            continue
        # Ohita itse määrittelyrivi
        before = src[max(0, m.start() - 40):m.start()]
        if re.search(r"\bfun\s+(?:<[^>]*>\s*)?$", before):
            continue

        open_paren = m.end() - 1
        close = find_matching(src, open_paren)
        if close < 0:
            continue
        args_text = src[open_paren + 1:close]
        # Lambda-argumentti suluissa tekee jäsennyksestä epäluotettavan; ohitetaan
        if args_text.count("{") != args_text.count("}"):
            continue

        arg_names, positional = [], 0
        for a in split_params(args_text):
            a = a.strip()
            if not a:
                continue
            am = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", a)
            if am:
                arg_names.append(am.group(1))
            else:
                positional += 1

        declared = [p for p, _ in info["params"]]
        line = raw[:m.start()].count("\n") + 1

        for a in arg_names:
            if a not in declared:
                PROBLEMS.append(
                    "%s:%d  kutsu %s(...) käyttää argumenttia '%s' jota ei ole "
                    "(määrittely %s:%d, parametrit: %s)"
                    % (fn, line, name, a, info["file"], info["line"], ", ".join(declared)))

        # Puuttuvat pakolliset parametrit (vain kun kaikki argumentit ovat nimettyjä
        # ja perässä ei ole lambdaa — muuten emme tiedä mitä trailing lambda täyttää)
        trailing_lambda = src[close + 1:close + 3].strip().startswith("{")
        if positional == 0 and arg_names and not trailing_lambda:
            required = [p for p, has_default in info["params"] if not has_default]
            missing = [r for r in required if r not in arg_names]
            if missing:
                PROBLEMS.append(
                    "%s:%d  kutsu %s(...) ei anna pakollisia parametreja: %s"
                    % (fn, line, name, ", ".join(missing)))


# ── 3. Viittaukset omiin tyyppeihin ────────────────────────────────────────

KNOWN_EXTERNAL = {
    "Int", "String", "Boolean", "Double", "Float", "Long", "Unit", "Any", "List",
    "Map", "Set", "Pair", "Triple", "MutableList", "MutableMap", "ArrayList",
    "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "Exception", "Modifier",
    "Nothing", "Char", "Byte", "Short", "Array", "ArrayDeque", "Comparator",
}

for fn in sorted(os.listdir(SRC)):
    if not fn.endswith(".kt"):
        continue
    raw = io.open(os.path.join(SRC, fn), encoding="utf-8").read()
    src = strip_noise(raw)
    imported = set(re.findall(r"^import\s+[\w.]*\.(\w+)", src, re.M))
    # Tyyppiannotaatiot: ": Tyyppi"
    for m in re.finditer(r":\s*([A-Z][A-Za-z0-9_]*)", src):
        t = m.group(1)
        if t in declared_types or t in KNOWN_EXTERNAL or t in imported:
            continue
        NOTES.append((fn, t))


# ── 4. Tasapaino ───────────────────────────────────────────────────────────

for fn in sorted(os.listdir(SRC)):
    if not fn.endswith(".kt"):
        continue
    raw = io.open(os.path.join(SRC, fn), encoding="utf-8").read()
    src = strip_noise(raw)
    for ch_open, ch_close, label in (("{", "}", "aaltosulut"), ("(", ")", "sulut"),
                                     ("[", "]", "hakasulut")):
        d = src.count(ch_open) - src.count(ch_close)
        if d != 0:
            PROBLEMS.append("%s  %s epätasapainossa: %+d" % (fn, label, d))


# ── Raportti ───────────────────────────────────────────────────────────────

print("Tarkistettu %d funktiomäärittelyä, %d tyyppiä.\n" % (len(funcs), len(declared_types)))

if PROBLEMS:
    print("ONGELMAT (%d):" % len(PROBLEMS))
    for p in PROBLEMS:
        print("  ✗ " + p)
else:
    print("  ✓ Ei nimettyjen argumenttien eikä tasapainon ongelmia.")

# Tuntemattomat tyypit ovat enimmäkseen Compose/Android-kirjastoa; näytetään
# vain ne jotka näyttävät omilta (esiintyvät harvoin ja alkavat tutulla etuliitteellä).
unknown = {}
for fn, t in NOTES:
    unknown.setdefault(t, set()).add(fn)
suspicious = {t: f for t, f in unknown.items()
              if t.startswith(("Sync", "Reko", "Lan", "Merge", "Ring", "Product",
                               "Order", "Delivery", "Snapshot", "Peer", "Route", "Rank"))}
if suspicious:
    print("\nHUOM — omalta näyttävä tyyppi ilman määrittelyä tai importtia:")
    for t, files in sorted(suspicious.items()):
        print("  ? %-24s %s" % (t, ", ".join(sorted(files))))

sys.exit(1 if PROBLEMS else 0)
