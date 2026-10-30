# Kovennus ja jakelu — Kasvila REKO

Tämä ohje koskee sovelluksen suojaamista (kovennus) ja jakelua muille
REKO-tuottajille. **v55 sisältää kovennuksen**; jakelutapa (GitHub tai Google
Play) päätetään ja toteutetaan erikseen.

---

## 1. Mitä v55 tekee (kovennus)

**a) R8/minify + obfuskointi** (`app/build.gradle.kts`, `app/proguard-rules.pro`)

Release-buildissa R8 nyt kutistaa, optimoi ja **obfuskoi** koodin: luokka- ja
metodinimet muutetaan lyhyiksi ja merkityksettömiksi. Tämä on se osa, joka tekee
sovelluksesta selvästi vaikeamman lukea, muokata ja murtaa. Käyttämätön koodi ja
resurssit karsitaan, joten myös APK pienenee.

- Tämä koskee **vain release-buildia**. Debug-build toimii kuten ennenkin (ei
  obfuskointia), joten kehitys ja testaus eivät hidastu.
- ProGuard-säännöt on kirjoitettu niin, että kaikki herkkä pysyy ehjänä:
  kotlinx.serialization-serialisoijat (JSON-tallennus + työpöytäsynkronointi),
  Argon2:n natiivikoodi, WorkManager-workerit, biometria ja tulostinkirjastot.
- **Tärkeää JSON-yhteensopivuudesta:** obfuskointi EI muuta tallennetun tai
  synkronoidun JSON:n avaimia — kotlinx.serialization kirjoittaa kenttien nimet
  serialisoijaan käännösaikana. Vanha data ja työpöytäsynkronointi toimivat siis
  ennallaan.

**b) `allowBackup=false`** (`AndroidManifest.xml`)

Estää salatun tilatiedoston ja avainkääreiden kopioimisen laitteelta adb- tai
pilvivarmuuskopiolla. Varmuuskopiointi tehdään sovelluksen omalla salatulla
varmuuskopiolla (Tiedot ja synkronointi → Ylläpito).

**c) Julkaisuallekirjoituksen rakenne** (ks. kohta 2)

---

## 2. Julkaisuavain (tee kerran)

Release-APK pitää allekirjoittaa, jotta sen voi asentaa ja päivittää. Avain on
sinun — sitä ei ole eikä saa olla tässä paketissa.

**1. Luo avain** (projektin juuressa, komentorivillä):

```
keytool -genkeypair -v -keystore kasvila-reko.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias kasvila
```

Vastaa kysymyksiin ja **valitse vahvat salasanat**. Ota `kasvila-reko.jks`-
tiedostosta ja salasanoista varmuuskopio turvalliseen paikkaan.

> ⚠️ **Sama avain on käytettävä joka päivityksessä.** Jos avain katoaa tai
> vaihtuu, päivitystä ei voi asentaa vanhan sovelluksen päälle — käyttäjien
> pitäisi poistaa ja asentaa uudelleen (ja he menettävät datansa, ellei
> varmuuskopiota ole). Säilytä avain ja salasanat huolella.

**2. Kerro avain buildille:** kopioi `keystore.properties.example` →
`keystore.properties` ja täytä arvot:

```
storeFile=kasvila-reko.jks
storePassword=<salasana>
keyAlias=kasvila
keyPassword=<salasana>
```

`keystore.properties` ja `*.jks` ovat `.gitignore`ssa, joten ne eivät päädy
versionhallintaan eivätkä jakoon.

**3. Rakenna allekirjoitettu release:**

- Komentoriviltä: `./gradlew assembleRelease` → APK löytyy polusta
  `app/build/outputs/apk/release/`.
- Tai Android Studiossa: **Build → Generate Signed App Bundle / APK** (velho
  osaa allekirjoittaa myös ilman `keystore.properties`-tiedostoa).

Jos `keystore.properties`-tiedostoa ei ole, release rakentuu **obfuskoituna
mutta allekirjoittamattomana** — silloin allekirjoitus tehdään Android Studion
velholla.

---

## 3. PAKOLLINEN testaus ennen jakoa

Obfuskointi on ainoa muutos, joka voi teoriassa rikkoa jotain vasta ajossa
(ei käännöksessä). **Testaa aina ASENNETTU RELEASE-BUILD** ennen kuin jaat sen
eteenpäin — ei pelkkää debug-buildia. Käy läpi vähintään:

1. Ensiasennus: tilin luonti, kirjautuminen, palautuskoodi.
2. Sulje ja avaa uudelleen → pysyy kirjautuneena; biometria (jos päällä).
3. Olemassa olevan datan avautuminen (salauksen purku) päivityksen jälkeen.
4. Työpöytäsynkronointi molempiin suuntiin (JSON-yhteensopivuus).
5. Kuitin tulostus (Bluetooth) + QR + PDF + myyntiloki.
6. Varmuuskopio ja palautus.
7. REKO-ilmoituksen / tuottajaesittelyn luonti (+ tekoäly, jos avain).

Jos jokin kaatuu vain release-buildissa, syy on lähes varmasti puuttuva
keep-sääntö. Kaatumisraportin voi kääntää luettavaksi tiedostolla
`app/build/outputs/mapping/release/mapping.txt` (**ota se talteen jokaisesta
julkaistusta versiosta**). Lisää tarvittaessa kohdennettu sääntö
`proguard-rules.pro`-tiedostoon — tai hätävarana laita
`isMinifyEnabled = false` väliaikaisesti pois päältä.

---

## 4. Jakelu (päätetään myöhemmin)

Kun kovennus on testattu, jakelu voidaan toteuttaa. Vaihtoehdot:

- **GitHub Releases + sovelluksen sisäinen päivitystarkistus** (ilmainen):
  julkaiset allekirjoitetun APK:n repoon `PaxOmraj/kasvila-reko`, ja sovellus
  tarkistaa uuden version ja tarjoaa latauksen. Tämä oli alustava suuntasi.
- **Google Play (suljettu testi)**: ~25 € kertamaksu kehittäjätilistä,
  automaattiset päivitykset, mutta Googlen arviointi ja lisävaatimukset.

Sano, kumpaan mennään, niin toteutan sen (GitHub-tapauksessa rakennan
päivitystarkistuksen repoa `PaxOmraj/kasvila-reko` vasten).
