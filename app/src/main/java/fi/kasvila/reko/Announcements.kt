package fi.kasvila.reko

import java.time.LocalDate

/**
 * Sales-announcement and producer-intro text generation (v54).
 *
 * Two tiers: a rule-based draft built purely from the app's own data (rings,
 * pickup day/time/place, catalog + prices, seller info) — works offline and free
 * — and an optional AI polish (see [AnnouncementView], which calls [AiClient]).
 * Uses only non-personal data: no customer names ever go into a draft.
 */
object AnnouncementBuilder {

    /** A ready-to-edit Facebook/Messenger sales announcement for one pickup. */
    fun salesAnnouncement(state: AppState, ringId: String, date: String): String {
        val ring = state.rings.firstOrNull { it.id == ringId }
        val dist = state.distributionFor(ringId, date)
        val seller = state.sellerInfo
        val name = seller.name.ifBlank { "REKO-tuottaja" }

        val time = (dist?.time?.ifBlank { null }) ?: ring?.time.orEmpty()
        val place = (dist?.address?.ifBlank { null })
            ?: listOf(ring?.location, ring?.address, ring?.city).firstOrNull { !it.isNullOrBlank() }
            ?: ""
        val ringName = ring?.name ?: ""

        val header = buildString {
            append("🥬 ").append(name).append(" REKO")
            if (ringName.isNotBlank()) append(" — ").append(ringName)
            append("\n").append(prettyDate(date))
            if (time.isNotBlank()) append(" klo ").append(time)
            if (place.isNotBlank()) append(", ").append(place)
            append("!")
        }

        val products = state.activeProducts().sortedBy { it.name.lowercase() }
        val list = if (products.isEmpty()) "(lisää tuotteet sovellukseen)"
        else products.joinToString("\n") { p ->
            "• ${p.name} — ${Reko.money(p.price, state.currency)}/${p.unit}"
        }

        val payment = buildString {
            val parts = mutableListOf<String>()
            if (seller.mobilePay.isNotBlank()) parts.add("MobilePay ${seller.mobilePay}")
            if (seller.iban.isNotBlank()) parts.add("tilisiirto")
            if (parts.isNotEmpty()) append("Maksu: ").append(parts.joinToString(" tai "))
        }
        val contact = listOf(
            seller.phones.firstOrNull { it.isNotBlank() },
            seller.emails.firstOrNull { it.isNotBlank() },
            seller.website.ifBlank { null }
        ).filterNotNull().joinToString("  ·  ")

        return buildString {
            append(header).append("\n\n")
            append("Tällä viikolla tarjolla:\n").append(list).append("\n\n")
            append("Tilaa kommenttiin: nimi + tuotteet ja määrät.")
            if (time.isNotBlank() || place.isNotBlank()) {
                append(" Nouto")
                if (place.isNotBlank()) append(" ").append(place)
                if (time.isNotBlank()) append(" klo ").append(time)
                append(".")
            }
            append("\n")
            if (payment.isNotBlank()) append(payment).append("\n")
            if (contact.isNotBlank()) append(contact).append("\n")
            if (seller.footerNote.isNotBlank()) append("\n").append(seller.footerNote).append("\n")
            append("\nKiitos! 🌱")
        }.trim()
    }

    /** A ready-to-edit "Tietoa meistä" producer introduction. */
    fun producerIntro(state: AppState): String {
        val seller = state.sellerInfo
        val name = seller.name.ifBlank { "Tuottaja" }
        val products = state.activeProducts().sortedBy { it.name.lowercase() }.take(8)
            .joinToString(", ") { it.name }
        val contact = listOf(
            seller.phones.firstOrNull { it.isNotBlank() },
            seller.emails.firstOrNull { it.isNotBlank() },
            seller.website.ifBlank { null }
        ).filterNotNull().joinToString("  ·  ")

        return buildString {
            append("Tietoa meistä — ").append(name).append("\n\n")
            if (seller.introText.isNotBlank()) append(seller.introText).append("\n\n")
            append("Löydät meidät REKO-lähiruokapiireistä.")
            if (products.isNotBlank()) append(" Tuotteitamme: ").append(products).append(".")
            append("\n")
            if (contact.isNotBlank()) append("\nYhteys: ").append(contact)
            if (seller.businessId.isNotBlank()) append("\nY-tunnus: ").append(seller.businessId)
        }.trim()
    }

    /** System + user prompt for the optional AI polish. The [draft] carries the
     *  facts, so the model rewrites rather than invents. */
    fun aiPrompts(draft: String, intro: Boolean, tone: String, length: String): Pair<String, String> {
        val kind = if (intro) "tuottajaesittelyn" else "REKO-myynti-ilmoituksen"
        val system =
            "Olet avulias markkinointiassistentti suomalaiselle REKO-lähiruokatuottajalle. " +
                "Kirjoita $kind suomeksi ALLA annettujen tietojen pohjalta. Älä keksi mitään " +
                "uutta: säilytä tuotteet, hinnat, ajat ja yhteystiedot täsmälleen. Palauta vain " +
                "valmis teksti ilman selityksiä."
        val user = buildString {
            append("Sävy: ").append(tone).append(". Pituus: ").append(length).append(".\n\n")
            append("Tiedot:\n").append(draft)
        }
        return system to user
    }

    private fun prettyDate(date: String): String {
        return try {
            val d = LocalDate.parse(date.trim().take(10))
            val wd = Reko.weekdayFull(d.dayOfWeek.value - 1).replaceFirstChar { it.uppercase() }
            "$wd ${d.dayOfMonth}.${d.monthValue}.${d.year}"
        } catch (e: Exception) {
            date
        }
    }
}
