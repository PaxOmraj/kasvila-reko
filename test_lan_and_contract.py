#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kaksi testiä joita yksikkötestit eivät tavoita:

  A. Lähiverkkoprotokolla oikeasti ajettuna — palvelin päälle, pyynnöt läpi,
     koodin tarkistus, kaappauksen esto.
  B. Sopimustesti Kotlinia vastaan: työpöydän lähettämän tiedoston kentät
     verrattuna puhelimen @Serializable-luokkien kenttiin. Nimivirhe tässä
     rajapinnassa ei näy kummankaan kielen kääntäjälle, mutta hukkaa dataa
     hiljaa — siksi se on syytä tarkistaa koneellisesti.
"""
import io
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request

# Työpöytäohjelma tuo tkinterin. Jos sitä ei ole (palvelin, CI), aseta
# KASVILA_TK_STUB osoittamaan tynkäkansioon.
_stub = os.environ.get("KASVILA_TK_STUB")
if _stub:
    sys.path.insert(0, _stub)


def _find_desktop():
    """Etsi työpöytäohjelman lähdetiedosto. Testit toimivat sekä kehityskansiosta
    että puretusta zipistä, kunhan Kasvila_TK_1xx_tuloste.py on lähistöllä."""
    import glob
    for pat in ("Kasvila_TK_*.py", "../Kasvila_TK_*.py", "../../Kasvila_TK_*.py",
                "tk103_new.py", "../tk103_new.py"):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    raise SystemExit(
        "Työpöytäohjelman lähdetiedostoa ei löytynyt.\n"
        "Kopioi Kasvila_TK_104_tuloste.py tämän testin viereen ja aja uudelleen.")

DESKTOP = _find_desktop()

import importlib.util
spec = importlib.util.spec_from_file_location("tk103mod", DESKTOP)
M = importlib.util.module_from_spec(spec)
sys.modules["tk103mod"] = M
spec.loader.exec_module(M)

OK, FAIL = [], []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ✓ " if cond else "  ✗ ") + name + (("  — " + detail) if detail and not cond else ""))


# ════════════════════════════════════════════════════════════════════════════
#  A. Lähiverkkoprotokolla
# ════════════════════════════════════════════════════════════════════════════

class FakeApp:
    def __init__(self, data):
        self.data = data


DATA = {
    "cost_settings": {"currency": "€", "default_vat_rate": 13.5},
    "reko_device": {"id": "pc_test", "name": "Kasvila-PC"},
    "reko_peers": {},
    "products": {"P00001": {"name": "Tomaatti", "unit": "pc", "sell_price": 3.96,
                            "sell_price_gross": 4.50, "vat_rate": 13.5,
                            "cost_per_unit": 1.1, "recipe": "Erä A", "active": True,
                            "updated_at": "2026-09-01T10:00:00"}},
    "reko_groups": [{"id": "rg_a", "name": "Tampere REKO", "weekday": 3, "time": "17:00-18:00",
                     "parity": "even", "active": True, "updated_at": "2026-09-01T10:00:00"}],
    "reko_distributions": [],
    "reko_home_deliveries": [],
    "sales": [],
}

print("\n=== A. Lähiverkkoprotokolla ===")

app = FakeApp(DATA)
session = M.RekoLanSession(app)
handler = M.reko_make_lan_handler(session)

from http.server import ThreadingHTTPServer
srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
srv.daemon_threads = True
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.2)
BASE = "http://127.0.0.1:%d" % port


def call(path, method="GET", code=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if code is not None:
        req.add_header("X-Kasvila-Code", code)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=5) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


st, body = call("/kasvila/hello", code="000000")
check("Väärä koodi torjutaan", st == 403, "status %s" % st)

st, body = call("/kasvila/hello", code=session.code)
check("Oikea koodi hyväksytään", st == 200, "status %s" % st)
check("Hello palauttaa laitetunnisteen", body.get("device_id") == "pc_test", json.dumps(body))
check("Hello kertoo protokollaversion", body.get("version") == 2)

phone_payload = {
    "format": "kasvila-reko-sync", "version": 2,
    "device_id": "phone_test", "device_name": "Jarmon puhelin",
    "exported_at": M.reko_now_iso(), "last_sync_at": "",
    "currency": "€", "default_vat": 13.5, "catalog_version": 0,
    "rings": [], "products": [], "orders": [], "deliveries": [], "aliases": {},
}
st, body = call("/kasvila/exchange", "POST", session.code, phone_payload)
check("Vaihto onnistuu", st == 200, "status %s" % st)
check("Vastaus on v2-synkronointitiedosto", body.get("format") == "kasvila-reko-sync")
check("Vastaus sisältää työpöydän tuotteet", len(body.get("products", [])) == 1)
check("Vastaus sisältää työpöydän piirit", len(body.get("rings", [])) == 1)
check("Puhelimen kuorma jäi odottamaan pääsäiettä",
      session.pending_payload is not None)
check("Työpöytä EI muuttunut vaihdossa",
      DATA["products"]["P00001"]["sell_price_gross"] == 4.50,
      "vaihto ei saa kirjoittaa mitään")

st, body = call("/kasvila/commit", "POST", session.code, {})
check("Commit onnistuu", st == 200)
check("Commit kirjattiin", session.peer_committed is True)

st, body = call("/kasvila/ei-ole", code=session.code)
check("Tuntematon polku on 404", st == 404, "status %s" % st)

# Koodi on sidottu ensimmäiseen laitteeseen. Samasta osoitteesta jatko sallitaan;
# eri osoitteesta ei. Testataan sidonnan logiikka suoraan.
ok, why = session.check(session.code, "192.168.1.99")
check("Toinen laite torjutaan kesken vaihdon", (not ok) and why == "busy", str((ok, why)))
ok, why = session.check(session.code, "8.8.8.8")
check("Julkisesta osoitteesta torjutaan", not ok, str((ok, why)))

session.expires = time.time() - 1
ok, why = session.check(session.code, "127.0.0.1")
check("Vanhentunut koodi torjutaan", (not ok) and why == "expired", str((ok, why)))

srv.shutdown()
srv.server_close()


# ════════════════════════════════════════════════════════════════════════════
#  B. Sopimus Kotlinin kanssa
# ════════════════════════════════════════════════════════════════════════════

print("\n=== B. Kenttäsopimus puhelimen kanssa ===")

def _find_kotlin():
    import glob
    for pat in ("app/src/main/java/fi/kasvila/reko/",
                "KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko/",
                "../KasvilaRekoTilaukset_*/app/src/main/java/fi/kasvila/reko/"):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    raise SystemExit("Kotlin-lähteitä ei löytynyt.")

KT_DIR = _find_kotlin()


def kotlin_fields(path, cls):
    """Poimi @Serializable data classin kenttänimet."""
    src = io.open(KT_DIR + path, encoding="utf-8").read()
    m = re.search(r"data class %s\((.*?)\n\)" % cls, src, re.S)
    if not m:
        return set()
    body = m.group(1)
    names = set()
    for line in body.split("\n"):
        line = line.strip()
        mm = re.match(r"(?:override\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:", line)
        if mm:
            names.add(mm.group(1))
    return names


payload = M.reko_sync_payload(DATA, "")

kt_payload = kotlin_fields("SyncPayload.kt", "SyncPayload")
missing = set(payload.keys()) - kt_payload
check("Kaikki payload-kentät tunnetaan Kotlinissa", not missing, "puuttuu: %s" % missing)

kt_product = kotlin_fields("Model.kt", "Product")
p_keys = set(payload["products"][0].keys())
check("Tuotteen kentät tunnetaan Kotlinissa", not (p_keys - kt_product),
      "puuttuu: %s" % (p_keys - kt_product))
for req in ("price", "vat", "cost", "active", "updated_at", "deleted", "origin"):
    check("Tuote lähettää kentän '%s'" % req, req in p_keys)

kt_ring = kotlin_fields("Model.kt", "Ring")
r_keys = set(payload["rings"][0].keys())
check("Piirin kentät tunnetaan Kotlinissa", not (r_keys - kt_ring),
      "puuttuu: %s" % (r_keys - kt_ring))
for req in ("fb_url", "notes", "active", "updated_at", "deleted", "origin"):
    check("Piiri lähettää kentän '%s'" % req, req in r_keys)

# Tilaukset ja kuljetukset: rakennetaan yksi kumpaakin, jotta kentät näkyvät.
DATA["reko_distributions"] = [{
    "id": "rd_1", "group_id": "rg_a", "date": "2026-09-24", "time": "17:00", "address": "X",
    "orders": [{"id": "ro_1", "customer": "A", "contact": "", "paid": False, "picked": False,
                "notPicked": False, "cancelled": False, "note": "",
                "updated_at": "2026-09-01T10:00:00",
                "lines": [{"name": "Tomaatti", "qty": 1, "unit": "kpl",
                           "unit_price": 4.5, "product_id": "P00001", "given": False}]}]}]
DATA["reko_home_deliveries"] = [{
    "id": "del_1", "customer": "B", "contact": "", "address": "Y", "deliveryAddress": "",
    "orderDate": "2026-09-01", "deliveryDate": "2026-09-05", "deliveryTime": "",
    "paymentMethod": "cash", "paid": False, "delivered": False, "cancelled": False,
    "reminderHoursBefore": None, "note": "", "updated_at": "2026-09-01T10:00:00", "lines": []}]
payload = M.reko_sync_payload(DATA, "")

kt_order = kotlin_fields("Model.kt", "Order")
o_keys = set(payload["orders"][0].keys())
check("Tilauksen kentät tunnetaan Kotlinissa", not (o_keys - kt_order),
      "puuttuu: %s" % (o_keys - kt_order))

kt_line = kotlin_fields("Model.kt", "Line")
l_keys = set(payload["orders"][0]["lines"][0].keys())
check("Tilausrivin kentät tunnetaan Kotlinissa", not (l_keys - kt_line),
      "puuttuu: %s" % (l_keys - kt_line))

kt_deliv = kotlin_fields("Model.kt", "Delivery")
d_keys = set(payload["deliveries"][0].keys())
check("Kuljetuksen kentät tunnetaan Kotlinissa", not (d_keys - kt_deliv),
      "puuttuu: %s" % (d_keys - kt_deliv))

# ── Asiakkaat (v47) ──
DATA["customers"] = {"C00001": {
    "name": "Venla V", "kind": "person", "type": "retail", "business_id": "",
    "contact": "fb: venla", "phone": "040 1234567", "email": "venla@example.fi",
    "address": "Kotikatu 1", "hours": "", "billing": "", "einvoice_address": "",
    "einvoice_operator": "", "notes": "Pitää chilistä",
    "created": "2026-01-01", "active": True, "updated_at": "2026-09-01T10:00:00"}}
payload = M.reko_sync_payload(DATA, "")

kt_cust = kotlin_fields("Model.kt", "Customer")
c_keys = set(payload["customers"][0].keys())
check("Asiakkaan kentät tunnetaan Kotlinissa", not (c_keys - kt_cust),
      "puuttuu: %s" % (c_keys - kt_cust))
for req in ("name", "contact", "phone", "email", "address", "notes",
            "active", "updated_at", "deleted", "origin"):
    check("Asiakas lähettää kentän '%s'" % req, req in c_keys)
check("Työpöydän omat asiakaskentät EIVÄT lähde matkaan",
      not (c_keys & {"kind", "business_id", "hours", "billing",
                     "einvoice_address", "einvoice_operator", "created"}),
      str(c_keys))

# Kenttäomistus käytännössä: puhelimen muokkaus ei saa hukata työpöydän kenttiä.
_w = dict(payload["customers"][0])
_w["name"] = "Venla Virtanen"
_w["phone"] = "050 7654321"
_w["updated_at"] = M.reko_now_iso()
M.reko_customer_apply_wire(DATA["customers"]["C00001"], _w)
_c = DATA["customers"]["C00001"]
check("Asiakkaan nimi päivittyi", _c["name"] == "Venla Virtanen", _c["name"])
check("Asiakkaan puhelin päivittyi", _c["phone"] == "050 7654321", _c["phone"])
check("Laskutustiedot säilyivät", _c.get("kind") == "person" and _c.get("created") == "2026-01-01",
      str((_c.get("kind"), _c.get("created"))))
check("Verkkolaskuosoite säilyi", "einvoice_operator" in _c)

# ── Jakelut (v44) ──
kt_dist = kotlin_fields("Model.kt", "Distribution")
payload = M.reko_sync_payload(DATA, "")
dist_keys = set(payload["distributions"][0].keys()) if payload["distributions"] else set()
check("Jakeluja lähetetään", bool(dist_keys), "ei yhtään jakelua")
check("Jakelun kentät tunnetaan Kotlinissa", not (dist_keys - kt_dist),
      "puuttuu: %s" % (dist_keys - kt_dist))
for req in ("group_id", "date", "time", "address", "status", "cancelled",
            "notes", "dist_id", "updated_at", "deleted", "origin"):
    check("Jakelu lähettää kentän '%s'" % req, req in dist_keys)
check("Tilaukset EIVÄT kulje jakelun mukana", "orders" not in dist_keys, str(dist_keys))
check("Jakelun tunniste on luonnollinen avain group_id|date",
      payload["distributions"][0]["id"] == "rg_a|2026-09-24",
      payload["distributions"][0]["id"])

# Kotlinin puolella sama avain rakennetaan Distribution.keyOf():lla.
_ktsrc = io.open(KT_DIR + "Model.kt", encoding="utf-8").read()
check("Kotlin muodostaa saman avaimen",
      'fun keyOf(ringId: String, date: String): String = "$ringId|$date"' in _ktsrc)

# Muototunniste ja versio täsmäävät molemmilla puolilla
kt_sync = io.open(KT_DIR + "SyncPayload.kt", encoding="utf-8").read()
check("Muototunniste sama molemmissa",
      'const val FORMAT = "kasvila-reko-sync"' in kt_sync and
      payload["format"] == "kasvila-reko-sync")
check("Protokollaversio sama molemmissa",
      "const val VERSION = 2" in kt_sync and payload["version"] == 2)

# JSON pyöreä: mitä lähetetään, on aitoa JSONia ja säilyy identtisenä
raw = json.dumps(payload, ensure_ascii=False)
back = json.loads(raw)
check("Payload on aitoa JSONia ja pyöristyy muuttumattomana", back == payload)
check("Weekday menee int-muodossa kun se on asetettu",
      isinstance(payload["rings"][0]["weekday"], int),
      str(type(payload["rings"][0]["weekday"])))

DATA["reko_groups"][0]["weekday"] = ""
payload2 = M.reko_sync_payload(DATA, "")
check("Weekday menee tyhjänä merkkijonona kun sitä ei ole",
      payload2["rings"][0]["weekday"] == "",
      repr(payload2["rings"][0]["weekday"]))

print("\n" + "=" * 60)
print("Läpi: %d   Epäonnistui: %d" % (len(OK), len(FAIL)))
if FAIL:
    print("EPÄONNISTUNEET:")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("Kaikki testit läpi.")
