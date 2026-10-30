package fi.kasvila.reko

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.IsoFields
import java.util.UUID

/**
 * Data model — mirrors the Kasvila desktop REKO format exactly so the two sides
 * can exchange JSON without translation. Field names match the desktop on
 * purpose (group_id, unit_price, updated_at …).
 *
 * v42: every synced record now carries the four sync fields
 *   updated_at / deleted / deleted_at / origin
 * so the catalog can be merged record-by-record instead of replaced wholesale.
 * See [SyncEngine] for the merge rules and [SyncPayload] for the exchange file.
 */

// ── sync primitives ─────────────────────────────────────────────────────────

/**
 * What every synced record carries. Not a base class (kotlinx.serialization works
 * best with flat data classes), but an interface so [SyncEngine] can treat rings,
 * products, orders and deliveries with one set of merge rules.
 */
interface Syncable {
    val id: String
    val updated_at: String
    val deleted: Boolean
    val deleted_at: String
    val origin: String
    /** Human-readable name for conflict lists and the trash view. */
    fun syncLabel(): String

    /**
     * The fields BOTH devices are allowed to edit, flattened to a string.
     *
     * Two records with the same signature are the same as far as syncing is
     * concerned, so identical edits on both devices are not reported as a
     * conflict. Desktop-only fields (a product's cost, recipe, allergens, EAN
     * codes) are deliberately left out: the phone never writes them, so they can
     * never disagree, and including them would raise phantom conflicts every
     * time the desktop recalculated a cost.
     */
    fun ownedSignature(): String
}

/** One peer device we have synced with, and when we last did. */
@Serializable
data class PeerInfo(
    val name: String = "",
    val lastSyncAt: String = "",        // decides "changed on my side since then"
    val lastDirection: String = ""      // "file" | "lan"
)

/** One line in the sync journal (the 50 most recent are kept). */
@Serializable
data class SyncLogEntry(
    val at: String = "",
    val peer: String = "",
    val peerName: String = "",
    val via: String = "file",           // "file" | "lan"
    val ringsNew: Int = 0,
    val ringsUpd: Int = 0,
    val productsNew: Int = 0,
    val productsUpd: Int = 0,
    val ordersNew: Int = 0,
    val ordersUpd: Int = 0,
    val deliveriesNew: Int = 0,
    val deliveriesUpd: Int = 0,
    val customersNew: Int = 0,
    val customersUpd: Int = 0,
    val distsNew: Int = 0,
    val distsUpd: Int = 0,
    val deletes: Int = 0,
    val conflicts: Int = 0,
    val snapshot: String = ""           // backup file this sync can be undone to
)

// ── domain records ──────────────────────────────────────────────────────────

@Serializable
data class Ring(
    override val id: String,
    val name: String = "",
    val city: String = "",
    val location: String = "",
    val address: String = "",
    @Serializable(with = WeekdaySerializer::class)
    val weekday: Int? = null,        // 0 = Mon … 6 = Sun ; desktop may send "" when unset
    val time: String = "",
    val parity: String = "weekly",   // weekly | even | odd
    val handler: String = "",
    val fb_url: String = "",
    val notes: String = "",
    val active: Boolean = true,      // false = taken out of use (soft delete, kept in history)
    // Local-only: dropped from the catalog but still has open orders. Never synced.
    val retired: Boolean = false,
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = ""
) : Syncable {
    override fun syncLabel(): String = name.ifBlank { id }

    /** Fields both sides may edit — what a conflict compares. */
    override fun ownedSignature(): String = listOf(
        name, city, location, address, weekday?.toString() ?: "", time, parity,
        handler, fb_url, notes, active.toString()
    ).joinToString("")
}

@Serializable
data class Product(
    override val id: String = "",
    val name: String,
    val price: Double = 0.0,         // GROSS price (incl. VAT) — what the customer pays
    val unit: String = "kpl",
    val aliases: List<String> = emptyList(),  // order names customers use
    val vat: Double = 0.0,           // VAT rate %, editable on the phone
    val cost: Double = 0.0,          // cost per unit — READ ONLY, owned by the desktop
    val category: String = "",       // read only, for grouping
    val active: Boolean = true,      // false = discontinued (soft delete)
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = ""
) : Syncable {
    /** Stable key for grouping/matching. Falls back to name if id is empty
     *  (e.g. catalogs exported before ids existed). */
    fun key(): String = if (id.isNotBlank()) id else name

    override fun syncLabel(): String = name.ifBlank { id }

    /** Net (VAT 0) price derived from the gross price and the VAT rate. */
    fun netPrice(): Double = if (vat > 0) price / (1.0 + vat / 100.0) else price

    /** Margin in currency per unit, using the desktop's cost. 0 when unknown. */
    fun margin(): Double = if (cost > 0) netPrice() - cost else 0.0

    /** Margin as a percentage of the net price. Null when it cannot be computed. */
    fun marginPct(): Double? {
        val net = netPrice()
        if (cost <= 0 || net <= 0) return null
        return (net - cost) / net * 100.0
    }

    /** Fields the phone may edit — what a conflict compares. Cost, recipe,
     *  allergens and the other desktop-only fields are deliberately excluded:
     *  the phone never writes them, so they can never conflict. */
    override fun ownedSignature(): String = listOf(
        name, String.format(java.util.Locale.US, "%.4f", price), unit,
        aliases.sorted().joinToString(","),
        String.format(java.util.Locale.US, "%.2f", vat), active.toString()
    ).joinToString("")
}

/**
 * One pickup: a ring on a date.
 *
 * Until v44 a pickup was implied — the ring's weekday and parity generated dates
 * and nothing could be said ABOUT one of them. That works right up until the week
 * it doesn't: a pickup cancelled for midsummer, one moved to the Friday, one held
 * in a different car park because the usual one is dug up. Those are ordinary
 * events in a REKO year and they had nowhere to live.
 *
 * ## Why the id is a natural key
 *
 * Identity here is "this ring, this day" — not a random id. The desktop has had
 * its own `rd_…` ids since long before the phone existed, so if both sides
 * minted ids independently the same pickup would arrive as two records and the
 * merge would have no way to know. Using `ringId|date` as the synced id means
 * both devices name the same pickup identically without ever having spoken.
 * The desktop's own id rides along in [dist_id] so it can find its existing
 * record and keep the orders attached to it.
 *
 * Orders are NOT held here. They carry their own group_id and date, exactly as
 * before, so this record is metadata about a slot rather than a container — and
 * the order sync that already works is left completely alone.
 */
@Serializable
data class Distribution(
    override val id: String = "",        // "ringId|date" — see the class comment
    val group_id: String = "",
    val date: String = "",
    /** Overrides the ring's usual time for this one pickup. Blank = as usual. */
    val time: String = "",
    /** Overrides the ring's usual address for this one pickup. Blank = as usual. */
    val address: String = "",
    val status: String = "planned",      // planned | open | closed | delivered
    /** No pickup at all this day. The date disappears from the pickers. */
    val cancelled: Boolean = false,
    val notes: String = "",
    /** The desktop's own distribution id, when we have learned it. */
    val dist_id: String = "",
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = ""
) : Syncable {
    override fun syncLabel(): String = date

    override fun ownedSignature(): String = listOf(
        group_id, date, time, address, status, cancelled.toString(), notes
    ).joinToString("")

    /** True when this record says anything worth keeping. */
    fun isNoteworthy(): Boolean =
        cancelled || notes.isNotBlank() || time.isNotBlank() ||
            address.isNotBlank() || status != "planned"

    companion object {
        fun keyOf(ringId: String, date: String): String = "$ringId|$date"

        fun statusLabel(s: String): String = when (s) {
            "open" -> "Avoin"
            "closed" -> "Suljettu"
            "delivered" -> "Toimitettu"
            else -> "Suunniteltu"
        }

        val STATUSES = listOf("planned", "open", "closed", "delivered")
    }
}

/**
 * A REKO customer.
 *
 * Mirrors the desktop's `customers[id]` so the two can be merged like anything
 * else. Order history is NOT stored here — it is derived from the orders by
 * matching the name, so there is no second copy of the same fact to go stale,
 * and a customer renamed on one device does not orphan their past orders.
 */
@Serializable
data class Customer(
    override val id: String = "",
    val name: String = "",
    val contact: String = "",          // puhelin tai sähköposti, vapaamuotoinen
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val type: String = "",             // read only: desktop's retail/wholesale/…
    val active: Boolean = true,
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = ""
) : Syncable {
    override fun syncLabel(): String = name.ifBlank { id }

    override fun ownedSignature(): String = listOf(
        name, contact, phone, email, address, notes, active.toString()
    ).joinToString("")

    /** Normalised name, used to tie a customer to their orders. */
    fun key(): String = norm(name)

    companion object {
        /** One place decides when two spellings are the same customer. */
        fun norm(s: String): String =
            s.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

@Serializable
data class Line(
    val name: String = "",
    val qty: Double = 1.0,
    val unit: String = "kpl",
    val unit_price: Double = 0.0,
    val product_id: String = "",
    val given: Boolean = false     // product handed over (delivery view)
)

@Serializable
data class Order(
    override val id: String,
    val group_id: String = "",
    val date: String = "",           // ISO yyyy-MM-dd
    val time: String = "",
    val address: String = "",
    val customer: String = "",
    val contact: String = "",
    val paid: Boolean = false,
    val picked: Boolean = false,
    val notPicked: Boolean = false,   // marked "left un-picked" in the delivery view
    val cancelled: Boolean = false,   // soft-cancelled (customer cancelled before the distribution)
    val note: String = "",
    // v45: maksutapa kuittia varten (cash|card|mobilepay|transfer|other).
    // Deliberately EXCLUDED from ownedSignature: the desktop never sends it, so
    // including it would raise a phantom conflict on every sync. The IssuedReceipt
    // in AppState.issuedReceipts is the authoritative record of the payment method.
    val paymentMethod: String = "",
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = "",
    val lines: List<Line> = emptyList()
) : Syncable {
    val total: Double get() = lines.sumOf { it.qty * it.unit_price }

    override fun syncLabel(): String = customer.ifBlank { "(nimetön)" } + "  ·  " + date

    override fun ownedSignature(): String = listOf(
        group_id, date, customer, contact, paid.toString(), picked.toString(),
        notPicked.toString(), cancelled.toString(), note,
        lines.joinToString(";") { "${it.product_id}|${it.name}|${it.qty}|${it.unit}|${it.unit_price}|${it.given}" }
    ).joinToString("")
}

/**
 * Home-delivery order (kotiinkuljetus). Independent of REKO rings — the customer
 * orders directly and the goods are delivered to an address instead of picked up
 * at a ring. Uses the same [Line] rows as REKO so products/quantities/prices work
 * identically, and so the desktop can reuse its line handling on import.
 */
@Serializable
data class Delivery(
    override val id: String,
    val customer: String = "",            // tilaajan nimi
    val contact: String = "",             // puhelin / sähköposti (valinnainen)
    val address: String = "",             // tilaajan osoite
    val deliveryAddress: String = "",     // toimitusosoite (valinnainen; tyhjä = sama kuin osoite)
    val orderDate: String = "",           // tilauspäivä, ISO yyyy-MM-dd
    val deliveryDate: String = "",        // haluttu toimituspäivä, ISO yyyy-MM-dd
    val deliveryTime: String = "",        // toimitusaika (valinnainen), HH:mm; tyhjä = ei kelloaikaa
    val paymentMethod: String = "cash",   // cash | card | mobilepay | transfer | other
    val paid: Boolean = false,
    val delivered: Boolean = false,       // toimitettu
    val cancelled: Boolean = false,       // peruttu (asiakas perui ennen toimitusta)
    val reminderHoursBefore: Int? = null, // muistutus N tuntia ennen toimitusta; null = ei muistutusta
    val note: String = "",
    override val updated_at: String = "",
    override val deleted: Boolean = false,
    override val deleted_at: String = "",
    override val origin: String = "",
    val lines: List<Line> = emptyList()
) : Syncable {
    val total: Double get() = lines.sumOf { it.qty * it.unit_price }

    override fun syncLabel(): String =
        customer.ifBlank { "(nimetön)" } + (if (deliveryDate.isNotBlank()) "  ·  $deliveryDate" else "")

    override fun ownedSignature(): String = listOf(
        customer, contact, address, deliveryAddress, orderDate, deliveryDate,
        deliveryTime, paymentMethod, paid.toString(), delivered.toString(),
        cancelled.toString(), reminderHoursBefore?.toString() ?: "", note,
        lines.joinToString(";") { "${it.product_id}|${it.name}|${it.qty}|${it.unit}|${it.unit_price}|${it.given}" }
    ).joinToString("")
}

/**
 * Seller/producer details printed on a receipt (v45).
 *
 * Mirrors the desktop's `company_info` shape on purpose so the two can be joined
 * later: name, business_id, addresses, phones, emails, website, logo. The phone
 * is self-sufficient — the producer fills these in on the phone (Asetukset →
 * Myyjän tiedot) and never needs the desktop. addresses/phones/emails are kept as
 * simple string lists here (one per line in the editor); the desktop's labelled
 * structure can be mapped in when receipt sync is added.
 */
@Serializable
data class SellerInfo(
    val name: String = "",
    val businessId: String = "",              // Y-tunnus
    val addresses: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val website: String = "",
    val logoPath: String = "",                // local file copied into filesDir; "" = none
    // Payment details used for the receipt's QR + payment line.
    val mobilePay: String = "",               // MobilePay number or link
    val iban: String = "",
    val paymentReference: String = "",        // default reference / message
    // Free receipt texts.
    val introText: String = "",               // thank-you / intro under the header
    val footerNote: String = ""               // pickup / opening note at the bottom
) {
    /** Enough to be a lawful receipt header (name + Y-tunnus). */
    fun isComplete(): Boolean = name.isNotBlank() && businessId.isNotBlank()

    /** Header contact lines, in print order, blanks dropped. */
    fun headerLines(): List<String> = buildList {
        if (businessId.isNotBlank()) add("Y-tunnus: $businessId")
        addresses.firstOrNull { it.isNotBlank() }?.let { add(it) }
        phones.firstOrNull { it.isNotBlank() }?.let { add(it) }
        emails.firstOrNull { it.isNotBlank() }?.let { add(it) }
        if (website.isNotBlank()) add(website)
    }
}

/** One VAT-rate row of a receipt's tax breakdown (Finnish receipt requirement). */
@Serializable
data class ReceiptVatLine(
    val rate: Double = 0.0,     // ALV-%
    val gross: Double = 0.0,    // verollinen
    val net: Double = 0.0,      // veroton (veron peruste)
    val vat: Double = 0.0       // veron määrä
)

/** One stored receipt line, so the sales log holds the products sold and a
 *  reprint is a faithful copy (the law requires the quantity and kind of goods). */
@Serializable
data class ReceiptLineRecord(
    val name: String = "",
    val qty: Double = 0.0,
    val unit: String = "",
    val unitPrice: Double = 0.0,
    val lineTotal: Double = 0.0,
    val vatRate: Double = 0.0
)

/**
 * A receipt that has been issued, kept as a lightweight sales log (v45).
 *
 * Immutable once written. `number` is the running receipt id shown to the
 * customer; `operator` is reserved for the multi-user shift feature (Vaihe 3) and
 * stays blank for now.
 */
@Serializable
data class IssuedReceipt(
    val number: String = "",                  // e.g. "2026-000123"
    val dateTime: String = "",                // ISO
    val orderIds: List<String> = emptyList(),
    val deliveryIds: List<String> = emptyList(),
    val customer: String = "",
    val paymentMethod: String = "",
    val total: Double = 0.0,
    val vatBreakdown: List<ReceiptVatLine> = emptyList(),
    val items: List<ReceiptLineRecord> = emptyList(),
    val deviceId: String = "",
    val qrPayload: String = "",
    val operator: String = ""                 // reserved for Vaihe 3 multi-user
)

/** Local on-device state (persisted to internal storage). */
@Serializable
data class AppState(
    val rings: List<Ring> = emptyList(),
    val products: List<Product> = emptyList(),
    val currency: String = "€",
    val catalogExportedAt: String = "",
    val catalogImportedAt: String = "",
    val catalogVersion: Int = 0,
    val orders: List<Order> = emptyList(),
    // Home-delivery orders (kotiinkuljetukset) — separate from REKO ring orders.
    val deliveries: List<Delivery> = emptyList(),
    // Device-only extra aliases the user teaches on the phone: product id -> names.
    val localAliases: Map<String, List<String>> = emptyMap(),
    // v47: customer register, shared with the desktop.
    val customers: List<Customer> = emptyList(),
    // v44: per-pickup exceptions (cancelled days, moved times, notes).
    val distributions: List<Distribution> = emptyList(),

    // v45: seller details for receipts, the running receipt number, and the
    // issued-receipt log (phone-local sales journal). See [SellerInfo] /
    // [IssuedReceipt]. Not synced yet — the phone is self-sufficient.
    val sellerInfo: SellerInfo = SellerInfo(),
    val receiptCounter: Int = 0,
    val issuedReceipts: List<IssuedReceipt> = emptyList(),

    // ── v42 sync state ──────────────────────────────────────────────────────
    /** Stable identity of THIS phone. Generated once, never changes. */
    val deviceId: String = "",
    /** Name shown on the other device during a sync. User-editable. */
    val deviceName: String = "",
    /** device id -> when we last synced with it. */
    val peers: Map<String, PeerInfo> = emptyMap(),
    /** Default VAT rate for new products, carried over from the desktop. */
    val defaultVat: Double = 13.5,
    /** Bumped when the on-disk shape changes, so [Store] can migrate. */
    val schemaVersion: Int = 2,
    /** The 50 most recent sync events, newest last. */
    val syncLog: List<SyncLogEntry> = emptyList()
) {
    /** Products offered in pickers: in use, not deleted. */
    fun activeProducts(): List<Product> = products.filter { it.active && !it.deleted }

    /**
     * Orders and deliveries that still exist.
     *
     * A record deleted on this phone is kept as a tombstone rather than removed,
     * because a removed record is indistinguishable from one the peer has simply
     * never heard of — and the peer would helpfully send it back on the next sync.
     * So every list, total and summary reads through these, and only the sync
     * engine ever looks at the raw lists.
     */
    val liveOrders: List<Order> get() = orders.filter { !it.deleted }
    val liveDeliveries: List<Delivery> get() = deliveries.filter { !it.deleted }

    /** Rings to show on the home screen: in use, or retired but still owed orders. */
    fun visibleRings(): List<Ring> = rings.filter { !it.deleted && it.active || it.retired }

    /** Rings offered in pickers: in use, not deleted. */
    fun activeRings(): List<Ring> = rings.filter { it.active && !it.deleted }

    /** Customers offered for autocomplete: in use, not deleted. */
    fun activeCustomers(): List<Customer> = customers.filter { it.active && !it.deleted }

    /** Everything currently in the trash, for the roskakori view. */
    fun trashedProducts(): List<Product> = products.filter { it.deleted }
    fun trashedRings(): List<Ring> = rings.filter { it.deleted }
    fun trashedCustomers(): List<Customer> = customers.filter { it.deleted }

    /** The exception record for one pickup, if there is one. */
    fun distributionFor(ringId: String, date: String): Distribution? =
        distributions.firstOrNull {
            !it.deleted && it.id == Distribution.keyOf(ringId, date)
        }
}

// ── legacy exchange formats (still read and written, unchanged) ─────────────

/** Desktop -> phone file (v1, pre-v42). Still imported. */
@Serializable
data class RingsPayload(
    val format: String = "kasvila-reko-rings",
    val version: Int = 1,
    val exported_at: String = "",
    val catalog_version: Int = 0,
    val currency: String = "€",
    val default_vat: Double = 13.5,
    val rings: List<Ring> = emptyList(),
    val products: List<Product> = emptyList()
)

/** Phone -> desktop file. */
@Serializable
data class OrdersPayload(
    val format: String = "kasvila-reko-orders",
    val version: Int = 1,
    val exported_at: String = "",
    val orders: List<Order> = emptyList()
)

/** Phone -> desktop file for home-delivery orders. */
@Serializable
data class DeliveriesPayload(
    val format: String = "kasvila-reko-deliveries",
    val version: Int = 1,
    val exported_at: String = "",
    val deliveries: List<Delivery> = emptyList()
)

/** Combined phone -> desktop export: REKO orders + home deliveries + learned
 *  product aliases in one file, so a single import brings everything across. */
@Serializable
data class BundlePayload(
    val format: String = "kasvila-reko-bundle",
    val version: Int = 1,
    val exported_at: String = "",
    val orders: List<Order> = emptyList(),
    val deliveries: List<Delivery> = emptyList(),
    val aliases: Map<String, List<String>> = emptyMap()
)

// ── constants ───────────────────────────────────────────────────────────────

/** Payment methods for home deliveries. Stored as stable ids; labels are Finnish. */
val PAYMENT_METHODS = listOf("cash", "card", "mobilepay", "transfer", "other")

/** Reminder lead-time options (hours before delivery) offered in the UI. */
val REMINDER_HOURS = listOf(12, 24, 48, 96)

val REKO_UNITS = listOf(
    "kpl", "kg", "g", "l", "dl", "purkki", "pussi", "rasia",
    "pkt", "nippu", "kenno", "levy", "tlk", "erä"
)

/** VAT rates offered in the product editor. Finland, 2026. */
val VAT_RATES = listOf(0.0, 10.0, 13.5, 14.0, 25.5)

/** How long a soft-deleted record stays in the trash before it is purged. */
const val TRASH_DAYS = 30L

/** How long a tombstone is kept so the other device learns about the delete. */
const val TOMBSTONE_DAYS = 180L

/**
 * The desktop stores weekday as an int (0..6) OR as an empty string "" when the
 * ring has no weekday set. Accept both, and also a numeric string, mapping blank
 * to null so importing never fails on one unset field.
 */
object WeekdaySerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("weekday", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int? {
        val jd = decoder as? JsonDecoder ?: return null
        val el = jd.decodeJsonElement()
        return when {
            el is JsonPrimitive && el.isString -> el.content.trim().toIntOrNull()
            el is JsonPrimitive -> el.intOrNull
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeString("") else encoder.encodeInt(value)
    }
}

object Reko {

    /**
     * The unit to SHOW for an order/delivery line.
     *
     * A line freezes the product's unit at the moment it is created (see the
     * order/delivery editors: `pUnit = pr.unit`). If the product's unit is later
     * corrected in the desktop catalog and re-imported, that frozen value goes
     * stale — the line keeps its old unit even though the catalog now says
     * something else. That is why a per-piece product like "FavaDuja Tulinen 200g"
     * could still render as "3 kg" instead of "3 kpl".
     *
     * So for any line that carries a catalog product_id we prefer the product's
     * current unit — exactly how [Summaries] already resolves it — and fall back
     * to the line's own unit only when the product is unknown (e.g. a free-typed
     * line with no id, or a product that has since been removed from the catalog).
     */
    fun unitFor(line: Line, products: List<Product>): String {
        if (line.product_id.isNotBlank()) {
            val p = products.firstOrNull { it.id == line.product_id }
                ?: products.firstOrNull { it.key() == line.product_id }
            if (p != null && p.unit.isNotBlank()) return p.unit
        }
        return line.unit
    }

    /** Rewrite each line's stored unit to the catalog's current unit (matched by
     *  product_id). Used when a catalog is imported so existing orders and the
     *  files exported to the desktop carry the catalog's unit, not a stale
     *  snapshot. Lines without a matching catalog product are left untouched. */
    fun alignUnits(lines: List<Line>, products: List<Product>): List<Line> {
        if (products.isEmpty()) return lines
        val byId = HashMap<String, Product>()
        for (p in products) {
            if (p.id.isNotBlank()) byId.putIfAbsent(p.id, p)
            byId.putIfAbsent(p.key(), p)
        }
        return lines.map { l ->
            val p = if (l.product_id.isNotBlank()) byId[l.product_id] else null
            if (p != null && p.unit.isNotBlank() && p.unit != l.unit) l.copy(unit = p.unit) else l
        }
    }

    fun newOrderId(): String = "ro_phone_" + UUID.randomUUID().toString().take(8)

    fun newDeliveryId(): String = "del_phone_" + UUID.randomUUID().toString().take(8)

    /** Phone-created product id. Kept in its own namespace so it can never collide
     *  with the desktop's sequential "P00001" ids. */
    fun newProductId(): String = "Pph_" + UUID.randomUUID().toString().replace("-", "").take(8)

    /** Phone-created ring id. Own namespace, same reason as [newProductId]. */
    fun newRingId(): String = "rgph_" + UUID.randomUUID().toString().replace("-", "").take(8)

    /** Phone-created customer id. Own namespace; the desktop counts C00001 up. */
    fun newCustomerId(): String = "Cph_" + UUID.randomUUID().toString().replace("-", "").take(8)

    /** Identity of this phone. Generated once and then persisted in [AppState]. */
    fun newDeviceId(): String = "phone_" + UUID.randomUUID().toString().replace("-", "").take(8)

    fun paymentLabel(p: String): String = when (p) {
        "cash" -> "Käteinen"
        "card" -> "Kortti"
        "mobilepay" -> "MobilePay"
        "transfer" -> "Tilisiirto"
        else -> "Muu"
    }

    fun nowIso(): String = LocalDateTime.now().withNano(0).toString()

    /** Parse an ISO timestamp for comparison. Returns null when unparseable, which
     *  the merge rules treat as "oldest possible" rather than failing. */
    fun parseIso(s: String): LocalDateTime? = try {
        if (s.isBlank()) null else LocalDateTime.parse(s.trim().take(19))
    } catch (e: Exception) {
        try { LocalDate.parse(s.trim().take(10)).atStartOfDay() } catch (e2: Exception) { null }
    }

    /** True when [a] is strictly newer than [b]. Blank/unparseable counts as oldest. */
    fun isNewer(a: String, b: String): Boolean {
        val pa = parseIso(a) ?: return false
        val pb = parseIso(b) ?: return true
        return pa.isAfter(pb)
    }

    /** True when [ts] is strictly after [mark]. A blank mark means "never synced",
     *  so anything with a timestamp counts as changed since. */
    fun changedSince(ts: String, mark: String): Boolean {
        if (mark.isBlank()) return true
        val t = parseIso(ts) ?: return false
        val m = parseIso(mark) ?: return true
        return t.isAfter(m)
    }

    /** Whole days between an ISO timestamp and now. Large when unparseable, so a
     *  corrupt stamp never keeps a tombstone alive forever. */
    fun daysSince(iso: String): Long {
        val t = parseIso(iso) ?: return Long.MAX_VALUE
        return java.time.Duration.between(t, LocalDateTime.now()).toDays()
    }

    fun weekdayLabel(wd: Int?): String = when (wd) {
        0 -> "ma"; 1 -> "ti"; 2 -> "ke"; 3 -> "to"; 4 -> "pe"; 5 -> "la"; 6 -> "su"; else -> ""
    }

    fun weekdayFull(wd: Int?): String = when (wd) {
        0 -> "maanantai"; 1 -> "tiistai"; 2 -> "keskiviikko"; 3 -> "torstai"
        4 -> "perjantai"; 5 -> "lauantai"; 6 -> "sunnuntai"; else -> "ei päivää"
    }

    fun parityLabel(p: String): String = when (p) {
        "even" -> "parillinen vko"; "odd" -> "pariton vko"; else -> "joka viikko"
    }

    /** Next [count] pickup dates (ISO) for a ring, by weekday + week parity. */
    fun upcomingDates(ring: Ring, count: Int = 8): List<String> {
        val wd = ring.weekday ?: return emptyList()
        val out = ArrayList<String>()
        val today = LocalDate.now()
        var i = 0
        while (out.size < count && i < 400) {
            val day = today.plusDays(i.toLong())
            // java DayOfWeek: Mon=1..Sun=7 ; desktop weekday: Mon=0..Sun=6
            if (day.dayOfWeek.value == wd + 1) {
                val wk = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                val ok = ring.parity == "weekly" ||
                        (ring.parity == "even" && wk % 2 == 0) ||
                        (ring.parity == "odd" && wk % 2 == 1)
                if (ok) out.add(day.toString())
            }
            i++
        }
        return out
    }

    fun money(v: Double, cur: String): String =
        String.format(java.util.Locale.US, "%,.2f", v)
            .replace(",", "\u0000").replace(".", ",").replace("\u0000", " ") + " " + cur

    /** Parse a Finnish-or-English decimal the user typed ("4,50" or "4.50").
     *  Tolerates normal, non-breaking and narrow thousands separators. */
    fun parseDecimal(s: String): Double? =
        s.trim()
            .replace(" ", "")
            .replace("\u00a0", "")
            .replace("\u202f", "")
            .replace(",", ".")
            .toDoubleOrNull()
}
