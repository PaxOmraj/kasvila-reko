package fi.kasvila.reko

import java.time.DayOfWeek
import java.time.LocalDate

/** One product's aggregated total across a set of orders. */
data class ProductTotal(
    val name: String,
    val unit: String,
    val qty: Double,
    val amount: Double
)

/** Summary of a single distribution (one ring on one date). */
data class DaySummary(
    val ringName: String,
    val date: String,
    val orderCount: Int,
    val customerCount: Int,
    val totalAmount: Double,
    val productTotals: List<ProductTotal>,
    val orders: List<Order>
)

/** A distribution reference used in the weekly summary list. */
data class DistRef(
    val ringId: String,
    val ringName: String,
    val date: String,
    val orderCount: Int,
    val totalAmount: Double
)

/** Summary of all distributions in one week (Mon–Sun). */
data class WeekSummary(
    val monday: LocalDate,
    val distributions: List<DistRef>,
    val orderCount: Int,
    val totalAmount: Double,
    val productTotals: List<ProductTotal>
)

object Summaries {

    /** Normalize a product name/alias for tolerant matching. */
    private fun norm(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Aggregate all order lines by product. A line's identity is resolved so the
     * SAME product always merges into one row, no matter how the line was created:
     *  - a line with a product_id is keyed by that id;
     *  - a line without one (e.g. added manually) is resolved against the catalog
     *    by its name or alias to the same id;
     *  - only if the catalog has no match do we fall back to the normalized name.
     * The displayed name/unit prefer the catalog's current values, so a product
     * renamed in the catalog still shows as one row under its current name.
     */
    /**
     * The aggregation core. Works on a flat list of order/delivery lines so the
     * SAME logic serves REKO orders and home deliveries (kuljetustilaukset) — both
     * carry [Line] rows, so there is exactly one place that resolves product
     * identity, name, unit and price. Callers pass already-scoped, non-cancelled
     * lines; this function does no filtering of its own.
     */
    private fun productTotalsOfLines(lines: List<Line>, products: List<Product>): List<ProductTotal> {
        // Catalog lookups. Canonical identity = Product.key() (id, or name if id blank).
        val byKey = HashMap<String, Product>()
        val nameToKey = HashMap<String, String>()
        for (p in products) {
            val k = p.key()
            byKey.putIfAbsent(k, p)
            val n = norm(p.name)
            if (n.isNotEmpty()) nameToKey.putIfAbsent(n, k)
            for (a in p.aliases) {
                val an = norm(a)
                if (an.isNotEmpty()) nameToKey.putIfAbsent(an, k)
            }
        }
        val map = LinkedHashMap<String, ProductTotal>()
        for (l in lines) {
            val key = when {
                l.product_id.isNotBlank() -> l.product_id
                else -> nameToKey[norm(l.name)] ?: ("name:" + norm(l.name))
            }
            val catalog = byKey[key]
            val name = catalog?.name ?: l.name
            val unit = catalog?.unit ?: l.unit
            val amount = l.qty * l.unit_price
            val cur = map[key]
            map[key] = if (cur == null)
                ProductTotal(name, unit, l.qty, amount)
            else
                cur.copy(qty = cur.qty + l.qty, amount = cur.amount + amount)
        }
        return map.values.sortedWith(compareByDescending<ProductTotal> { it.qty }.thenBy { it.name.lowercase() })
    }

    private fun productTotals(orders: List<Order>, products: List<Product>): List<ProductTotal> =
        productTotalsOfLines(orders.flatMap { it.lines }, products)

    /** Public aggregation for an arbitrary order set (e.g. a whole delivery day). */
    fun totalsFor(orders: List<Order>, products: List<Product>): List<ProductTotal> =
        productTotalsOfLines(orders.filter { !it.cancelled && !it.deleted }.flatMap { it.lines }, products)

    /** Product totals for a set of home deliveries (kuljetustilaukset), excluding cancelled. */
    fun deliveryTotalsFor(deliveries: List<Delivery>, products: List<Product>): List<ProductTotal> =
        productTotalsOfLines(deliveries.filter { !it.cancelled && !it.deleted }.flatMap { it.lines }, products)

    /** Combined totals across REKO orders and home deliveries, excluding cancelled on both.
     *  This is the "what to prepare in total" figure for a day or a week. */
    fun combinedTotalsFor(
        orders: List<Order>,
        deliveries: List<Delivery>,
        products: List<Product>
    ): List<ProductTotal> =
        productTotalsOfLines(
            orders.filter { !it.cancelled && !it.deleted }.flatMap { it.lines } +
                deliveries.filter { !it.cancelled && !it.deleted }.flatMap { it.lines },
            products
        )

    /** Home deliveries scheduled for a given day (by deliveryDate), excluding cancelled. */
    fun deliveriesForDay(state: AppState, date: String): List<Delivery> =
        state.liveDeliveries
            .filter { it.deliveryDate == date && !it.cancelled }
            .sortedWith(compareBy({ it.deliveryTime.ifBlank { "99:99" } }, { it.customer.lowercase() }))

    /** Home deliveries whose deliveryDate falls in the Mon–Sun week, excluding cancelled.
     *  Deliveries with a blank/unparseable deliveryDate are not scheduled to any week and
     *  are intentionally left out (they remain visible in the Kuljetus list). */
    fun deliveriesForWeek(state: AppState, monday: LocalDate): List<Delivery> {
        val sunday = monday.plusDays(6)
        return state.liveDeliveries
            .filter {
                if (it.cancelled) return@filter false
                val d = try { LocalDate.parse(it.deliveryDate) } catch (e: Exception) { null }
                d != null && !d.isBefore(monday) && !d.isAfter(sunday)
            }
            .sortedWith(compareBy({ it.deliveryDate }, { it.deliveryTime.ifBlank { "99:99" } }, { it.customer.lowercase() }))
    }

    fun day(state: AppState, ringId: String, date: String): DaySummary {
        val orders = state.liveOrders.filter { it.group_id == ringId && it.date == date && !it.cancelled }
        val ringName = state.rings.firstOrNull { it.id == ringId }?.name ?: ringId
        val customers = orders.map { it.customer.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet().size
        return DaySummary(
            ringName = ringName,
            date = date,
            orderCount = orders.size,
            customerCount = customers,
            totalAmount = orders.sumOf { it.total },
            productTotals = productTotals(orders, state.products),
            orders = orders.sortedBy { it.customer.lowercase() }
        )
    }

    fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    /** REKO orders whose date falls in the Mon–Sun week, excluding cancelled. */
    fun ordersForWeek(state: AppState, monday: LocalDate): List<Order> {
        val sunday = monday.plusDays(6)
        return state.liveOrders.filter {
            val d = try { LocalDate.parse(it.date) } catch (e: Exception) { null }
            d != null && !d.isBefore(monday) && !d.isAfter(sunday) && !it.cancelled
        }
    }

    fun week(state: AppState, monday: LocalDate): WeekSummary {
        val inWeek = ordersForWeek(state, monday)
        val dists = inWeek.groupBy { it.group_id to it.date }
            .map { (k, os) ->
                DistRef(
                    ringId = k.first,
                    ringName = state.rings.firstOrNull { it.id == k.first }?.name ?: k.first,
                    date = k.second,
                    orderCount = os.size,
                    totalAmount = os.sumOf { it.total }
                )
            }
            .sortedWith(compareBy({ it.date }, { it.ringName.lowercase() }))
        return WeekSummary(
            monday = monday,
            distributions = dists,
            orderCount = inWeek.size,
            totalAmount = inWeek.sumOf { it.total },
            productTotals = productTotals(inWeek, state.products)
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  REKO summary (period / ring / product / customer)
// ════════════════════════════════════════════════════════════════════════════

/** One row in a ranked list: a name, a number, and a short explanation of it. */
data class RankRow(val name: String, val value: Double, val note: String = "")

/** Selectable reporting window. "ALL" deliberately includes undated records. */
enum class SummaryPeriod(val label: String) {
    ALL("Koko historia"),
    MONTH("Tämä kuukausi"),
    THREE("Viimeiset 3 kk"),
    YEAR("Tämä vuosi")
}

/**
 * Everything the REKO summary screen shows, computed in one pass.
 *
 * A port of the desktop's `_compute_summary`. Cancelled and deleted orders are
 * excluded everywhere — an order the customer cancelled is not revenue, and
 * including it would make the phone's figures disagree with the desktop's, which
 * is worse than having no figures at all.
 */
data class RekoSummary(
    val period: SummaryPeriod,
    val ringFilter: String,            // "" = all
    val total: Double,
    val paidTotal: Double,
    val unpaidTotal: Double,
    val orderCount: Int,
    val customerCount: Int,
    val distributionCount: Int,
    val pickedCount: Int,
    val notPickedCount: Int,
    val homeCount: Int,
    val homeTotal: Double,
    val perRing: List<RankRow>,
    val topProducts: List<RankRow>,
    val topCustomers: List<RankRow>,
    val perMonth: List<RankRow>,
    val unpaidByCustomer: List<RankRow>
) {
    val grandTotal: Double get() = total + homeTotal
}

object RekoReports {

    private fun inPeriod(iso: String, period: SummaryPeriod): Boolean {
        if (period == SummaryPeriod.ALL) return true
        // An undated record cannot belong to any dated window. Including it in
        // every window would make one month, three months and a year all show the
        // same number.
        val d = try { LocalDate.parse(iso.take(10)) } catch (e: Exception) { null } ?: return false
        val today = LocalDate.now()
        return when (period) {
            SummaryPeriod.MONTH -> d.year == today.year && d.monthValue == today.monthValue
            SummaryPeriod.YEAR -> d.year == today.year
            SummaryPeriod.THREE -> {
                val diff = (today.year - d.year) * 12 + (today.monthValue - d.monthValue)
                diff in 0..2
            }
            SummaryPeriod.ALL -> true
        }
    }

    /**
     * The orders and deliveries a period+ring filter selects.
     *
     * Exposed separately from [compute] so an export can send exactly the rows
     * the screen is showing. A CSV that silently covers a different set than the
     * figures above it is worse than no CSV.
     */
    fun filtered(
        state: AppState,
        period: SummaryPeriod = SummaryPeriod.ALL,
        ringId: String = ""
    ): Pair<List<Order>, List<Delivery>> {
        val orders = state.liveOrders.filter {
            !it.cancelled &&
                (ringId.isBlank() || it.group_id == ringId) &&
                inPeriod(it.date, period)
        }
        val deliveries = state.liveDeliveries.filter {
            !it.cancelled && ringId.isBlank() && inPeriod(it.deliveryDate, period)
        }
        return orders to deliveries
    }

    fun compute(
        state: AppState,
        period: SummaryPeriod = SummaryPeriod.ALL,
        ringId: String = ""
    ): RekoSummary {
        val ringName = HashMap<String, String>()
        state.rings.forEach { ringName[it.id] = it.name.ifBlank { it.id } }

        val (orders, deliveries) = filtered(state, period, ringId)

        var total = 0.0
        var paidTotal = 0.0
        var unpaidTotal = 0.0
        var picked = 0
        var notPicked = 0
        val perRing = HashMap<String, Double>()
        val perCustomer = HashMap<String, Double>()
        val perMonth = HashMap<String, Double>()
        val unpaidBy = HashMap<String, Double>()
        val customers = HashSet<String>()
        val dists = HashSet<String>()

        for (o in orders) {
            val amount = o.total
            total += amount
            if (o.paid) paidTotal += amount else unpaidTotal += amount
            if (o.picked) picked++
            if (o.notPicked) notPicked++

            val rn = ringName[o.group_id] ?: o.group_id
            perRing[rn] = (perRing[rn] ?: 0.0) + amount

            val cn = o.customer.trim()
            if (cn.isNotEmpty()) {
                customers.add(cn.lowercase())
                perCustomer[cn] = (perCustomer[cn] ?: 0.0) + amount
                if (!o.paid && amount > 0) unpaidBy[cn] = (unpaidBy[cn] ?: 0.0) + amount
            }
            if (o.date.length >= 7) {
                val m = o.date.take(7)
                perMonth[m] = (perMonth[m] ?: 0.0) + amount
            }
            if (o.group_id.isNotBlank() && o.date.isNotBlank()) dists.add(o.group_id + "|" + o.date)
        }

        val products = if (ringId.isBlank())
            Summaries.combinedTotalsFor(orders, deliveries, state.products)
        else
            Summaries.totalsFor(orders, state.products)

        fun rank(map: Map<String, Double>, limit: Int) =
            map.entries.sortedByDescending { it.value }.take(limit)
                .map { RankRow(it.key, it.value) }

        return RekoSummary(
            period = period,
            ringFilter = ringId,
            total = total,
            paidTotal = paidTotal,
            unpaidTotal = unpaidTotal,
            orderCount = orders.size,
            customerCount = customers.size,
            distributionCount = dists.size,
            pickedCount = picked,
            notPickedCount = notPicked,
            homeCount = deliveries.size,
            homeTotal = deliveries.sumOf { it.total },
            perRing = rank(perRing, 20),
            topProducts = products.take(15).map {
                RankRow(it.name, it.amount, "${trimQty(it.qty)} ${it.unit}")
            },
            topCustomers = rank(perCustomer, 15),
            perMonth = perMonth.entries.sortedByDescending { it.key }.take(12)
                .map { RankRow(monthLabel(it.key), it.value) },
            unpaidByCustomer = rank(unpaidBy, 20)
        )
    }

    private fun trimQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")

    private fun monthLabel(ym: String): String {
        val parts = ym.split("-")
        if (parts.size < 2) return ym
        val months = listOf(
            "tammikuu", "helmikuu", "maaliskuu", "huhtikuu", "toukokuu", "kesäkuu",
            "heinäkuu", "elokuu", "syyskuu", "lokakuu", "marraskuu", "joulukuu"
        )
        val m = parts[1].toIntOrNull() ?: return ym
        if (m !in 1..12) return ym
        return "${months[m - 1]} ${parts[0]}"
    }
}
