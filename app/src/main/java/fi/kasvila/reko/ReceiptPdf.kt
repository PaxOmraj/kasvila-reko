package fi.kasvila.reko

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The electronic / PDF receipt (v45, Vaihe 1).
 *
 * A plain A4 page, single column, generous type — the same "read standing up in a
 * car park" style as [RekoPdf]. Built with [PdfDocument] alone: no library, no
 * network. The finished file is handed to [RekoPdf.share] (send it to the
 * customer) or [RekoPdf.print] (Android print service → a real printer or
 * "Save as PDF"). The 58 mm thermal layout and the QR *image* arrive in Vaihe 2;
 * here the payment details are printed as text and the QR payload is only stored.
 */
object ReceiptPdf {

    private const val PAGE_W = 595   // A4 at 72 dpi (PdfDocument units)
    private const val PAGE_H = 842
    private const val MARGIN = 42f

    fun fileName(number: String): String =
        "kasvila_kuitti_" + number.replace(Regex("[^A-Za-z0-9_-]"), "-") + ".pdf"

    /** Render [data] to a PDF in the shared cache dir. Returns null on failure. */
    fun build(context: Context, data: ReceiptData): File? = try {
        val doc = PdfDocument()
        Renderer(doc, data).run()
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, fileName(data.number))
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        f
    } catch (e: Exception) {
        null
    }

    private class Renderer(private val doc: PdfDocument, private val data: ReceiptData) {
        private var page: PdfDocument.Page? = null
        private var canvas: android.graphics.Canvas? = null
        private var y = 0f
        private var pageNo = 0

        private val title = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        private val heading = Paint().apply { textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
        private val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        private val bodyBold = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        private val small = Paint().apply { textSize = 9f; color = 0xFF666666.toInt(); isAntiAlias = true }
        private val rule = Paint().apply { strokeWidth = 0.7f; color = 0xFF999999.toInt() }

        fun run() {
            newPage()
            logo()
            header()
            meta()
            itemLines()
            totals()
            vat()
            payment()
            extras()
            footer()
            close()
        }

        private fun newPage() {
            close()
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
        }

        private fun close() {
            page?.let { doc.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensure(space: Float) {
            if (y + space > PAGE_H - MARGIN) newPage()
        }

        private fun line(text: String, paint: Paint, gapAfter: Float = 4f) {
            ensure(paint.textSize + gapAfter)
            canvas?.drawText(text, MARGIN, y + paint.textSize, paint)
            y += paint.textSize + gapAfter
        }

        /** Left label + right-aligned value on one row. */
        private fun row(left: String, right: String, paint: Paint, gapAfter: Float = 4f) {
            ensure(paint.textSize + gapAfter)
            val c = canvas ?: return
            c.drawText(left, MARGIN, y + paint.textSize, paint)
            val rw = paint.measureText(right)
            c.drawText(right, PAGE_W - MARGIN - rw, y + paint.textSize, paint)
            y += paint.textSize + gapAfter
        }

        /** Three columns: qty/unit (fixed left), name (middle), amount (right). */
        private fun itemRow(qtyUnit: String, name: String, amount: String) {
            ensure(body.textSize + 4f)
            val c = canvas ?: return
            val leftW = 70f
            c.drawText(qtyUnit, MARGIN, y + body.textSize, body)
            val amtW = body.measureText(amount)
            val nameMax = PAGE_W - MARGIN - amtW - (MARGIN + leftW) - 8f
            val shown = ellipsize(name, body, nameMax)
            c.drawText(shown, MARGIN + leftW, y + body.textSize, body)
            c.drawText(amount, PAGE_W - MARGIN - amtW, y + body.textSize, body)
            y += body.textSize + 4f
        }

        private fun ellipsize(text: String, paint: Paint, maxW: Float): String {
            if (paint.measureText(text) <= maxW) return text
            var s = text
            while (s.isNotEmpty() && paint.measureText("$s…") > maxW) s = s.dropLast(1)
            return "$s…"
        }

        private fun divider(gap: Float = 8f) {
            ensure(gap)
            canvas?.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += gap
        }

        private fun gap(h: Float = 8f) { y += h }

        private fun money(v: Double) = Reko.money(v, data.currency)

        // ── sections ─────────────────────────────────────────────────────────

        private fun logo() {
            val path = data.seller.logoPath
            if (path.isBlank()) return
            try {
                val bmp = BitmapFactory.decodeFile(path) ?: return
                val maxW = 160f
                val scale = if (bmp.width > maxW) maxW / bmp.width else 1f
                val w = bmp.width * scale
                val h = bmp.height * scale
                ensure(h + 8f)
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = android.graphics.RectF(MARGIN, y, MARGIN + w, y + h)
                canvas?.drawBitmap(bmp, src, dst, null)
                y += h + 8f
            } catch (_: Exception) {
                // A bad logo file must never break the receipt.
            }
        }

        private fun header() {
            line(data.seller.name.ifBlank { "Myyjä" }, title, gapAfter = 2f)
            data.seller.headerLines().forEach { line(it, small, gapAfter = 1f) }
            gap(6f)
            divider()
        }

        private fun meta() {
            row("KUITTI", "Nro ${data.number}", heading)
            line(prettyDateTime(data.dateTimeIso), small, gapAfter = 2f)
            if (data.customer.isNotBlank()) line("Asiakas: ${data.customer}", body)
            if (data.showOperator && data.operator.isNotBlank()) line("Palvelija: ${data.operator}", body)
            gap(4f)
        }

        private fun itemLines() {
            divider()
            if (data.items.isEmpty()) {
                line("Ei rivejä.", body)
                return
            }
            data.items.forEach { it ->
                itemRow("${qtyFmt(it.qty)} ${it.unit}", it.name, money(it.lineTotal))
            }
        }

        private fun totals() {
            divider()
            row("Yhteensä", money(data.total), bodyBold, gapAfter = 6f)
            row("Maksutapa", Reko.paymentLabel(data.paymentMethod), body)
            gap(4f)
        }

        private fun vat() {
            if (data.vatLines.isEmpty()) return
            divider()
            line("ALV-erittely", heading)
            // header row
            vatRow("ALV %", "Veroton", "Vero", "Verollinen", bodyBold)
            data.vatLines.forEach { v ->
                vatRow(
                    "${trimRate(v.rate)} %",
                    money(v.net),
                    money(v.vat),
                    money(v.gross),
                    body
                )
            }
            gap(4f)
        }

        /** Four evenly spread columns for the VAT table. */
        private fun vatRow(a: String, b: String, c: String, d: String, paint: Paint) {
            ensure(paint.textSize + 4f)
            val cv = canvas ?: return
            val usable = PAGE_W - 2 * MARGIN
            val colW = usable / 4f
            // a left, b/c/d right-aligned within their columns
            cv.drawText(a, MARGIN, y + paint.textSize, paint)
            fun rightIn(text: String, colIndex: Int) {
                val colRight = MARGIN + colW * (colIndex + 1)
                val w = paint.measureText(text)
                cv.drawText(text, colRight - w, y + paint.textSize, paint)
            }
            rightIn(b, 1); rightIn(c, 2); rightIn(d, 3)
            y += paint.textSize + 4f
        }

        private fun payment() {
            val s = data.seller
            val parts = buildList {
                if (s.mobilePay.isNotBlank()) add("MobilePay: ${s.mobilePay}")
                if (s.iban.isNotBlank()) {
                    add("Tilisiirto: ${s.iban}" +
                        (if (s.paymentReference.isNotBlank()) "  ·  viite ${s.paymentReference}" else ""))
                }
            }
            if (parts.isEmpty() && data.qrPayload.isBlank()) return
            divider()
            line("Maksutiedot", heading)
            parts.forEach { line(it, body) }
            if (data.qrPayload.isNotBlank()) {
                val qr = Qr.bitmap(data.qrPayload, 360)
                if (qr != null) {
                    val size = 120f
                    ensure(size + 14f)
                    val c = canvas
                    if (c != null) {
                        val src = Rect(0, 0, qr.width, qr.height)
                        val dst = android.graphics.RectF(MARGIN, y, MARGIN + size, y + size)
                        c.drawBitmap(qr, src, dst, null)
                        y += size + 2f
                    }
                    line("Maksa skannaamalla pankki- tai MobilePay-sovelluksella.", small)
                }
            }
            gap(4f)
        }

        private fun extras() {
            val intro = data.seller.introText
            val foot = data.seller.footerNote
            if (intro.isBlank() && foot.isBlank()) return
            divider()
            if (intro.isNotBlank()) wrap(intro, body)
            if (foot.isNotBlank()) wrap(foot, small)
            gap(4f)
        }

        private fun footer() {
            gap(8f)
            line("Kiitos! · Kuitti ${data.number}", small)
        }

        /** Word-wrap a free-text block to the page width. */
        private fun wrap(text: String, paint: Paint) {
            val maxW = PAGE_W - 2 * MARGIN
            for (para in text.split("\n")) {
                var lineBuf = StringBuilder()
                for (word in para.split(" ")) {
                    val candidate = if (lineBuf.isEmpty()) word else "$lineBuf $word"
                    if (paint.measureText(candidate) > maxW && lineBuf.isNotEmpty()) {
                        line(lineBuf.toString(), paint, gapAfter = 2f)
                        lineBuf = StringBuilder(word)
                    } else {
                        lineBuf = StringBuilder(candidate)
                    }
                }
                line(lineBuf.toString(), paint, gapAfter = 2f)
            }
        }

        private fun qtyFmt(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        private fun trimRate(r: Double): String =
            if (r == r.toLong().toDouble()) r.toLong().toString()
            else String.format(Locale.US, "%.1f", r)

        private fun prettyDateTime(iso: String): String {
            val t = iso.trim()
            if (t.length < 16) return t
            val date = t.substring(0, 10)
            val time = t.substring(11, 16)
            // 2026-09-12 -> 12.9.2026
            val d = date.split("-")
            val human = if (d.size == 3)
                "${d[2].trimStart('0')}.${d[1].trimStart('0')}.${d[0]}" else date
            return "$human klo $time"
        }
    }
}
