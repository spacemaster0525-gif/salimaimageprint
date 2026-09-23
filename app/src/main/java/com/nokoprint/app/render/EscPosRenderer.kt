package com.nokoprint.app.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object EscPosRenderer {

    fun render(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()

        // ESC @ : reinitialise l imprimante
        out.write(byteArrayOf(0x1B, 0x40))

        val mono = ImageUtils.toMonochrome(bitmap)
        val widthBytes = (mono.width + 7) / 8

        // GS v 0 m xL xH yL yH : impression d image raster
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(widthBytes and 0xFF)
        out.write((widthBytes shr 8) and 0xFF)
        out.write(mono.height and 0xFF)
        out.write((mono.height shr 8) and 0xFF)
        out.write(mono.packedBits)

        // Avance papier + coupe : GS V 1
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        out.write(byteArrayOf(0x1D, 0x56, 0x01))

        return out.toByteArray()
    }
}
