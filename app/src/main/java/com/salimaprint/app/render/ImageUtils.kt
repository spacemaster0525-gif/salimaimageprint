package com.salimaprint.app.render

import android.graphics.Bitmap
import android.graphics.Color

object ImageUtils {

    class MonoBitmap(
        val width: Int,
        val height: Int,
        val packedBits: ByteArray
    )

    /**
     * Redimensionne l'image pour qu'elle ne dépasse pas la largeur imprimable
     * de la plupart des imprimantes thermiques USB (384 points ≈ 58mm à 203dpi).
     * Sans cette étape, une photo issue de l'appareil photo (souvent 3000-4000px
     * de large) est envoyée telle quelle : beaucoup d'imprimantes ignorent
     * silencieusement une image qui dépasse leur largeur imprimable, ce qui
     * donne l'impression que la tâche est envoyée sans aucune réaction.
     */
    fun scaleToPrinterWidth(bitmap: Bitmap, maxWidth: Int = 384): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    }

    fun toMonochrome(bitmap: Bitmap, threshold: Int = 128): MonoBitmap {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8
        val packed = ByteArray(widthBytes * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = (Color.red(pixel) * 0.3 +
                        Color.green(pixel) * 0.59 +
                        Color.blue(pixel) * 0.11)
                val isBlack = gray < threshold
                if (isBlack) {
                    val byteIndex = y * widthBytes + (x / 8)
                    val bitIndex = 7 - (x % 8)
                    packed[byteIndex] = (packed[byteIndex].toInt() or
                            (1 shl bitIndex)).toByte()
                }
            }
        }
        return MonoBitmap(width, height, packed)
    }
}
