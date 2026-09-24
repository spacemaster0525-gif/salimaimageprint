package com.salimaprint.app.render

import android.graphics.Bitmap
import android.graphics.Color

object ImageUtils {

    class MonoBitmap(
        val width: Int,
        val height: Int,
        val packedBits: ByteArray
    )

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
