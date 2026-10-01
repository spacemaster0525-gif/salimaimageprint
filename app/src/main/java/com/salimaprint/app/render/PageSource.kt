package com.salimaprint.app.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** مصدر صفحات للطباعة: صورة واحدة، أو ملف PDF متعدد الصفحات. */
interface PageSource : Closeable {
    val count: Int
    fun render(index: Int): Bitmap
    /** يُستدعى بعد طباعة الصفحة لتحرير الـ Bitmap الذي أعطاه render. */
    fun release(bitmap: Bitmap)
}

class BitmapPageSource(private val bitmap: Bitmap) : PageSource {
    override val count: Int = 1
    override fun render(index: Int): Bitmap = bitmap
    override fun release(bitmap: Bitmap) { /* يُحرَّر في close */ }
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

class PdfPageSource(file: File) : PageSource {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    override val count: Int get() = renderer.pageCount

    override fun render(index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val wIn = page.width / 72f
            val hIn = page.height / 72f
            // أقصى دقة 400 dpi، وبحد أقصى 4700 بكسل للضلع الأطول
            var dpi = minOf(400f, 4700f / maxOf(wIn, hIn))
            while (true) {
                try {
                    val w = maxOf(1, (wIn * dpi).toInt())
                    val h = maxOf(1, (hIn * dpi).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bmp
                } catch (e: OutOfMemoryError) {
                    if (dpi <= 200f) throw e
                    dpi -= 100f
                }
            }
        } finally {
            page.close()
        }
    }

    override fun release(bitmap: Bitmap) { if (!bitmap.isRecycled) bitmap.recycle() }

    override fun close() {
        try { renderer.close() } catch (_: Exception) { }
        try { pfd.close() } catch (_: Exception) { }
    }
}
