# GitHub-julkaisu ja päivitystarkistus — Kasvila REKO

Sovellus tarkistaa uuden version **GitHubista** ja tarjoaa latausta. Tämä ohje
kertoo, miten julkaiset uuden version niin, että sovellus löytää sen. Kertaluvun
asetukset on jo tehty koodiin (v56); sinun tarvitsee vain julkaista versiot
oikein.

## Kertaluontoinen valmistelu

1. Luo GitHubiin repositorio nimeltä **`kasvila-reko`** tunnuksellasi
   `PaxOmraj` (osoite tulee olemaan `github.com/PaxOmraj/kasvila-reko`).
2. **Tärkeää: aseta repo julkiseksi (Public).** Sovellus lukee julkaisut
   GitHubin avoimen rajapinnan kautta ja lataa APK:n suoraan — yksityisestä
   reposta se ei onnistu ilman kirjautumista. Repossa **ei tarvitse olla
   lähdekoodia** — voit julkaista siellä pelkät APK-tiedostot.

> Sovellukseen on kovakoodattu osoite `PaxOmraj/kasvila-reko`. Jos haluat eri
> repon, kerro nimi, niin muutan sen koodiin.

## Uuden version julkaiseminen (joka kerta)

Kun saat minulta uuden version (esim. v57) ja olet rakentanut siitä
allekirjoitetun APK:n (samalla `kasvila-reko.jks`-avaimella):

1. Mene repoon → **Releases** → **Draft a new release** (Luo uusi julkaisu).
2. **Choose a tag:** kirjoita **`v57`** (eli `v` + versionumero) →
   **Create new tag on publish**.
   - Tagissa on oltava sama numero kuin sovelluksen versiossa. Sovellus vertaa
     tagin numeroa asennettuun versioon: jos tagi on suurempi, se tarjoaa
     päivitystä.
3. **Release title:** esim. `Kasvila REKO 57`.
4. **Description:** kirjoita lyhyt kuvaus muutoksista — **tämä teksti näkyy
   käyttäjille sovelluksen päivitysilmoituksessa.** (Voit kopioida sen
   MUUTOKSET-tiedostosta.)
5. **Attach binaries:** vedä `app-release.apk` (voit nimetä esim.
   `kasvila-reko-v57.apk`) julkaisun liitteeksi. Sovellus etsii `.apk`-päätteisen
   liitteen ja lataa sen.
6. **Publish release.**

Siinä kaikki. Seuraavan kerran kun käyttäjä avaa sovelluksen (tai painaa
Asetukset → Sovelluksen päivitykset → Tarkista nyt), hän saa ilmoituksen uudesta
versiosta ja "Lataa"-painikkeen.

## Miten se näkyy käyttäjälle

- **Automaattisesti:** käynnistyksessä, korkeintaan kerran vuorokaudessa. Jos
  uusi versio löytyy, näkyy ikkuna: *Uusi versio saatavilla* + muutoskuvaus +
  **Lataa** / **Ohita tämä versio** / **Myöhemmin**.
- **Käsin:** Asetukset → **Sovelluksen päivitykset** → *Tarkista päivitykset
  nyt*. Sieltä voi myös kytkeä automaattitarkistuksen pois.
- **Lataus:** "Lataa" avaa APK:n selaimessa. Käyttäjä avaa latautuneen tiedoston
  ja asentaa sen. Koska kaikki versiot on allekirjoitettu samalla avaimella,
  päivitys asentuu vanhan päälle ja **tiedot säilyvät** — ei poistoa.

## Huomioita

- Päivitystarkistus on mukana **versiosta 56 alkaen**. Ensimmäinen versio, jonka
  se voi ilmoittaa käyttäjille, on siis v57. (v56 on se, joka opettaa sovelluksen
  tarkistamaan.)
- Käyttäjän pitää sallia "asennus tuntemattomasta lähteestä" sille sovellukselle,
  josta APK avataan (selain tai tiedostonhallinta) — Android kysyy tämän
  ensimmäisellä kerralla.
- GitHubin avoimella rajapinnalla on tuntikohtainen kyselyraja, mutta koska
  tarkistus tehdään enintään kerran vuorokaudessa laitetta kohti, se ei tule
  vastaan.
