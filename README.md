# Kasvila REKO – tilausten kirjaus (Android)

Android-sovellus REKO-tilausten ja kotiinkuljetusten kirjaamiseen kentällä.
Toimii täysin **offline**. Versiosta 42 alkaen sovellus ei ole enää pelkkä
kirjauspää työpöytäohjelmalle, vaan hallitsee myös tuotteet, hinnat ja
ruokapiirit — ja synkronoituu työpöytäohjelman kanssa **kahteen suuntaan**.

## Ominaisuudet

- **REKO-tilaukset:** valitse piiri ja noutopäivä, kirjaa asiakkaan tilaukset.
- **Jaa tilaus appiin:** valitse asiakkaan kommentti Facebookissa tai
  Messengerissä → Jaa → Kasvila REKO. Teksti jäsennetään heti, ja näet mitä
  tunnistettiin ennen kuin mitään tallennetaan.
- **Asiakasrekisteri:** nimi täydentyy kirjoittaessa, yhteystieto muistetaan,
  tilaushistoria näkyy, ja "Sama kuin viimeksi" luo koko tilauksen yhdellä
  napilla nykyisillä hinnoilla.
- **Tulosteet:** keräilylista, jakelun koonti, asiakkaiden noutoliput ja
  viikkokoonti suoraan puhelimesta — tulostimeen tai PDF:nä jaettavaksi.
- **CSV-vienti:** tilausrivit, kuljetusrivit, tuotesummat, maksamattomat ja
  asiakasrekisteri suomalaiselle Excelille kelpaavassa muodossa.
- **Maksamattomat:** kuka on velkaa ja kuinka paljon, muistutusviesti yhdellä
  napilla, ja valinnainen viikoittainen ilmoitus.
- **Poikkeuspäivät:** yksittäinen nouto voidaan perua, siirtää toiseen paikkaan
  tai antaa sille poikkeava aika. Peruttu päivä katoaa noutopäivävalikoista
  molemmilla laitteilla.
- **Automaattinen varmuuskopio:** yöllinen kopio valitsemaasi kansioon.
- **Kotiinkuljetukset:** oma osionsa muistutuksineen.
- **Koosteet:** päivä- ja viikkokohtaiset tuotesummat, jakelukooste.
- **REKO-yhteenveto:** myynti jaksoittain ja piireittäin, suosituimmat tuotteet,
  parhaat asiakkaat, maksamattomat.
- **Tuotteet ja hinnat:** koko tuotekatalogi muokattavissa puhelimessa —
  nimi, verollinen hinta, ALV-kanta, yksikkö, aliakset, käytössä/pois.
  Kustannus, kate ja kate-% näkyvät, mutta tulevat työpöytäohjelmasta.
- **Ruokapiirit:** lisääminen, muokkaus ja poistaminen puhelimessa.
- **REKO-reitit:** piirit ryhmiteltyinä noutopäivän ja viikkorytmin mukaan,
  päällekkäisten noutoaikojen varoitus ja koko reitin avaus kartalle.
- **Roskakori:** poistot ovat palautettavissa 30 päivää.
- **Tekoälytunnistus:** tilausrivien tunnistus vapaasta tekstistä tai sanelusta.
- **Kaksisuuntainen synkronointi:** lähiverkossa tai tiedostolla. Kattaa
  tuotteet, piirit, tilaukset, kuljetukset, asiakkaat ja jakelupoikkeukset.

## Kääntäminen (Windows + Android Studio)

1. Avaa Android Studio → **Open** → valitse tämä kansio.
2. Anna Gradlen synkronoida ja ladata riippuvuudet (vaatii netin kerran).
3. Kytke puhelin USB:llä (USB-virheenkorjaus päällä) ja paina **Run ▶**.

Komentoriviltä: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

Tekninen perusta: Kotlin 2.0.20, Jetpack Compose (Material 3), AGP 8.5.2,
minSdk 26, targetSdk 34. Ei uusia käyttöoikeuksia: lähiverkkosynkronointi
käyttää samaa `INTERNET`-oikeutta kuin tekoälytoiminnot, eikä kameraa tarvita.

---

## Synkronointi

Löytyy: **Asetukset → Tiedot ja synkronointi → Laiteyhteys ja synkronointi**.
Työpöytäohjelmassa: **REKO → Synkronointi**.

### Miten se toimii

Molemmat ohjelmat lähettävät koko tilansa samassa muodossa
(`kasvila-reko-sync` v2), ja **kumpikin yhdistää sen omaan tilaansa tietue
kerrallaan**. Jokainen tuote, piiri, tilaus, kuljetus ja asiakas kantaa mukanaan
muokkausaikaleimaa, ja kumpikin laite muistaa milloin ne viimeksi sopivat
keskenään. Siitä seuraa päätössääntö:

| Tilanne | Lopputulos |
|---|---|
| Vain toinen muokkasi | Muuttunut voittaa, hiljaa |
| Molemmat muokkasivat samaksi | Ei muutosta |
| Molemmat muokkasivat eri tavalla | **Ristiriita → kysytään** |
| Toinen poisti, toinen muokkasi | **Ristiriita → oletus: säilytä** |
| Vain toinen tuntee tietueen | Säilytetään ennallaan |

Synkronointi ei siis koskaan poista tietoa jota toinen laite ei tunne, eikä
ylikirjoita muokkausta hiljaa.

### Kenttäomistus

Puhelin muokkaa vain osaa tuotteen kentistä. Työpöydän resepti, kustannus,
allergeenit, kuvaus, EAN-koodit ja tuotekortin kentät **eivät koskaan kulje
puhelimen kautta**, joten ne eivät voi kadota — puhelin ei tunne niitä.

| Kenttä | Puhelin | Työpöytä |
|---|---|---|
| Nimi, verollinen hinta, ALV, yksikkö, aliakset, käytössä | muokkaa | muokkaa |
| Kustannus, kate | näkee | muokkaa |
| Resepti, allergeenit, kuvaus, EAN, tuotekortti | ei näe | muokkaa |
| Asiakkaan nimi, puhelin, sähköposti, osoite, muistiinpano | muokkaa | muokkaa |
| Asiakkaan laskutustiedot, verkkolaskuosoite, y-tunnus, tyyppi | ei näe | muokkaa |
| Noudon peruutus, poikkeava aika/paikka, tila, muistiinpano | muokkaa | muokkaa |

Puhelimen verollinen hinta muunnetaan työpöydällä verottomaksi sen omalla
ALV-kannalla; hinnan muuttaminen puhelimessa ei siis riko katelaskentaa.

### 1) Lähiverkkoyhteys (suositus)

1. Työpöytä: **REKO → Synkronointi → Avaa yhteys**. Ruudulle tulee
   kuusinumeroinen kertakoodi (voimassa 5 min, yksi laite).
2. Puhelin: **Asetukset → Tiedot ja synkronointi → Laiteyhteys →
   Yhdistä työpöytään**. Kone löytyy listalta automaattisesti; jos ei, syötä
   sen osoite käsin.
3. Syötä koodi → **Yhdistä ja hae muutokset**.
4. Molemmat näyttävät oman esikatselunsa. Kumpikin tallentaa vasta kun sen
   käyttäjä hyväksyy.

Palvelin on auki vain niin kauan kuin yhteysikkuna. Se hyväksyy vain
lähiverkko-osoitteita ja sitoo koodin ensimmäiseen yhteyden ottaneeseen
laitteeseen.

Automaattinen löytäminen käyttää `zeroconf`-kirjastoa jos se on asennettu
(`pip install zeroconf`). Ilman sitä kaikki toimii, osoite vain syötetään käsin.

### 2) Tiedostosynkronointi

Toimii aina. Sama tiedosto kulkee molempiin suuntiin:

- Puhelin → kone: **Tallenna** tai **Jaa synkronointitiedosto** → tuo koneella
  **REKO → Synkronointi → Lue synkronointitiedosto puhelimesta**.
- Kone → puhelin: **Tallenna synkronointitiedosto puhelimelle** → tuo
  puhelimessa **Tuo synkronointitiedosto**.

### Vanhat tiedostot

Kaikki v41-aikaiset muodot toimivat edelleen: `kasvila-reko-rings`,
`-orders`, `-deliveries` ja `-bundle`. Vanhakin katalogitiedosto ajetaan nyt
yhdistämisen läpi, joten se ei enää pyyhi puhelimessa tehtyjä muokkauksia.

---

## Miksi mitään ei katoa

1. **Yhdistäminen, ei korvaaminen.** Katalogia ei enää ylikirjoiteta.
2. **Esikatselu ennen jokaista muutosta.** Väärä tiedosto torjutaan, ei
   huomata jälkikäteen.
3. **Varmuuskopio ennen jokaista synkronointia.** 10 viimeisintä säilyy, ja
   viimeisimmän synkronoinnin voi perua yhdellä napilla.
4. **Atominen tallennus.** Kesken jäänyt kirjoitus ei riko tilaa, ja
   vioittuneesta tiedostosta palaudutaan tuoreimpaan tilannekuvaan.
5. **Pehmeä poisto.** Poisto vie roskakoriin, ei levyltä — ja poistettu tietue
   pysyy erotettavissa "en ole kuullutkaan" -tilanteesta, joten toinen laite ei
   herätä sitä henkiin.
6. **Poistosuojat.** Käytöstä poistettava tuote tai piiri varoittaa avoimista
   tilauksista; tilaukset ja myyntihistoria säilyvät aina.
7. **Synkronointiloki.** Jos jokin hinta joskus muuttuu yllättäen, vastaus
   löytyy lokista.

---

## Poikkeuspäivät

Piiri sanoo "joka toinen torstai klo 17, Ratinassa". Se on aikomus, ei se mitä
tapahtuu: juhannusnouto perutaan, yksi siirtyy perjantaille, yksi pidetään
toisaalla koska tavallinen paikka on kaivettu auki.

**Ruokapiiri → valitse päivä → Tämän noudon tiedot.** Siellä voi merkitä noudon
perutuksi, antaa poikkeavan kellonajan ja paikan, asettaa tilan ja kirjoittaa
muistiinpanon. Tyhjä kenttä tarkoittaa "kuten aina".

Peruttu päivä katoaa noutopäivävalikoista **molemmilla laitteilla**, ja
perjantaille siirretty nouto ilmestyy niihin vaikka viikonpäivä-sääntö ei sitä
koskaan tuottaisi. Tulevat peruutukset näkyvät etusivulla punaisella — se on
asia jota ei saa huomata vasta jakelupäivänä.

Jakelun tunniste synkronoinnissa on **luonnollinen avain `piiri|päivä`**, ei
satunnaisluku. Näin molemmat laitteet nimeävät saman noudon samalla tavalla
ilman että ne ovat koskaan puhuneet; työpöydän oma `rd_`-tunniste kulkee mukana,
jotta se löytää tietueen johon sen tilaukset on jo liitetty. Tilaukset eivät
kulje jakelun mukana — ne kantavat itse piirinsä ja päivänsä, kuten ennenkin.

## Maksamattomat

Etusivun **Maksamatta**-painike näyttää summan suoraan. Listasta näkee kuka on
velkaa, kuinka paljon ja kuinka kauan; asiakkaan avaamalla näkee yksittäiset
tilaukset, voi merkitä kaikki maksetuiksi yhdellä napilla, tai lähettää
muistutusviestin.

Muistutus avautuu valitsemaasi sovellukseen (viestit, WhatsApp, sähköposti)
valmiiksi kirjoitettuna ja muokattavana. Teksti on lyhyt ja asiallinen ja kertoo
mistä tilauksesta on kyse — päivämäärätön "olet velkaa" on juuri se mikä
ärsyttää ihmisiä.

**Asetukset → Tiedot ja synkronointi → Muistutukset ja varmuuskopio** kytkee
päälle viikoittaisen ilmoituksen. Se tulee vain jos maksamattomia on: joka viikko
toistuva "ei mitään" on tapa jolla hyödyllinen muistutus muuttuu sellaiseksi
joka kytketään pois.

## Automaattinen varmuuskopio

Samasta asetuksesta valitaan kansio, johon sovellus kirjoittaa koko tilansa
päivätyksi tiedostoksi joka yö ja säilyttää valitun määrän viimeisimpiä. Jos
valitset pilvipalvelun synkronoiman kansion, kopio on turvassa myös silloin kun
puhelin katoaa synkronointien välissä.

Varmuuskopio on sama kaksisuuntainen synkronointitiedosto, joten sen voi tuoda
takaisin sekä puhelimeen että työpöytäohjelmaan. Kansion käyttöoikeus otetaan
pysyvänä, ja jos se joskus katoaa (kortti pois, pilvipalvelu ulos kirjautunut),
virhe näkyy asetuksissa sen sijaan että kopiointi epäonnistuisi hiljaa joka yö.

## Tulosteet

Löytyvät tulostin-kuvakkeesta: **Jakopäivän kooste**, **Jakelukooste** ja
**Viikkokooste**.

| Tuloste | Mihin |
|---|---|
| Keräilylista | Mitä autoon lastataan. Tuotesummat, rasti ruutuun per rivi. Ei hintoja eikä asiakasnimiä — tämä luetaan kylmiössä. |
| Jakelun koonti | Koko nouto yhdellä arkilla: asiakkaat, rivit, summat, maksamatta-merkinnät. |
| Noutoliput | Asiakaskohtainen lippu, kolme per arkki leikkausviivoin. |
| Viikkokoonti | Viikon jakelut, kuljetukset ja tuotesummat. |

Tulostus käyttää Androidin omaa tulostuspalvelua, joten sama valikko tarjoaa
oikean tulostimen ja "Tallenna PDF:nä".

## Jaettu tilaus

Kun asiakas kommentoi tilauksensa Facebookiin: maalaa kommentti → **Jaa** →
**Kasvila REKO**. Sovellus avaa näkymän joka kertoo mitä tunnistettiin, kysyy
piirin ja noutopäivän (arvaten lähimmän noudon) ja avaa tilauslomakkeen
esitäytettynä. Tunnistus on **offline-sääntöpohjainen** — tekoälyä ei kutsuta
automaattisesti, koska noutopisteellä ei aina ole verkkoa eikä jakamisen
tarkoitus ole odottaa.

## Testit

Kaikki ajettavia (`test_sync.py`, `test_parser.py`, `test_lan_and_contract.py`,
`check_kotlin.py` projektin juuressa). Työpöytäohjelman lähdetiedosto pitää olla
samassa kansiossa tai sen yläpuolella.

| Testi | Kattaa | Tarkistuksia |
|---|---|---|
| `test_sync.py` | Merge-säännöt: idempotenssi, kenttäomistus, ristiriidat, poisto vs. muokkaus, kellopoikkeama, hautakivet, asiakasrekisteri, jakelupoikkeukset | 105 |
| `test_parser.py` | Tilaustekstin jäsennys suomeksi + Kotlinin ja Pythonin sääntöjen yhtenevyys | 35 |
| `test_lan_and_contract.py` | Lähiverkkoprotokolla ajettuna + kenttäsopimus puhelimen ja työpöydän välillä | 72 |
| `check_kotlin.py` | Kotlinin kutsujen nimetyt argumentit ja sulkeiden tasapaino | — |

Ajo palvelimella ilman tkinteriä: `KASVILA_TK_STUB=<tynkäkansio> python3 test_sync.py`.
