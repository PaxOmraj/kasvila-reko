package fi.kasvila.reko

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Receipt logic (v45), kept pure so it can be reasoned about and tested without
 * Android: build one receipt from a customer's whole pickup (several orders and
 * deliveries), work out the VAT breakdown the law requires, allocate the running
 * number, and produce a payment QR payload. Rendering lives in [ReceiptPdf] (and,
 * later, the thermal printer); this file never touches the UI or the filesystem.
 */

/** One printed line on a receipt. */
data class ReceiptItem(
    val name: String,
    val qty: Double,
    val unit: String,
    val unitPrice: Double,   // gross (incl. VAT) — what the customer pays
    val lineTotal: Double,   // gross
    val vatRate: Double      // VAT %
)

/** Everything a receipt renderer needs, computed once. */
data class ReceiptData(
    val number: String,
    val dateTimeIso: String,
    val customer: String,
    val items: List<ReceiptItem>,
    val vatLines: List<ReceiptVatLine>,
    val total: Double,             // gross
    val paymentMethod: String,     // cash|card|mobilepay|transfer|other
    val qrPayload: String,         // "" when there is nothing to pay to
    val seller: SellerInfo,
    val currency: String,
    val operator: String = "",       // who served (for the log; shown if showOperator)
    val showOperator: Boolean = false
) {
    /** The record to keep in the phone-local sales log. */
    fun toIssued(orderIds: List<String>, deliveryIds: List<String>, deviceId: String) =
        IssuedReceipt(
            number = number,
            dateTime = dateTimeIso,
            orderIds = orderIds,
            deliveryIds = deliveryIds,
            customer = customer,
            paymentMethod = paymentMethod,
            total = total,
            vatBreakdown = vatLines,
            items = items.map {
                ReceiptLineRecord(it.name, it.qty, it.unit, it.unitPrice, it.lineTotal, it.vatRate)
            },
            deviceId = deviceId,
            qrPayload = qrPayload,
            operator = operator
        )
}

object ReceiptBuilder {

    /** Format the running counter as a customer-facing receipt id, e.g. 2026-000123. */
    fun formatNumber(counter: Int, year: Int = LocalDate.now().year): String =
        "$year-" + String.format(Locale.US, "%06d", counter)

    /**
     * Build a receipt for a customer's whole pickup. [orders] and [deliveries] are
     * everything being handed over now; [number] is the running counter value this
     * receipt will carry (formatted with [formatNumber]).
     */
    fun build(
        state: AppState,
        orders: List<Order>,
        deliveries: List<Delivery>,
        paymentMethod: String,
        number: Int,
        operator: String = "",
        showOperator: Boolean = false
    ): ReceiptData {
        val products = state.products
        val orderLines = orders.flatMap { it.lines }
        val deliveryLines = deliveries.flatMap { it.lines }
        val items = (orderLines + deliveryLines).map { line ->
            val product = products.firstOrNull { it.id == line.product_id }
                ?: products.firstOrNull { it.key() == line.product_id }
            val rate = product?.vat ?: state.defaultVat
            ReceiptItem(
                name = line.name.ifBlank { product?.name ?: "" },
                qty = line.qty,
                unit = Reko.unitFor(line, products),
                unitPrice = line.unit_price,
                lineTotal = line.qty * line.unit_price,
                vatRate = rate
            )
        }
        val total = items.sumOf { it.lineTotal }
        val vatLines = vatBreakdown(items)
        val customer = (orders.map { it.customer } + deliveries.map { it.customer })
            .firstOrNull { it.isNotBlank() } ?: ""
        val reference = state.sellerInfo.paymentReference.ifBlank {
            ("REKO " + customer).trim()
        }
        return ReceiptData(
            number = formatNumber(number),
            dateTimeIso = Reko.nowIso(),
            customer = customer,
            items = items,
            vatLines = vatLines,
            total = total,
            paymentMethod = paymentMethod,
            qrPayload = qrPayload(state.sellerInfo, total, reference),
            seller = state.sellerInfo,
            currency = state.currency,
            operator = operator,
            showOperator = showOperator
        )
    }

    /** Rebuild a printable [ReceiptData] from a stored [IssuedReceipt] for reprint.
     *  Keeps the original number/date; uses the current seller + currency for the
     *  header. */
    fun fromIssued(state: AppState, r: IssuedReceipt): ReceiptData = ReceiptData(
        number = r.number,
        dateTimeIso = r.dateTime,
        customer = r.customer,
        items = r.items.map {
            ReceiptItem(it.name, it.qty, it.unit, it.unitPrice, it.lineTotal, it.vatRate)
        },
        vatLines = r.vatBreakdown,
        total = r.total,
        paymentMethod = r.paymentMethod,
        qrPayload = r.qrPayload,
        seller = state.sellerInfo,
        currency = state.currency,
        operator = r.operator,
        showOperator = r.operator.isNotBlank()
    )

    /** Group lines by VAT rate and split each group's gross into net + tax. */
    fun vatBreakdown(items: List<ReceiptItem>): List<ReceiptVatLine> =
        items.groupBy { it.vatRate }
            .map { (rate, group) ->
                val gross = group.sumOf { it.lineTotal }
                val net = if (rate > 0.0) gross / (1.0 + rate / 100.0) else gross
                ReceiptVatLine(rate = rate, gross = gross, net = net, vat = gross - net)
            }
            .sortedBy { it.rate }

    /**
     * A "scan to pay" payload for the receipt QR.
     *
     * Prefers a SEPA/EPC credit-transfer code (IBAN + amount + reference), which
     * many Finnish bank apps can read. Falls back to a MobilePay link when one is
     * configured. Returns "" when the producer has set no payment details — the
     * receipt then simply has no QR. The QR *image* is rendered in Vaihe 2; the
     * payload is stored now so the log is complete.
     */
    fun qrPayload(seller: SellerInfo, total: Double, reference: String): String {
        if (seller.iban.isNotBlank() && total > 0.0) {
            return epcCreditTransfer(
                name = seller.name,
                iban = seller.iban,
                amount = total,
                message = reference
            )
        }
        val mp = seller.mobilePay.trim()
        if (mp.startsWith("http", ignoreCase = true) || mp.startsWith("mobilepay", ignoreCase = true)) {
            return mp
        }
        return ""
    }

    /** EPC069-12 SEPA Credit Transfer QR payload (version 002, UTF-8). */
    private fun epcCreditTransfer(name: String, iban: String, amount: Double, message: String): String =
        listOf(
            "BCD",
            "002",
            "1",
            "SCT",
            "",                                                   // BIC (optional in v002)
            name.take(70),
            iban.replace(" ", ""),
            "EUR" + String.format(Locale.US, "%.2f", amount),
            "",                                                   // purpose
            "",                                                   // structured reference
            message.take(140)                                    // unstructured remittance
        ).joinToString("\n")

    fun parseIsoDate(iso: String): LocalDateTime? =
        try { LocalDateTime.parse(iso.trim().take(19)) } catch (e: Exception) { null }
}
