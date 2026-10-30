# Automaattinen rakennus ja julkaisu (GitHub Actions)

Tästä lähtien APK:ta ei tarvitse rakentaa Android Studiossa. GitHub rakentaa sen
(`.github/workflows/android.yml`):

| Tapahtuma | Mitä syntyy |
|---|---|
| Muutos main-haaraan | Debug-APK testausta varten (ajon *Artifacts*). Kertoo samalla, kääntyykö koodi |
| Tagi `vN`, esim. `v58` | Allekirjoitettu APK ja GitHub-julkaisu. Puhelimen *Tarkista päivitykset* löytää sen |

Julkaisun kuvaus otetaan tiedostosta `docs/julkaisut/vN.md`, jos se on olemassa.
Tämä teksti näkyy puhelimen päivitysilmoituksessa.

## Kertaluontoinen asetus: allekirjoitusavain GitHubiin

Päivitys asentuu vanhan päälle vain, jos APK on allekirjoitettu **samalla
avaimella** kuin nykyinen sovellus (`kasvila-reko.jks` Windows-koneella).
Avain tallennetaan repon salaisuuksiin. Niitä ei näe kukaan, ei edes repon
omistaja jälkikäteen, eikä niitä näy lokeissa.

1. Etsi avaintiedosto. Sen polku on Android Studio -projektin
   `keystore.properties`-tiedoston rivillä `storeFile=`.
2. Muuta avain tekstiksi Windowsin PowerShellissä (vaihda polku):

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\polku\kasvila-reko.jks")) | Set-Clipboard
   ```

   Teksti on nyt leikepöydällä.
3. GitHubissa: repo → **Settings** → **Secrets and variables** → **Actions** →
   **New repository secret**. Lisää neljä salaisuutta:

   | Nimi | Arvo |
   |---|---|
   | `KEYSTORE_BASE64` | leikepöydän teksti (liitä) |
   | `KEYSTORE_PASSWORD` | `storePassword` keystore.properties-tiedostosta |
   | `KEY_ALIAS` | `keyAlias` (esim. `kasvila`) |
   | `KEY_PASSWORD` | `keyPassword` |

Ilman salaisuuksia debug-rakennus toimii, mutta julkaisu pysähtyy ja kertoo,
mitä puuttuu.

## Uuden version julkaisu

1. Nosta `versionCode` ja `versionName` tiedostossa `app/build.gradle.kts`.
2. Kirjoita muutokset tiedostoon `docs/julkaisut/vN.md`.
3. Luo tagi `vN` ja pushaa se. GitHub rakentaa ja julkaisee noin 5–10 minuutissa.

Claude tekee nämä vaiheet, kun Jarmo pyytää julkaisemaan version.
