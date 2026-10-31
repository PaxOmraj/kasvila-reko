package fi.kasvila.ostot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Puhtaan logiikan testit. Ajetaan GitHubissa jokaisella käännöksellä. */
class OstoLogicTest {

    @Test fun labelWithUnitPrice() {
        val g = Osto.parseLabel("Kikherneet\nRainbow 1 kg\n2,49\n2,49 €/kg")
        assertEquals(2.49, g.price!!, 1e-9)
        assertEquals(2.49, g.perKg!!, 1e-9)
        assertEquals(1000.0, g.packG!!, 1e-9)
    }

    @Test fun labelComparisonLine() {
        val g = Osto.parseLabel("Tahini 400 g\n4,95\nVertailuhinta 12,38 /kg")
        assertEquals(4.95, g.price!!, 1e-9)
        assertEquals(12.38, g.perKg!!, 1e-9)
        assertEquals(400.0, g.packG!!, 1e-9)
    }

    @Test fun labelPriceFromUnitPriceAndSize() {
        val g = Osto.parseLabel("Kilohinta 3,20 e/kg\n500g")
        assertEquals(1.6, g.price!!, 1e-9)
    }

    @Test fun labelNothing() {
        assertTrue(Osto.parseLabel("Tervetuloa").isEmpty)
    }

    @Test fun weighCodes() {
        assertTrue(Osto.isWeighCode("2312345012343"))
        assertEquals(12.34, Osto.weighPriceGuess("2312345012343")!!, 1e-9)
        assertFalse(Osto.isWeighCode("6411402202208"))
        assertNull(Osto.weighPriceGuess("6411402202208"))
    }

    @Test fun dates() {
        assertEquals("2027-12-31", Osto.parseDate("31.12.2027"))
        assertEquals("2027-12-31", Osto.parseDate("31.12.27"))
        assertEquals("2027-06-30", Osto.parseDate("6/27"))
        assertEquals("2027-01-05", Osto.parseDate("2027-01-05"))
        assertEquals("", Osto.parseDate("31.02.2027"))
    }

    @Test fun numbers() {
        assertEquals("1", Osto.fmt(1.0, 2))
        assertEquals("13,5", Osto.fmt(13.5, 1))
        assertEquals(1234.5, Osto.parseNum("1 234,5")!!, 1e-9)
        assertEquals("1,5 kg", Osto.qtyText(1500.0, Kind.ING))
        assertEquals("12 kpl", Osto.qtyText(12.0, Kind.PACK))
    }

    @Test fun unitPriceMatchesDesktop() {
        // 4 × 1 kg à 2,49 € sis. alv 13,5 % → 2,49 / 1,135 €/kg
        val l = OstoLine(id = "L1", kind = Kind.ING, key = "X", packages = 4.0, packQty = 1000.0,
            price = 2.49, vat = 13.5)
        assertEquals(2.49 / 1.135, Osto.unitPriceNet(l, 13.5)!!, 1e-9)
        // rivin yhteishinta 6 € 1,5 kg:sta, ilman alv:tä
        val t = l.copy(packages = null, packQty = null, amount = 1500.0, price = 6.0,
            pricePer = PricePer.TOTAL, priceInclVat = false)
        assertEquals(4.0, Osto.unitPriceNet(t, 13.5)!!, 1e-9)
        // kilohinta pakkaukselle ei käy
        assertNull(Osto.unitPriceNet(l.copy(kind = Kind.PACK, pricePer = PricePer.KG), 13.5))
    }

    @Test fun uploadHasNoPrivateFields() {
        val l = OstoLine(id = "L1", kind = Kind.ING, key = "X", packages = 2.0, packQty = 500.0, price = 1.0)
        val st = OstoState(
            sessions = listOf(
                OstoSession(id = "S-1", date = "2026-10-30", finishedAt = "x", sentAt = "y", lines = listOf(l)),
                OstoSession(id = "S-2", lines = listOf(l))  // kesken: ei lähde
            )
        )
        val up = Osto.buildUpload(st, "phone-1", "Testi")
        assertFalse(up.contains("_sent"))
        assertTrue(up.contains("\"kasvila-osto-sync\""))
        assertTrue(up.contains("\"S-1\""))
        assertFalse(up.contains("\"S-2\""))
        assertTrue(up.contains("\"pack_qty\""))
    }

    @Test fun catalogRoundTrip() {
        val text = """
            {"format": "kasvila-osto-sync", "version": 1, "kind": "catalog", "device_id": "desk-1",
             "default_vat": 13.5, "future_field": {"a": 1},
             "items": [{"kind": "ing", "key": "Chickpeas (dried)", "name": "Kikherne", "unit": "g",
                        "price": 2.19, "vat": 13.5, "supplier": "Valio Aimo", "stock": 2300, "min": null,
                        "used": true, "eans": ["6411402202208"],
                        "last": {"date": "2026-10-12", "price": 2.19, "shop": "Valio Aimo"}},
                       {"kind": "pack", "key": "Purkki 250", "name": "", "unit": "kpl", "price": 0.3,
                        "vat": 25.5, "supplier": "", "stock": null, "min": null, "used": true, "eans": []}],
             "ean_library": [{"ean": "6411402202208", "kind": "ing", "key": "Chickpeas (dried)",
                              "pack_qty": 1000, "name": "Kikherneet 1 kg", "brand": "", "updated_at": ""}],
             "shops": ["Lidl"], "shopping_list": [{"kind": "ing", "key": "Chickpeas (dried)", "need": 2700.0}],
             "sessions_status": [{"id": "S-1", "status": "approved", "decided_at": "2026-10-30T15:10:00", "note": ""}],
             "received": ["S-1"]}
        """.trimIndent()
        val p = Osto.parseCatalog(text)
        assertTrue(p is Osto.Parsed.Catalog)
        val cat = (p as Osto.Parsed.Catalog).catalog
        assertEquals(2, cat.items.size)
        assertNull(cat.items[0].min)
        val learned = EanEntry("6411402202208", Kind.ING, "Chickpeas (dried)", 1000.0)
        val st = Osto.applyCatalog(OstoState(sessions = listOf(OstoSession(id = "S-1")), learned = listOf(learned)), cat, "lan")
        assertTrue(st.learned.isEmpty())          // työpöytä tuntee jo saman
        assertTrue(st.sessions[0].sentAt.isNotBlank())
        assertTrue(st.isDecided("S-1"))
        assertNotNull(Osto.lookup(st, "6411402202208"))
        assertNull(Osto.lookup(st, "2312345012343"))
    }

    @Test fun catalogWrongFormat() {
        assertTrue(Osto.parseCatalog("{\"format\": \"kasvila-reko-sync\"}") is Osto.Parsed.Error)
        assertTrue(Osto.parseCatalog("{\"format\": \"kasvila-osto-sync\", \"kind\": \"catalog\", \"version\": 9}") is Osto.Parsed.Error)
        assertTrue(Osto.parseCatalog("ei json") is Osto.Parsed.Error)
    }

    @Test fun searchIgnoresAccents() {
        val items = listOf(
            CatalogItem(Kind.ING, "a", "Härkäpapu, fermentoitu", used = false),
            CatalogItem(Kind.ING, "b", "Kaura", used = true),
            CatalogItem(Kind.PACK, "c", "Harkapurkki")
        )
        assertEquals(listOf("a"), Osto.search(items, "harka", setOf(Kind.ING)).map { it.key })
        assertEquals(listOf("b", "a"), Osto.search(items, "", setOf(Kind.ING)).map { it.key })
    }

    @Test fun cartTicksShoppingList() {
        val l = OstoLine(id = "L1", kind = Kind.ING, key = "X", packages = 2.0, packQty = 1000.0)
        val st = OstoState(sessions = listOf(
            OstoSession(id = "S-1", lines = listOf(l, l.copy(id = "L2", amount = 500.0))),
            OstoSession(id = "S-2", type = "count", lines = listOf(l))
        ))
        assertEquals(2500.0, Osto.inCart(st)[Kind.ING to "X"]!!, 1e-9)
    }
}
