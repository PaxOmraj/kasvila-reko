package fi.kasvila.reko

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * Printing from the phone.
 *
 * This is the one thing that kept the desktop in the loop on a distribution day:
 * the picking list you work from, the summary you check against, and the slips
 * you hand to customers all lived on a computer that is not at the pickup point.
 * Built here with [PdfDocument] — no library, no network — and handed either to
 * Android's print service or to the share sheet.
 *
 * Layout is deliberately plain: A4, one column, generous type. These pages are
 * read standing up, in a car park, often in poor light, and sometimes in the
 * rain. Density is not a virtue here.
 */

// A4 at 72 dpi, which is what PdfDocument's page units are.
private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 42f

/**
 * A tiny text layout engine: a cursor that walks down the page and starts a new
 * one when it runs out of room. Everything else in this file is content.
 */
private class Sheet(private val doc: PdfDocument, private val footer: String) {
    private var page: PdfDocument.Page? = null
    private var y = 0f
    private var pageNo = 0

    val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
    val heading = Paint().apply { textSize = 13f; isFakeBoldText = true }
    val body = Paint().apply { textSize = 11f }
    val bodyBold = Paint().apply { textSize = 11f; isFakeBoldText = true }
    val small = Paint().apply { textSize = 9f; color = 0xFF666666.toInt() }
    val rule = Paint().apply { strokeWidth = 0.7f; color = 0xFF999999.toInt() }

    fun newPage() {
        close()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        y = MARGIN
    }

    /** Make sure [need] points of vertical space are available. */
    fun ensure(need: Float) {
        if (page == null) { newPage(); return }
        if (y + need > PAGE_H - MARGIN - 24f) newPage()
    }

    fun text(s: String, p: Paint, indent: Float = 0f) {
        ensure(p.textSize + 4f)
        page!!.canvas.drawText(s, MARGIN + indent, y + p.textSize, p)
        y += p.textSize + 4f
    }

    /** A left label, an optional middle, and a right-aligned value. */
    fun row(left: String, middle: String, right: String, p: Paint, leftW: Float = 70f) {
        ensure(p.textSize + 4f)
        val c = page!!.canvas
        val base = y + p.textSize
        c.drawText(left, MARGIN, base, p)
        if (middle.isNotEmpty()) c.drawText(middle, MARGIN + leftW, base, p)
        if (right.isNotEmpty()) {
            val w = p.measureText(right)
            c.drawText(right, PAGE_W - MARGIN - w, base, p)
        }
        y += p.textSize + 4f
    }

    fun divider() {
        ensure(8f)
        page!!.canvas.drawLine(MARGIN, y + 3f, PAGE_W - MARGIN, y + 3f, rule)
        y += 8f
    }

    fun gap(h: Float = 10f) {
        ensure(h)
        y += h
    }

    /** A checkbox line for lists that get ticked off by hand while picking. */
    fun checkRow(left: String, right: String, p: Paint) {
        ensure(p.textSize + 6f)
        val c = page!!.canvas
        val box = p.textSize - 1f
        val top = y + 2f
        c.drawRect(MARGIN, top, MARGIN + box, top + box, rule)
        val base = y + p.textSize
        c.drawText(left, MARGIN + box + 8f, base, p)
        if (right.isNotEmpty()) {
            val w = p.measureText(right)
            c.drawText(right, PAGE_W - MARGIN - w, base, p)
        }
        y += p.textSize + 6f
    }

    private fun stampFooter() {
        val pg = page ?: return
        val w = small.measureText(footer)
        pg.canvas.drawText(footer, PAGE_W - MARGIN - w, (PAGE_H - MARGIN + 14f), small)
        pg.canvas.drawText("Kasvila REKO", MARGIN, (PAGE_H - MARGIN + 14f), small)
    }

    fun close() {
        page?.let { stampFooter(); doc.finishPage(it) }
        page = null
    }
}

object RekoPdf {

    private fun qty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")

    private fun stamp(): String {
        val now = java.time.LocalDateTime.now()
        return "Tulostettu %d.%d.%d klo %02d:%02d".format(
            now.dayOfMonth, now.monthValue, now.year, now.hour, now.minute)
    }

    private fun build(context: Context, fileName: String, body: (Sheet) -> Unit): File? = try {
        val doc = PdfDocument()
        val sheet = Sheet(doc, stamp())
        sheet.newPage()
        body(sheet)
        sheet.close()
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, fileName)
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        f
    } catch (e: Exception) {
        null
    }

    // ── 1. Picking list: what to load into the car ──────────────────────────

    /**
     * Totals per product across everything going out, with a tick box per row.
     *
     * This is the sheet you actually work from in the cold room, so it carries
     * quantities and nothing else: no prices, no customer names, no per-order
     * breakdown to lose your place in.
     */
    fun pickingList(
        context: Context,
        state: AppState,
        heading: String,
        orders: List<Order>,
        deliveries: List<Delivery>
    ): File? = build(context, "kasvila_kerailylista.pdf") { s ->
        s.text("Keräilylista", s.title)
        s.text(heading, s.body)
        s.divider()

        val totals = Summaries.combinedTotalsFor(orders, deliveries, state.products)
        if (totals.isEmpty()) {
            s.text("Ei tuotteita.", s.body)
        } else {
            totals.sortedBy { it.name.lowercase() }.forEach { t ->
                s.checkRow("${qty(t.qty)} ${t.unit}   ${t.name}", "", s.bodyBold)
            }
        }

        s.gap()
        s.divider()
        val live = orders.filter { !it.cancelled && !it.deleted }
        val liveD = deliveries.filter { !it.cancelled && !it.deleted }
        s.text("${live.size} REKO-tilausta" +
            (if (liveD.isNotEmpty()) "  ·  ${liveD.size} kotiinkuljetusta" else ""), s.body)
        s.text("Yhteensä ${Reko.money(live.sumOf { it.total } + liveD.sumOf { it.total }, state.currency)}",
            s.bodyBold)
    }

    // ── 2. Distribution summary: the whole pickup on one sheet ──────────────

    fun distributionSummary(
        context: Context,
        state: AppState,
        ringName: String,
        date: String,
        orders: List<Order>
    ): File? = build(context, "kasvila_jakelun_koonti.pdf") { s ->
        s.text("Jakelun koonti", s.title)
        s.text("$ringName  ·  $date", s.body)
        s.divider()

        val live = orders.filter { !it.deleted }.sortedBy { it.customer.lowercase() }
        if (live.isEmpty()) {
            s.text("Ei tilauksia.", s.body)
            return@build
        }
        live.forEach { o ->
            s.ensure(56f)
            val flags = buildList {
                if (o.cancelled) add("PERUTTU")
                if (o.paid) add("maksettu") else add("MAKSAMATTA")
                if (o.picked) add("noudettu")
            }.joinToString(" · ")
            s.row(o.customer.ifBlank { "(nimetön)" }, "", Reko.money(o.total, state.currency), s.bodyBold)
            if (o.contact.isNotBlank()) s.text(o.contact, s.small, indent = 8f)
            o.lines.forEach { l ->
                s.row("", "${qty(l.qty)} ${Reko.unitFor(l, state.products)}  ${l.name}",
                    Reko.money(l.qty * l.unit_price, state.currency), s.body, leftW = 12f)
            }
            s.text(flags, s.small, indent = 8f)
            s.gap(6f)
        }

        s.divider()
        val notCancelled = live.filter { !it.cancelled }
        s.row("Yhteensä", "${notCancelled.size} tilausta",
            Reko.money(notCancelled.sumOf { it.total }, state.currency), s.heading)
        val unpaid = notCancelled.filter { !it.paid }
        if (unpaid.isNotEmpty()) {
            s.row("Maksamatta", "${unpaid.size} tilausta",
                Reko.money(unpaid.sumOf { it.total }, state.currency), s.bodyBold)
        }
    }

    // ── 3. Customer slips: one per customer, cut and hand over ──────────────

    /**
     * A slip per customer, three to a page.
     *
     * Cut lines rather than page breaks: a REKO pickup is twenty customers, and
     * twenty sheets of A4 is both wasteful and awkward to shuffle through in the
     * dark.
     */
    fun customerSlips(
        context: Context,
        state: AppState,
        ringName: String,
        date: String,
        orders: List<Order>
    ): File? = build(context, "kasvila_noutoliput.pdf") { s ->
        val live = orders.filter { !it.deleted && !it.cancelled }
            .sortedBy { it.customer.lowercase() }
        if (live.isEmpty()) {
            s.text("Noutoliput", s.title)
            s.text("Ei tilauksia.", s.body)
            return@build
        }
        live.forEachIndexed { i, o ->
            // Roughly a third of a page each, so three fit before a break.
            val need = 90f + o.lines.size * 16f
            s.ensure(need)
            s.text(o.customer.ifBlank { "(nimetön)" }, s.heading)
            s.text("$ringName  ·  $date", s.small)
            s.gap(4f)
            o.lines.forEach { l ->
                s.row("", "${qty(l.qty)} ${Reko.unitFor(l, state.products)}  ${l.name}",
                    Reko.money(l.qty * l.unit_price, state.currency), s.body, leftW = 12f)
            }
            s.gap(2f)
            s.row("Yhteensä", "", Reko.money(o.total, state.currency), s.bodyBold)
            s.text(if (o.paid) "Maksettu" else "MAKSAMATTA", s.bodyBold)
            if (o.note.isNotBlank()) s.text(o.note, s.small)
            if (i < live.size - 1) {
                s.gap(6f)
                s.divider()
                s.gap(10f)
            }
        }
    }

    // ── 4. Week summary ─────────────────────────────────────────────────────

    fun weekSummary(
        context: Context,
        state: AppState,
        monday: LocalDate
    ): File? = build(context, "kasvila_viikkokoonti.pdf") { s ->
        val w = Summaries.week(state, monday)
        val deliveries = Summaries.deliveriesForWeek(state, monday)
        s.text("Viikkokoonti", s.title)
        s.text("${monday} – ${monday.plusDays(6)}", s.body)
        s.divider()

        s.text("Jakelut", s.heading)
        if (w.distributions.isEmpty()) s.text("Ei jakeluja.", s.body)
        w.distributions.forEach { d ->
            s.row(d.date, d.ringName, Reko.money(d.totalAmount, state.currency), s.body, leftW = 80f)
        }
        if (deliveries.isNotEmpty()) {
            s.gap(6f)
            s.text("Kotiinkuljetukset", s.heading)
            deliveries.forEach { d ->
                s.row(d.deliveryDate, d.customer, Reko.money(d.total, state.currency), s.body, leftW = 80f)
            }
        }

        s.gap()
        s.divider()
        s.text("Tuotteet yhteensä", s.heading)
        val totals = Summaries.combinedTotalsFor(
            Summaries.ordersForWeek(state, monday), deliveries, state.products)
        if (totals.isEmpty()) s.text("–", s.body)
        totals.forEach { t ->
            s.row("${qty(t.qty)} ${t.unit}", t.name,
                Reko.money(t.amount, state.currency), s.body, leftW = 80f)
        }

        s.gap()
        s.divider()
        s.row("Koko viikko", "${w.orderCount + deliveries.size} tilausta",
            Reko.money(w.totalAmount + deliveries.sumOf { it.total }, state.currency), s.heading)
    }

    // ── delivery ────────────────────────────────────────────────────────────

    /** Hand the PDF to the share sheet (email, Drive, another phone, a printer app). */
    fun share(context: Context, file: File, title: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, title))
        } catch (e: Exception) {
            Toast.makeText(context, "Jako epäonnistui: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Print through Android's own print service, which covers real printers,
     * "save as PDF", and anything else the phone has set up.
     *
     * The adapter is the minimal legal one: the document is already a finished
     * PDF, so onWrite is a file copy. Android insists on the ceremony anyway.
     */
    fun print(context: Context, file: File, jobName: String) {
        try {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (pm == null) {
                Toast.makeText(context, "Tulostus ei ole käytettävissä", Toast.LENGTH_LONG).show()
                return
            }
            val adapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        FileInputStream(file).use { input ->
                            FileOutputStream(destination!!.fileDescriptor).use { out ->
                                input.copyTo(out)
                            }
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }
            pm.print(jobName, adapter, PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build())
        } catch (e: Exception) {
            Toast.makeText(context, "Tulostus epäonnistui: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Build, then print or share — with one honest message if the build failed. */
    fun deliver(context: Context, file: File?, jobName: String, share: Boolean) {
        if (file == null) {
            Toast.makeText(context, "Tulosteen luonti epäonnistui", Toast.LENGTH_LONG).show()
            return
        }
        if (share) share(context, file, jobName) else print(context, file, jobName)
    }
}
