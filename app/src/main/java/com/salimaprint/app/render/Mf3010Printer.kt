package com.salimaprint.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.min

/**
 * تحويل صورة إلى مهمة طباعة لـ Canon MF3010:
 * صفحة A4 بمساحة طباعة 4720 × 4516 (أفقياً 600 dpi، عمودياً 400 dpi)،
 * مقسمة إلى 18 band من 256 سطراً، كل band مضغوط بـ JBIG1.
 */
object Mf3010Printer {
    const val PAGE_W = 4720
    const val PAGE_H = 4516
    const val BAND = 256
    private const val DPI_X = 600f
    private const val DPI_Y = 400f
    /** عتبة الأسود بعد تسوية الإضاءة (0..255 نسبةً إلى لون الورقة المحلي) */
    private const val FLAT_THRESHOLD = 185f

    /**
     * تصغير تدريجي (نصف في كل خطوة) حتى يصبح المصدر ضمن ضعف حجم الرسم الهدف.
     * رسم صورة كبيرة بمرشح ثنائي الخطية مباشرة يتخطى بكسلات المصدر فتضيع الخطوط الرفيعة؛
     * التصغير بنصف الخطوة يعادل تقريباً متوسط المساحة فيحفظ الخط الرفيع كدرجة رمادية قابلة للعتبة.
     */
    private fun prescale(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var cur = src
        while (cur.width >= targetW * 2 && cur.height >= targetH * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }

    fun buildJob(context: Context, source: Bitmap, dither: Boolean, threshold: Int = 128): ByteArray {
        val prefix = context.assets.open("job_prefix.bin").use { it.readBytes() }
        val suffix = context.assets.open("job_suffix.bin").use { it.readBytes() }

        // الصور العرضية تُدار 90° لتملأ الصفحة الطولية
        val src = if (source.width > source.height) {
            val m = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else source

        // مساحة الطباعة الفيزيائية بالبوصة، ثم أكبر تحجيم يحفظ النسبة
        val physW = PAGE_W / DPI_X
        val physH = PAGE_H / DPI_Y
        val inchPerPx = min(physW / src.width, physH / src.height)
        val drawW = src.width * inchPerPx * DPI_X
        val drawH = src.height * inchPerPx * DPI_Y
        val offX = (PAGE_W - drawW) / 2f
        val offY = (PAGE_H - drawH) / 2f
        val drawSrc = prescale(src, drawW.toInt(), drawH.toInt())

        // صور المستندات (بدون تنقيط): تسوية الإضاءة كي لا تتحول الظلال والزوايا المعتمة إلى سواد
        val flat: DocFlatten? = if (!dither) {
            val sc = 320f / maxOf(src.width, src.height)
            val sw = maxOf(1, (src.width * sc).toInt())
            val sh = maxOf(1, (src.height * sc).toInt())
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val sp = IntArray(sw * sh)
            small.getPixels(sp, 0, sw, 0, 0, sw, sh)
            if (small !== src) small.recycle()
            DocFlatten(sp, sw, sh, PAGE_W, offX, drawW, offY, drawH)
        } else null
        val bgRow = FloatArray(PAGE_W)

        val bandBmp = Bitmap.createBitmap(PAGE_W, BAND, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bandBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val pixels = IntArray(PAGE_W * BAND)
        val rowBytes = PAGE_W / 8
        val pageBits = ByteArray(rowBytes * PAGE_H)
        val bandCount = (PAGE_H + BAND - 1) / BAND
        val bands = arrayOfNulls<ByteArray>(bandCount)
        val rowsPerBand = IntArray(bandCount)

        // أخطاء Floyd–Steinberg تنتقل بين الأسطر وبين الـ bands
        var errCur = FloatArray(PAGE_W + 2)
        var errNext = FloatArray(PAGE_W + 2)

        for (b in 0 until bandCount) {
            val rows = min(BAND, PAGE_H - b * BAND)
            rowsPerBand[b] = rows
            canvas.drawColor(Color.WHITE)
            val m = Matrix()
            m.postScale(drawW / drawSrc.width, drawH / drawSrc.height)
            m.postTranslate(offX, offY - b * BAND)
            canvas.drawBitmap(drawSrc, m, paint)
            bandBmp.getPixels(pixels, 0, PAGE_W, 0, 0, PAGE_W, BAND)
            val bandBase = b * BAND * rowBytes

            for (y in 0 until rows) {
                val rowOff = y * PAGE_W
                val bitOff = bandBase + y * rowBytes
                if (flat != null) flat.row(b * BAND + y, bgRow)
                for (x in 0 until PAGE_W) {
                    val p = pixels[rowOff + x]
                    var gray = Color.red(p) * 0.3f + Color.green(p) * 0.59f + Color.blue(p) * 0.11f
                    val black: Boolean
                    if (dither) {
                        gray += errCur[x + 1]
                        black = gray < 128f
                        val e = gray - (if (black) 0f else 255f)
                        errCur[x + 2] += e * 7f / 16f
                        errNext[x] += e * 3f / 16f
                        errNext[x + 1] += e * 5f / 16f
                        errNext[x + 2] += e * 1f / 16f
                    } else {
                        black = if (flat != null) {
                            // bg <= 0 تعني: خارج الورقة (طاولة/ظل كثيف) فتُطبع بيضاء
                            val bg = bgRow[x]
                            bg > 0f && min(255f, gray * 255f / bg) < FLAT_THRESHOLD
                        } else {
                            gray < threshold
                        }
                    }
                    if (black) {
                        val i = bitOff + (x shr 3)
                        pageBits[i] = (pageBits[i].toInt() or (0x80 shr (x and 7))).toByte()
                    }
                }
                if (dither) {
                    val t = errCur; errCur = errNext; errNext = t
                    java.util.Arrays.fill(errNext, 0f)
                }
            }
        }
        // إزالة البقع السوداء المعزولة (تُتخطى مع التنقيط لأن نقاطه كلها معزولة بطبيعتها)
        if (!dither) Despeckle.run(pageBits, rowBytes, PAGE_W, PAGE_H)
        for (b in 0 until bandCount) {
            bands[b] = JbigBandEncoder.encodeBand(pageBits, b * BAND, rowBytes, PAGE_W, rowsPerBand[b])
        }
        bandBmp.recycle()
        if (drawSrc !== src) drawSrc.recycle()
        if (src !== source) src.recycle()

        return Mf3010Job.build(prefix, suffix, bands.requireNoNulls(), rowsPerBand)
    }
}
