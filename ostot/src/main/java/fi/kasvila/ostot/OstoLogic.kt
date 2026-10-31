package fi.kasvila.ostot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Puhtaat apufunktiot: ei Androidia, ei tilaa. */
object Osto {

    fun nowIso(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
    fun today(): String = LocalDate.now().toString()

    fun newSessionId(): String {
        val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val r = (0 until 4).map { "0123456789abcdef".random() }.joinToString("")
        return "S-$t-$r"
    }

    fun nextLineId(lines: List<OstoLine>): String {
        val used = lines.map { it.id }.toSet()
        var n = lines.size + 1
        while ("L$n" in used) n++
        return "L$n"
    }

    // ── viivakoodit ────────────────────────────────────────────────────────

    fun normEan(code: String?): String = code.orEmpty().filter { it.isDigit() }

    /** Kaupan oma punnitus- tai hintatarra (EAN-13, alkaa 2:lla). Ei koskaan opita. */
    fun isWeighCode(ean: String): Boolean = ean.length == 13 && ean.startsWith("2")

    /**
     * Punnitustarran hintaehdotus. Suomessa tarran viisi viimeistä numeroa ennen
     * tarkistetta ovat yleensä hinta senteissä. Kaikki kaupat eivät tee näin, joten
     * tämä on vain ehdotus, jonka käyttäjä hyväksyy tai korjaa.
     */
    fun weighPriceGuess(ean: String): Double? {
        if (!isWeighCode(ean)) return null
        val cents = ean.substring(7, 12).toIntOrNull() ?: return null
        if (cents <= 0) return null
        return cents / 100.0
    }

    /**
     * Mihin koodi viittaa. Järjestys: puhelimessa opittu (uusin), työpöydän
     * kirjasto, luettelon tuotteiden omat koodit.
     */
    fun lookup(state: OstoState, ean: String): EanEntry? {
        if (ean.isBlank() || isWeighCode(ean)) return null
        state.learned.firstOrNull { it.ean == ean }?.let { return it }
        state.catalog.eanLibrary.firstOrNull { it.ean == ean }?.let { return it }
        state.catalog.items.firstOrNull { ean in it.eans }?.let {
            return EanEntry(ean = ean, kind = it.kind, key = it.key, name = it.label())
        }
        return null
    }

    fun item(state: OstoState, kind: String, key: String): CatalogItem? =
        if (key.isBlank()) null else state.catalog.items.firstOrNull { it.kind == kind && it.key == key }

    /** Opi koodi: korvaa saman koodin vanha merkintä. */
    fun learn(state: OstoState, line: OstoLine): OstoState {
        if (line.ean.isBlank() || isWeighCode(line.ean) || line.key.isBlank() || line.kind.isBlank()) return state
        val e = EanEntry(
            ean = line.ean, kind = line.kind, key = line.key, packQty = line.packQty,
            name = line.name, brand = line.brand, updatedAt = nowIso()
        )
        return state.copy(learned = state.learned.filter { it.ean != line.ean } + e)
    }

    // ── haku ───────────────────────────────────────────────────────────────

    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    fun search(items: List<CatalogItem>, query: String, kinds: Set<String>): List<CatalogItem> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        return items.asSequence()
            .filter { it.kind in kinds }
            .filter { item ->
                val hay = fold(item.label() + " " + item.key + " " + item.supplier)
                words.all { it in hay }
            }
            .sortedWith(compareByDescending<CatalogItem> { it.used }.thenBy { fold(it.label()) })
            .toList()
    }

    // ── luvut ja tekstit ───────────────────────────────────────────────────

    /** "2,49", "2.49", "1 234,5" → luku. */
    fun parseNum(text: String): Double? {
        val t = text.trim().replace(" ", "").replace(" ", "").replace("€", "").replace(',', '.')
        if (t.isBlank()) return null
        return t.toDoubleOrNull()
    }

    fun fmt(v: Double?, decimals: Int = 2): String {
        if (v == null) return ""
        val s = String.format(Locale.ROOT, "%.${decimals}f", v).replace('.', ',')
        return if (decimals > 0) s.trimEnd('0').trimEnd(',') else s
    }

    fun money(v: Double?, currency: String = "€"): String =
        if (v == null) "–" else String.format(Locale.ROOT, "%.2f", v).replace('.', ',') + " " + currency

    /** Määrä luettavaksi: 1500 g → "1,5 kg", 12 kpl → "12 kpl". */
    fun qtyText(qty: Double?, kind: String): String {
        if (qty == null) return "–"
        return if (kind == Kind.ING || kind.isBlank()) {
            if (abs(qty) >= 1000) fmt(qty / 1000.0, 3) + " kg" else fmt(qty, 1) + " g"
        } else fmt(qty, 2) + " kpl"
    }

    /** "31.12.2027", "31.12.27", "2027-12-31", "12/27" → "2027-12-31", muuten "". */
    fun parseDate(text: String): String {
        val t = text.trim()
        if (t.isBlank()) return ""
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(t)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{2,4})$").find(t)?.let { m ->
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            return safeDate(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        Regex("^(\\d{1,2})[./](\\d{2,4})$").find(t)?.let { m ->
            var y = m.groupValues[2].toInt()
            if (y < 100) y += 2000
            val month = m.groupValues[1].toInt()
            if (month !in 1..12) return ""
            return LocalDate.of(y, month, 1).withDayOfMonth(LocalDate.of(y, month, 1).lengthOfMonth()).toString()
        }
        return ""
    }

    private fun safeDate(y: Int, m: Int, d: Int): String =
        runCatching { LocalDate.of(y, m, d).toString() }.getOrDefault("")

    fun dateFi(iso: String): String {
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
        return "${d.dayOfMonth}.${d.monthValue}.${d.year}"
    }

    // ── hinnat ─────────────────────────────────────────────────────────────

    /**
     * Rivin yksikköhinta ALV 0 % (€/kg raaka-aineelle, €/kpl muille) samalla
     * tavalla kuin työpöytä laskee (osto_line_price_net). null = ei laskettavissa.
     */
    fun unitPriceNet(line: OstoLine, defaultVat: Double): Double? {
        val price = line.price ?: return null
        if (price <= 0) return null
        val isIng = line.kind == Kind.ING
        val qty = line.totalQty()
        val gross = when (line.pricePer) {
            PricePer.KG -> if (isIng) price else null
            PricePer.UNIT -> if (!isIng) price else null
            else -> {
                val total = when (line.pricePer) {
                    PricePer.TOTAL -> price
                    else -> {
                        val n = line.packages ?: if (line.amount == null) 1.0 else null
                        if (n != null) price * n
                        else {
                            val pq = line.packQty
                            if (pq != null && pq > 0 && qty != null) price * (qty / pq) else null
                        }
                    }
                }
                if (total == null || qty == null || qty <= 0) null
                else if (isIng) total / (qty / 1000.0) else total / qty
            }
        } ?: return null
        val vat = line.vat ?: defaultVat
        return if (line.priceInclVat) gross / (1 + vat / 100.0) else gross
    }

    /** Rivin summa sellaisena kuin se nähtiin (yleensä ALV:n kanssa). */
    fun lineTotalSeen(line: OstoLine): Double? {
        val price = line.price ?: return null
        val qty = line.totalQty()
        return when (line.pricePer) {
            PricePer.TOTAL -> price
            PricePer.KG -> qty?.let { price * it / 1000.0 }
            PricePer.UNIT -> qty?.let { price * it }
            else -> {
                val n = line.packages ?: run {
                    val pq = line.packQty
                    if (pq != null && pq > 0 && qty != null) qty / pq else null
                }
                n?.let { price * it }
            }
        }
    }

    fun sessionTotalSeen(s: OstoSession): Double =
        s.lines.sumOf { lineTotalSeen(it) ?: 0.0 }

    /** Muutos prosentteina tallennettuun hintaan, kun molemmat tiedossa. */
    fun priceChangePct(newNet: Double?, stored: Double?): Double? {
        if (newNet == null || stored == null || stored <= 0) return null
        return (newNet / stored - 1.0) * 100.0
    }

    // ── hintalapun luku (OCR) ──────────────────────────────────────────────

    data class LabelGuess(
        /** Pakkauksen hinta, € */
        val price: Double? = null,
        /** Vertailuhinta €/kg (tai €/l) */
        val perKg: Double? = null,
        /** Pakkauskoko grammoina (litra = 1000 g) */
        val packG: Double? = null,
        val text: String = ""
    ) {
        val isEmpty: Boolean get() = price == null && perKg == null && packG == null
    }

    private val priceRe = Regex("(\\d{1,4})\\s?[,.]\\s?(\\d{2})(?!\\d)")
    private val sizeRe = Regex("(\\d+(?:[,.]\\d+)?)\\s?(kg|g|l|dl|cl|ml)\\b", RegexOption.IGNORE_CASE)

    /**
     * Hyllylapun tekstistä hinta, vertailuhinta ja pakkauskoko. Vertailuhinta
     * on rivillä, jossa on "/kg" tai "/l". Pakkaushinta on yleensä lapun suurin
     * muu hinta. Kaikki on ehdotusta: käyttäjä näkee luvut ennen tallennusta.
     */
    fun parseLabel(text: String): LabelGuess {
        var perKg: Double? = null
        val others = mutableListOf<Double>()
        for (raw in text.lines()) {
            val line = raw.lowercase(Locale.ROOT)
            val prices = priceRe.findAll(line).map { m ->
                "${m.groupValues[1]}.${m.groupValues[2]}".toDouble()
            }.toList()
            if (prices.isEmpty()) continue
            val unitLine = line.contains("/kg") || line.contains("/ kg") || line.contains("/l") ||
                line.contains("e/kg") || line.contains("€/kg") || line.contains("kg-hinta") ||
                line.contains("kilohinta") || line.contains("litrahinta")
            if (unitLine) {
                if (perKg == null) perKg = prices.first()
            } else others += prices
        }
        // Isot "kampanja" -luvut ilman desimaalierotinta ("249") jäävät pois tarkoituksella.
        val price = others.filter { it > 0 }.maxOrNull()
        var packG: Double? = null
        for (m in sizeRe.findAll(text)) {
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val g = when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "kg", "l" -> n * 1000
                "dl" -> n * 100
                "cl" -> n * 10
                else -> n
            }
            if (g in 1.0..50000.0) { packG = g; break }
        }
        // Jos pakkaushinta puuttuu mutta koko ja kilohinta ovat, laske se.
        val p = price ?: if (perKg != null && packG != null) round2(perKg * packG / 1000.0) else null
        return LabelGuess(price = p, perKg = perKg, packG = packG, text = text)
    }

    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    /**
     * Mitä on jo korissa: ostokertojen rivit, joista työpöytä ei vielä ole
     * päättänyt, summattuina tuotteittain (g tai kpl). Ostoslista kuittautuu näistä.
     */
    fun inCart(state: OstoState): Map<Pair<String, String>, Double> {
        val out = HashMap<Pair<String, String>, Double>()
        for (s in state.sessions) {
            if (s.isCount || state.isDecided(s.id)) continue
            for (l in s.lines) {
                if (l.key.isBlank()) continue
                val q = l.totalQty() ?: continue
                val k = l.kind to l.key
                out[k] = (out[k] ?: 0.0) + q
            }
        }
        return out
    }

    // ── lähetys ────────────────────────────────────────────────────────────

    /** Lähetettävät: valmiit kerrat, joista työpöytä ei vielä ole päättänyt. */
    fun sendable(state: OstoState): List<OstoSession> =
        state.sessions.filter { it.isFinished && !state.isDecided(it.id) && it.lines.isNotEmpty() }

    fun unsentCount(state: OstoState): Int =
        sendable(state).count { it.sentAt.isBlank() || it.changedAfterSend }

    /**
     * v60: lähteekö kuitin kuva tässä lähetyksessä. Kuva on iso, joten se lähetetään
     * vain, kun työpöytä ei vielä kerro saaneensa sitä: kerran lähetettyä ei lähetetä
     * uudelleen, ennen kuin työpöydän tila näyttää kerran ilman kuvaa.
     */
    fun needsPhoto(state: OstoState, s: OstoSession): Boolean {
        if (s.receiptPhotoHash.isBlank() || s.receiptPhoto.isBlank()) return false
        val st = state.statusOf(s.id)
        if (st?.photo == s.receiptPhotoHash) return false
        return s.photoSentHash != s.receiptPhotoHash || st != null
    }

    fun buildUpload(
        state: OstoState, deviceId: String, deviceName: String,
        image: (OstoSession) -> String = { "" }
    ): String {
        val up = OstoUpload(
            deviceId = deviceId, deviceName = deviceName, exportedAt = nowIso(),
            sessions = sendable(state).map { if (needsPhoto(state, it)) it.copy(receiptImage = image(it)) else it },
            eanLearned = state.learned
        )
        val el = ostoJson.encodeToJsonElement(up)
        return ostoJson.encodeToString(JsonElement.serializer(), stripPrivate(el))
    }

    /** Pois kentät, jotka alkavat alaviivalla: ne ovat vain puhelimen omia. */
    private fun stripPrivate(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { !it.startsWith("_") }.mapValues { stripPrivate(it.value) })
        is JsonArray -> JsonArray(e.map { stripPrivate(it) })
        else -> e
    }

    sealed interface Parsed {
        data class Catalog(val catalog: OstoCatalog) : Parsed
        data class Error(val reason: String) : Parsed
    }

    fun parseCatalog(text: String): Parsed {
        val c = try {
            ostoJson.decodeFromString(OstoCatalog.serializer(), text)
        } catch (e: Exception) {
            return Parsed.Error("Tiedosto ei ole kelvollinen luettelo.")
        }
        if (c.format != OSTO_FORMAT || c.kind != "catalog")
            return Parsed.Error("Tämä ei ole työpöydän ostoluettelo (${c.format.ifBlank { "?" }}).")
        if (c.version > OSTO_VERSION)
            return Parsed.Error("Luettelo on uudempaa versiota (v${c.version}) kuin tämä sovellus osaa (v$OSTO_VERSION). Päivitä sovellus.")
        return Parsed.Catalog(c)
    }

    /**
     * Uusi luettelo tuli. Merkitään vastaanotetut kerrat lähetetyiksi ja
     * pudotetaan opitut koodit, jotka työpöytä nyt jo tuntee samalla tavalla.
     */
    fun applyCatalog(state: OstoState, catalog: OstoCatalog, via: String): OstoState {
        val received = catalog.received.toSet()
        val now = nowIso()
        val sessions = state.sessions.map { s ->
            if (s.id in received) s.copy(sentAt = now, sentVia = via, changedAfterSend = false,
                photoSentHash = s.receiptPhotoHash) else s
        }
        val lib = catalog.eanLibrary.associateBy { it.ean }
        val learned = state.learned.filter { l ->
            val d = lib[l.ean]
            d == null || d.kind != l.kind || d.key != l.key || d.packQty != l.packQty
        }
        return state.copy(sessions = sessions, learned = learned, catalog = catalog, catalogReceivedAt = now)
    }

    fun statusText(status: String?): String = when (status) {
        "approved" -> "Kirjattu"
        "partial" -> "Osittain kirjattu"
        "rejected" -> "Hylätty"
        "pending" -> "Odottaa hyväksyntää"
        else -> ""
    }
}
