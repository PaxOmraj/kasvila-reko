#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Merge-moottorin testit.

Nämä ajavat TK_103:n synkronointilohkon läpi ne tilanteet joiden takia koko
kaksisuuntaisuus on riskialtista. Sama päätöstaulukko on Kotlinissa
(SyncEngine.kt), joten läpimennyt testi tarkoittaa että molempien laitteiden
sääntö on oikein muotoiltu — ei vain että Python toimii.
"""
import json
import sys
from datetime import datetime, timedelta


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

_src = open(DESKTOP, encoding="utf-8").read()

# REKO:n pikkuapurit (tunnisteet, nimilaput, noutopäivät) ovat eri kohdassa
# tiedostoa kuin synkronointilohko, mutta yhdistäminen kutsuu niitä. Lohko on
# puhdasta Pythonia — ei tkinteriä, ei luokkia — joten se ajetaan sellaisenaan.
_REKO_LABEL_DICTS = """
import uuid
REKO_UNITS = {"kpl": {"fi": "kpl"}, "kg": {"fi": "kg"}, "pkt": {"fi": "pkt"}}
REKO_PARITY = {"weekly": {"fi": "Joka viikko"}, "even": {"fi": "Parillinen vko"},
               "odd": {"fi": "Pariton vko"}}
REKO_STATUS = {"planned": {"fi": "Suunniteltu"}, "open": {"fi": "Avoin"},
               "closed": {"fi": "Suljettu"}, "delivered": {"fi": "Toimitettu"}}
REKO_WEEKDAYS = {i: {"fi": d} for i, d in enumerate(
    ["ma", "ti", "ke", "to", "pe", "la", "su"])}
def _cl(d):
    return d.get("fi", "")
"""
exec(_REKO_LABEL_DICTS)

_da = _src.index("_REKO_PROD_UNIT = ")
_db = _src.index("def reko_ring_on_date(")
exec(_src[_da:_db])

_a = _src.index('REKO_SYNC_V2 = "kasvila-reko-sync"')
_b = _src.index("REKO_SNAP_DIR = os.path.join")
exec(_src[_a:_b])
print("Testataan: %s\n" % DESKTOP)

OK, FAIL = [], []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ✓ " if cond else "  ✗ ") + name + (("  — " + detail) if detail and not cond else ""))


def ago(minutes):
    return (datetime.now() - timedelta(minutes=minutes)).replace(microsecond=0).isoformat()


def desktop_state():
    """Työpöydän tila: tuote jolla on reseptit ja kustannus, piiri, tilaus."""
    return {
        "cost_settings": {"currency": "€", "default_vat_rate": 13.5},
        "reko_device": {"id": "pc_test", "name": "Kasvila-PC"},
        "reko_peers": {"phone_test": {"name": "Jarmon puhelin", "last_sync_at": ago(120)}},
        "reko_catalog_version": 5,
        "products": {
            "P00001": {
                "name": "Kirsikkatomaatti 250 g",
                "recipe": "Kasvihuone-erä A",
                "category": "Vihannekset",
                "unit": "pc",
                "cost_per_unit": 1.10,
                "sell_price": 3.9648,
                "sell_price_gross": 4.50,
                "vat_rate": 13.5,
                "allergens": "ei allergeeneja",
                "description": "Makeita kirsikkatomaatteja.",
                "ean_unit": "6412345678901",
                "box_qty": 12,
                "aliases": ["kirsikkatomaatti", "kirsikkatom"],
                "active": True,
                "updated_at": ago(180),
                "origin": "pc_test",
            },
            "P00002": {
                "name": "Basilika ruukku",
                "unit": "pc", "cost_per_unit": 0.6,
                "sell_price": 2.2026, "sell_price_gross": 2.50, "vat_rate": 13.5,
                "aliases": ["basilika"], "active": True,
                "updated_at": ago(180), "origin": "pc_test",
            },
        },
        "reko_groups": [{
            "id": "rg_aaa", "name": "Tampere REKO", "city": "Tampere",
            "location": "Ratinan ranta", "address": "Ratinanranta 1",
            "weekday": 3, "time": "17:00-18:00", "parity": "even",
            "handler": "", "fb_url": "", "notes": "", "active": True,
            "updated_at": ago(180), "origin": "pc_test",
        }],
        "reko_distributions": [{
            "id": "rd_1", "group_id": "rg_aaa", "date": "2026-09-24",
            "time": "17:00-18:00", "address": "Ratinanranta 1",
            "status": "planned", "notes": "",
            "orders": [{
                "id": "ro_1", "customer": "Matti M", "contact": "", "paid": False,
                "picked": False, "notPicked": False, "cancelled": False, "note": "",
                "updated_at": ago(180), "origin": "pc_test",
                "lines": [{"name": "Kirsikkatomaatti 250 g", "qty": 2, "unit": "kpl",
                           "unit_price": 4.50, "product_id": "P00001", "given": False}],
            }],
        }],
        "reko_home_deliveries": [],
        "customers": {
            "C00001": {
                "name": "Venla V", "kind": "person", "type": "retail",
                "business_id": "", "contact": "fb: venla", "phone": "040 1234567",
                "email": "", "address": "Kotikatu 1", "hours": "", "billing": "",
                "einvoice_address": "", "einvoice_operator": "OKOYFIHH",
                "notes": "Pit\u00e4\u00e4 chilist\u00e4", "created": "2026-01-01",
                "active": True, "updated_at": ago(180), "origin": "pc_test",
            },
        },
        "sales": [],
    }


def phone_payload(**over):
    """Puhelimen lähettämä tila. Oletuksena identtinen työpöydän kanssa."""
    d = desktop_state()
    p = reko_sync_payload(d, "")
    p["device_id"] = "phone_test"
    p["device_name"] = "Jarmon puhelin"
    p["last_sync_at"] = ago(120)
    p["exported_at"] = ago(1)
    p.update(over)
    return p


def find(lst, pid):
    for x in lst:
        if x.get("id") == pid:
            return x
    return None


print("\n=== 1. Idempotenssi: sama tiedosto kahdesti ===")
d = desktop_state()
pay = phone_payload()
plan = reko_sync_plan(d, pay)
check("Ensimmäinen ajo: ei muutoksia (tilat samat)", plan["stats"]["total_changes"] == 0,
      json.dumps(plan["stats"]))
check("Ei ristiriitoja", plan["stats"]["conflicts"] == 0)
reko_sync_apply(d, plan)
plan2 = reko_sync_plan(d, pay)
check("Toinen ajo: edelleen 0 muutosta", plan2["stats"]["total_changes"] == 0,
      json.dumps(plan2["stats"]))


print("\n=== 2. Vain puhelin muokkasi hintaa → otetaan hiljaa ===")
d = desktop_state()
pay = phone_payload()
prod = find(pay["products"], "P00001")
prod["price"] = 4.80
prod["updated_at"] = ago(30)          # muokattu edellisen synkan (120 min) jälkeen
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0)
check("Yksi tuotepäivitys", plan["stats"]["products_upd"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
check("Hinta päivittyi 4,80:aan", abs(d["products"]["P00001"]["sell_price_gross"] - 4.80) < 1e-6,
      str(d["products"]["P00001"]["sell_price_gross"]))
check("Veroton hinta laskettiin uudelleen",
      abs(d["products"]["P00001"]["sell_price"] - 4.80 / 1.135) < 1e-3,
      str(d["products"]["P00001"]["sell_price"]))


print("\n=== 3. KENTTÄOMISTUS: työpöydän omat kentät säilyvät ===")
check("Resepti säilyi", d["products"]["P00001"].get("recipe") == "Kasvihuone-erä A")
check("Kustannus säilyi", abs(d["products"]["P00001"].get("cost_per_unit", 0) - 1.10) < 1e-9)
check("Allergeenit säilyivät", d["products"]["P00001"].get("allergens") == "ei allergeeneja")
check("EAN säilyi", d["products"]["P00001"].get("ean_unit") == "6412345678901")
check("Laatikkokoko säilyi", d["products"]["P00001"].get("box_qty") == 12)
check("Kuvaus säilyi", d["products"]["P00001"].get("description", "").startswith("Makeita"))


print("\n=== 4. Vain työpöytä muokkasi → puhelimen vanha kopio ei kumoa ===")
d = desktop_state()
d["products"]["P00001"]["sell_price_gross"] = 5.20
d["products"]["P00001"]["updated_at"] = ago(10)
pay = phone_payload()                  # puhelimella vanha 4,50 ja vanha leima
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0)
check("Ei tuotepäivityksiä", plan["stats"]["products_upd"] == 0, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
check("Työpöydän 5,20 jäi voimaan",
      abs(d["products"]["P00001"]["sell_price_gross"] - 5.20) < 1e-6,
      str(d["products"]["P00001"]["sell_price_gross"]))


print("\n=== 5. MOLEMMAT muokkasivat hintaa → ristiriita, ei hiljaista ylikirjoitusta ===")
d = desktop_state()
d["products"]["P00001"]["sell_price_gross"] = 4.20
d["products"]["P00001"]["updated_at"] = ago(5)          # työpöytä tuoreempi
pay = phone_payload()
prod = find(pay["products"], "P00001")
prod["price"] = 4.80
prod["updated_at"] = ago(45)
plan = reko_sync_plan(d, pay)
check("Yksi ristiriita", plan["stats"]["conflicts"] == 1, json.dumps(plan["stats"]))
c = plan["conflicts"][0]
check("Ristiriita koskee tuotetta", c["kind"] == "product")
check("Oletus = uudempi (työpöytä)", c["default"] == "LOCAL", c["default"])
check("Kuvaa vain eroavan kentän", [f["label"] for f in c["fields"]] == ["Hinta (sis. ALV)"],
      str([f["label"] for f in c["fields"]]))
# Käyttäjä valitsee puhelimen
reko_sync_apply(d, plan, {c["key"]: "REMOTE"})
check("Valinta 'puhelin' tuotti 4,80",
      abs(d["products"]["P00001"]["sell_price_gross"] - 4.80) < 1e-6,
      str(d["products"]["P00001"]["sell_price_gross"]))
check("Resepti säilyi ristiriidan ratkaisussakin",
      d["products"]["P00001"].get("recipe") == "Kasvihuone-erä A")


print("\n=== 6. Poisto puhelimessa vs. muokkaus työpöydällä → ristiriita, oletus säilytä ===")
d = desktop_state()
d["products"]["P00001"]["name"] = "Kirsikkatomaatti 250 g (luomu)"
d["products"]["P00001"]["updated_at"] = ago(5)
pay = phone_payload()
prod = find(pay["products"], "P00001")
prod["deleted"] = True
prod["deleted_at"] = ago(40)
prod["active"] = False
prod["updated_at"] = ago(40)
plan = reko_sync_plan(d, pay)
check("Yksi ristiriita", plan["stats"]["conflicts"] == 1, json.dumps(plan["stats"]))
c = plan["conflicts"][0]
check("Luonne = poisto vs. muokkaus", c["nature"] == "delete", c["nature"])
check("Oletus EI ole poisto", c["default"] == "LOCAL", c["default"])
reko_sync_apply(d, plan)               # oletusvalinnoilla
check("Tuote säilyi", not d["products"]["P00001"].get("deleted"))
check("Nimi säilyi muokattuna",
      d["products"]["P00001"]["name"] == "Kirsikkatomaatti 250 g (luomu)")


print("\n=== 7. Poisto puhelimessa, ei muokkausta työpöydällä → poisto menee läpi ===")
d = desktop_state()
pay = phone_payload()
prod = find(pay["products"], "P00002")
prod["deleted"] = True
prod["deleted_at"] = ago(40)
prod["active"] = False
prod["updated_at"] = ago(40)
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0, json.dumps(plan["conflicts"]))
check("Poisto laskettiin", plan["stats"]["deletes"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
check("Tuote on hautakivenä", d["products"]["P00002"].get("deleted") is True)
check("Tuote on yhä tallessa (ei kadonnut)", "P00002" in d["products"])
check("Tuote ei ole käytössä", d["products"]["P00002"].get("active") is False)


print("\n=== 8. Puhelimessa luotu uusi tuote ===")
d = desktop_state()
pay = phone_payload()
pay["products"].append({
    "id": "Pph_9f3a2b1c", "name": "Chilihillo 200 g", "price": 6.50, "unit": "purkki",
    "aliases": ["chilihillo"], "vat": 13.5, "cost": 0.0, "category": "",
    "active": True, "updated_at": ago(20), "deleted": False, "deleted_at": "",
    "origin": "phone_test",
})
plan = reko_sync_plan(d, pay)
check("Yksi uusi tuote", plan["stats"]["products_new"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
np = d["products"].get("Pph_9f3a2b1c")
check("Tuote luotiin työpöydälle", np is not None)
check("Nimi oikein", np and np["name"] == "Chilihillo 200 g")
check("Verollinen hinta 6,50", np and abs(np["sell_price_gross"] - 6.50) < 1e-6)
check("Puhelimen yksikkö 'purkki' säilyi omassa kentässään",
      np and np.get("reko_unit") == "purkki", str(np.get("reko_unit") if np else None))
check("Työpöydän oma unit jäi kanoniseksi", np and np.get("unit") == "pc", str(np.get("unit") if np else None))
check("Tunnus ei törmää työpöydän P-sarjaan", "Pph_9f3a2b1c" not in ("P00001", "P00002"))


print("\n=== 9. Piiri pois käytöstä, avoimia tilauksia → merkitään poistuvaksi ===")
d = desktop_state()
pay = phone_payload()
ring = find(pay["rings"], "rg_aaa")
ring["active"] = False
ring["updated_at"] = ago(20)
# tilaus on tulevaisuudessa
d["reko_distributions"][0]["date"] = (datetime.now() + timedelta(days=14)).date().isoformat()
plan = reko_sync_plan(d, pay)
reko_sync_apply(d, plan)
g = d["reko_groups"][0]
check("Piiri pois käytöstä", g.get("active") is False)
check("Piiri merkitty poistuvaksi", g.get("retired") is True, str(g.get("retired")))
check("Tilaus säilyi", len(d["reko_distributions"][0]["orders"]) == 1)


print("\n=== 10. Kellopoikkeama varoittaa ===")
d = desktop_state()
pay = phone_payload()
pay["exported_at"] = (datetime.now() + timedelta(minutes=45)).replace(microsecond=0).isoformat()
plan = reko_sync_plan(d, pay)
check("Poikkeama havaittu", plan["clock_skew"] >= 40, str(plan["clock_skew"]))
check("Varoitus näkyy", any("kellot" in w for w in plan["warnings"]), str(plan["warnings"]))


print("\n=== 11. Tyhjä työpöytä + ensimmäinen synkronointi ===")
d = {"cost_settings": {"currency": "€", "default_vat_rate": 13.5}, "products": {},
     "reko_groups": [], "reko_distributions": [], "reko_home_deliveries": [], "sales": []}
pay = phone_payload()
pay["last_sync_at"] = ""
plan = reko_sync_plan(d, pay)
check("Kaikki tuotteet uusia", plan["stats"]["products_new"] == 2, json.dumps(plan["stats"]))
check("Piiri uusi", plan["stats"]["rings_new"] == 1)
check("Tilaus uusi", plan["stats"]["orders_new"] == 1)
check("Ei ristiriitoja ensisynkassa", plan["stats"]["conflicts"] == 0)
reko_sync_apply(d, plan)
check("Tuotteet tallessa", len(d["products"]) == 2)
check("Jakelu luotiin tilaukselle", len(d["reko_distributions"]) == 1)
check("Tilaus oikeassa jakelussa",
      d["reko_distributions"][0]["orders"][0]["id"] == "ro_1")


print("\n=== 12. Tilaus siirtyi toiselle päivälle puhelimessa ===")
d = desktop_state()
pay = phone_payload()
o = find(pay["orders"], "ro_1")
o["date"] = "2026-10-08"
o["updated_at"] = ago(15)
plan = reko_sync_plan(d, pay)
reko_sync_apply(d, plan)
dates = {dd["date"]: [x["id"] for x in dd["orders"]] for dd in d["reko_distributions"]}
check("Tilaus siirtyi uudelle päivälle", dates.get("2026-10-08") == ["ro_1"], json.dumps(dates))
check("Vanhalle päivälle ei jäänyt kaksoiskappaletta",
      dates.get("2026-09-24", []) == [], json.dumps(dates))


print("\n=== 13. Väärä tiedostomuoto torjutaan ===")
d = desktop_state()
plan = reko_sync_plan(d, {"format": "kasvila-reko-rings", "rings": []})
check("Vanha muoto torjutaan v2-polulla", plan.get("bad_file") is True)
plan = reko_sync_plan(d, {"format": REKO_SYNC_V2, "version": 99})
check("Uudempi versio torjutaan", plan.get("bad_file") is True, json.dumps(plan))
check("Syy kertoo päivityksestä", "Päivitä" in plan.get("reason", ""), plan.get("reason", ""))


print("\n=== 14. Tyhjä katalogi varoittaa ===")
d = desktop_state()
pay = phone_payload()
pay["products"] = []
pay["rings"] = []
plan = reko_sync_plan(d, pay)
check("Varoitus tyhjästä tiedostosta",
      any("ei ole yhtään tuotetta" in w for w in plan["warnings"]), str(plan["warnings"]))


print("\n=== 15. Hautakivien siivous säästää yhä viitatut ===")
d = desktop_state()
d["products"]["P00001"]["deleted"] = True
d["products"]["P00001"]["deleted_at"] = (datetime.now() - timedelta(days=300)).isoformat()
d["products"]["P00002"]["deleted"] = True
d["products"]["P00002"]["deleted_at"] = (datetime.now() - timedelta(days=300)).isoformat()
reko_purge_tombstones(d)
check("Tilauksessa viitattu tuote säilyi (P00001)", "P00001" in d["products"])
check("Viittaamaton vanha hautakivi siivottiin (P00002)", "P00002" not in d["products"])


print("\n=== 16. Synkronointiloki ja kumppanimerkintä ===")
d = desktop_state()
pay = phone_payload()
prod = find(pay["products"], "P00001")
prod["price"] = 4.99
prod["updated_at"] = ago(30)
plan = reko_sync_plan(d, pay)
plan["snapshot"] = "snap_test.json"
reko_sync_apply(d, plan)
check("Loki sai merkinnän", len(d.get("reko_sync_log", [])) == 1)
check("Loki muistaa varmuuskopion",
      d["reko_sync_log"][-1].get("snapshot") == "snap_test.json")
check("Kumppanin last_sync_at päivittyi",
      bool(d["reko_peers"]["phone_test"]["last_sync_at"]))
check("Uusi last_sync_at on tuoreempi kuin vanha",
      reko_is_newer(d["reko_peers"]["phone_test"]["last_sync_at"], ago(60)))



print("\n=== 17. Asiakasrekisteri: vain puhelin muokkasi ===")
d = desktop_state()
pay = phone_payload()
c = find(pay["customers"], "C00001")
c["phone"] = "050 7654321"
c["updated_at"] = ago(30)
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0, json.dumps(plan["conflicts"]))
check("Yksi asiakaspäivitys", plan["stats"]["customers_upd"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
check("Puhelin päivittyi", d["customers"]["C00001"]["phone"] == "050 7654321",
      d["customers"]["C00001"]["phone"])
check("Verkkolaskuoperaattori säilyi",
      d["customers"]["C00001"].get("einvoice_operator") == "OKOYFIHH",
      str(d["customers"]["C00001"].get("einvoice_operator")))
check("Asiakastyyppi säilyi", d["customers"]["C00001"].get("kind") == "person")
check("Luontipäivä säilyi", d["customers"]["C00001"].get("created") == "2026-01-01")


print("\n=== 18. Asiakas: molemmat muokkasivat → ristiriita ===")
d = desktop_state()
d["customers"]["C00001"]["address"] = "Uusikatu 9"
d["customers"]["C00001"]["updated_at"] = ago(5)
pay = phone_payload()
c = find(pay["customers"], "C00001")
c["address"] = "Rantatie 3"
c["updated_at"] = ago(40)
plan = reko_sync_plan(d, pay)
check("Yksi ristiriita", plan["stats"]["conflicts"] == 1, json.dumps(plan["stats"]))
cf = plan["conflicts"][0]
check("Ristiriita koskee asiakasta", cf["kind"] == "customer", cf["kind"])
check("Vain osoite eroaa", [f["label"] for f in cf["fields"]] == ["Osoite"],
      str([f["label"] for f in cf["fields"]]))
reko_sync_apply(d, plan, {cf["key"]: "REMOTE"})
check("Puhelimen osoite valittiin", d["customers"]["C00001"]["address"] == "Rantatie 3",
      d["customers"]["C00001"]["address"])


print("\n=== 19. Puhelimessa luotu uusi asiakas ===")
d = desktop_state()
pay = phone_payload()
pay["customers"].append({
    "id": "Cph_1a2b3c4d", "name": "Matti M", "contact": "", "phone": "040 111222",
    "email": "", "address": "", "notes": "", "type": "", "active": True,
    "updated_at": ago(15), "deleted": False, "deleted_at": "", "origin": "phone_test",
})
plan = reko_sync_plan(d, pay)
check("Yksi uusi asiakas", plan["stats"]["customers_new"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
nc = d["customers"].get("Cph_1a2b3c4d")
check("Asiakas luotiin työpöydälle", nc is not None)
check("Nimi oikein", nc and nc["name"] == "Matti M")
check("Työpöydän oletuskentät täytettiin", nc and "created" in nc and "type" in nc)


print("\n=== 20. Asiakas poistettu puhelimessa → hautakivi, historia säilyy ===")
d = desktop_state()
pay = phone_payload()
c = find(pay["customers"], "C00001")
c["deleted"] = True
c["deleted_at"] = ago(20)
c["active"] = False
c["updated_at"] = ago(20)
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0, json.dumps(plan["conflicts"]))
reko_sync_apply(d, plan)
check("Asiakas on hautakivenä", d["customers"]["C00001"].get("deleted") is True)
check("Asiakas on yhä tallessa", "C00001" in d["customers"])
check("Nimi säilyi historiaa varten", d["customers"]["C00001"]["name"] == "Venla V")



print("\n=== 21. Jakelut: luonnollinen avain tunnistaa saman noudon ===")
d = desktop_state()
pay = phone_payload()
# Työpöydällä on jo jakelu rd_1 (rg_aaa, 2026-09-24). Puhelin lähettää saman
# noudon omalla luonnollisella avaimellaan — ei satunnaista tunnistetta.
key = "rg_aaa|2026-09-24"
check("Työpöytä lähettää luonnollisen avaimen",
      [x["id"] for x in pay["distributions"]] == [key],
      str([x["id"] for x in pay["distributions"]]))
check("Työpöydän oma tunniste kulkee mukana",
      pay["distributions"][0]["dist_id"] == "rd_1",
      pay["distributions"][0].get("dist_id"))
check("Tilaukset EIVÄT ole jakelun mukana",
      "orders" not in pay["distributions"][0],
      str(sorted(pay["distributions"][0].keys())))


print("\n=== 22. Nouto peruttu puhelimessa ===")
d = desktop_state()
pay = phone_payload()
dist = find(pay["distributions"], key)
dist["cancelled"] = True
dist["notes"] = "Juhannus"
dist["updated_at"] = ago(20)
plan = reko_sync_plan(d, pay)
check("Ei ristiriitaa", plan["stats"]["conflicts"] == 0, json.dumps(plan["conflicts"]))
check("Yksi jakelupäivitys", plan["stats"]["dists_upd"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
target = d["reko_distributions"][0]
check("Jakelu merkitty perutuksi", target.get("cancelled") is True)
check("Muistiinpano tallentui", target.get("notes") == "Juhannus")
check("Tilaukset säilyivät", len(target.get("orders", [])) == 1, str(len(target.get("orders", []))))
check("Työpöydän oma tunniste ei muuttunut", target.get("id") == "rd_1", target.get("id"))
check("Peruttu päivä katoaa noutopäivistä",
      "2026-09-24" not in reko_upcoming_dates_ex(d, d["reko_groups"][0], 12),
      str(reko_upcoming_dates_ex(d, d["reko_groups"][0], 12)[:4]))


print("\n=== 23. Nouto siirretty kaavan ulkopuoliselle päivälle ===")
d = desktop_state()
pay = phone_payload()
moved = "rg_aaa|2026-09-25"          # perjantai, ei kaavan mukainen
pay["distributions"].append({
    "id": moved, "group_id": "rg_aaa", "date": "2026-09-25",
    "time": "18:00-19:00", "address": "Keskustori", "status": "planned",
    "cancelled": False, "notes": "Siirretty, Ratina kaivettu auki",
    "dist_id": "", "updated_at": ago(10), "deleted": False,
    "deleted_at": "", "origin": "phone_test",
})
plan = reko_sync_plan(d, pay)
check("Yksi uusi jakelu", plan["stats"]["dists_new"] == 1, json.dumps(plan["stats"]))
reko_sync_apply(d, plan)
new_d = [x for x in d["reko_distributions"] if x.get("date") == "2026-09-25"]
check("Jakelu luotiin työpöydälle", len(new_d) == 1, str(len(new_d)))
check("Työpöytä antoi sille oman tunnisteen",
      new_d and new_d[0].get("id", "").startswith("rd_"), str(new_d[0].get("id") if new_d else None))
check("Poikkeava paikka tallentui", new_d and new_d[0].get("address") == "Keskustori")
check("Kaavan ulkopuolinen päivä tuli noutopäiviin",
      "2026-09-25" in reko_upcoming_dates_ex(d, d["reko_groups"][0], 12),
      str(reko_upcoming_dates_ex(d, d["reko_groups"][0], 12)[:4]))


print("\n=== 24. Jakelu: molemmat muokkasivat → ristiriita ===")
d = desktop_state()
d["reko_distributions"][0]["time"] = "16:00-17:00"
d["reko_distributions"][0]["updated_at"] = ago(5)
pay = phone_payload()
dist = find(pay["distributions"], key)
dist["time"] = "18:00-19:00"
dist["updated_at"] = ago(40)
plan = reko_sync_plan(d, pay)
check("Yksi ristiriita", plan["stats"]["conflicts"] == 1, json.dumps(plan["stats"]))
cf = plan["conflicts"][0]
check("Ristiriita koskee jakelua", cf["kind"] == "dist", cf["kind"])
check("Vain kellonaika eroaa", [f["label"] for f in cf["fields"]] == ["Kellonaika"],
      str([f["label"] for f in cf["fields"]]))
check("Oletus = uudempi (työpöytä)", cf["default"] == "LOCAL", cf["default"])
reko_sync_apply(d, plan)
check("Työpöydän aika jäi voimaan",
      d["reko_distributions"][0]["time"] == "16:00-17:00",
      d["reko_distributions"][0]["time"])


print("\n=== 25. Jakelu ilman tilausta ei riko tilaussynkronointia ===")
d = desktop_state()
pay = phone_payload()
o = find(pay["orders"], "ro_1")
o["paid"] = True
o["updated_at"] = ago(10)
dist = find(pay["distributions"], key)
dist["status"] = "delivered"
dist["updated_at"] = ago(10)
plan = reko_sync_plan(d, pay)
reko_sync_apply(d, plan)
target = d["reko_distributions"][0]
check("Jakelun tila päivittyi", target.get("status") == "delivered", str(target.get("status")))
check("Tilaus on yhä samassa jakelussa", len(target.get("orders", [])) == 1)
check("Tilaus merkittiin maksetuksi", target["orders"][0].get("paid") is True)
check("Vain yksi jakelu tälle päivälle",
      sum(1 for x in d["reko_distributions"] if x.get("date") == "2026-09-24") == 1,
      str([x.get("id") for x in d["reko_distributions"]]))

print("\n" + "=" * 60)
print("Läpi: %d   Epäonnistui: %d" % (len(OK), len(FAIL)))
if FAIL:
    print("EPÄONNISTUNEET:")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("Kaikki testit läpi.")
