package fi.kasvila.ostot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/**
 * v60: kuitin kuva. Kuva pienennetään (pidempi sivu enintään 2000 px, JPEG 85 %),
 * käännetään oikein päin ja tallennetaan kansioon filesDir/ostot/kuitit/<kerta>.jpg.
 * Työpöytä lukee hinnat kuvasta tekoälyllä: Varasto → Puhelimesta → Lue kuittikuva.
 */
object OstoPhoto {
    private const val MAX_SIDE = 2000

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "ostot/kuitit").apply { mkdirs() }

    fun file(context: Context, s: OstoSession): File? =
        if (s.receiptPhoto.isBlank()) null
        else File(dir(context), s.receiptPhoto).takeIf { it.exists() }

    /** Kuva base64-tekstinä lähetystä varten, tai "" jos kuvaa ei ole. */
    fun base64(context: Context, s: OstoSession): String =
        file(context, s)?.let { runCatching { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }.getOrNull() } ?: ""

    /** Pieni esikatselukuva. */
    fun thumbnail(context: Context, s: OstoSession, maxSide: Int = 600): Bitmap? {
        val f = file(context, s) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Lukee kuvan [uri]:sta, pienentää ja tallentaa kerran [sessionId] kuvaksi.
     * Palauttaa (tiedostonimi, tunniste) tai null, jos kuvaa ei saatu luettua.
     * Kutsu taustasäikeessä.
     */
    fun save(context: Context, uri: Uri, sessionId: String): Pair<String, String>? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = maxOf(bmp.width, bmp.height)
        if (side > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val rotation = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }
        val name = sessionId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg"
        val out = File(dir(context), name)
        val tmp = File(dir(context), "$name.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        val hash = MessageDigest.getInstance("SHA-1").digest(out.readBytes())
            .joinToString("") { "%02x".format(it) }.take(12)
        name to hash
    }.getOrNull()

    fun delete(context: Context, s: OstoSession) {
        file(context, s)?.delete()
    }
}
