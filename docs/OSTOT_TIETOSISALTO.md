# Ostot-moduuli: puhelimen ja työpöydän tietosisältö, versio 1

30.10.2026 · Sama sisältö on KasvilaReko-repossa tiedostona `docs/OSTOT_TIETOSISALTO.md`.
Työpöydän toteutus: `kasvila/ydin/ostosovellus.py` (TK_162).

## Periaatteet

1. **Puhelin ei muuta työpöydän varastoa.** Puhelin lähettää *ostokertoja* ja *inventaarioita*,
   jotka työpöytä näyttää kohdassa Varasto → *Puhelimesta saapuneet*. Ne kirjataan vasta, kun
   joku hyväksyy ne työpöydällä.
2. **Ostokerta on muuttumaton tunnisteeltaan.** Sama `id` voidaan lähettää monta kertaa.
   Työpöytä päivittää odottavan kerran uusimpaan versioon ja jättää jo käsitellyn rauhaan.
   Siksi ristiriitoja ei synny, eikä REKO-synkronoinnin sääntöjä tarvita.
3. **Luettelo kulkee työpöydältä puhelimeen.** Siinä ovat raaka-aineet, pakkaukset, tuotteet,
   varastosaldot, ostoslista, EAN-kirjasto ja käsiteltyjen kertojen tila. Puhelin korvaa oman
   luettelonsa uudella joka kerta.
4. **EAN-kirjasto opitaan kummassakin päässä.** Puhelimessa opittu koodi lähtee työpöydälle
   (`ean_learned`). Työpöytä yhdistää sen kirjastoonsa (uudempi `updated_at` voittaa) ja palauttaa
   koko kirjaston luettelossa.
5. **Yksiköt:** raaka-aineet grammoina (`g`), pakkaukset ja tuotteet kappaleina (`kpl`).
   Hinnat raaka-aineille €/kg, muille €/kpl. Puhelin lähettää hinnan sellaisena kuin se nähtiin
   (yleensä ALV:n kanssa) ja kertoo sen kentissä `price_incl_vat` ja `vat`. Työpöytä muuntaa
   ALV 0 %:iin.

## Siirtotavat

| Tapa | Puhelin → työpöytä | Työpöytä → puhelin |
|---|---|---|
| Lähiverkko | `POST http://<kone>:8765/kasvila/osto`, otsake `X-Kasvila-Code: <6 numeroa>`, runko = lähetys | Vastaus = luettelo |
| Tiedosto | Puhelin tallentaa lähetyksen (`kasvila-ostot-<pvm>.json`) → työpöytä: *Tuo puhelimen tiedosto* | Työpöytä: *Vie luettelo puhelimeen* → puhelin avaa tiedoston |

Lähiverkkoyhteys on sama kuin REKO-synkronoinnissa: työpöydällä avataan yhteysikkuna, joka
näyttää koodin, ja puhelin löytää koneen mDNS:llä (`_kasvilareko._tcp.`). Lähetys ilman
ostokertoja (`sessions: []`) on sallittu: se vain hakee tuoreen luettelon.

## Lähetys (puhelin → työpöytä)

```json
{
  "format": "kasvila-osto-sync",
  "version": 1,
  "kind": "upload",
  "device_id": "phone-…",
  "device_name": "Jarmon puhelin",
  "exported_at": "2026-10-30T14:05:00+02:00",
  "sessions": [
    {
      "id": "S-20261030-140211-a3f9",
      "type": "purchase",
      "shop": "Prisma Kaleva",
      "date": "2026-10-30",
      "started_at": "2026-10-30T13:40:02+02:00",
      "finished_at": "2026-10-30T14:02:11+02:00",
      "operator": "Jarmo",
      "note": "",
      "receipt_total": 87.40,
      "lines": [
        {
          "id": "L1",
          "ean": "6411402202208",
          "name": "Kikherneet 1 kg",
          "brand": "Rainbow",
          "kind": "ing",
          "key": "Chickpeas (dried)",
          "packages": 4,
          "pack_qty": 1000,
          "amount": null,
          "price": 2.49,
          "price_per": "package",
          "price_incl_vat": true,
          "vat": 13.5,
          "price_source": "label",
          "best_before": "2027-06-30",
          "note": ""
        }
      ]
    }
  ],
  "ean_learned": [
    {"ean": "6411402202208", "kind": "ing", "key": "Chickpeas (dried)",
     "pack_qty": 1000, "name": "Kikherneet 1 kg", "brand": "Rainbow",
     "updated_at": "2026-10-30T13:41:30+02:00"}
  ]
}
```

| Kenttä | Pakollinen | Selitys |
|---|---|---|
| `sessions[].id` | kyllä | Puhelimen luoma, pysyvä. Ei koskaan uudelleenkäyttöä. |
| `sessions[].type` | kyllä | `purchase` (ostokerta) tai `count` (inventaario) |
| `sessions[].shop` | ostolle | Kauppa tai tukku vapaana tekstinä; työpöytä tallentaa toimittajaksi |
| `sessions[].date` | kyllä | `YYYY-MM-DD`, oston tai laskennan päivä |
| `lines[].id` | kyllä | Ainutkertainen kerran sisällä |
| `lines[].kind` | ei | `ing`, `pack` tai `product`. Tyhjä = puhelin ei tiennyt, työpöytä kysyy |
| `lines[].key` | ei | Työpöydän avain luettelosta. Tyhjä = yhdistetään työpöydällä |
| `lines[].packages` | – | Pakkausten määrä (voi olla desimaali) |
| `lines[].pack_qty` | – | Yhden pakkauksen määrä grammoina (`ing`) tai kappaleina |
| `lines[].amount` | – | Kokonaismäärä suoraan (g tai kpl). Jos annettu, se voittaa `packages × pack_qty`:n. Käytetään ei koodia -riveillä ja inventaariossa |
| `lines[].price` | ei | Hinta sellaisena kuin nähtiin |
| `lines[].price_per` | ei | `package` (pakkaushinta, oletus), `kg`, `unit` (kpl) tai `total` (rivin yhteishinta) |
| `lines[].price_incl_vat` | ei | Oletus `true` (hyllyhinta) |
| `lines[].vat` | ei | ALV-%, oletus luettelon `default_vat` |
| `lines[].price_source` | ei | `manual`, `label` (hyllylapun kuva), `weigh` (punnitustarra), `last` (edellinen hinta), `none` |
| `lines[].best_before` | ei | `YYYY-MM-DD` |

Inventaariossa (`type: count`) hinta jätetään pois. Työpöytä arvottaa määrät omilla hinnoillaan.

## Luettelo (työpöytä → puhelin)

```json
{
  "format": "kasvila-osto-sync",
  "version": 1,
  "kind": "catalog",
  "device_id": "desk-…",
  "device_name": "Työpöytä",
  "exported_at": "2026-10-30T14:05:01+02:00",
  "currency": "€",
  "default_vat": 13.5,
  "items": [
    {"kind": "ing", "key": "Chickpeas (dried)", "name": "Kikherne, kuivattu",
     "unit": "g", "price": 2.19, "vat": 13.5, "supplier": "Valio Aimo",
     "stock": 2300, "min": 5000, "used": true, "eans": ["6411402202208"],
     "last": {"date": "2026-10-12", "price": 2.19, "shop": "Valio Aimo"}}
  ],
  "ean_library": [
    {"ean": "6411402202208", "kind": "ing", "key": "Chickpeas (dried)",
     "pack_qty": 1000, "name": "Kikherneet 1 kg", "brand": "Rainbow",
     "updated_at": "2026-10-30T13:41:30+02:00"}
  ],
  "shops": ["Valio Aimo", "Kespro", "Prisma", "K-Citymarket", "Lidl",
            "Rokfood", "Alanya Market", "Beno Market"],
  "shopping_list": [{"kind": "ing", "key": "Chickpeas (dried)", "need": 2700}],
  "sessions_status": [
    {"id": "S-20261030-140211-a3f9", "status": "approved",
     "decided_at": "2026-10-30T15:10:00", "note": ""}
  ]
}
```

| Kenttä | Selitys |
|---|---|
| `items[].price` | ALV 0 %, €/kg raaka-aineille, €/kpl muille |
| `items[].stock`, `min` | Vain raaka-aineille, grammoina. `null` jos varastoa ei seurata |
| `items[].used` | `true`, jos raaka-aine on jossain reseptissä, varastossa tai ostettu. Puhelin näyttää nämä ensin; loput löytyvät haulla |
| `sessions_status[].status` | `pending` (odottaa), `approved` (kirjattu), `partial` (osa riveistä kirjattu), `rejected` (hylätty). Puhelin voi arkistoida kerran, kun tila on muu kuin `pending` |
| `sessions_status[].photo` | (v60 / TK_167) sen kuittikuvan tunniste, joka työpöydällä on, tai tyhjä. Puhelin lähettää kuvan uudelleen vain, kun tämä ei vastaa sen omaa kuvaa |

## Mitä työpöytä tekee hyväksyessään

| Rivi | Ostokerta | Inventaario |
|---|---|---|
| Raaka-aine | `record_purchase`: varastoerä (FIFO), keskihinta, raaka-aineen hinta ja hintahistoria, rivi Ostot-sivun ostopäiväkirjaan | Inventaarioerä Kulut ja kannattavuus -sivun kohtaan 10 (kg × hinta); valinnaisesti varastosaldo laskettuun määrään |
| Pakkaus | Pakkauksen hinta ja hintahistoria | Inventaarioon (kpl × hinta) |
| Tuote | Ei kirjata (tuotteita ei osteta) | Inventaarioon (kpl × aine- ja pakkauskustannus) |

Lisäksi rivin EAN-koodi ja pakkauskoko tallentuvat EAN-kirjastoon, kun rivi on yhdistetty.

## Versiointi

`version` kasvaa vain, kun kenttien merkitys muuttuu. Uusia valinnaisia kenttiä saa lisätä
versiota nostamatta; molemmat päät ohittavat tuntemattomat kentät. Jos vastaanottaja saa
uudemman version kuin osaa, se kieltäytyy ja pyytää päivittämään ohjelman.

## Lisäys v60 / TK_167: kuitin kuva

Ostokerralla (`purchase`) voi olla kaksi lisäkenttää. Vanha työpöytä ohittaa ne.

| Kenttä | Selitys |
|---|---|
| `receipt_photo_hash` | kuvan tunniste (SHA-1:n 12 ensimmäistä merkkiä), aina mukana kun kuva on |
| `receipt_image` | JPEG base64-muodossa, enintään 2000 px. Mukana vain, kun työpöytä ei vielä kerro saaneensa kuvaa (`sessions_status[].photo`). Muuten tyhjä |

Työpöytä tallentaa kuvan tiedostoksi datakansion alikansioon `kuitit/<kerran id>.jpg` eikä pidä base64-tekstiä tiedoissa. Hinnat luetaan vasta, kun käyttäjä painaa "Lue kuittikuva": kuitin rivit yhdistetään kerran riveihin viivakoodin, tuotteen tai nimen perusteella, ja yhdistetty rivi saa kuitin rivihinnan (`price_per` = `total`, `price_source` = `receipt`). Yhdistämättömät kuittirivit lisätään uusina riveinä (id `R1`, `R2` …).
