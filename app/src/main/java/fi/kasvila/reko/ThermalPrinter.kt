package fi.kasvila.reko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dantsu.escposprinter.EscPosCharsetEncoding
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import java.util.Locale

/**
 * Bluetooth thermal (ESC/POS) receipt printing (v46), via the DantSu library.
 *
 * Everything the DantSu API touches is isolated here so a first-compile tweak is
 * local. Every call is synchronous and throwing — run it off the main thread (the
 * caller uses a coroutine on Dispatchers.IO) and it returns a Finnish error
 * string, or null on success. Logo and QR are printed as images so they render
 * the same as on the PDF; Finnish ä/ö go out on the windows-1252 code page.
 *
 * Requires the BLUETOOTH_CONNECT runtime permission (Android 12+) — the caller
 * asks for it before printing.
 */
object ThermalPrinter {

    data class BtPrinter(val name: String, val address: String)

    private const val DPI = 203

    private data class Paper(val widthMm: Float, val chars: Int, val dots: Int)

    private fun paperFor(widthMm: Int): Paper =
        if (widthMm >= 80) Paper(72f, 48, dotsFor(72f)) else Paper(48f, 32, dotsFor(48f))

    private fun dotsFor(mm: Float): Int = (mm / 25.4f * DPI).toInt()

    /** Paired Bluetooth printers for the settings picker. Empty on any failure
     *  (e.g. permission not granted, Bluetooth off). */
    fun pairedPrinters(): List<BtPrinter> = try {
        val list = BluetoothPrintersConnections().list ?: emptyArray()
        list.mapNotNull { conn ->
            try {
                val dev = conn.device
                val addr = dev?.address ?: return@mapNotNull null
                BtPrinter(name = dev.name ?: addr, address = addr)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Print [data] as a receipt. Returns null on success, else an error message. */
    fun printReceipt(context: Context, data: ReceiptData, prefs: ReceiptPrefs): String? =
        withPrinter(prefs) { printer, paper ->
            printer.printFormattedTextAndCut(buildReceipt(printer, data, paper))
        }

    /** Print a short test slip so the producer can verify the printer + ä/ö. */
    fun printTest(context: Context, prefs: ReceiptPrefs): String? =
        withPrinter(prefs) { printer, paper ->
            val div = "-".repeat(paper.chars)
            printer.printFormattedTextAndCut(
                "[C]<b>Kasvila REKO</b>\n" +
                    "[C]Testitulostus\n" +
                    "[C]$div\n" +
                    "[L]Ääkköset: Perämeri, öljy, Ähtäri\n" +
                    "[L]Leveys: ${prefs.paperWidthMm} mm\n" +
                    "[C]$div\n" +
                    "[C]Jos tämä näkyy oikein,\n[C]tulostin toimii.\n"
            )
        }

    // ── internals ────────────────────────────────────────────────────────────

    private fun withPrinter(prefs: ReceiptPrefs, block: (EscPosPrinter, Paper) -> Unit): String? {
        val connection = connectionFor(prefs)
            ?: return "Ei paritettua Bluetooth-tulostinta. Paritä tulostin puhelimen asetuksista ja valitse se Kuitti ja tulostin -asetuksista."
        var printer: EscPosPrinter? = null
        return try {
            val paper = paperFor(prefs.paperWidthMm)
            printer = EscPosPrinter(
                connection, DPI, paper.widthMm, paper.chars,
                EscPosCharsetEncoding("windows-1252", 16)
            )
            block(printer, paper)
            null
        } catch (e: Exception) {
            "Tulostus epäonnistui: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { printer?.disconnectPrinter() } catch (_: Exception) {}
        }
    }

    private fun connectionFor(prefs: ReceiptPrefs): BluetoothConnection? {
        val mac = prefs.printerAddress.trim()
        if (mac.isEmpty()) return BluetoothPrintersConnections.selectFirstPaired()
        return try {
            val list = BluetoothPrintersConnections().list ?: emptyArray()
            list.firstOrNull {
                try { it.device?.address == mac } catch (e: Exception) { false }
            } ?: BluetoothPrintersConnections.selectFirstPaired()
        } catch (e: Exception) {
            BluetoothPrintersConnections.selectFirstPaired()
        }
    }

    /** Build the DantSu formatted text for a receipt. */
    private fun buildReceipt(printer: EscPosPrinter, data: ReceiptData, paper: Paper): String {
        val sb = StringBuilder()
        val div = "-".repeat(paper.chars)

        // Logo (image)
        imageTag(printer, loadLogo(data.seller.logoPath), paper.dots)?.let {
            sb.append("[C]").append(it).append("\n")
        }

        // Header
        sb.append("[C]<b>").append(esc(data.seller.name.ifBlank { "Myyjä" })).append("</b>\n")
        data.seller.headerLines().forEach { sb.append("[C]").append(esc(it)).append("\n") }
        sb.append("[C]$div\n")

        // Meta
        sb.append("[L]KUITTI[R]Nro ").append(esc(data.number)).append("\n")
        sb.append("[L]").append(esc(prettyDateTime(data.dateTimeIso))).append("\n")
        if (data.customer.isNotBlank()) sb.append("[L]Asiakas: ").append(esc(data.customer)).append("\n")
        if (data.showOperator && data.operator.isNotBlank())
            sb.append("[L]Palvelija: ").append(esc(data.operator)).append("\n")
        sb.append("[C]$div\n")

        // Items
        if (data.items.isEmpty()) {
            sb.append("[L]Ei rivejä.\n")
        } else {
            data.items.forEach { item ->
                sb.append("[L]").append(qtyText(item.qty)).append(" ").append(esc(item.unit))
                    .append(" ").append(esc(item.name))
                    .append("[R]").append(esc(money(item.lineTotal, data.currency))).append("\n")
            }
        }
        sb.append("[C]$div\n")

        // Total + payment method
        sb.append("[L]<b>Yhteensä</b>[R]<b>").append(esc(money(data.total, data.currency))).append("</b>\n")
        sb.append("[L]Maksutapa: ").append(esc(Reko.paymentLabel(data.paymentMethod))).append("\n")

        // VAT breakdown
        if (data.vatLines.isNotEmpty()) {
            sb.append("[C]$div\n")
            sb.append("[L]<b>ALV-erittely</b>\n")
            sb.append("[L]ALV %[R]veroton / vero\n")
            data.vatLines.forEach { v ->
                sb.append("[L]").append(rateText(v.rate)).append(" %")
                    .append("[R]").append(esc(money(v.net, data.currency)))
                    .append(" / ").append(esc(money(v.vat, data.currency))).append("\n")
            }
        }

        // Payment details + QR
        val payParts = buildList {
            if (data.seller.mobilePay.isNotBlank()) add("MobilePay: ${data.seller.mobilePay}")
            if (data.seller.iban.isNotBlank()) add("IBAN: ${data.seller.iban}")
        }
        if (payParts.isNotEmpty() || data.qrPayload.isNotBlank()) {
            sb.append("[C]$div\n")
            payParts.forEach { sb.append("[L]").append(esc(it)).append("\n") }
            imageTag(printer, Qr.bitmap(data.qrPayload, paper.dots.coerceAtMost(360)), paper.dots)?.let {
                sb.append("[C]").append(it).append("\n")
                sb.append("[C]Maksa skannaamalla\n")
            }
        }

        // Extras + footer
        if (data.seller.introText.isNotBlank()) {
            sb.append("[C]$div\n")
            sb.append("[C]").append(esc(data.seller.introText)).append("\n")
        }
        if (data.seller.footerNote.isNotBlank()) {
            sb.append("[C]").append(esc(data.seller.footerNote)).append("\n")
        }
        sb.append("[C]Kiitos!\n")
        return sb.toString()
    }

    /** Convert a bitmap to a DantSu <img> body, scaled to at most [maxDots] wide. */
    private fun imageTag(printer: EscPosPrinter, bmp: Bitmap?, maxDots: Int): String? {
        if (bmp == null) return null
        return try {
            val scaled = scaleToWidth(bmp, maxDots)
            "<img>" + PrinterTextParserImg.bitmapToHexadecimalString(printer, scaled) + "</img>"
        } catch (e: Exception) {
            null
        }
    }

    private fun loadLogo(path: String): Bitmap? =
        if (path.isBlank()) null
        else try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }

    private fun scaleToWidth(bmp: Bitmap, maxW: Int): Bitmap {
        if (maxW <= 0 || bmp.width <= maxW) return bmp
        val ratio = maxW.toFloat() / bmp.width
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, maxW, h, true)
    }

    /** DantSu uses <, > for tags — keep them out of free text. */
    private fun esc(s: String): String = s.replace("<", " ").replace(">", " ")

    private fun money(v: Double, cur: String): String = Reko.money(v, cur)

    private fun qtyText(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun rateText(r: Double): String =
        if (r == r.toLong().toDouble()) r.toLong().toString()
        else String.format(Locale.US, "%.1f", r)

    private fun prettyDateTime(iso: String): String {
        val t = iso.trim()
        if (t.length < 16) return t
        val d = t.substring(0, 10).split("-")
        val time = t.substring(11, 16)
        val human = if (d.size == 3) "${d[2].trimStart('0')}.${d[1].trimStart('0')}.${d[0]}" else t.substring(0, 10)
        return "$human klo $time"
    }
}
