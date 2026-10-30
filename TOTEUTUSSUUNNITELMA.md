# Kasvila REKO — kaksisuuntainen synkronointi ja itsenäinen app

Toteutussuunnitelma v42 → v47. Lähtötila: app v41, TK_103.

---

## 0. Päätetyt linjaukset

| # | Päätös |
|---|---|
| 1 | App saa muokata ALV-kantaa. Kustannushinta näkyy **vain lukuoikeudella**. |
| 2 | Appin "poista tuote/piiri" = **poista käytöstä** (`active=false`), ei lopullinen. |
| 3 | REKO-yhteenveto tulee **etusivulle**, ei asetuksiin. |
| 4 | **Sekä** tiedostosynkka **että** lähiverkkoyhteys. |
| 5 | Asetusosion nimi: **"Tiedot ja synkronointi"**. |

---

## 1. Tietomalli

### 1.1 Yhteiset synkronointikentät

Jokaiselle synkronoitavalle tietueelle (Ring, Product, Order, Delivery):

```
updated_at : String   ISO-8601 "2026-09-11T14:02:00"  — päivitetään JOKA muokkauksessa
deleted    : Boolean  pehmeä poisto (hautakivi)
deleted_at : String   milloin haudattiin (siivous 180 vrk jälkeen)
origin     : String   laitetunnus joka loi tietueen
```

`Order` ja `Delivery` -tietueilla `updated_at` on jo. Lisätään `deleted`/`deleted_at`/`origin`.

### 1.2 Appin `AppState` — uudet kentät

```kotlin
val deviceId: String = ""            // luodaan kerran: "phone_<uuid8>"
val deviceName: String = ""          // "Jarmon puhelin", käyttäjä voi muuttaa
val peers: Map<String, PeerInfo>     // laitetunnus -> viimeisin synkka
val defaultVat: Double = 13.5        // työpöydältä tuotu oletus-ALV
val schemaVersion: Int = 2           // migraatiota varten
val syncLog: List<SyncLogEntry>      // 50 viimeisintä tapahtumaa
```

```kotlin
@Serializable data class PeerInfo(
    val name: String = "",
    val lastSyncAt: String = "",      // ratkaisee "muuttunut minun puolellani"
    val lastDirection: String = ""    // file | lan
)

@Serializable data class SyncLogEntry(
    val at: String, val peer: String, val via: String,
    val ringsNew: Int, val ringsUpd: Int, val productsNew: Int, val productsUpd: Int,
    val ordersNew: Int, val ordersUpd: Int, val deliveriesNew: Int, val deliveriesUpd: Int,
    val deletes: Int, val conflicts: Int, val snapshot: String   // varmuuskopion tiedostonimi
)
```

### 1.3 `Product` — uudet kentät

```kotlin
val vat: Double = 0.0          // ALV-% (appista muokattavissa)
val cost: Double = 0.0         // kustannus/yksikkö — VAIN LUKU, työpöydältä
val active: Boolean = true     // käytössä / poistettu käytöstä
val category: String = ""      // vain luku, ryhmittelyyn
+ updated_at / deleted / deleted_at / origin
```

`price` pysyy **verollisena** hintana (kuten nyt).

### 1.4 `Ring` — uudet kentät

```kotlin
val fbUrl: String = ""
val notes: String = ""
val active: Boolean = true
+ updated_at / deleted / deleted_at / origin
```

`retired` (haamupiiri) säilyy erillisenä paikallisena lippuna — se ei synkronoidu.

### 1.5 TK_103 vastaavat

- `data["products"][pid]`: lisätään `updated_at`, `deleted`, `deleted_at`, `origin`. `active`, `vat_rate`, `sell_price`, `sell_price_gross`, `cost_per_unit` ovat jo.
- `data["reko_groups"][i]`: lisätään `updated_at`, `deleted`, `deleted_at`, `origin`. `active`, `notes`, `fb_url` ovat jo.
- `data["reko_device"]`: `{"id": "pc_<uuid8>", "name": "<hostname>"}`
- `data["reko_peers"]`: `{ "<device_id>": {"name":..., "last_sync_at":...} }`
- `data["reko_sync_log"]`: lista, 50 viimeisintä.

**Tunnusavaruudet** (törmäyksen esto):
- Työpöytä: tuotteet `P00001`, piirit `rg_<hex8>`
- Puhelin: tuotteet `Pph_<hex8>`, piirit `rgph_<hex8>`

---

## 2. Kenttäomistus

| Kenttä | App muokkaa | TK_103 muokkaa | Merge-sääntö |
|---|---|---|---|
| `name`, `unit`, `aliases`, `active` | ✅ | ✅ | LWW, ristiriita näytetään |
| `price` (verollinen) | ✅ | ✅ | LWW, **ristiriita aina näytetään** |
| `vat` | ✅ | ✅ | LWW |
| `cost_per_unit` | ❌ luku | ✅ | työpöytä voittaa aina |
| `recipe`, `allergens`, `description`, `category`, EAN-kentät ym. | ❌ ei näe | ✅ | **app ei koskaan kirjoita** |
| Piirin kaikki kentät | ✅ | ✅ | LWW |

**Hintamuunnos TK_103:ssa** kun app-hinta saapuu:
```python
vat = incoming.get("vat", existing.get("vat_rate", default_vat))
existing["sell_price_gross"] = gross
existing["sell_price"]       = round(gross / (1.0 + vat/100.0), 4)
existing["vat_rate"]         = vat
# recipe / allergens / EAN / description SÄILYVÄT koskemattomina
```

---

## 3. Synkronointiformaatti `kasvila-reko-sync` v2

Sama tiedosto **molempiin suuntiin** → yksi koodipolku.

```json
{
  "format": "kasvila-reko-sync",
  "version": 2,
  "device_id": "phone_a1b2c3d4",
  "device_name": "Jarmon puhelin",
  "exported_at": "2026-09-11T14:02:00",
  "last_sync_at": "2026-09-04T18:20:00",
  "currency": "€",
  "default_vat": 13.5,
  "catalog_version": 12,
  "rings":      [ {...ring, updated_at, deleted, origin} ],
  "products":   [ {...product, updated_at, deleted, origin} ],
  "orders":     [ {...order} ],
  "deliveries": [ {...delivery} ],
  "aliases":    { "P00001": ["kirsikkatomaatti", "kirsikkatom"] }
}
```

**Yhteensopivuus:** molemmat lukevat edelleen `kasvila-reko-rings`, `-orders`,
`-deliveries`, `-bundle`. Vanhat tiedostot eivät lakkaa toimimasta.
Vanha `-rings`-tuonti ajetaan **merge-moottorin läpi**, ei enää korvaavana.

---

## 4. Merge-algoritmi

`SyncEngine.merge(local, incoming, peerLastSync) → MergeResult`

Per tietue, avaimena `id`:

```
A = paikallinen,  B = saapuva,  ls = peerLastSync

1. B puuttuu paikallisesta        → LISÄÄ (new)
2. A puuttuu saapuvasta           → SÄILYTÄ ennallaan (toinen ei tiedä siitä)
3. A.updated_at <= ls && B uudempi → OTA B          (vain B muuttunut)
4. B.updated_at <= ls && A uudempi → PIDÄ A         (vain A muuttunut)
5. molemmat > ls:
     sisältö sama (omistetut kentät) → ei mitään
     sisältö eri                     → RISTIRIITA → käyttäjälle
                                        oletusvalinta = uudempi updated_at
6. A.deleted XOR B.deleted && toinen muokattu > ls
                                    → RISTIRIITA "poistettu vs. muokattu"
                                        oletusvalinta = SÄILYTÄ
7. molemmat deleted                 → deleted, varhaisempi deleted_at
```

`MergeResult`:
```kotlin
data class MergeResult(
    val rings: List<Ring>, val products: List<Product>,
    val orders: List<Order>, val deliveries: List<Delivery>,
    val aliases: Map<String, List<String>>,
    val stats: MergeStats,
    val conflicts: List<Conflict>,
    val clockSkewMinutes: Long
)
data class Conflict(
    val kind: String,       // "product" | "ring" | "order" | "delivery"
    val id: String, val label: String, val field: String,
    val localValue: String, val localAt: String,
    val remoteValue: String, val remoteAt: String,
    val defaultChoice: String   // "local" | "remote" | "keep"
)
```

**Kellopoikkeama:** jos `|incoming.exported_at − now| > 10 min`, `clockSkewMinutes`
täytetään ja esikatselu näyttää varoituksen.

**Idempotenssi:** sama tiedosto kahdesti → toinen kerta 0 muutosta.

---

## 5. Turvallisuuskerros

| Suoja | Toteutus |
|---|---|
| Atominen tallennus | `Store.save`: kirjoita `.tmp`, `renameTo` |
| Kiertävät varmuuskopiot | `snap_<epoch>.json`, 10 kpl, vanhin poistuu |
| Esikatselu | `SyncPreviewScreen` ennen soveltamista, aina |
| Ristiriitojen ratkaisu | `SyncConflictScreen`, rivikohtainen valinta |
| Peru synkronointi | palauta `syncLog.last().snapshot` |
| Synkronointiloki | 50 viimeisintä, näkyy asetuksissa |
| Roskakori | `deleted=true`, näkyy 30 vrk, palautus yhdellä napilla |
| Hautakivien siivous | `deleted_at` > 180 vrk → poistetaan tietue lopullisesti |
| Versiotarkistus | `version > 2` → kieltäydy selkeällä viestillä |
| Tuotepoiston suoja | ei voi poistaa käytöstä jos avoimia tilauksia → varoitus |

---

## 6. Appin tiedostorakenne

```
fi/kasvila/reko/
  Model.kt            MUOKKAA  sync-kentät, PeerInfo, SyncLogEntry, migraatio
  Store.kt            MUOKKAA  atominen tallennus, snapshotit, v2-formaatti
  SyncEngine.kt       UUSI     merge-algoritmi + MergeResult + Conflict
  SyncPayload.kt      UUSI     kasvila-reko-sync v2 rakenne + parsinta + yhteensopivuus
  LanSync.kt          UUSI     NSD-haku, HTTP-asiakas, handshake  (v45)
  Summaries.kt        MUOKKAA  RekoSummary (jaksot, piirit, top-tuotteet, maksamattomat)
  Routes.kt           UUSI     reittiryhmittely + päällekkäisyystarkistus  (v46)
  MainActivity.kt     MUOKKAA  uudet Screenit + navigointi
  ui/
    ProductsView.kt   UUSI     tuotelista + muokkaus
    RingsView.kt      UUSI     piirilista + muokkaus
    SyncView.kt       UUSI     laiteyhteys + esikatselu + ristiriidat + loki
    TrashView.kt      UUSI     roskakori
    RoutesView.kt     UUSI     REKO-reitit  (v46)
    RekoSummaryView.kt UUSI    yhteenveto etusivulla  (v46)
```

Uudet `Screen`-tyypit:
```kotlin
data object DataSyncHubScreen      // "Tiedot ja synkronointi"
data object ProductsScreen
data class  ProductEditScreen(val productId: String?)
data object RingsScreen
data class  RingEditScreen(val ringId: String?)
data object SyncScreen             // laiteyhteys + tiedostosynkka
data object SyncPreviewScreen
data object SyncLogScreen
data object TrashScreen
data object RoutesScreen
data object RekoSummaryScreen
```

---

## 7. TK_103 muutoskohdat (rivinumerot v_103)

| Rivi | Kohde | Muutos |
|---|---|---|
| ~6523 | `"products": {}` kommentti | dokumentoi uudet kentät |
| ~6540 | `"reko_groups": []` | dokumentoi uudet kentät |
| ~6543 | `reko_catalog_version` | lisää `reko_device`, `reko_peers`, `reko_sync_log` |
| ~480–535 | `TRANSLATIONS` | uudet `reko_sync2_*` avaimet |
| 38969 | `reko_product_catalog` | **älä suodata** `active=False`; lisää `vat`, `cost`, `active`, `updated_at`, `deleted` |
| 44883 | `RekoGroupDialog._save` | stampaa `updated_at`, `origin` |
| 30576 | tuotelomakkeen `_save` | stampaa `updated_at`, `origin` |
| 47415 | `_del_group` | kova poisto → **pehmeä poisto** |
| 46154 | `reko_export_payload` | rinnalle `reko_sync_payload()` v2 |
| 46186 | `reko_import_orders` | lisää `deleted`-tuki |
| 46658 | `reko_import_bundle` | rinnalle `reko_sync_merge()` v2 |
| 47331 | `_import_orders` | tunnista `kasvila-reko-sync`, avaa esikatselu |
| 47230 | `_build_groups` | napit: "Synkronoi puhelimen kanssa", "Laiteyhteys" |
| 46745 | sub-välilehdet | lisää `("synkronointi", "Synkronointi")` |

Uudet Python-funktiot (yksi lohko `reko_export_payload`:n jälkeen):
```
reko_device_id(data)             hae/luo laitetunnus
reko_stamp(rec, data)            aseta updated_at + origin
reko_sync_payload(data, peer)    rakenna v2-tiedosto
reko_sync_plan(data, payload)    laske merge → suunnitelma + ristiriidat
reko_sync_apply(data, plan, choices)  sovella
reko_sync_snapshot(data)         varmuuskopio ennen sovellusta
reko_sync_undo(data)             palauta viimeisin snapshot
RekoSyncPreviewDialog            esikatselu + ristiriitataulukko
RekoLanServerDialog              lähiverkkopalvelin + kertakoodi   (v45)
```

---

## 8. Lähiverkkoyhteys (v45)

**TK_103 palvelin** — `http.server.ThreadingHTTPServer` omassa säikeessä,
elinkaari = dialogin elinkaari.

```
GET  /kasvila/hello                → {device_id, device_name, version, peers[me].last_sync_at}
POST /kasvila/exchange  (+X-Kasvila-Code)  ← puhelimen tila
                                   → työpöydän tila
POST /kasvila/commit    (+X-Kasvila-Code)  ← "sovellettu", päivitä last_sync_at
```

- Kertakoodi: 6 numeroa, voimassa 5 min, kertakäyttöinen
- Sidonta: `0.0.0.0:8765`, mutta pyyntö hyväksytään vain samasta aliverkosta
- mDNS-julkaisu `_kasvilareko._tcp.local` (`zeroconf`, valinnainen; ilman sitä IP käsin)
- Palvelin sammuu kun dialogi suljetaan

**App asiakas** — `android.net.nsd.NsdManager` löytää palvelun, käyttäjä valitsee,
syöttää koodin. `HttpURLConnection`, sama kuin `AiClient`.

**Kaksoisvahvistus:** kumpikin laskee mergen paikallisesti, kumpikin näyttää
esikatselun, mitään ei kirjoiteta ennen kuin **molemmat** ovat vahvistaneet
(`/commit`). Katkos ennen commitia → ei muutoksia kummallakaan.

Ei uusia Android-oikeuksia (`INTERNET` on jo). Ei kameraa, ei QR-koodia.

---

## 9. Vaiheistus

| Versio | Sisältö | Tiedostot |
|---|---|---|
| **v42** | Tietomalli + sync-kentät + migraatio. Atominen tallennus, 10 snapshotia. `SyncPayload` v2 rakenne. TK_103: samat kentät, `reko_device`, stampaus, pehmeä poisto. | Model, Store, SyncPayload / tk103 |
| **v43** | "Tiedot ja synkronointi" -osio. ProductsView, RingsView, TrashView. Paikallinen muokkaus. | ui/*, MainActivity |
| **v44** | `SyncEngine` + esikatselu + ristiriidat + loki + peru. TK_103: `reko_sync_*` + esikatseludialogi. | SyncEngine, SyncView / tk103 |
| **v45** | Lähiverkkoyhteys molemmin puolin. | LanSync / tk103 |
| **v46** | REKO-reitit + yhteenveto etusivulle. | Routes, RoutesView, RekoSummaryView |
| **v43** | Asiakasrekisteri, jakotoiminto, tulostus (PDF/print), CSV-vienti. Jäsentimen desimaalikorjaus. | ✅ valmis |
| **v44** | Maksamattomat + muistutusviestit + viikkoilmoitus. Jakelupoikkeukset (peruttu/siirretty nouto). Automaattinen varmuuskopio valittuun kansioon. | ✅ valmis |

---

## 11. Mitä v44 lisäsi tietomalliin

### Distribution — nouto, jolla on luonnollinen avain

```
id         "piiri|päivä"     ← MOLEMMAT laitteet nimeävät saman noudon samoin
group_id   piirin tunnus
date       ISO-päivä
time       "" = piirin oma
address    "" = piirin oma
status     planned | open | closed | delivered
cancelled  ei jakelua tänä päivänä
notes      vapaa teksti
dist_id    työpöydän oma rd_-tunniste, kun se on opittu
+ updated_at / deleted / deleted_at / origin
```

**Miksi luonnollinen avain.** Työpöydällä on ollut omat satunnaiset
`rd_`-tunnisteensa kauan ennen puhelinta. Jos molemmat loisivat tunnisteita
itsenäisesti, sama nouto saapuisi kahtena tietueena eikä yhdistäminen tietäisi
niitä samaksi. `piiri|päivä` ratkaisee tämän ilman että laitteiden tarvitsee
sopia mistään.

**Tilaukset eivät ole jakelun sisällä.** Ne kantavat itse `group_id`:nsä ja
päivänsä kuten ennenkin, joten jakelu on *tietoa noudosta*, ei säiliö. Nykyinen
toimiva tilaussynkronointi jää täysin koskematta, eikä synny säiliö/lapsi-
yhdistämisongelmaa.

**Soveltamisjärjestys.** Jakelut sovelletaan ennen tilauksia: jos tilaus osuisi
jakeluun jota ei vielä ole, se loisi sen piirin oletustiedoilla ja poikkeusaika
menisi hukkaan.

### Laitekohtaiset asetukset (ei synkronoida)

`MaintenancePrefs` omassa tiedostossaan: maksumuistutuksen päivä ja kello,
varmuuskopiokansion URI, kellonaika ja säilytettävien määrä. Kansiovalinta ei
tarkoita työpöydällä mitään, ja sen synkronointi olisi parhaimmillaan kohinaa.

Ratkaiseva kohta on **v44**. Sitä ennen mikään ei ole rikki; sen jälkeen loput
ovat näkymiä valmiin moottorin päälle.

---

## 10. Testattavat skenaariot (vaihe 8)

1. Sama tiedosto tuodaan kahdesti → toinen kerta 0 muutosta (idempotenssi)
2. Hinta muutettu molemmissa → ristiriita, ei hiljaista ylikirjoitusta
3. Tuote poistettu käytöstä puhelimessa, muokattu työpöydällä → ristiriita, oletus säilytä
4. Työpöydän `recipe`/`allergens`/EAN säilyvät kun app-hinta tuodaan
5. Piiri poistettu käytöstä, avoimia tilauksia → varoitus, tilaukset säilyvät
6. Kello 30 min edellä → varoitus esikatselussa
7. Vanha `kasvila-reko-rings` -tiedosto → toimii, ei pyyhi appin muokkauksia
8. Vanha `kasvila-reko-bundle` työpöydälle → toimii kuten ennen
9. Tyhjä tila + ensimmäinen synkka → kaikki tulee läpi
10. Snapshot-palautus → tila palautuu tarkalleen
