# Fontit / Fonts

Sovellus tarjoaa neljä fonttia asetuksissa (**Asetukset → Ulkoasu → Fontti**):

| Valinta | Vaatii tiedoston |
|---|---|
| Roboto | — (Androidin järjestelmäfontti, ei vaadi mitään) |
| Inter | `inter.ttf` |
| Lexend | `lexend.ttf` |
| Atkinson Hyperlegible | `atkinson_hyperlegible.ttf` |

Sovellus **kääntyy ja toimii heti** ilman näitä tiedostoja: jos fonttitiedosto puuttuu,
kyseinen valinta näyttää toistaiseksi järjestelmäfonttia. Kun lisäät tiedoston ja käännät
uudelleen, fontti tulee automaattisesti käyttöön — koodiin ei tarvitse koskea.

## Tiedostojen lisääminen

1. Lataa fontit ilmaiseksi osoitteesta <https://fonts.google.com>:
   - Inter — <https://fonts.google.com/specimen/Inter>
   - Lexend — <https://fonts.google.com/specimen/Lexend>
   - Atkinson Hyperlegible — <https://fonts.google.com/specimen/Atkinson+Hyperlegible>
2. Ota kustakin yksi säännöllisen paksuuden `.ttf` (esim. "Regular" / 400) tai variable-fontti.
3. Luo kansio `app/src/main/res/font/` (jos sitä ei ole).
4. Nimeä tiedostot **täsmälleen** näin ja kopioi ne siihen kansioon:
   ```
   inter.ttf
   lexend.ttf
   atkinson_hyperlegible.ttf
   ```
5. Käännä sovellus uudelleen.

> **Tärkeää:** Android `res/font/` -kansion tiedostonimet saavat sisältää vain pieniä
> kirjaimia, numeroita ja alaviivoja (`a–z`, `0–9`, `_`). Älä käytä väliviivoja tai isoja
> kirjaimia. **Älä myöskään laita muita tiedostoja** (kuten `.txt`- tai `.md`-tiedostoja)
> `res/font/`-kansioon — Android hyväksyy sinne vain `.ttf`, `.ttc`, `.otf` ja `.xml`
> -tiedostot, muuten käännös epäonnistuu.
