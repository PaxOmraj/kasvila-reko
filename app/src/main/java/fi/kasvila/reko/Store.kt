package fi.kasvila.reko

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal val JSON = Json {
    ignoreUnknownKeys = true   // desktop may add fields we don't model
    encodeDefaults = true
    prettyPrint = true
    isLenient = true
    coerceInputValues = true   // treat null / bad values as the property default (e.g. handler=null -> "")
}

/** A restorable point in time, newest first in [Store.snapshots]. */
data class Snapshot(
    val file: String,
    val at: String,          // ISO timestamp parsed out of the file name
    val label: String,       // why it was taken, e.g. "ennen synkronointia"
    val bytes: Long
)

/**
 * Persists the whole app state to a single JSON file in internal storage.
 *
 * v42 changes, both aimed at never losing data:
 *  - **Atomic save.** The state is written to a temp file and renamed over the
 *    real one. A crash or a battery death mid-write can no longer leave a
 *    half-written `reko_state.json` that fails to parse on next launch.
 *  - **Rotating snapshots.** [backup] used to keep exactly ONE copy, which the
 *    next import promptly overwrote. Now up to [MAX_SNAPSHOTS] timestamped
 *    snapshots are kept, so "undo the sync before last" is possible and a bad
 *    merge is never a dead end.
 *
 * The single legacy `reko_state_backup.json` is still read by [restoreBackup] so
 * an upgrade from v41 keeps whatever safety net was already on disk.
 */
class Store(context: Context, private val dek: ByteArray? = null) {
    private val dir = context.filesDir
    private val file = File(dir, "reko_state.json")
    private val tmpFile = File(dir, "reko_state.json.tmp")
    private val encFile = File(dir, "reko_state.enc")
    private val tmpEnc = File(dir, "reko_state.enc.tmp")
    private val legacyBackup = File(dir, "reko_state_backup.json")
    private val snapDir = File(dir, "snapshots").apply { mkdirs() }

    /** True when this store encrypts at rest (a DEK was supplied at login). */
    val encrypted: Boolean get() = dek != null

    companion object {
        const val MAX_SNAPSHOTS = 10
        private const val SNAP_PREFIX = "snap_"
    }

    // ── load / save ─────────────────────────────────────────────────────────

    /**
     * Read the state, run any migration it needs, and make sure this device has
     * an identity. Falls back to the newest snapshot if the main file is corrupt,
     * and only then to an empty state — a parse error should cost you the last
     * few edits, not everything.
     */
    fun load(): AppState {
        // Encrypted mode (logged in): primary is the .enc file.
        if (dek != null) {
            readOrNull(encFile)?.let { return migrate(it) }
            // First launch after enabling login: migrate the legacy plaintext state,
            // save it encrypted, verify it reads back, and only THEN drop the
            // plaintext. Any failure leaves the plaintext untouched — no data loss.
            readOrNull(file)?.let { legacy ->
                val migrated = migrate(legacy)
                if (saveEncryptedVerified(migrated)) {
                    try { file.delete() } catch (_: Exception) {}
                    try { tmpFile.delete() } catch (_: Exception) {}
                }
                return migrated
            }
            for (s in snapshots()) {
                readOrNull(File(snapDir, s.file))?.let { return migrate(it) }
            }
            readOrNull(legacyBackup)?.let { return migrate(it) }
            return migrate(AppState())
        }

        // Plaintext mode (pre-login / no account).
        val primary = readOrNull(file)
        if (primary != null) return migrate(primary)
        for (s in snapshots()) {
            val recovered = readOrNull(File(snapDir, s.file))
            if (recovered != null) return migrate(recovered)
        }
        val legacy = readOrNull(legacyBackup)
        if (legacy != null) return migrate(legacy)
        return migrate(AppState())
    }

    /**
     * Read a state file, handling both encrypted and legacy plaintext content.
     * When a DEK is set we try to decrypt first; content that is not valid
     * ciphertext is treated as plaintext, so old snapshots survive the switch.
     */
    private fun readOrNull(f: File): AppState? = try {
        if (!f.exists() || f.length() == 0L) {
            null
        } else {
            val bytes = f.readBytes()
            val text = if (dek != null) {
                Crypto.aesGcmDecrypt(dek, bytes)?.toString(Charsets.UTF_8)
                    ?: bytes.toString(Charsets.UTF_8)   // legacy plaintext fallback
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            JSON.decodeFromString<AppState>(text)
        }
    } catch (e: Exception) {
        null
    }

    /** Encrypt + write the state to [encFile] atomically, then read it back to
     *  confirm it decodes. Returns false (without touching the plaintext) on any
     *  failure, so a migration that cannot be verified never loses data. */
    private fun saveEncryptedVerified(state: AppState): Boolean = try {
        val key = dek ?: return false
        val bytes = Crypto.aesGcmEncrypt(key, JSON.encodeToString(state).toByteArray(Charsets.UTF_8))
        tmpEnc.writeBytes(bytes)
        if (!tmpEnc.renameTo(encFile)) {
            encFile.delete()
            if (!tmpEnc.renameTo(encFile)) { encFile.writeBytes(tmpEnc.readBytes()); tmpEnc.delete() }
        }
        readOrNull(encFile) != null
    } catch (e: Exception) {
        false
    }

    /** Atomic write: full content to a temp file, then rename over the target.
     *  Encrypted when a DEK is set; the old plaintext copy is removed once so it
     *  can't linger unencrypted. */
    fun save(state: AppState) {
        try {
            if (dek != null) {
                val bytes = Crypto.aesGcmEncrypt(dek, JSON.encodeToString(state).toByteArray(Charsets.UTF_8))
                tmpEnc.writeBytes(bytes)
                if (!tmpEnc.renameTo(encFile)) {
                    encFile.delete()
                    if (!tmpEnc.renameTo(encFile)) { encFile.writeBytes(tmpEnc.readBytes()); tmpEnc.delete() }
                }
                if (file.exists()) try { file.delete() } catch (_: Exception) {}
            } else {
                tmpFile.writeText(JSON.encodeToString(state))
                if (!tmpFile.renameTo(file)) {
                    // Rename can fail on some filesystems if the target exists.
                    file.delete()
                    if (!tmpFile.renameTo(file)) {
                        file.writeText(tmpFile.readText())
                        tmpFile.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // ── migration + device identity ─────────────────────────────────────────

    /**
     * Bring an older on-disk state up to the current shape. Runs on every load
     * and is idempotent, so it is safe to call repeatedly.
     *
     *  - gives this phone a stable [AppState.deviceId] the first time
     *  - stamps `updated_at` on records saved before v42 so the merge engine has
     *    something to compare (they are stamped to the epoch, meaning "older than
     *    anything the desktop sends", which is the safe default: the desktop's
     *    copy wins on the first sync rather than a blank phone record clobbering it)
     */
    private fun migrate(s: AppState): AppState {
        var st = s
        if (st.deviceId.isBlank()) {
            st = st.copy(deviceId = Reko.newDeviceId())
        }
        if (st.deviceName.isBlank()) {
            val model = (android.os.Build.MODEL ?: "").trim()
            st = st.copy(deviceName = if (model.isBlank()) "Puhelin" else model)
        }
        if (st.schemaVersion < 2) {
            val epoch = "1970-01-01T00:00:00"
            st = st.copy(
                rings = st.rings.map {
                    if (it.updated_at.isBlank()) it.copy(updated_at = epoch, origin = "legacy") else it
                },
                products = st.products.map {
                    if (it.updated_at.isBlank()) it.copy(updated_at = epoch, origin = "legacy") else it
                },
                orders = st.orders.map {
                    if (it.updated_at.isBlank()) it.copy(updated_at = epoch, origin = "legacy") else it
                },
                deliveries = st.deliveries.map {
                    if (it.updated_at.isBlank()) it.copy(updated_at = epoch, origin = "legacy") else it
                },
                schemaVersion = 2
            )
        }
        return st
    }

    // ── snapshots ───────────────────────────────────────────────────────────

    /**
     * Take a restorable snapshot of the current state before something risky
     * (a sync, a catalog import, a bulk delete). Returns the snapshot's file name
     * so the caller can record it in the sync log and offer "undo".
     * Returns "" when there was nothing to snapshot.
     */
    fun snapshot(label: String): String {
        return try {
            val src = if (dek != null && encFile.exists()) encFile else file
            if (!src.exists()) return ""
            val stamp = Reko.nowIso().replace(":", "-")
            val safeLabel = label.replace(Regex("[^A-Za-z0-9ÄÖäöÅå ]"), "").trim().replace(" ", "_")
            val name = "$SNAP_PREFIX${stamp}__$safeLabel.json"
            src.copyTo(File(snapDir, name), overwrite = true)
            prune()
            name
        } catch (e: Exception) {
            ""
        }
    }

    /** Newest first. */
    fun snapshots(): List<Snapshot> = try {
        (snapDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(SNAP_PREFIX) && it.name.endsWith(".json") }
            .map { f ->
                val body = f.name.removePrefix(SNAP_PREFIX).removeSuffix(".json")
                val parts = body.split("__", limit = 2)
                val at = parts.getOrElse(0) { "" }
                    // "2026-09-11T14-02-00" -> "2026-09-11T14:02:00"
                    .let { if (it.length >= 19) it.take(13) + ":" + it.substring(14, 16) + ":" + it.substring(17, 19) else it }
                Snapshot(
                    file = f.name,
                    at = at,
                    label = parts.getOrElse(1) { "" }.replace("_", " "),
                    bytes = f.length()
                )
            }
            .sortedByDescending { it.file }
    } catch (e: Exception) {
        emptyList()
    }

    /** Keep only the newest [MAX_SNAPSHOTS]. */
    private fun prune() {
        try {
            snapshots().drop(MAX_SNAPSHOTS).forEach { File(snapDir, it.file).delete() }
        } catch (_: Exception) {
        }
    }

    /** Read a snapshot back without applying it (for a preview/diff). */
    fun readSnapshot(name: String): AppState? = readOrNull(File(snapDir, name))

    /**
     * Restore a snapshot wholesale. Takes a snapshot of the CURRENT state first,
     * labelled "ennen palautusta", so restoring is itself undoable.
     */
    fun restoreSnapshot(name: String): AppState? {
        val target = readSnapshot(name) ?: return null
        snapshot("ennen palautusta")
        val restored = migrate(target)
        save(restored)
        return restored
    }

    // ── legacy v41 single backup (kept for the upgrade path) ────────────────

    fun backup() {
        try {
            val src = if (dek != null && encFile.exists()) encFile else file
            if (src.exists()) src.copyTo(legacyBackup, overwrite = true)
        } catch (_: Exception) {
        }
    }

    fun hasBackup(): Boolean = legacyBackup.exists() || snapshots().isNotEmpty()

    fun restoreBackup(): AppState? = readOrNull(legacyBackup)

    // ── housekeeping ────────────────────────────────────────────────────────

    /**
     * Purge tombstones the other device has certainly seen by now
     * ([TOMBSTONE_DAYS]). Deleted records younger than that stay, because they
     * are what tells the peer "this was deleted, don't resurrect it".
     * Records still referenced by an order are never purged.
     */
    fun purgeTombstones(state: AppState): AppState {
        val usedProductIds = (state.orders.flatMap { it.lines } + state.deliveries.flatMap { it.lines })
            .map { it.product_id }.filter { it.isNotBlank() }.toSet()
        val usedRingIds = state.orders.map { it.group_id }.filter { it.isNotBlank() }.toSet()
        // Customers are tied to their orders by NAME, not by id, so that is what
        // decides whether a tombstone may go: dropping the record of someone who
        // still appears in the order history would leave those orders belonging
        // to a person the app no longer knows anything about.
        val usedNames = (state.orders.map { it.customer } + state.deliveries.map { it.customer })
            .map { Customer.norm(it) }.filter { it.isNotEmpty() }.toSet()

        val products = state.products.filterNot {
            it.deleted && it.id !in usedProductIds && Reko.daysSince(it.deleted_at) > TOMBSTONE_DAYS
        }
        val rings = state.rings.filterNot {
            it.deleted && it.id !in usedRingIds && Reko.daysSince(it.deleted_at) > TOMBSTONE_DAYS
        }
        val customers = state.customers.filterNot {
            it.deleted && Customer.norm(it.name) !in usedNames &&
                Reko.daysSince(it.deleted_at) > TOMBSTONE_DAYS
        }
        return if (products.size == state.products.size &&
            rings.size == state.rings.size &&
            customers.size == state.customers.size) state
        else state.copy(products = products, rings = rings, customers = customers)
    }
}

object SyncJson {
    /** Parse a desktop "kasvila-reko-rings" file. Throws with a readable message
     *  if the file is malformed or the wrong type (used for on-screen diagnostics). */
    fun parseRingsOrThrow(text: String): RingsPayload {
        val p = JSON.decodeFromString<RingsPayload>(text)
        require(p.format == "kasvila-reko-rings") { "väärä muototunniste: '${p.format}'" }
        return p
    }

    /** Parse a desktop "kasvila-reko-rings" file. Returns null if it isn't one. */
    fun parseRings(text: String): RingsPayload? = try {
        parseRingsOrThrow(text)
    } catch (e: Exception) {
        null
    }

    /** Build the "kasvila-reko-orders" file the desktop imports. */
    fun buildOrders(orders: List<Order>): String =
        JSON.encodeToString(OrdersPayload(exported_at = Reko.nowIso(), orders = orders))

    /** Build the "kasvila-reko-deliveries" file (home deliveries). */
    fun buildDeliveries(deliveries: List<Delivery>): String =
        JSON.encodeToString(DeliveriesPayload(exported_at = Reko.nowIso(), deliveries = deliveries))

    /** Build the combined "kasvila-reko-bundle" file: orders + home deliveries +
     *  learned aliases in one export, for a single desktop import. */
    fun buildBundle(
        orders: List<Order>,
        deliveries: List<Delivery>,
        aliases: Map<String, List<String>>
    ): String = JSON.encodeToString(
        BundlePayload(exported_at = Reko.nowIso(), orders = orders,
            deliveries = deliveries, aliases = aliases))
}
