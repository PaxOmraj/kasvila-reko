package fi.kasvila.ostot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tietosisältö "kasvila-osto-sync" v1. Sama kuvaus: docs/OSTOT_TIETOSISALTO.md,
 * työpöydän vastinpari kasvila/ydin/ostosovellus.py.
 *
 * Kenttien nimet ovat JSON-nimiä (snake_case), koska sama tiedosto kulkee
 * Python-ohjelmaan sellaisenaan.
 */

const val OSTO_FORMAT = "kasvila-osto-sync"
const val OSTO_VERSION = 1

/** Rivin tuotelaji työpöydällä. */
object Kind {
    const val ING = "ing"          // raaka-aine, grammoina
    const val PACK = "pack"        // pakkaus, kappaleina
    const val PRODUCT = "product"  // oma tuote, kappaleina (vain inventaariossa)
}

/** Mistä hinta tuli. */
object PriceSource {
    const val MANUAL = "manual"
    const val LABEL = "label"
    const val WEIGH = "weigh"
    const val LAST = "last"
    const val NONE = "none"
}

/** Mihin hinta kohdistuu. */
object PricePer {
    const val PACKAGE = "package"
    const val KG = "kg"
    const val UNIT = "unit"
    const val TOTAL = "total"
}

@Serializable
data class OstoLine(
    val id: String,
    val ean: String = "",
    val name: String = "",
    val brand: String = "",
    val kind: String = "",
    val key: String = "",
    val packages: Double? = null,
    @SerialName("pack_qty") val packQty: Double? = null,
    val amount: Double? = null,
    val price: Double? = null,
    @SerialName("price_per") val pricePer: String = PricePer.PACKAGE,
    @SerialName("price_incl_vat") val priceInclVat: Boolean = true,
    val vat: Double? = null,
    @SerialName("price_source") val priceSource: String = PriceSource.NONE,
    @SerialName("best_before") val bestBefore: String = "",
    val note: String = ""
) {
    /** Kokonaismäärä grammoina tai kappaleina, kuten työpöytä sen laskee. */
    fun totalQty(): Double? = amount ?: run {
        val n = packages ?: return null
        val pq = packQty ?: return null
        n * pq
    }
}

@Serializable
data class OstoSession(
    val id: String,
    val type: String = "purchase",          // purchase | count
    val shop: String = "",
    val date: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("finished_at") val finishedAt: String = "",
    val operator: String = "",
    val note: String = "",
    @SerialName("receipt_total") val receiptTotal: Double? = null,
    val lines: List<OstoLine> = emptyList(),
    /** v60: kuitin kuvan tunniste. Itse kuva kulkee lähetyksessä kentässä receipt_image. */
    @SerialName("receipt_photo_hash") val receiptPhotoHash: String = "",
    /** v60: base64-JPEG, täytetään vain lähetykseen (ks. Osto.buildUpload). */
    @SerialName("receipt_image") val receiptImage: String = "",
    // ── vain puhelimessa: eivät lähde työpöydälle (ks. toUpload) ──
    @SerialName("_receipt_photo") val receiptPhoto: String = "",
    @SerialName("_photo_sent_hash") val photoSentHash: String = "",
    @SerialName("_sent_at") val sentAt: String = "",
    @SerialName("_sent_via") val sentVia: String = "",
    @SerialName("_changed_after_send") val changedAfterSend: Boolean = false
) {
    val isCount: Boolean get() = type == "count"
    val isFinished: Boolean get() = finishedAt.isNotBlank()
}

@Serializable
data class EanEntry(
    val ean: String,
    val kind: String = "",
    val key: String = "",
    @SerialName("pack_qty") val packQty: Double? = null,
    val name: String = "",
    val brand: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class LastPurchase(
    val date: String = "",
    val price: Double = 0.0,
    val shop: String = ""
)

@Serializable
data class CatalogItem(
    val kind: String,
    val key: String,
    val name: String = "",
    val unit: String = "g",
    /** ALV 0 %, €/kg raaka-aineille, €/kpl muille. */
    val price: Double = 0.0,
    val vat: Double? = null,
    val supplier: String = "",
    val stock: Double? = null,
    val min: Double? = null,
    val used: Boolean = false,
    val eans: List<String> = emptyList(),
    val last: LastPurchase? = null
) {
    val isIng: Boolean get() = kind == Kind.ING
    fun label(): String = name.ifBlank { key }
}

@Serializable
data class ShoppingNeed(
    val kind: String = Kind.ING,
    val key: String,
    val need: Double = 0.0
)

@Serializable
data class SessionStatus(
    val id: String,
    val status: String = "pending",
    @SerialName("decided_at") val decidedAt: String = "",
    val note: String = "",
    /** v60: kuittikuva, joka työpöydällä on (tunniste). */
    val photo: String = ""
)

@Serializable
data class OstoCatalog(
    val format: String = OSTO_FORMAT,
    val version: Int = OSTO_VERSION,
    val kind: String = "catalog",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("exported_at") val exportedAt: String = "",
    val currency: String = "€",
    @SerialName("default_vat") val defaultVat: Double = 13.5,
    val items: List<CatalogItem> = emptyList(),
    @SerialName("ean_library") val eanLibrary: List<EanEntry> = emptyList(),
    val shops: List<String> = emptyList(),
    @SerialName("shopping_list") val shoppingList: List<ShoppingNeed> = emptyList(),
    @SerialName("sessions_status") val sessionsStatus: List<SessionStatus> = emptyList(),
    /** Vain lähiverkon vastauksessa: ne kerrat, jotka työpöytä otti vastaan. */
    val received: List<String> = emptyList()
)

@Serializable
data class OstoUpload(
    val format: String = OSTO_FORMAT,
    val version: Int = OSTO_VERSION,
    val kind: String = "upload",
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("exported_at") val exportedAt: String,
    val sessions: List<OstoSession>,
    @SerialName("ean_learned") val eanLearned: List<EanEntry>
)

/** Kaikki mitä Ostot-osa pitää puhelimessa. Yksi tiedosto: filesDir/ostot/ostot.json */
@Serializable
data class OstoState(
    val sessions: List<OstoSession> = emptyList(),
    /** Puhelimessa opitut koodit, jotka lähtevät työpöydälle seuraavassa lähetyksessä. */
    val learned: List<EanEntry> = emptyList(),
    val catalog: OstoCatalog = OstoCatalog(),
    @SerialName("catalog_received_at") val catalogReceivedAt: String = "",
    val operator: String = "",
    @SerialName("last_shop") val lastShop: String = "",
    @SerialName("last_host") val lastHost: String = ""
) {
    fun statusOf(id: String): SessionStatus? = catalog.sessionsStatus.firstOrNull { it.id == id }

    /** Työpöytä on jo päättänyt kerrasta (kirjattu tai hylätty): sitä ei enää muokata. */
    fun isDecided(id: String): Boolean = statusOf(id)?.status.let { it != null && it != "pending" }
}

val ostoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = true
}

/** Lähetykseen vain työpöydän kentät: puhelimen omat _-kentät jätetään pois. */
fun OstoSession.forUpload(): OstoSession = copy(sentAt = "", sentVia = "", changedAfterSend = false)
