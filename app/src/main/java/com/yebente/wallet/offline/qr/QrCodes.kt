package com.yebente.wallet.offline.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
  fun toBitmap(text: String, sizePx: Int = 640): Bitmap {
    val hints = mapOf(
      EncodeHintType.CHARACTER_SET to "UTF-8",
      EncodeHintType.MARGIN to 1,
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
      for (y in 0 until sizePx) {
        bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
      }
    }
    return bitmap
  }
}
