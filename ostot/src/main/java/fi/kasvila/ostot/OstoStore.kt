package fi.kasvila.ostot

import android.content.Context
import java.io.File

/**
 * Ostot-osan tallennus: yksi JSON-tiedosto sovelluksen omassa kansiossa.
 * Kirjoitus tehdään ensin väliaikaistiedostoon ja vaihdetaan sitten paikalleen,
 * jotta katkennut tallennus ei koskaan jätä puolikasta tiedostoa.
 * Edellinen versio jää talteen (.bak) ja luetaan, jos päätiedosto on rikki.
 */
class OstoStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "ostot").apply { mkdirs() }
    private val file = File(dir, "ostot.json")
    private val backup = File(dir, "ostot.json.bak")

    fun load(): OstoState {
        for (f in listOf(file, backup)) {
            if (!f.exists()) continue
            val st = runCatching {
                ostoJson.decodeFromString(OstoState.serializer(), f.readText(Charsets.UTF_8))
            }.getOrNull()
            if (st != null) return st
        }
        return OstoState()
    }

    @Synchronized
    fun save(state: OstoState) {
        val text = ostoJson.encodeToString(OstoState.serializer(), state)
        val tmp = File(dir, "ostot.json.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) {
            backup.delete()
            file.renameTo(backup)
        }
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
