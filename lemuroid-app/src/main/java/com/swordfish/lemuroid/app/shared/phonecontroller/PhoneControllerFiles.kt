package com.swordfish.lemuroid.app.shared.phonecontroller

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/**
 * The hub (main process) and the game (":game" process) run separate servers.
 * They tell each other their ports through small files in the app data folder.
 */
object PhoneControllerFiles {
    private fun hubPortFile(context: Context) = File(context.filesDir, "phone_hub_port")

    private fun gamePortFile(context: Context) = File(context.filesDir, "phone_game_port")

    fun writeHubPort(
        context: Context,
        port: Int,
    ) = runCatching { hubPortFile(context).writeText(port.toString()) }

    fun readHubPort(context: Context): Int? = runCatching { hubPortFile(context).readText().trim().toInt() }.getOrNull()

    fun writeGamePort(
        context: Context,
        port: Int,
    ) = runCatching { gamePortFile(context).writeText(port.toString()) }

    fun clearGamePort(context: Context) = runCatching { gamePortFile(context).delete() }

    fun readGamePort(context: Context): Int? = runCatching { gamePortFile(context).readText().trim().toInt() }.getOrNull()

    fun createQrBitmap(text: String): ImageBitmap? {
        return runCatching {
            val matrix =
                QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(EncodeHintType.MARGIN to 0),
                )
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
}
