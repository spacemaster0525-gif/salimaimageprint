package com.nokoprint.app.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object PclRenderer {

    private const val ESC = 0x1B

    fun render(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val mono = ImageUtils.toMonochrome(bitmap)
        val widthBytes = (mono.width + 7) / 8

        // Enveloppe PJL
        writeUel(out)
        writePjlLine(out, "@PJL JOB NAME=\"NokoPrint\"")
        writePjlLine(out, "@PJL ENTER LANGUAGE=PCL")

        // Reset imprimante
        out.write(ESC); out.write(69)

        // Resolution raster : 300 dpi
        writeEsc(out, "*t300R")
        // Demarrage du mode raster
        writeEsc(out, "*r1A")

        var offset = 0
        for (row in 0 until mono.height) {
            writeEsc(out, "*b${widthBytes}W")
            out.write(mono.packedBits, offset, widthBytes)
            offset += widthBytes
        }

        // Fin du mode raster
        writeEsc(out, "*rB")
        // Ejection de page
        out.write(12)

        // Fin de tache PJL
        writeUel(out)
        writePjlLine(out, "@PJL EOJ")
        writeUel(out)

        return out.toByteArray()
    }

    private fun writeUel(out: ByteArrayOutputStream) {
        out.write(ESC)
        out.write("%-12345X".toByteArray(Charsets.US_ASCII))
    }

    private fun writePjlLine(out: ByteArrayOutputStream, line: String) {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(0x0D); out.write(0x0A)
    }

    private fun writeEsc(out: ByteArrayOutputStream, sequence: String) {
        out.write(ESC)
        out.write(sequence.toByteArray(Charsets.US_ASCII))
    }
}
