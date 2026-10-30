package fi.kasvila.reko

import java.time.Duration

/**
 * The merge engine.
 *
 * Everything that protects the user's data lives here, so there is exactly one
 * place to reason about — and one place to test. The engine never writes; it
 * produces a [MergePlan] that the UI shows as a preview, and only when the user
 * confirms does [applyPlan] turn that plan into a new [AppState].
 *
 * ## Why `lastSyncAt` is the whole trick
 *
 * Plain last-writer-wins ("newest timestamp wins") cannot tell the difference
 * between *"they edited it and I didn't"* and *"we BOTH edited it"*. The first
 * is routine and should be silent; the second is a conflict and must be shown,
 * because one of the two edits is about to disappear. Remembering when the two
 * devices last agreed is what separates them: a record is "changed on my side"
 * exactly when its `updated_at` is newer than that mark.
 *
 * ## Which mark, when the two disagree
 *
 * Both devices remember their own last-sync time and they can drift apart (an
 * abandoned sync, a restored backup). We deliberately take the EARLIER of the
 * two. An `lastSyncAt` that is too early makes unchanged records look changed —
 * that shows a conflict the user can wave away. One that is too late makes
 * changed records look unchanged — and that silently drops an edit. Given the
 * choice between annoying and lossy, annoying wins every time.
 */
object SyncEngine {

    enum class Choice { LOCAL, REMOTE }

    enum class Action {
        /** Nothing to do: keep our copy. */
        KEEP,
        /** Only they changed it: take theirs. */
        TAKE,
        /** We have never seen this record: add theirs. */
        ADD,
        /** Both changed it differently: ask. */
        CONFLICT
    }

    /** How much of a lie about the clock we tolerate before warning the user. */
    const val CLOCK_SKEW_WARN_MINUTES = 10L

    // ── plan types ──────────────────────────────────────────────────────────

    data class RecordPlan<T : Syncable>(
        val id: String,
        val action: Action,
        val local: T?,
        val remote: T?,
        val conflictKey: String = ""
    )

    data class ConflictField(val label: String, val local: String, val remote: String)

    data class Conflict(
        val key: String,                 // "product:P00012" — matches RecordPlan.conflictKey
        val kind: String,                // product | ring | order | delivery
        val kindLabel: String,           // "Tuote" | "Ruokapiiri" | …
        val label: String,               // the record's own name
        val fields: List<ConflictField>, // only the fields that actually differ
        val localAt: String,
        val remoteAt: String,
        val defaultChoice: Choice,
        /** "edit" = both edited; "delete" = one deleted while the other edited. */
        val nature: String
    )

    data class MergeStats(
        val ringsNew: Int = 0, val ringsUpd: Int = 0,
        val productsNew: Int = 0, val productsUpd: Int = 0,
        val ordersNew: Int = 0, val ordersUpd: Int = 0,
        val deliveriesNew: Int = 0, val deliveriesUpd: Int = 0,
        val customersNew: Int = 0, val customersUpd: Int = 0,
        val distsNew: Int = 0, val distsUpd: Int = 0,
        val deletesIncoming: Int = 0,
        val aliasesAdded: Int = 0,
        val conflicts: Int = 0
    ) {
        fun totalChanges(): Int =
            ringsNew + ringsUpd + productsNew + productsUpd +
                ordersNew + ordersUpd + deliveriesNew + deliveriesUpd +
                customersNew + customersUpd + distsNew + distsUpd + aliasesAdded
        fun isEmpty(): Boolean = totalChanges() == 0 && conflicts == 0
    }

    data class MergePlan(
        val peerId: String,
        val peerName: String,
        val via: String,                    // "file" | "lan"
        val effectiveLastSync: String,
        val rings: List<RecordPlan<Ring>>,
        val products: List<RecordPlan<Product>>,
        val orders: List<RecordPlan<Order>>,
        val deliveries: List<RecordPlan<Delivery>>,
        val customers: List<RecordPlan<Customer>>,
        val distributions: List<RecordPlan<Distribution>>,
        val aliases: Map<String, List<String>>,
        val stats: MergeStats,
        val conflicts: List<Conflict>,
        val clockSkewMinutes: Long,
        val incomingCurrency: String,
        val incomingDefaultVat: Double,
        val incomingCatalogVersion: Int,
        val incomingExportedAt: String,
        /** Set when the file looks suspicious enough to warn about before applying. */
        val warnings: List<String>
    ) {
        val hasClockSkew: Boolean get() = clockSkewMinutes > CLOCK_SKEW_WARN_MINUTES
        /** Default answers, so "Hyväksy kaikki uusimmat" is one tap. */
        fun defaultChoices(): Map<String, Choice> = conflicts.associate { it.key to it.defaultChoice }
    }

    // ── planning ────────────────────────────────────────────────────────────

    fun plan(local: AppState, incoming: SyncPayload, via: String = "file"): MergePlan {
        val peerId = incoming.device_id.ifBlank { SyncFiles.legacyPeerId("unknown") }
        val myMark = local.peers[peerId]?.lastSyncAt ?: ""
        val theirMark = incoming.last_sync_at
        val ls = earlierOf(myMark, theirMark)

        val conflicts = ArrayList<Conflict>()

        val ringPlans = planRecords(
            local.rings, incoming.rings, ls, "ring", "Ruokapiiri", conflicts, ::ringConflictFields)

        val productPlans = planRecords(
            local.products, incoming.products, ls, "product", "Tuote", conflicts, ::productConflictFields)

        val orderPlans = planRecords(
            local.orders, incoming.orders, ls, "order", "Tilaus", conflicts, ::orderConflictFields)

        val deliveryPlans = planRecords(
            local.deliveries, incoming.deliveries, ls, "delivery", "Kuljetus", conflicts, ::deliveryConflictFields)

        val customerPlans = planRecords(
            local.customers, incoming.customers, ls, "customer", "Asiakas", conflicts, ::customerConflictFields)

        val distPlans = planRecords(
            local.distributions, incoming.distributions, ls, "dist", "Jakelu", conflicts, ::distConflictFields)

        // Aliases are additive: a learned name is never wrong, so the union is
        // always the right answer and there is nothing to conflict over.
        var aliasesAdded = 0
        val mergedAliases = LinkedHashMap<String, List<String>>()
        for ((k, v) in local.localAliases) mergedAliases[k] = v
        for ((k, v) in incoming.aliases) {
            val cur = mergedAliases[k] ?: emptyList()
            val merged = (cur + v).map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (merged.size > cur.size) aliasesAdded += merged.size - cur.size
            mergedAliases[k] = merged
        }

        val stats = MergeStats(
            ringsNew = ringPlans.count { it.action == Action.ADD },
            ringsUpd = ringPlans.count { it.action == Action.TAKE },
            productsNew = productPlans.count { it.action == Action.ADD },
            productsUpd = productPlans.count { it.action == Action.TAKE },
            ordersNew = orderPlans.count { it.action == Action.ADD },
            ordersUpd = orderPlans.count { it.action == Action.TAKE },
            deliveriesNew = deliveryPlans.count { it.action == Action.ADD },
            deliveriesUpd = deliveryPlans.count { it.action == Action.TAKE },
            customersNew = customerPlans.count { it.action == Action.ADD },
            customersUpd = customerPlans.count { it.action == Action.TAKE },
            distsNew = distPlans.count { it.action == Action.ADD },
            distsUpd = distPlans.count { it.action == Action.TAKE },
            deletesIncoming = countIncomingDeletes(ringPlans) + countIncomingDeletes(productPlans) +
                countIncomingDeletes(orderPlans) + countIncomingDeletes(deliveryPlans) +
                countIncomingDeletes(customerPlans) + countIncomingDeletes(distPlans),
            aliasesAdded = aliasesAdded,
            conflicts = conflicts.size
        )

        val skew = clockSkewMinutes(incoming.exported_at)
        val warnings = buildWarnings(local, incoming, stats, skew)

        return MergePlan(
            peerId = peerId,
            peerName = incoming.device_name.ifBlank { "Tuntematon laite" },
            via = via,
            effectiveLastSync = ls,
            rings = ringPlans,
            products = productPlans,
            orders = orderPlans,
            deliveries = deliveryPlans,
            customers = customerPlans,
            distributions = distPlans,
            aliases = mergedAliases,
            stats = stats,
            conflicts = conflicts,
            clockSkewMinutes = skew,
            incomingCurrency = incoming.currency,
            incomingDefaultVat = incoming.default_vat,
            incomingCatalogVersion = incoming.catalog_version,
            incomingExportedAt = incoming.exported_at,
            warnings = warnings
        )
    }

    private fun <T : Syncable> countIncomingDeletes(plans: List<RecordPlan<T>>): Int =
        plans.count { (it.action == Action.TAKE || it.action == Action.ADD) && it.remote?.deleted == true }

    /**
     * The per-record decision table. Shared by all four record types; the only
     * per-type parts are how two records are compared for equality and how a
     * difference is described to the user.
     */
    private fun <T : Syncable> planRecords(
        localList: List<T>,
        remoteList: List<T>,
        lastSync: String,
        kind: String,
        kindLabel: String,
        conflictSink: MutableList<Conflict>,
        describe: (T, T) -> List<ConflictField>
    ): List<RecordPlan<T>> {
        val byIdLocal = localList.associateBy { it.id }
        val byIdRemote = remoteList.associateBy { it.id }
        val allIds = LinkedHashSet<String>().apply {
            addAll(localList.map { it.id })
            addAll(remoteList.map { it.id })
        }

        val out = ArrayList<RecordPlan<T>>(allIds.size)
        for (id in allIds) {
            val a = byIdLocal[id]
            val b = byIdRemote[id]

            // 1. We have never seen it — take it, tombstone or not.
            if (a == null) {
                out.add(RecordPlan(id, Action.ADD, null, b))
                continue
            }
            // 2. They have never seen it — our record stands. Nothing to decide:
            //    silence here is not data loss, it is simply news they don't have.
            if (b == null) {
                out.add(RecordPlan(id, Action.KEEP, a, null))
                continue
            }

            val aChanged = Reko.changedSince(a.updated_at, lastSync)
            val bChanged = Reko.changedSince(b.updated_at, lastSync)

            // 3. Delete on one side, edit on the other. Never resolved silently
            //    in favour of the delete: an edit proves someone still wanted the
            //    record, and restoring something is cheap while un-deleting
            //    something you never noticed vanish is not.
            if (a.deleted != b.deleted) {
                val editedSideChanged = if (a.deleted) bChanged else aChanged
                if (editedSideChanged) {
                    val key = "$kind:$id"
                    val keeper = if (a.deleted) b else a          // the non-deleted one
                    conflictSink.add(
                        Conflict(
                            key = key,
                            kind = kind,
                            kindLabel = kindLabel,
                            label = keeper.syncLabel(),
                            fields = listOf(
                                ConflictField(
                                    "Tila",
                                    if (a.deleted) "Poistettu" else "Käytössä (muokattu)",
                                    if (b.deleted) "Poistettu" else "Käytössä (muokattu)"
                                )
                            ),
                            localAt = a.updated_at,
                            remoteAt = b.updated_at,
                            // Default to whichever side is NOT the delete.
                            defaultChoice = if (a.deleted) Choice.REMOTE else Choice.LOCAL,
                            nature = "delete"
                        )
                    )
                    out.add(RecordPlan(id, Action.CONFLICT, a, b, key))
                } else {
                    // Only the delete is news — apply it.
                    if (a.deleted) out.add(RecordPlan(id, Action.KEEP, a, b))
                    else out.add(RecordPlan(id, Action.TAKE, a, b))
                }
                continue
            }

            // 4. Nobody touched it since we last agreed.
            if (!aChanged && !bChanged) {
                out.add(RecordPlan(id, Action.KEEP, a, b))
                continue
            }
            // 5. Only they touched it.
            if (!aChanged) {
                out.add(RecordPlan(id, Action.TAKE, a, b))
                continue
            }
            // 6. Only we touched it.
            if (!bChanged) {
                out.add(RecordPlan(id, Action.KEEP, a, b))
                continue
            }
            // 7. Both touched it. Identical edits are not a conflict — two people
            //    typing the same corrected price is agreement, not a dispute.
            if (a.ownedSignature() == b.ownedSignature()) {
                out.add(RecordPlan(id, Action.KEEP, a, b))
                continue
            }
            // 8. A real conflict.
            val key = "$kind:$id"
            conflictSink.add(
                Conflict(
                    key = key,
                    kind = kind,
                    kindLabel = kindLabel,
                    label = a.syncLabel(),
                    fields = describe(a, b),
                    localAt = a.updated_at,
                    remoteAt = b.updated_at,
                    defaultChoice = if (Reko.isNewer(b.updated_at, a.updated_at)) Choice.REMOTE else Choice.LOCAL,
                    nature = "edit"
                )
            )
            out.add(RecordPlan(id, Action.CONFLICT, a, b, key))
        }
        return out
    }

    // ── conflict descriptions (only the differing fields) ───────────────────

    private fun diff(label: String, a: String, b: String, out: MutableList<ConflictField>) {
        if (a != b) out.add(ConflictField(label, a, b))
    }

    private fun productConflictFields(a: Product, b: Product): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Nimi", a.name, b.name, out)
        diff("Hinta (sis. ALV)", fmt(a.price), fmt(b.price), out)
        diff("ALV-%", fmt(a.vat), fmt(b.vat), out)
        diff("Yksikkö", a.unit, b.unit, out)
        diff("Aliakset", a.aliases.sorted().joinToString(", "), b.aliases.sorted().joinToString(", "), out)
        diff("Käytössä", yesNo(a.active), yesNo(b.active), out)
        return out
    }

    private fun ringConflictFields(a: Ring, b: Ring): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Nimi", a.name, b.name, out)
        diff("Paikkakunta", a.city, b.city, out)
        diff("Noutopaikka", a.location, b.location, out)
        diff("Osoite", a.address, b.address, out)
        diff("Viikonpäivä", Reko.weekdayFull(a.weekday), Reko.weekdayFull(b.weekday), out)
        diff("Kellonaika", a.time, b.time, out)
        diff("Rytmi", Reko.parityLabel(a.parity), Reko.parityLabel(b.parity), out)
        diff("Hoitaja", a.handler, b.handler, out)
        diff("Facebook", a.fb_url, b.fb_url, out)
        diff("Muistiinpano", a.notes, b.notes, out)
        diff("Käytössä", yesNo(a.active), yesNo(b.active), out)
        return out
    }

    private fun orderConflictFields(a: Order, b: Order): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Asiakas", a.customer, b.customer, out)
        diff("Päivä", a.date, b.date, out)
        diff("Yhteystieto", a.contact, b.contact, out)
        diff("Maksettu", yesNo(a.paid), yesNo(b.paid), out)
        diff("Noudettu", yesNo(a.picked), yesNo(b.picked), out)
        diff("Peruttu", yesNo(a.cancelled), yesNo(b.cancelled), out)
        diff("Muistiinpano", a.note, b.note, out)
        diff("Rivit", linesText(a.lines), linesText(b.lines), out)
        diff("Summa", fmt(a.total), fmt(b.total), out)
        return out
    }

    private fun distConflictFields(a: Distribution, b: Distribution): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Jakelu peruttu", yesNo(a.cancelled), yesNo(b.cancelled), out)
        diff("Kellonaika", a.time.ifBlank { "(piirin oma)" }, b.time.ifBlank { "(piirin oma)" }, out)
        diff("Paikka", a.address.ifBlank { "(piirin oma)" }, b.address.ifBlank { "(piirin oma)" }, out)
        diff("Tila", Distribution.statusLabel(a.status), Distribution.statusLabel(b.status), out)
        diff("Muistiinpano", a.notes, b.notes, out)
        return out
    }

    private fun customerConflictFields(a: Customer, b: Customer): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Nimi", a.name, b.name, out)
        diff("Yhteystieto", a.contact, b.contact, out)
        diff("Puhelin", a.phone, b.phone, out)
        diff("Sähköposti", a.email, b.email, out)
        diff("Osoite", a.address, b.address, out)
        diff("Muistiinpano", a.notes, b.notes, out)
        diff("Käytössä", yesNo(a.active), yesNo(b.active), out)
        return out
    }

    private fun deliveryConflictFields(a: Delivery, b: Delivery): List<ConflictField> {
        val out = ArrayList<ConflictField>()
        diff("Asiakas", a.customer, b.customer, out)
        diff("Toimituspäivä", a.deliveryDate, b.deliveryDate, out)
        diff("Toimitusaika", a.deliveryTime, b.deliveryTime, out)
        diff("Osoite", a.address, b.address, out)
        diff("Toimitusosoite", a.deliveryAddress, b.deliveryAddress, out)
        diff("Maksutapa", Reko.paymentLabel(a.paymentMethod), Reko.paymentLabel(b.paymentMethod), out)
        diff("Maksettu", yesNo(a.paid), yesNo(b.paid), out)
        diff("Toimitettu", yesNo(a.delivered), yesNo(b.delivered), out)
        diff("Peruttu", yesNo(a.cancelled), yesNo(b.cancelled), out)
        diff("Muistiinpano", a.note, b.note, out)
        diff("Rivit", linesText(a.lines), linesText(b.lines), out)
        diff("Summa", fmt(a.total), fmt(b.total), out)
        return out
    }

    private fun linesText(lines: List<Line>): String =
        if (lines.isEmpty()) "(ei rivejä)"
        else lines.joinToString(", ") { "${it.name} ${fmtQty(it.qty)} ${it.unit}" }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")
    private fun fmtQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")
    private fun yesNo(b: Boolean): String = if (b) "kyllä" else "ei"

    // ── sanity warnings ─────────────────────────────────────────────────────

    private fun buildWarnings(
        local: AppState,
        incoming: SyncPayload,
        stats: MergeStats,
        skew: Long
    ): List<String> {
        val w = ArrayList<String>()
        if (skew > CLOCK_SKEW_WARN_MINUTES) {
            w.add(
                "⚠ Laitteiden kellot poikkeavat noin $skew minuuttia toisistaan. " +
                    "\"Uudempi voittaa\" ei ole luotettava — tarkista ristiriidat käsin."
            )
        }
        if (incoming.device_id.isBlank()) {
            w.add("⚠ Tiedostosta puuttuu laitetunniste (vanha muoto). Ristiriitojen tunnistus on karkeampi.")
        }
        // A catalog that suddenly turns up empty is almost always a wrong file or
        // a broken export, never a real intention.
        if (local.products.isNotEmpty() && incoming.products.isEmpty() && incoming.rings.isEmpty()) {
            w.add("⚠ Tiedostossa ei ole yhtään tuotetta eikä piiriä. Varmista että valitsit oikean tiedoston.")
        }
        val incomingDeletes = incoming.products.count { it.deleted } + incoming.rings.count { it.deleted }
        if (incomingDeletes > 0 && incomingDeletes >= (incoming.products.size + incoming.rings.size) / 2) {
            w.add("⚠ Tiedostossa on poikkeuksellisen paljon poistoja ($incomingDeletes). Tarkista ennen hyväksymistä.")
        }
        return w
    }

    private fun clockSkewMinutes(exportedAt: String): Long {
        val t = Reko.parseIso(exportedAt) ?: return 0
        val now = java.time.LocalDateTime.now()
        return kotlin.math.abs(Duration.between(now, t).toMinutes())
    }

    private fun earlierOf(a: String, b: String): String {
        if (a.isBlank() || b.isBlank()) return ""      // either side never synced → treat all as changed
        val pa = Reko.parseIso(a) ?: return ""
        val pb = Reko.parseIso(b) ?: return ""
        return if (pa.isBefore(pb)) a else b
    }

    // ── applying ────────────────────────────────────────────────────────────

    /**
     * Turn a plan plus the user's conflict answers into the new state.
     *
     * Pure: it takes a state and returns a state, touching no files. The caller
     * snapshots, saves and logs. That separation is what makes the engine
     * testable and makes "undo" a matter of restoring a snapshot rather than
     * trying to invert a merge.
     */
    fun applyPlan(
        local: AppState,
        plan: MergePlan,
        choices: Map<String, Choice>,
        snapshotName: String = ""
    ): Pair<AppState, SyncLogEntry> {
        val rings = resolve(plan.rings, choices) { a, b -> mergeRing(a, b) }
        val products = resolve(plan.products, choices) { a, b -> mergeProduct(a, b) }
        val orders = resolve(plan.orders, choices) { a, b -> a ?: b!! }
        val deliveries = resolve(plan.deliveries, choices) { a, b -> a ?: b!! }
        val customers = resolve(plan.customers, choices) { a, b -> mergeCustomer(a, b) }
        val distributions = resolve(plan.distributions, choices) { a, b -> mergeDistribution(a, b) }

        // Heal order/delivery line units against the freshly merged catalog, the
        // same way a v41 catalog import did. Prices and quantities are untouched.
        val healedOrders = orders.map { it.copy(lines = Reko.alignUnits(it.lines, products)) }
        val healedDeliveries = deliveries.map { it.copy(lines = Reko.alignUnits(it.lines, products)) }

        val now = Reko.nowIso()
        val peers = local.peers.toMutableMap()
        peers[plan.peerId] = PeerInfo(
            name = plan.peerName,
            lastSyncAt = now,
            lastDirection = plan.via
        )

        val entry = SyncLogEntry(
            at = now,
            peer = plan.peerId,
            peerName = plan.peerName,
            via = plan.via,
            ringsNew = plan.stats.ringsNew,
            ringsUpd = plan.stats.ringsUpd,
            productsNew = plan.stats.productsNew,
            productsUpd = plan.stats.productsUpd,
            ordersNew = plan.stats.ordersNew,
            ordersUpd = plan.stats.ordersUpd,
            deliveriesNew = plan.stats.deliveriesNew,
            deliveriesUpd = plan.stats.deliveriesUpd,
            customersNew = plan.stats.customersNew,
            customersUpd = plan.stats.customersUpd,
            distsNew = plan.stats.distsNew,
            distsUpd = plan.stats.distsUpd,
            deletes = plan.stats.deletesIncoming,
            conflicts = plan.stats.conflicts,
            snapshot = snapshotName
        )

        val newState = local.copy(
            rings = retagRetired(rings, healedOrders),
            products = products,
            orders = healedOrders,
            deliveries = healedDeliveries,
            customers = customers,
            distributions = distributions,
            localAliases = plan.aliases,
            currency = plan.incomingCurrency.ifBlank { local.currency },
            defaultVat = if (plan.incomingDefaultVat > 0) plan.incomingDefaultVat else local.defaultVat,
            catalogVersion = maxOf(local.catalogVersion, plan.incomingCatalogVersion),
            catalogExportedAt = plan.incomingExportedAt.ifBlank { local.catalogExportedAt },
            catalogImportedAt = now,
            peers = peers,
            syncLog = (local.syncLog + entry).takeLast(50)
        )
        return newState to entry
    }

    private fun <T : Syncable> resolve(
        plans: List<RecordPlan<T>>,
        choices: Map<String, Choice>,
        combine: (T?, T?) -> T
    ): List<T> {
        val out = ArrayList<T>(plans.size)
        for (p in plans) {
            when (p.action) {
                Action.KEEP -> out.add(combine(p.local, p.remote))
                Action.TAKE -> out.add(combine(p.remote, p.local))
                Action.ADD -> p.remote?.let { out.add(combine(it, null)) }
                Action.CONFLICT -> {
                    val choice = choices[p.conflictKey] ?: Choice.LOCAL
                    val winner = if (choice == Choice.REMOTE) p.remote else p.local
                    val loser = if (choice == Choice.REMOTE) p.local else p.remote
                    if (winner != null) out.add(combine(winner, loser))
                }
            }
        }
        return out
    }

    /**
     * `winner` decides every field the phone may edit. `other` is consulted only
     * for fields the phone can never own, so a desktop-supplied cost or category
     * survives even when the phone's copy of the product wins the merge.
     */
    private fun mergeProduct(winner: Product?, other: Product?): Product {
        val w = winner ?: other!!
        if (other == null) return w
        val cost = if (other.cost > 0 && w.cost <= 0) other.cost else w.cost
        val category = if (other.category.isNotBlank() && w.category.isBlank()) other.category else w.category
        return if (cost == w.cost && category == w.category) w
        else w.copy(cost = cost, category = category)
    }

    /**
     * The desktop's own distribution id is how it finds the record its orders are
     * already attached to. The phone never invents one, so it is carried across
     * from whichever copy knows it rather than being lost when the phone wins.
     */
    private fun mergeDistribution(winner: Distribution?, other: Distribution?): Distribution {
        val w = winner ?: other!!
        if (other == null) return w
        return if (w.dist_id.isBlank() && other.dist_id.isNotBlank())
            w.copy(dist_id = other.dist_id) else w
    }

    /**
     * `type` is the desktop's customer classification (retail / wholesale / …).
     * The phone never shows or edits it, so it is carried across from whichever
     * copy has it rather than being lost when the phone's record wins.
     */
    private fun mergeCustomer(winner: Customer?, other: Customer?): Customer {
        val w = winner ?: other!!
        if (other == null) return w
        return if (w.type.isBlank() && other.type.isNotBlank()) w.copy(type = other.type) else w
    }

    /** `retired` is local bookkeeping and must survive whichever copy wins. */
    private fun mergeRing(winner: Ring?, other: Ring?): Ring {
        val w = winner ?: other!!
        return w.copy(retired = w.retired || (other?.retired ?: false))
    }

    /**
     * Re-derive the "poistuva" (ghost) flag after a merge: a ring that is deleted
     * or out of use but still has open orders stays visible so those orders can be
     * finished. This replaces v41's import-time ghost logic and now runs after
     * every merge, so the flag can never go stale.
     */
    private fun retagRetired(rings: List<Ring>, orders: List<Order>): List<Ring> {
        val today = java.time.LocalDate.now().toString()
        fun hasOpenOrders(rid: String) = orders.any {
            it.group_id == rid && !it.cancelled && !it.deleted &&
                (it.date.isBlank() || it.date >= today)
        }
        return rings.map { r ->
            val gone = r.deleted || !r.active
            val ghost = gone && hasOpenOrders(r.id)
            if (r.retired == ghost) r else r.copy(retired = ghost)
        }
    }

    // ── local edit helpers (stamping) ───────────────────────────────────────
    //
    // Every local mutation must go through one of these, or the record will look
    // unchanged to the next merge and the edit will be silently reverted by the
    // peer's older copy. That is the single easiest way to lose data in a system
    // like this, so the stamping lives here rather than being retyped at each
    // call site.

    fun stamp(p: Product, deviceId: String): Product =
        p.copy(updated_at = Reko.nowIso(), origin = p.origin.ifBlank { deviceId })

    fun stamp(r: Ring, deviceId: String): Ring =
        r.copy(updated_at = Reko.nowIso(), origin = r.origin.ifBlank { deviceId })

    fun stamp(o: Order, deviceId: String): Order =
        o.copy(updated_at = Reko.nowIso(), origin = o.origin.ifBlank { deviceId })

    fun stamp(d: Delivery, deviceId: String): Delivery =
        d.copy(updated_at = Reko.nowIso(), origin = d.origin.ifBlank { deviceId })

    fun stamp(c: Customer, deviceId: String): Customer =
        c.copy(updated_at = Reko.nowIso(), origin = c.origin.ifBlank { deviceId })

    fun stamp(d: Distribution, deviceId: String): Distribution =
        d.copy(updated_at = Reko.nowIso(), origin = d.origin.ifBlank { deviceId })

    /** Soft-delete: to the trash, not off the disk. */
    fun softDelete(p: Product, deviceId: String): Product =
        stamp(p.copy(deleted = true, deleted_at = Reko.nowIso(), active = false), deviceId)

    fun softDelete(r: Ring, deviceId: String): Ring =
        stamp(r.copy(deleted = true, deleted_at = Reko.nowIso(), active = false), deviceId)

    fun restore(p: Product, deviceId: String): Product =
        stamp(p.copy(deleted = false, deleted_at = "", active = true), deviceId)

    fun restore(r: Ring, deviceId: String): Ring =
        stamp(r.copy(deleted = false, deleted_at = "", active = true), deviceId)

    fun softDelete(c: Customer, deviceId: String): Customer =
        stamp(c.copy(deleted = true, deleted_at = Reko.nowIso(), active = false), deviceId)

    fun restore(c: Customer, deviceId: String): Customer =
        stamp(c.copy(deleted = false, deleted_at = "", active = true), deviceId)
}
