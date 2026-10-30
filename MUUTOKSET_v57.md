# v57 — Virransäästö- ja resurssiviilaus

Kasvila REKO (Android), versio **57**.

Tämä versio on kevyt "virtapihi"-viilaus. Sovellus oli jo lähtökohtaisesti erittäin
taloudellinen (ei taustapalvelua, ei paikannusta, ei seurantaa, ajastukset
WorkManagerilla eikä tarkoilla herätyksillä, Bluetooth vain tulostuksen ajan,
raskas salaus vain kirjautuessa). Nämä muutokset hiovat sitä hieman lisää — ilman
riskiä luotettavuudelle: salauksen parametreihin ja kirjanpitoon (myyntiloki) ei
kosketa.

## Muutokset

- **Varmuuskopio ja maksumuistutus siirtyvät, jos akku on kriittisen alhaalla.**
  Taustatöihin lisättiin ehto "ei kun akku vähissä" (WorkManager
  `setRequiresBatteryNotLow`). Ne eivät ole koskaan niin kiireisiä, että
  kannattaisi kuluttaa viimeisiä prosentteja; järjestelmä ajaa ne heti kun akku
  ei enää ole vähissä.
- **Tallennus tapahtuu taustasäikeellä ja peräkkäiset tallennukset yhdistetään.**
  Käyttöliittymä päivittyy heti; levylle kirjoittaminen (ja salaus) siirtyi pois
  pääsäikeeltä (`Dispatchers.IO`). Kun muokkauksia tulee nopeasti peräkkäin,
  vain uusin täysi tilannekuva kirjoitetaan — vähemmän levykirjoituksia
  (flash-kulumista) eikä hetkellistä nykimistä isollakaan datamäärällä.
- **Automaattinen päivitystarkistus vain verkossa.** Jos laite on offline,
  tarkistusta ei edes yritetä (eikä se "kuluta" käynnistyshetkeä) — se yrittää
  uudelleen seuraavalla avauksella, kun yhteys on. (Lisätty kevyt
  `ACCESS_NETWORK_STATE`-oikeus verkkotilan lukemiseen.)
- **Kevyempi muistutusten uudelleenajastus käynnistyksessä.** Ennen jokainen
  kuljetusmuistutus peruttiin ja ajastettiin uudelleen joka avauksella; nyt
  käynnistys jättää olemassa olevat muistutukset rauhaan (WorkManager säilyttää
  ne yli uudelleenkäynnistysten) ja luo vain puuttuvat. Muistutuksen muokkaus
  päivittää sen edelleen heti.

## Uudet / muutetut tiedostot

- `Maintenance.kt`: akkuehto (`Constraints.setRequiresBatteryNotLow`) molempiin
  taustatöihin.
- `MainActivity.kt`: tallennus CONFLATED-kanavan kautta taustasäikeelle;
  päivitystarkistus vain verkossa (`isOnline`).
- `Reminders.kt`: `schedule(replace=false)` / KEEP käynnistyksen `syncAll`:ssa.
- `AndroidManifest.xml`: `ACCESS_NETWORK_STATE`. Versio 56 → 57.

## Tarkistettu

- `check_kotlin.py`: puhdas (430 funktiota, 157 tyyppiä). Ei uusia
  riippuvuuksia. Kovennus (v55) ja päivitystarkistus (v56) säilyvät ennallaan.
- Testattavaa release-buildissa: normaali käyttö (tilausten muokkaus tallentuu
  yhä luotettavasti), muistutukset toimivat, päivitystarkistus toimii verkossa
  ja on hiljaa offline-tilassa.

## Miten voit itse todeta kulutuksen

Puhelimen **Asetukset → Akku → sovelluslista** näyttää sovelluksen todellisen
kulutuksen parin käyttöpäivän jälkeen — offline-first-sovelluksena sen pitäisi
näkyä lähes olemattomana.

## Tila

Kokonaisuus on valmis ja nyt myös viilattu resurssien osalta: kuitti, kirjautuminen
+ salaus + biometria, monikäyttäjä, ilmoitusgeneraattori, kovennus, GitHub-jakelu +
päivitystarkistus, ja virransäästöviilaus.
