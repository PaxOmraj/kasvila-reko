package fi.kasvila.reko

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The v2 exchange file, `kasvila-reko-sync`.
 *
 * One symmetric format for BOTH directions: the phone writes exactly what the
 * desktop writes, and both read it with the same code. That is the whole point —
 * the v1 world had `kasvila-reko-rings` going one way and `kasvila-reko-bundle`
 * the other, which meant two merge paths, two sets of bugs, and a catalog that
 * could only ever be replaced rather than merged.
 *
 * Every record carries `updated_at` / `deleted` / `origin`, so the receiver can
 * merge record-by-record instead of trusting the file wholesale. `last_sync_at`
 * is the sender's memory of when these two devices last agreed — it is what lets
 * the merge tell "changed since we last talked" from "unchanged all along", and
 * therefore what makes real conflict detection possible instead of blind
 * last-writer-wins.
 */
@Serializable
data class SyncPayload(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val device_id: String = "",
    val device_name: String = "",
    val exported_at: String = "",
    /** When the SENDER last synced with the receiver. Blank = never. */
    val last_sync_at: String = "",
    val currency: String = "€",
    val default_vat: Double = 13.5,
    val catalog_version: Int = 0,
    val rings: List<Ring> = emptyList(),
    val products: List<Product> = emptyList(),
    val orders: List<Order> = emptyList(),
    val deliveries: List<Delivery> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val distributions: List<Distribution> = emptyList(),
    val aliases: Map<String, List<String>> = emptyMap()
) {
    companion object {
        const val FORMAT = "kasvila-reko-sync"
        const val VERSION = 2
    }
}

/** What a picked file turned out to be. */
sealed interface ParsedFile {
    /** The v2 two-way sync file. */
    data class Sync(val payload: SyncPayload) : ParsedFile

    /** A v1 desktop catalog export. Still accepted; merged, never applied raw. */
    data class LegacyRings(val payload: RingsPayload) : ParsedFile

    /** A v1 phone export (orders / deliveries / bundle) — e.g. a file from a
     *  second phone, or one this phone wrote before v42. */
    data class LegacyBundle(val payload: BundlePayload) : ParsedFile

    /** Recognised as JSON but not one of ours, or a version we are too old for. */
    data class Unsupported(val reason: String) : ParsedFile
}

object SyncFiles {

    /** Suggested file name for an export. */
    fun fileName(): String = "kasvila_reko_sync.json"

    /**
     * Build this phone's side of a sync.
     *
     * Sends the WHOLE state, not a delta. At this data size (hundreds of products,
     * thousands of orders) a full state is a few hundred kilobytes, and it makes
     * the exchange self-healing: a file that went missing, a sync that was
     * abandoned half way, a peer restored from an old backup — all recover on the
     * next exchange, because nothing depends on the receiver having seen an
     * earlier increment. Deltas would be smaller and much easier to get wrong.
     *
     * Tombstones ride along deliberately: a deleted record is data too, and
     * dropping it is exactly how a delete gets silently undone by the other side.
     */
    fun build(state: AppState, peerId: String): SyncPayload = SyncPayload(
        device_id = state.deviceId,
        device_name = state.deviceName,
        exported_at = Reko.nowIso(),
        last_sync_at = state.peers[peerId]?.lastSyncAt ?: "",
        currency = state.currency,
        default_vat = state.defaultVat,
        catalog_version = state.catalogVersion,
        // `retired` is a local-only presentation flag (a ring dropped from the
        // catalog that still has open orders). It must not travel, or the other
        // device would treat our local bookkeeping as a real catalog change.
        rings = state.rings.map { it.copy(retired = false) },
        products = state.products,
        orders = state.orders,
        deliveries = state.deliveries,
        customers = state.customers,
        distributions = state.distributions,
        aliases = state.localAliases
    )

    fun encode(p: SyncPayload): String = JSON.encodeToString(p)

    fun buildText(state: AppState, peerId: String): String = encode(build(state, peerId))

    /**
     * Work out what a file is and decode it. Never throws: the caller gets a
     * [ParsedFile.Unsupported] with a Finnish reason it can put on screen.
     *
     * Format detection is by the `format` field rather than by trying each
     * decoder in turn, so a malformed file of a KNOWN type reports "this sync
     * file is damaged" instead of the far more confusing "this is not a Kasvila
     * file at all".
     */
    fun parse(text: String): ParsedFile {
        val fmt = try {
            Json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            return ParsedFile.Unsupported("Tiedosto ei ole kelvollista JSON-muotoa.")
        }
        return when (fmt) {
            SyncPayload.FORMAT -> {
                val p = try {
                    JSON.decodeFromString<SyncPayload>(text)
                } catch (e: Exception) {
                    return ParsedFile.Unsupported("Synkronointitiedosto on vioittunut: ${e.message}")
                }
                // Refuse a newer format rather than guess at fields we don't know.
                if (p.version > SyncPayload.VERSION) {
                    return ParsedFile.Unsupported(
                        "Tiedosto on uudempaa versiota (v${p.version}) kuin tämä sovellus " +
                            "ymmärtää (v${SyncPayload.VERSION}). Päivitä sovellus."
                    )
                }
                ParsedFile.Sync(p)
            }

            "kasvila-reko-rings" -> try {
                ParsedFile.LegacyRings(JSON.decodeFromString(text))
            } catch (e: Exception) {
                ParsedFile.Unsupported("Katalogitiedosto on vioittunut: ${e.message}")
            }

            "kasvila-reko-bundle" -> try {
                ParsedFile.LegacyBundle(JSON.decodeFromString(text))
            } catch (e: Exception) {
                ParsedFile.Unsupported("Tilaustiedosto on vioittunut: ${e.message}")
            }

            "kasvila-reko-orders" -> try {
                val o = JSON.decodeFromString<OrdersPayload>(text)
                ParsedFile.LegacyBundle(BundlePayload(exported_at = o.exported_at, orders = o.orders))
            } catch (e: Exception) {
                ParsedFile.Unsupported("Tilaustiedosto on vioittunut: ${e.message}")
            }

            "kasvila-reko-deliveries" -> try {
                val d = JSON.decodeFromString<DeliveriesPayload>(text)
                ParsedFile.LegacyBundle(BundlePayload(exported_at = d.exported_at, deliveries = d.deliveries))
            } catch (e: Exception) {
                ParsedFile.Unsupported("Kuljetustiedosto on vioittunut: ${e.message}")
            }

            "" -> ParsedFile.Unsupported("Tiedostosta puuttuu muototunniste — se ei ole Kasvila REKO -tiedosto.")
            else -> ParsedFile.Unsupported("Tuntematon muototunniste: '$fmt'.")
        }
    }

    /**
     * Adapt a v1 catalog export into a v2 payload so there is exactly ONE merge
     * path. The important part is what we do about missing timestamps.
     *
     * A v1 file carries no per-record `updated_at`. If we stamped those records
     * "now", every one of them would look freshly edited on the desktop and would
     * beat anything the phone had changed — the v41 "replace the catalog" bug
     * wearing a merge engine's clothes. So instead each incoming record inherits
     * the file's own `exported_at`: that IS when the desktop last wrote it, as
     * closely as a v1 file can say. Records the phone has edited since that export
     * then correctly win, and records it hasn't correctly lose.
     */
    fun fromLegacyRings(p: RingsPayload, peerDeviceId: String): SyncPayload {
        val stamp = p.exported_at.ifBlank { "1970-01-01T00:00:00" }
        return SyncPayload(
            device_id = peerDeviceId,
            device_name = "Työpöytäohjelma",
            exported_at = p.exported_at.ifBlank { Reko.nowIso() },
            last_sync_at = "",
            currency = p.currency,
            default_vat = p.default_vat,
            catalog_version = p.catalog_version,
            rings = p.rings.map {
                it.copy(
                    updated_at = it.updated_at.ifBlank { stamp },
                    origin = it.origin.ifBlank { peerDeviceId },
                    retired = false
                )
            },
            products = p.products.map {
                it.copy(
                    updated_at = it.updated_at.ifBlank { stamp },
                    origin = it.origin.ifBlank { peerDeviceId },
                    // A v1 export omitted inactive products entirely, so anything
                    // that IS in the file is by definition in use.
                    active = true
                )
            }
        )
    }

    /** Adapt a v1 phone bundle (orders + deliveries + aliases) into a v2 payload. */
    fun fromLegacyBundle(p: BundlePayload, peerDeviceId: String): SyncPayload {
        val stamp = p.exported_at.ifBlank { "1970-01-01T00:00:00" }
        return SyncPayload(
            device_id = peerDeviceId,
            device_name = "Toinen laite",
            exported_at = p.exported_at.ifBlank { Reko.nowIso() },
            last_sync_at = "",
            orders = p.orders.map { it.copy(updated_at = it.updated_at.ifBlank { stamp }) },
            deliveries = p.deliveries.map { it.copy(updated_at = it.updated_at.ifBlank { stamp }) },
            aliases = p.aliases
        )
    }

    /**
     * A v1 file tells us nothing about which device wrote it, but the merge needs
     * a stable peer key to remember "when did we last sync with this one".
     * Derive a deterministic pseudo-id so repeated imports of files from the same
     * source keep accumulating against the same peer entry instead of creating a
     * new one each time.
     */
    fun legacyPeerId(kind: String): String = "legacy_$kind"
}
