package rs.pametnakupovina.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/**
 * Kod mora da proradi iz prve, pred redom ljudi na kasi, pa se crta u crno na
 * belo bez ijednog ukrasa i bez providnosti — čitač gleda kontrast, ne temu
 * aplikacije.
 */
@Composable
fun Barcode(
    value: String,
    format: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(value, format) { renderBarcode(value, format) } ?: return

    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = "Crtični kod: $value",
        contentScale = ContentScale.FillBounds,
        modifier = modifier
    )
}

internal fun renderBarcode(value: String, format: String): Bitmap? {
    val barcodeFormat = runCatching { BarcodeFormat.valueOf(format) }
        .getOrElse { return null }
    val square = barcodeFormat == BarcodeFormat.QR_CODE ||
        barcodeFormat == BarcodeFormat.PDF_417
    val width = if (square) 512 else 1024
    val height = if (square) 512 else 320

    // Kartice lojalnosti često nose brojeve koji ne staju u oblik u kom su
    // odštampane — 16 cifara nije EAN-13. Prazan ekran na kasi je najgori
    // mogući ishod, pa se tada crta CODE-128, koji prima bilo koji broj i
    // koji svaki trgovački čitač razume.
    val matrix = runCatching {
        MultiFormatWriter().encode(value, barcodeFormat, width, height)
    }.recoverCatching {
        MultiFormatWriter().encode(value, BarcodeFormat.CODE_128, 1024, 320)
    }.getOrElse { return null }

    val bitmap = createBitmap(matrix.width, matrix.height)

    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
        }
    }

    return bitmap
}
