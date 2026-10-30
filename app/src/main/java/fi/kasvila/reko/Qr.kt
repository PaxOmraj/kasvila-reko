package fi.kasvila.reko

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR generation (v46). Turns a payment payload (SEPA/EPC or a MobilePay link,
 * see [ReceiptBuilder.qrPayload]) into a black-and-white bitmap used both on the
 * PDF receipt and, as an image, on the thermal printer. Pure and offline —
 * ZXing only, no network.
 */
object Qr {

    /** A square QR bitmap for [payload], or null if it is blank or encoding fails. */
    fun bitmap(payload: String, size: Int = 480): Bitmap? {
        if (payload.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (x in 0 until w) {
                for (yy in 0 until h) {
                    bmp.setPixel(x, yy, if (matrix.get(x, yy)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
