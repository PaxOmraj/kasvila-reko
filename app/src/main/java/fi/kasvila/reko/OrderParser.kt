package fi.kasvila.reko

import kotlin.math.min
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** One recognised order line from pasted free text. */
data class ParsedLine(
    val qty: Double,
    val unit: String,
    val unitPrice: Double,
    val productId: String,
    val name: String,
    val confidence: String,   // "high" | "medium"
    val weightG: Double?,
    val raw: String
)

data class ParseResult(
    val lines: List<ParsedLine>,
    val unmatched: List<String>,
    val customer: String? = null
)

/**
 * Offline, rule-based parser for pasted Facebook / free-text REKO orders.
 * Mirrors the desktop parse_reko_order_text so both platforms behave the same.
 * Nothing is committed here — the UI shows a preview and the user confirms.
 */
object OrderParser {

    // Finnish spelled-out numbers customers use in orders.
    private val NUM_WORDS = linkedMapOf(
        "yksi" to 1, "yks" to 1, "kaksi" to 2, "kaks" to 2, "kolme" to 3,
        "nelj\u00e4" to 4, "nelja" to 4, "viisi" to 5, "kuusi" to 6,
        "seitsem\u00e4n" to 7, "seitseman" to 7, "kahdeksan" to 8,
        "yhdeks\u00e4n" to 9, "yhdeksan" to 9, "kymmenen" to 10, "pari" to 2
    )

    private val PUNCT = Regex("[()\\[\\]{}.,;:!?\"'*/\\\\_\\-]+")
    private val SPACES = Regex("\\s+")
    /**
     * Fragment separators.
     *
     * A comma between two digits is a DECIMAL SEPARATOR, not a list separator.
     * Splitting on it turned "1,5 kg perunoita" into "1" and "5 kg perunoita",
     * and the order came out as five kilos — the customer's comma quietly
     * tripled their order. So a comma splits only when it is NOT surrounded by
     * digits on both sides.
     */
    private val SEG_SPLIT = Regex(
        "[\\n;]+|(?<!\\d),|,(?!\\d)|\\s+ja\\s+|\\s*&\\s*|\\s*\\+\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * Finnish package words customers type between the number and the product
     * ("2 purkkia hilloa", "kolme pkt jauhelihaa"). Matched with a permissive
     * suffix because Finnish inflects them (purkki / purkkia / purkkeja).
     */
    private val UNIT_WORDS = Regex(
        "\\b(?:kpl|kappalet\\w*|kappale|purkk\\w*|purki\\w*|puss\\w*|rasia\\w*|rasio\\w*|" +
            "pkt|pakett\\w*|paketti|nipp\\w*|kenno\\w*|kenn\\w*|lev\\w*|tlk|prk|" +
            "t\u00f6lkk\\w*|t\u00f6lki\\w*|er\u00e4\u00e4|eri\u00e4|laatikk\\w*|laatiko\\w*|" +
            "s\u00e4kk\\w*|s\u00e4ki\\w*)\\b",
        RegexOption.IGNORE_CASE
    )
    private val KIITOS = Regex("\\bkiitos\\b[!.\\s]*", RegexOption.IGNORE_CASE)
    private val WEIGHT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kg|g)\\b", RegexOption.IGNORE_CASE)
    private val COUNT_X1 = Regex("(\\d+)\\s*[x\u00d7]\\b")
    private val COUNT_X2 = Regex("[x\u00d7]\\s*(\\d+)\\b")
    private val COUNT_PLAIN = Regex("(?<!\\d)(\\d+)(?!\\s*(?:kg|g)\\b)", RegexOption.IGNORE_CASE)

    private fun norm(t: String): String {
        var s = t.lowercase()
        s = PUNCT.replace(s, " ")
        s = SPACES.replace(s, " ").trim()
        return s
    }

    private fun segments(text: String): List<String> {
        val t = KIITOS.replace(text, " ")
        return SEG_SPLIT.split(t).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private data class Qty(val count: Int?, val weightG: Double?, val leftover: String)

    private fun extractQty(seg: String): Qty {
        var s = " $seg "
        var weight: Double? = null
        WEIGHT.find(s)?.let { m ->
            val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            val u = m.groupValues[2].lowercase()
            weight = if (u == "kg") v * 1000.0 else v
            s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
        }
        var count: Int? = null
        val mx = COUNT_X1.find(s) ?: COUNT_X2.find(s)
        if (mx != null) {
            count = mx.groupValues[1].toIntOrNull()
            s = s.substring(0, mx.range.first) + " " + s.substring(mx.range.last + 1)
        } else {
            val mp = COUNT_PLAIN.find(s)
            if (mp != null) {
                count = mp.groupValues[1].toIntOrNull()
                s = s.substring(0, mp.range.first) + " " + s.substring(mp.range.last + 1)
            }
        }
        if (count == null) {
            val nrm = norm(s)
            for ((w, n) in NUM_WORDS) {
                if (Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(nrm)) {
                    count = n
                    s = Regex("\\b" + Regex.escape(w) + "\\b", RegexOption.IGNORE_CASE)
                        .replaceFirst(s, " ")
                    break
                }
            }
        }
        // Strip ONE package word — but only when a count was actually found.
        // A bare "erä" or "levy" can be part of a product name ("Suklaalevy"),
        // and removing it unprompted would quietly break the match; right after
        // a number it is a unit, not a name.
        if (count != null) {
            UNIT_WORDS.find(s)?.let { m ->
                s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
            }
        }
        return Qty(count, weight, norm(s))
    }

    /** Dice coefficient on character bigrams — a stable stand-in for difflib's
     *  ratio for short product strings. */
    private fun seqRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        fun bigrams(s: String): HashMap<String, Int> {
            val m = HashMap<String, Int>()
            for (i in 0 until s.length - 1) {
                val bg = s.substring(i, i + 2)
                m[bg] = (m[bg] ?: 0) + 1
            }
            return m
        }
        val ba = bigrams(a); val bb = bigrams(b)
        var overlap = 0
        for ((bg, c) in ba) overlap += min(c, bb[bg] ?: 0)
        val total = (a.length - 1) + (b.length - 1)
        return if (total > 0) 2.0 * overlap / total else 0.0
    }

    private data class Match(val product: Product?, val score: Double)

    private fun matchProduct(text: String, catalog: List<Product>): Match {
        val n = norm(text)
        if (n.isEmpty()) return Match(null, 0.0)
        val nTokens = n.split(" ").toSet()
        var best: Product? = null
        var bestScore = 0.0
        for (prod in catalog) {
            val cands = ArrayList<String>()
            cands.add(prod.name)
            cands.addAll(prod.aliases)
            for (c in cands) {
                val cn = norm(c)
                if (cn.isEmpty()) continue
                if (cn == n) return Match(prod, 1.0)
                val score: Double
                if (n.contains(cn) || cn.contains(n)) {
                    // Longer (more specific) alias scores higher, so
                    // "favado aleppo" beats a bare "favado".
                    val frac = min(1.0, cn.length.toDouble() / max(1, n.length))
                    score = 0.80 + 0.19 * frac
                } else {
                    val ct = cn.split(" ").toSet()
                    val inter = nTokens.intersect(ct).size.toDouble()
                    val uni = nTokens.union(ct).size.toDouble()
                    val jac = if (uni > 0) inter / uni else 0.0
                    val ratio = seqRatio(cn, n)
                    score = max(jac, ratio)
                }
                if (score > bestScore) { best = prod; bestScore = score }
            }
        }
        return Match(best, bestScore)
    }

    /** Try to pull a customer name from spoken/typed text (used by voice input).
     *  Conservative: only explicit cues, and never a name that is actually a
     *  catalog product. Returns (customerName?, remainingText). */
    fun extractCustomer(text: String, catalog: List<Product>): Pair<String?, String> {
        val t = text.trim()
        // "asiakas Venla, ..." / "asiakkaalle Venla: ..."
        Regex("^asiak\\p{L}*\\s+(\\p{L}[\\p{L}\\-]+(?:\\s+\\p{L}[\\p{L}\\-]+)?)\\s*[:,\\-]?\\s*(.*)$",
            RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return Pair(cap(m.groupValues[1]), m.groupValues[2].trim())
        }
        // "Venla: ..." / "Venla, ..." (leading capitalised name + separator)
        Regex("^(\\p{Lu}[\\p{L}\\-]+(?:\\s+\\p{Lu}[\\p{L}\\-]+)?)\\s*[:,]\\s*(.+)$")
            .find(t)?.let { m ->
                val name = m.groupValues[1].trim()
                if (!isProduct(name, catalog) && name.lowercase() !in NUM_WORDS.keys)
                    return Pair(cap(name), m.groupValues[2].trim())
            }
        // Allative "Venlalle yksi ..." (strip -lle)
        Regex("^(\\p{Lu}[\\p{L}]{2,})lle\\s+(.+)$").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (!isProduct(name, catalog)) return Pair(cap(name), m.groupValues[2].trim())
        }
        return Pair(null, text)
    }

    private fun cap(s: String): String =
        s.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun isProduct(name: String, catalog: List<Product>): Boolean =
        matchProduct(norm(name), catalog).score >= 0.88

    /** Merge device-only local aliases (product id -> extra names) into the
     *  catalog used for matching. */
    fun mergeAliases(products: List<Product>, local: Map<String, List<String>>): List<Product> {
        if (local.isEmpty()) return products
        return products.map { p ->
            val extra = local[p.key()]
            if (extra.isNullOrEmpty()) p
            else p.copy(aliases = (p.aliases + extra).distinct())
        }
    }

    /** The product-name part of a fragment (quantity words stripped). Used when
     *  the user teaches an unmatched fragment as a new alias. */
    fun productPart(fragment: String): String = extractQty(fragment).leftover

    fun parse(text: String, catalog: List<Product>): ParseResult {
        val lines = ArrayList<ParsedLine>()
        val unmatched = ArrayList<String>()
        for (seg in segments(text)) {
            val q = extractQty(seg)
            val m = matchProduct(q.leftover, catalog)
            val conf = when {
                m.score >= 0.88 -> "high"
                m.score >= 0.60 -> "medium"
                else -> "none"
            }
            val prod = m.product
            if (prod == null || conf == "none") {
                unmatched.add(seg.trim())
                continue
            }
            val unit = prod.unit
            val qty: Double = when {
                q.count != null -> q.count.toDouble()
                q.weightG != null && unit == "kg" ->
                    Math.round(q.weightG!! / 1000.0 * 1000.0) / 1000.0
                else -> 1.0
            }
            lines.add(
                ParsedLine(
                    qty = qty,
                    unit = unit,
                    unitPrice = prod.price,
                    productId = prod.id,
                    name = prod.name,
                    confidence = conf,
                    weightG = q.weightG,
                    raw = seg.trim()
                )
            )
        }
        return ParseResult(lines, unmatched)
    }

    // ── AI-assisted parsing (same catalog, uses the chosen chat model) ──

    /** Build (system, user) messages asking a chat model to extract order lines
     *  and return STRICT JSON referencing catalog ids. Mirrors the desktop. */
    fun buildAiMessages(text: String, catalog: List<Product>): Pair<String, String> {
        val cat = JSONArray()
        for (p in catalog) {
            val al = JSONArray()
            for (a in p.aliases) al.put(a)
            cat.put(
                JSONObject().put("id", p.key()).put("name", p.name)
                    .put("aliases", al).put("unit", p.unit)
            )
        }
        val system =
            "You extract REKO food-order lines from a customer's free-text message " +
            "(often Finnish, informal, inflected, with variant words like " +
            "'mieto'/'tulinen'/'aleppo'). You get a product catalog as JSON " +
            "(id, name, aliases, unit). Match each ordered item to exactly ONE " +
            "catalog product by its id. Quantities may be '1x', '3 x', Finnish " +
            "number words (yksi=1, kaksi=2, kolme=3, nelja=4, viisi=5 ...), or a " +
            "weight ('270 g', '1,5 kg'). If a weight is given AND the product unit " +
            "is 'kg', qty is that weight in kilograms; otherwise qty is the item " +
            "count (default 1). Return ONLY a JSON object, no prose and no markdown " +
            "fences, of the form: {\"customer\":\"<customer name or empty>\",\"lines\":[{\"product_id\":\"<catalog id>\"," +
            "\"qty\":<number>,\"confidence\":\"high\"|\"medium\"," +
            "\"raw\":\"<source fragment>\"}],\"unmatched\":[\"<fragment you " +
            "could not confidently match>\"]}. Never invent products or ids. If " +
            "unsure, put the fragment in unmatched. Ignore politeness words like " +
            "'kiitos'. If the text names a customer (e.g. 'Venlalle ...', " +
            "'asiakas Venla'), put that name in the customer field; otherwise use " +
            "an empty string."
        val user = "CATALOG (JSON):\n" + cat.toString() +
            "\n\nORDER TEXT:\n" + text.trim() +
            "\n\nReturn ONLY the JSON object."
        return Pair(system, user)
    }

    /** Parse a model JSON reply into a ParseResult. Name/unit/price always come
     *  from the catalog (by id, or by name as a fallback) so the model cannot
     *  invent figures. Returns empty lists if the reply is not valid JSON. */
    fun parseAiResponse(raw: String, catalog: List<Product>): ParseResult {
        var txt = raw.trim()
        if (txt.contains("```")) {
            txt = txt.replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim()
        }
        val obj: JSONObject = try {
            JSONObject(txt)
        } catch (e: Exception) {
            val m = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(txt)
            if (m != null) {
                try { JSONObject(m.value) } catch (e2: Exception) {
                    return ParseResult(emptyList(), emptyList())
                }
            } else {
                return ParseResult(emptyList(), emptyList())
            }
        }
        val byId = catalog.associateBy { it.key() }
        val byName = catalog.associateBy { it.name.lowercase() }
        val lines = ArrayList<ParsedLine>()
        val unmatched = ArrayList<String>()
        val arr = obj.optJSONArray("lines")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val pid = item.optString("product_id", item.optString("id", ""))
                val prod = byId[pid] ?: byName[item.optString("name", "").lowercase()]
                val rawFrag = item.optString("raw", item.optString("name", ""))
                if (prod == null) {
                    if (rawFrag.isNotBlank()) unmatched.add(rawFrag.trim())
                    continue
                }
                var qty = item.optDouble("qty", 1.0)
                if (qty <= 0) qty = 1.0
                var conf = item.optString("confidence", "high")
                if (conf != "high" && conf != "medium") conf = "high"
                lines.add(
                    ParsedLine(qty, prod.unit, prod.price, prod.id, prod.name, conf, null, rawFrag.trim())
                )
            }
        }
        val un = obj.optJSONArray("unmatched")
        if (un != null) {
            for (i in 0 until un.length()) {
                val u = un.optString(i, "")
                if (u.isNotBlank()) unmatched.add(u.trim())
            }
        }
        val cust = obj.optString("customer", "").trim()
        return ParseResult(lines, unmatched, if (cust.isBlank()) null else cust)
    }
}
