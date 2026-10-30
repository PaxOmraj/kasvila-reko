package fi.kasvila.reko

import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * REKO routes: which rings you visit on the same trip, in what order, and where
 * two of them clash.
 *
 * A port of the desktop's route logic (`_route_buckets`, `_conflict_ids`,
 * `reko_time_overlap`) so the phone can answer "what does Thursday look like"
 * without the desktop. The clash check is the reason this is worth having on the
 * phone at all: a ring added in the field is exactly when you are least likely to
 * notice you have just double-booked yourself.
 */

/** One stop on a route. */
data class RouteStop(
    val ring: Ring,
    val conflicting: Boolean
)

/** All the rings sharing one weekday + week parity, i.e. one trip. */
data class Route(
    val weekday: Int,
    val parity: String,
    val stops: List<RouteStop>,
    val nextDate: String
) {
    val title: String get() = Reko.weekdayFull(weekday).replaceFirstChar { it.uppercase() } +
        "  ·  " + Reko.parityLabel(parity)
    val hasConflict: Boolean get() = stops.any { it.conflicting }
}

object Routes {

    /** Minutes past midnight for a start/end pair, or null when unparseable. */
    internal fun parseTimeRange(s: String): Pair<Int, Int>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val parts = t.split(Regex("\\s*[-–—]\\s*"), limit = 2)

        fun toMin(raw: String): Int? {
            val x = raw.trim()
            Regex("^(\\d{1,2})[.:](\\d{2})$").find(x)?.let {
                val h = it.groupValues[1].toInt()
                val m = it.groupValues[2].toInt()
                if (h > 23 || m > 59) return null
                return h * 60 + m
            }
            Regex("^(\\d{1,2})$").find(x)?.let {
                val h = it.groupValues[1].toInt()
                if (h > 23) return null
                return h * 60
            }
            return null
        }

        val a = toMin(parts[0]) ?: return null
        var b = if (parts.size > 1) toMin(parts[1]) else null
        // A single time is a point, not a window — otherwise every ring with just
        // "17:00" would appear to clash with every other one at 17:00 all evening.
        if (b == null) b = a + 1
        if (b < a) b = a + 1
        return a to b
    }

    /** Touching ends (one finishes exactly as the next starts) do NOT overlap. */
    internal fun timesOverlap(t1: String, t2: String): Boolean {
        val a = parseTimeRange(t1)
        val b = parseTimeRange(t2)
        if (a == null || b == null) {
            // Unparseable on either side: fall back to an exact string match, which
            // at least catches two rings both labelled "iltapäivä".
            val s1 = t1.trim()
            return s1.isNotEmpty() && s1 == t2.trim()
        }
        return a.first < b.second && b.first < a.second
    }

    /**
     * Ring ids that clash: same weekday and parity, overlapping time windows, and
     * NOT split between two different named handlers. The handler exemption
     * matters — a family operation routinely runs two pickups at once with two
     * people, and flagging that as an error every week would train the user to
     * ignore the warning entirely.
     */
    fun conflictIds(rings: List<Ring>): Set<String> {
        val buckets = HashMap<Pair<Int, String>, MutableList<Ring>>()
        for (r in rings) {
            val wd = r.weekday ?: continue
            if (r.time.isBlank()) continue
            buckets.getOrPut(wd to r.parity) { ArrayList() }.add(r)
        }
        val out = HashSet<String>()
        for (group in buckets.values) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val a = group[i]
                    val b = group[j]
                    if (!timesOverlap(a.time, b.time)) continue
                    val ha = a.handler.trim().lowercase()
                    val hb = b.handler.trim().lowercase()
                    if (ha.isNotEmpty() && hb.isNotEmpty() && ha != hb) continue
                    out.add(a.id); out.add(b.id)
                }
            }
        }
        return out
    }

    /** Rings grouped into routes, ordered Mon→Sun then weekly/even/odd. */
    fun build(rings: List<Ring>): Pair<List<Route>, List<Ring>> {
        val usable = rings.filter { !it.deleted && it.active }
        val conflicts = conflictIds(usable)
        val unscheduled = usable.filter { it.weekday == null }

        val buckets = HashMap<Pair<Int, String>, MutableList<Ring>>()
        for (r in usable) {
            val wd = r.weekday ?: continue
            buckets.getOrPut(wd to r.parity) { ArrayList() }.add(r)
        }

        val parityOrder = mapOf("weekly" to 0, "even" to 1, "odd" to 2)
        val routes = buckets.entries
            .sortedWith(compareBy({ it.key.first }, { parityOrder[it.key.second] ?: 9 }))
            .map { (key, list) ->
                val sorted = list.sortedWith(
                    compareBy(
                        // Rings with no time sort last: "~" is above every digit.
                        { it.time.ifBlank { "~" } },
                        { it.name.lowercase() }
                    )
                )
                Route(
                    weekday = key.first,
                    parity = key.second,
                    stops = sorted.map { RouteStop(it, it.id in conflicts) },
                    nextDate = nextDateFor(key.first, key.second)
                )
            }
        return routes to unscheduled.sortedBy { it.name.lowercase() }
    }

    /** The next calendar date matching a weekday + parity, or "" if none in a year. */
    fun nextDateFor(weekday: Int, parity: String): String {
        val today = LocalDate.now()
        for (i in 0..400) {
            val day = today.plusDays(i.toLong())
            if (day.dayOfWeek.value != weekday + 1) continue
            val wk = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val ok = parity == "weekly" ||
                (parity == "even" && wk % 2 == 0) ||
                (parity == "odd" && wk % 2 == 1)
            if (ok) return day.toString()
        }
        return ""
    }

    /**
     * A maps URL for one route: every stop as a waypoint in visiting order, so
     * the whole evening opens as a single navigation rather than one address at
     * a time. Stops with no usable address are skipped rather than turning into
     * a bogus waypoint that sends you to the wrong town.
     */
    fun mapsUrl(route: Route): String? {
        val points = route.stops
            .map { it.ring }
            .map { r ->
                val a = r.address.trim()
                val l = r.location.trim()
                val c = r.city.trim()
                when {
                    a.isNotBlank() && c.isNotBlank() && !a.contains(c, true) -> "$a, $c"
                    a.isNotBlank() -> a
                    l.isNotBlank() && c.isNotBlank() -> "$l, $c"
                    l.isNotBlank() -> l
                    else -> ""
                }
            }
            .filter { it.isNotBlank() }
        if (points.isEmpty()) return null
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val destination = enc(points.last())
        val waypoints = points.dropLast(1)
        return buildString {
            append("https://www.google.com/maps/dir/?api=1&destination=")
            append(destination)
            if (waypoints.isNotEmpty()) {
                append("&waypoints=")
                append(waypoints.joinToString("%7C") { enc(it) })
            }
            append("&travelmode=driving")
        }
    }

    /** A single stop's address, for the per-stop map button. */
    fun mapsUrlFor(ring: Ring): String? {
        val target = listOf(ring.address, ring.location)
            .firstOrNull { it.isNotBlank() } ?: return null
        val full = if (ring.city.isNotBlank() && !target.contains(ring.city, true))
            "$target, ${ring.city}" else target
        return "https://www.google.com/maps/search/?api=1&query=" +
            java.net.URLEncoder.encode(full, "UTF-8")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Pickups: the ring's pattern, corrected by real-world exceptions
// ════════════════════════════════════════════════════════════════════════════

/**
 * When a ring's pickups actually happen.
 *
 * [Reko.upcomingDates] generates the *pattern* — every Thursday on even weeks,
 * forever. That is what a ring intends, not what happens. A pickup gets
 * cancelled for midsummer, moved to the Friday, or held somewhere else because
 * the car park is dug up. Those live in [Distribution] records, and this object
 * is the one place that combines the two.
 *
 * Everything that asks "when is the next pickup" goes through here, so a
 * cancelled day cannot keep appearing in a date picker somewhere that was
 * written before exceptions existed.
 */
object Pickups {

    /**
     * Upcoming pickup dates for a ring, with exceptions applied.
     *
     * Cancelled pattern dates are removed and one-off extra dates are added, so
     * a pickup moved from Thursday to Friday shows the Friday and not the
     * Thursday — which is what the customer was told.
     */
    fun upcoming(state: AppState, ring: Ring, count: Int = 8): List<String> {
        val today = java.time.LocalDate.now().toString()
        val mine = state.distributions.filter { !it.deleted && it.group_id == ring.id }
        val cancelled = mine.filter { it.cancelled }.map { it.date }.toSet()

        // Generate extra pattern dates because some will be dropped.
        val pattern = Reko.upcomingDates(ring, count * 2 + 4)
        // Dates that exist ONLY because someone created an exception for them —
        // a pickup moved onto a day the weekday rule would never produce.
        val extras = mine
            .filter { !it.cancelled && it.date >= today && it.date.isNotBlank() }
            .map { it.date }

        return (pattern + extras)
            .distinct()
            .filter { it !in cancelled }
            .sorted()
            .take(count)
    }

    /** The next pickup date for a ring, or "" if none in the next year. */
    fun nextDate(state: AppState, ring: Ring): String =
        upcoming(state, ring, 1).firstOrNull() ?: ""

    /** The time this particular pickup runs at: the exception, else the ring's. */
    fun effectiveTime(state: AppState, ring: Ring?, date: String): String {
        val d = ring?.let { state.distributionFor(it.id, date) }
        return d?.time?.takeIf { it.isNotBlank() } ?: (ring?.time ?: "")
    }

    /** The place this particular pickup is held: the exception, else the ring's. */
    fun effectiveAddress(state: AppState, ring: Ring?, date: String): String {
        val d = ring?.let { state.distributionFor(it.id, date) }
        return d?.address?.takeIf { it.isNotBlank() } ?: (ring?.address ?: "")
    }

    /** True when this ring has no pickup on this date. */
    fun isCancelled(state: AppState, ringId: String, date: String): Boolean =
        state.distributionFor(ringId, date)?.cancelled == true

    /**
     * Exceptions worth showing on a ring's page: from today onwards, anything a
     * person deliberately said about a pickup. Past exceptions are history and
     * only clutter the view.
     */
    fun upcomingExceptions(state: AppState, ringId: String): List<Distribution> {
        val today = java.time.LocalDate.now().toString()
        return state.distributions
            .filter { !it.deleted && it.group_id == ringId && it.date >= today && it.isNoteworthy() }
            .sortedBy { it.date }
    }

    /**
     * Exceptions across every ring, for the home screen banner.
     *
     * A cancelled pickup is the one thing you must not find out about on the day,
     * so it is surfaced where it cannot be missed rather than on the ring's own
     * page where you would only look if you already suspected something.
     */
    fun upcomingCancellations(state: AppState, withinDays: Long = 21): List<Pair<Ring, Distribution>> {
        val today = java.time.LocalDate.now()
        val limit = today.plusDays(withinDays).toString()
        val ringById = state.rings.associateBy { it.id }
        return state.distributions
            .filter {
                !it.deleted && it.cancelled &&
                    it.date >= today.toString() && it.date <= limit
            }
            .mapNotNull { d -> ringById[d.group_id]?.let { it to d } }
            .sortedBy { it.second.date }
    }
}
