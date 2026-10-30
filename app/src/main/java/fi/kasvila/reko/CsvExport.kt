package fi.kasvila.reko

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * CSV export for bookkeeping.
 *
 * Written for Finnish Excel specifically, which is fussier than it looks:
 *
 *  - **UTF-8 BOM.** Without it Excel reads the file as the system codepage and
 *    every ä and ö turns to mojibake. The BOM is three bytes that save an hour
 *    of "why is it Kirsikkatomaatti?".
 *  - **Semicolon separator.** In a locale where the decimal mark is a comma,
 *    Excel expects `;` between fields and mis-parses a comma-separated file into
 *    one column.
 *  - **Comma decimals.** `4,50` is a number to Finnish Excel; `4.50` is text,
 *    and text does not add up.
 *
 * Getting any of these wrong produces a file that opens without complaint and is
 * quietly useless, which is worse than one that fails loudly.
 */
object CsvExport {

    private const val SEP = ";"

    /** Escape one field: quotes doubled, and quoted when it could confuse a parser. */
    private fun cell(v: String): String {
        val needsQuote = v.contains(SEP) || v.contains('"') || v.contains('\n') || v.contains('\r')
        val s = v.replace("\"", "\"\"")
        return if (needsQuote) "\"$s\"" else s
    }

    /** A number Finnish Excel will treat as a number. */
    private fun num(v: Double, decimals: Int = 2): String =
        String.format(java.util.Locale.US, "%.${decimals}f", v).replace(".", ",")

    private fun qty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else num(v, 3)

    private fun yesNo(b: Boolean): String = if (b) "kyllä" else "ei"

    private fun build(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append('﻿')                       // BOM — see the class comment
        for (r in rows) {
            sb.append(r.joinToString(SEP) { cell(it) })
            sb.append("\r\n")                     // CRLF: what Excel writes itself
        }
        return sb.toString()
    }

    // ── contents ────────────────────────────────────────────────────────────

    /**
     * One row per ORDER LINE, not per order.
     *
     * A line-level file can be pivoted back up to orders in a spreadsheet, but an
     * order-level file can never be broken down into products — and "how much
     * basil did we sell in March" is exactly the question this export exists to
     * answer.
     */
    fun orderLines(state: AppState, orders: List<Order>): String {
        val ringName = state.rings.associate { it.id to it.name }
        val rows = ArrayList<List<String>>()
        rows.add(listOf(
            "Päivä", "Piiri", "Asiakas", "Yhteystieto", "Tuote", "Määrä", "Yksikkö",
            "Yksikköhinta", "Rivisumma", "Maksettu", "Noudettu", "Peruttu",
            "Muistiinpano", "Tilaustunnus"
        ))
        for (o in orders.filter { !it.deleted }.sortedWith(compareBy({ it.date }, { it.customer.lowercase() }))) {
            if (o.lines.isEmpty()) {
                rows.add(listOf(
                    o.date, ringName[o.group_id] ?: o.group_id, o.customer, o.contact,
                    "", "", "", "", num(0.0),
                    yesNo(o.paid), yesNo(o.picked), yesNo(o.cancelled), o.note, o.id
                ))
                continue
            }
            for (l in o.lines) {
                rows.add(listOf(
                    o.date,
                    ringName[o.group_id] ?: o.group_id,
                    o.customer,
                    o.contact,
                    l.name,
                    qty(l.qty),
                    Reko.unitFor(l, state.products),
                    num(l.unit_price),
                    num(l.qty * l.unit_price),
                    yesNo(o.paid), yesNo(o.picked), yesNo(o.cancelled),
                    o.note, o.id
                ))
            }
        }
        return build(rows)
    }

    /** One row per home-delivery line. */
    fun deliveryLines(state: AppState, deliveries: List<Delivery>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf(
            "Toimituspäivä", "Tilauspäivä", "Asiakas", "Yhteystieto", "Toimitusosoite",
            "Tuote", "Määrä", "Yksikkö", "Yksikköhinta", "Rivisumma",
            "Maksutapa", "Maksettu", "Toimitettu", "Peruttu", "Muistiinpano", "Tunnus"
        ))
        for (d in deliveries.filter { !it.deleted }.sortedBy { it.deliveryDate }) {
            val addr = d.deliveryAddress.ifBlank { d.address }
            if (d.lines.isEmpty()) {
                rows.add(listOf(
                    d.deliveryDate, d.orderDate, d.customer, d.contact, addr,
                    "", "", "", "", num(0.0),
                    Reko.paymentLabel(d.paymentMethod), yesNo(d.paid),
                    yesNo(d.delivered), yesNo(d.cancelled), d.note, d.id
                ))
                continue
            }
            for (l in d.lines) {
                rows.add(listOf(
                    d.deliveryDate, d.orderDate, d.customer, d.contact, addr,
                    l.name, qty(l.qty), Reko.unitFor(l, state.products),
                    num(l.unit_price), num(l.qty * l.unit_price),
                    Reko.paymentLabel(d.paymentMethod), yesNo(d.paid),
                    yesNo(d.delivered), yesNo(d.cancelled), d.note, d.id
                ))
            }
        }
        return build(rows)
    }

    /** Totals per product over a set of orders and deliveries. */
    fun productTotals(state: AppState, orders: List<Order>, deliveries: List<Delivery>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Tuote", "Määrä", "Yksikkö", "Summa"))
        for (t in Summaries.combinedTotalsFor(orders, deliveries, state.products)) {
            rows.add(listOf(t.name, qty(t.qty), t.unit, num(t.amount)))
        }
        return build(rows)
    }

    /** Who owes what — the list you actually chase. */
    fun unpaid(state: AppState): String {
        val ringName = state.rings.associate { it.id to it.name }
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Asiakas", "Yhteystieto", "Päivä", "Piiri", "Summa", "Tyyppi"))
        for (o in state.liveOrders.filter { !it.paid && !it.cancelled && it.total > 0 }
            .sortedWith(compareBy({ it.customer.lowercase() }, { it.date }))) {
            rows.add(listOf(
                o.customer, o.contact, o.date,
                ringName[o.group_id] ?: o.group_id, num(o.total), "REKO"
            ))
        }
        for (d in state.liveDeliveries.filter { !it.paid && !it.cancelled && it.total > 0 }
            .sortedWith(compareBy({ it.customer.lowercase() }, { it.deliveryDate }))) {
            rows.add(listOf(
                d.customer, d.contact, d.deliveryDate, "", num(d.total), "Kuljetus"
            ))
        }
        return build(rows)
    }

    /**
     * One row per issued receipt — the phone-local sales journal, for bookkeeping
     * without the desktop. Net and VAT are summed across the receipt's rate rows.
     */
    fun receipts(receipts: List<IssuedReceipt>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf(
            "Numero", "Päivä", "Aika", "Asiakas", "Maksutapa",
            "Veroton", "ALV", "Yhteensä"
        ))
        for (r in receipts.sortedBy { it.number }) {
            val date = r.dateTime.take(10)
            val time = if (r.dateTime.length >= 16) r.dateTime.substring(11, 16) else ""
            val net = r.vatBreakdown.sumOf { it.net }
            val vat = r.vatBreakdown.sumOf { it.vat }
            rows.add(listOf(
                r.number, date, time, r.customer, Reko.paymentLabel(r.paymentMethod),
                num(net), num(vat), num(r.total)
            ))
        }
        return build(rows)
    }

    /** The customer register, for a mailing list or a backup outside the app. */
    fun customers(state: AppState): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Nimi", "Puhelin", "Sähköposti", "Muu yhteystieto", "Osoite",
            "Tilauksia", "Yhteensä", "Maksamatta", "Viimeksi", "Muistiinpano"))
        for (c in state.customers.filter { !it.deleted }.sortedBy { it.name.lowercase() }) {
            val h = CustomerBook.historyFor(state, c.name)
            rows.add(listOf(
                c.name, c.phone, c.email, c.contact, c.address,
                h.count.toString(), num(h.total), num(h.unpaid), h.lastDate, c.notes
            ))
        }
        return build(rows)
    }

    // ── delivery ────────────────────────────────────────────────────────────

    /**
     * Hand a CSV to the share sheet. Written as text/csv so mail apps and Drive
     * label it correctly; the phone itself rarely has anything that opens it,
     * which is fine — the destination is a computer.
     */
    fun share(context: Context, fileName: String, content: String, title: String) {
        try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, fileName)
            f.writeBytes(content.toByteArray(Charsets.UTF_8))
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, title))
        } catch (e: Exception) {
            Toast.makeText(context, "Vienti epäonnistui: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
