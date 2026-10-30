#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Jäsentimen testit oikeilla suomalaisilla tilausteksteillä.

Sama sääntöjoukko on Kotlinissa (OrderParser.kt). Nämä testit kertovat mitä
offline-tunnistuksen PITÄÄ osata ilman tekoälyä — se on se tila jossa
sovellusta oikeasti käytetään: noutopisteellä, ilman kenttää.
"""
import io
import os
import sys

_stub = os.environ.get("KASVILA_TK_STUB")
if _stub:
    sys.path.insert(0, _stub)


def _find_desktop():
    import glob
    for pat in ("Kasvila_TK_*.py", "../Kasvila_TK_*.py", "tk103_new.py", "../tk103_new.py"):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    raise SystemExit("Työpöytäohjelman lähdetiedostoa ei löytynyt.")


import importlib.util
spec = importlib.util.spec_from_file_location("tk103mod", _find_desktop())
M = importlib.util.module_from_spec(spec)
sys.modules["tk103mod"] = M
spec.loader.exec_module(M)

OK, FAIL = [], []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ✓ " if cond else "  ✗ ") + name + (("  — " + detail) if detail and not cond else ""))


CATALOG = [
    {"id": "P1", "name": "FavaDuja Mieto 200 g", "unit": "purkki", "price": 6.50,
     "aliases": ["favaduja mieto", "mieto favaduja", "favado mieto"]},
    {"id": "P2", "name": "FavaDuja Tulinen 200 g", "unit": "purkki", "price": 6.50,
     "aliases": ["favaduja tulinen", "tulinen favaduja", "favado tulinen"]},
    {"id": "P3", "name": "Kirsikkatomaatti 250 g", "unit": "rasia", "price": 4.50,
     "aliases": ["kirsikkatomaatti", "kirsikkatomaatit", "kirsikkatom"]},
    {"id": "P4", "name": "Perunat", "unit": "kg", "price": 2.20,
     "aliases": ["peruna", "perunaa"]},
    {"id": "P5", "name": "Basilika ruukku", "unit": "kpl", "price": 2.50,
     "aliases": ["basilika", "basilikaa"]},
    {"id": "P6", "name": "Mustaherukkahillo 220 g", "unit": "purkki", "price": 5.00,
     "aliases": ["mustaherukkahillo", "hillo", "mustaherukka hillo"]},
]


def parse(text):
    return M.parse_reko_order_text(text, CATALOG)


def ids(res):
    return [l["product_id"] for l in res["lines"]]


def qtys(res):
    return [l["qty"] for l in res["lines"]]


print("\n=== 1. Yksinkertainen lista ===")
r = parse("2 kirsikkatomaattia, 1 basilika")
check("Kaksi riviä", len(r["lines"]) == 2, str(ids(r)))
check("Tuotteet oikein", ids(r) == ["P3", "P5"], str(ids(r)))
check("Määrät oikein", qtys(r) == [2.0, 1.0], str(qtys(r)))
check("Ei tunnistamattomia", r["unmatched"] == [], str(r["unmatched"]))


print("\n=== 2. Pakkaussanat numeron jäljessä ===")
r = parse("2 purkkia mustaherukkahilloa")
check("Pakkaussana ei estä tunnistusta", ids(r) == ["P6"], str(ids(r)) + " " + str(r["unmatched"]))
check("Määrä 2", qtys(r) == [2.0], str(qtys(r)))

r = parse("kolme pkt kirsikkatomaattia")
check("'kolme pkt' tunnistuu", ids(r) == ["P3"], str(ids(r)) + " " + str(r["unmatched"]))
check("Numerosana kolme = 3", qtys(r) == [3.0], str(qtys(r)))

r = parse("1 rasia kirsikkatomaatteja ja 2 purkkia favaduja mietoa")
check("Kaksi riviä pakkaussanoilla", len(r["lines"]) == 2, str(ids(r)) + " " + str(r["unmatched"]))
check("Molemmat oikein", ids(r) == ["P3", "P1"], str(ids(r)))


print("\n=== 3. Paino kilotuotteelle ===")
r = parse("1,5 kg perunoita")
check("Perunat tunnistuu", ids(r) == ["P4"], str(ids(r)) + " " + str(r["unmatched"]))
check("Määrä 1,5", qtys(r) == [1.5], str(qtys(r)))

r = parse("500 g perunaa")
check("Grammat muuttuu kiloiksi", qtys(r) == [0.5], str(qtys(r)))


print("\n=== 4. Kertomerkinnät ===")
for text, want in [("3x kirsikkatomaatti", 3.0), ("kirsikkatomaatti x2", 2.0),
                   ("2 x basilika", 2.0)]:
    r = parse(text)
    check("'%s' → määrä %s" % (text, want), qtys(r) == [want],
          str(qtys(r)) + " " + str(r["unmatched"]))


print("\n=== 5. Variantit eivät mene sekaisin ===")
r = parse("1 favaduja mieto, 1 favaduja tulinen")
check("Mieto ja tulinen erottuvat", ids(r) == ["P1", "P2"], str(ids(r)))

r = parse("favado tulinen")
check("Alias 'favado tulinen' osuu tuliseen", ids(r) == ["P2"], str(ids(r)))


print("\n=== 6. Kohteliaisuudet ja roska ===")
r = parse("Moi! 2 basilikaa kiitos!")
check("'kiitos' ei tuota riviä", ids(r) == ["P5"], str(ids(r)) + " " + str(r["unmatched"]))
check("Määrä säilyy", qtys(r) == [2.0], str(qtys(r)))


print("\n=== 7. Monirivinen Facebook-kommentti ===")
text = """Hei!
2 rasiaa kirsikkatomaatteja
1 purkki favaduja tulinen
1,5 kg perunoita
Kiitos!"""
r = parse(text)
check("Kolme riviä", len(r["lines"]) == 3, str(ids(r)) + " " + str(r["unmatched"]))
check("Tuotteet oikein", ids(r) == ["P3", "P2", "P4"], str(ids(r)))
check("Määrät oikein", qtys(r) == [2.0, 1.0, 1.5], str(qtys(r)))


print("\n=== 8. Tunnistamaton jää tunnistamattomaksi, ei arvata ===")
r = parse("2 kg porkkanoita")
check("Tuntematon tuote ei tuota riviä", len(r["lines"]) == 0, str(ids(r)))
check("Fragmentti jää tunnistamattomiin", len(r["unmatched"]) == 1, str(r["unmatched"]))


print("\n=== 9. Asiakkaan nimen tunnistus ===")
for text, want in [("asiakas Venla: 2 basilikaa", "Venla"),
                   ("Venla: 2 basilikaa", "Venla"),
                   ("Venlalle kaksi basilikaa", "Venla")]:
    name, rest = M._reko_extract_customer(text, CATALOG) if hasattr(M, "_reko_extract_customer") else (None, text)
    if not hasattr(M, "_reko_extract_customer"):
        check("(työpöydällä ei ole erillistä nimentunnistusta — ohitetaan)", True)
        break
    check("'%s' → %s" % (text, want), name == want, str(name))


print("\n=== 10. Pakkaussana EI katoa tuotenimestä ilman numeroa ===")
# "levy" on osa monen tuotteen nimeä; ilman numeroa sitä ei saa riisua.
CAT2 = CATALOG + [{"id": "P7", "name": "Suklaalevy 100 g", "unit": "levy", "price": 3.50,
                   "aliases": ["suklaalevy"]}]
r = M.parse_reko_order_text("suklaalevy", CAT2)
check("Nimessä oleva pakkaussana säilyy", [l["product_id"] for l in r["lines"]] == ["P7"],
      str([l["product_id"] for l in r["lines"]]) + " " + str(r["unmatched"]))
r = M.parse_reko_order_text("2 levyä suklaalevy", CAT2)
check("Numeron jälkeinen pakkaussana riisutaan silti",
      [l["product_id"] for l in r["lines"]] == ["P7"],
      str([l["product_id"] for l in r["lines"]]) + " " + str(r["unmatched"]))



# ════════════════════════════════════════════════════════════════════════════
#  11. Kotlinin ja Pythonin säännöt ovat samat
# ════════════════════════════════════════════════════════════════════════════
#
#  Jäsennin on kahdessa kielessä. Jos säännöt eroavat, puhelin ja kone lukevat
#  saman asiakkaan kommentin eri tavalla — eikä kumpikaan kääntäjä huomaa sitä.
#  Siksi molempien regexit puretaan ja verrataan koneellisesti.

print("\n=== 11. Kotlinin ja Pythonin säännöt ovat samat ===")

import codecs
import glob
import re as _re


def _kt_file():
    for pat in ("app/src/main/java/fi/kasvila/reko/OrderParser.kt",
                "KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko/OrderParser.kt",
                "../KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko/OrderParser.kt"):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    return None


_ktp = _kt_file()
if _ktp is None:
    check("(Kotlin-lähdettä ei löytynyt — ohitetaan)", True)
else:
    _kt = io.open(_ktp, encoding="utf-8").read()

    def _kt_literal(marker):
        """Kotlin-merkkijonoliteraalit purettuna yhdeksi regexiksi."""
        i = _kt.index(marker)
        block = _kt[i:]
        block = block[:block.index("RegexOption")]
        parts = _re.findall(r'"((?:\\.|[^"\\])*)"', block)
        return "".join(codecs.decode(x, "unicode_escape") for x in parts)

    kt_seg = _kt_literal("private val SEG_SPLIT = Regex(")
    kt_uw = _kt_literal("private val UNIT_WORDS = Regex(")

    _py = io.open(_find_desktop(), encoding="utf-8").read()
    py_seg = _re.search(r're\.split\(r"([^"]+)"',
                        _py[_py.index("def _reko_split_segments"):]).group(1)
    _i = _py.index("REKO_UNIT_WORDS_RE = (")
    py_uw = "".join(_re.findall(r'r"([^"]*)"', _py[_i:_py.index("\n)", _i)]))
    # Python-lähteessä \u00f6 on kirjoitettu escapena; Kotlinista se purkautui
    # jo merkiksi, joten yhtenäistetään vertailua varten.
    py_uw = py_uw.replace("\\u00f6", "ö").replace("\\u00e4", "ä")

    check("Fragmenttijakaja on sama molemmissa", kt_seg == py_seg,
          "\n      KT: %s\n      PY: %s" % (kt_seg, py_seg))
    check("Pakkaussanalista on sama molemmissa", kt_uw == py_uw,
          "\n      KT: %s\n      PY: %s" % (kt_uw, py_uw))

    # Käyttäytyminen, ei vain merkkijonon yhtäläisyys.
    for text, want in [("1,5 kg perunoita", ["1,5 kg perunoita"]),
                       ("2 kirsikkatomaattia, 1 basilika", ["2 kirsikkatomaattia", "1 basilika"]),
                       ("3,5 ja 2,5", ["3,5", "2,5"])]:
        got = [x.strip() for x in _re.split(kt_seg, text, flags=_re.I) if x and x.strip()]
        check("Kotlinin jakaja: '%s'" % text, got == want, str(got))

    check("Kotlinin pakkaussana osuu 'purkkia'",
          bool(_re.search(kt_uw, "2 purkkia hilloa", flags=_re.I)))
    check("Kotlinin pakkaussana EI osu sanaan 'suklaalevy'",
          not _re.search(kt_uw, "suklaalevy", flags=_re.I))

print("\n" + "=" * 60)
print("Läpi: %d   Epäonnistui: %d" % (len(OK), len(FAIL)))
if FAIL:
    print("EPÄONNISTUNEET:")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("Kaikki testit läpi.")
