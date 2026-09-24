package com.salimaprint.app.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * محرّك بناء أوامر بروتوكول Canon CAPT (Canon Advanced Printing Technology).
 *
 * ⚠️ حالة هذا الملف: هيكل عام + الأجزاء الموثقة فقط من إعادة الهندسة العكسية
 * العامة لبروتوكول CAPT (مشروع captdriver مفتوح المصدر، GPLv3).
 * القيم الخاصة بطراز LBP6030B تحديدًا (بايتات إعداد الصفحة، صيغة ضغط
 * الراستر) غير مؤكدة بعد ولازم تُستخرج من التقاط USB حقيقي (usbmon على
 * لينكس مع تعريف Canon الرسمي) قبل الاعتماد عليها في الإنتاج.
 *
 * مرجع البروتوكول العام:
 * https://github.com/agalakhov/captdriver/blob/master/SPECS (GPLv3)
 */
object CaptRenderer {

    // ---------------------------------------------------------------
    // 1) تأطير الحزمة العام (موثّق ومؤكد لكل طرازات CAPT)
    // ---------------------------------------------------------------
    // بنية كل أمر: [command: uint16 LE][size: uint16 LE شامل الهيدر][payload...]

    private fun buildCommand(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 4 + payload.size
        val out = ByteArrayOutputStream(size)
        writeU16LE(out, command)
        writeU16LE(out, size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeU16LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    // ---------------------------------------------------------------
    // 2) أوامر الحالة/التعريف (موثقة - جزء "A0/A1" من البروتوكول)
    //    هذه الأوامر بدون بيانات وليها رد من الطابعة (لازم تُقرأ قبل أي أمر تاني)
    // ---------------------------------------------------------------

    /** NOP / احصل على حالة أساسية */
    fun cmdGetStatus(): ByteArray = buildCommand(0xA0A0)

    /** احصل على حالة الطابعة */
    fun cmdGetPrinterStatus(): ByteArray = buildCommand(0xA0A1)

    /** احصل على الحالة الموسّعة (تحتوي أرقام الصفحات الجاري معالجتها/طباعتها) */
    fun cmdGetExtendedStatus(): ByteArray = buildCommand(0xA0A8)

    /**
     * احصل على معرّف الطابعة (IEEE-1284 Device ID).
     * ملاحظة: هذا الأمر رده خام (raw string) وليس بصيغة الحزمة القياسية.
     */
    fun cmdGetDeviceId(): ByteArray = buildCommand(0xA1A0)

    /** احصل على إمكانيات الطابعة */
    fun cmdGetCapabilities(): ByteArray = buildCommand(0xA1A1)

    // ---------------------------------------------------------------
    // 3) أوامر إعداد الصفحة/الضغط (0xD0xx) — هيكل الحزمة مؤكد،
    //    لكن القيم الداخلية (byte values) تختلف من طراز لآخر ولازم
    //    تُستخرج فعليًا من التقاط حقيقي لطابعتك. القيم هنا Placeholders
    //    مبنية على أقرب طراز موثّق (lbp3010) ولازم تتفحص/تتعدل.
    // ---------------------------------------------------------------

    data class PageParams(
        val widthPixels: Int,      // عرض الصفحة بالبكسل (600 dpi)
        val heightPixels: Int,     // ارتفاع الصفحة بالبكسل
        val lineSizeBytes: Int,    // عرض السطر بالبايت (LINESIZE)
        val heightLines: Int,      // عدد الأسطر
        val marginWidth: Int = 0,
        val marginHeight: Int = 0,
        val tonerDensity: Int = 0x1c  // TODO: تأكيد القيمة الفعلية للطابعة
    )

    /**
     * أمر "معاملات الصفحة" (0xD0A0). إلزامي قبل كل صفحة.
     * TODO: البايتات المُعلّمة أدناه بحاجة لتأكيد من التقاط حقيقي —
     * راجع دالة parsePageSetupFromCapture() في أداة التحليل.
     */
    fun cmdPageParams(params: PageParams): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16LE(out, 0x0000)          // ?
        writeU16LE(out, 0x0000)          // TODO: قيمة ثابتة تخص الطراز (model magic)
        writeU16LE(out, 0x0002)          // ? مرتبط بأبعاد الصفحة
        writeU16LE(out, 0x0000)
        out.write(byteArrayOf(
            params.tonerDensity.toByte(), params.tonerDensity.toByte(),
            params.tonerDensity.toByte(), params.tonerDensity.toByte()
        ))
        out.write(0x00)                  // نوع الورق: TODO تأكيد (Plain=?)
        out.write(0x11)                  // TODO: قيمة مرتبطة بحجم الصفحة
        writeU16LE(out, 0x0004)
        out.write(0x00); out.write(0x01); out.write(0x01); out.write(0x02)
        out.write(0x00)                  // توفير الحبر: 0=لا
        writeU16LE(out, 0x0000)
        writeU16LE(out, params.marginHeight)
        writeU16LE(out, params.marginWidth)
        writeU16LE(out, params.lineSizeBytes)
        writeU16LE(out, params.heightLines)
        writeU16LE(out, params.widthPixels)
        writeU16LE(out, params.heightPixels)
        return buildCommand(0xD0A0, out.toByteArray())
    }

    /** تهيئة الصفحة (لا بيانات) — تُرسل قبل كل صفحة */
    fun cmdInitPage(): ByteArray = buildCommand(0xD0A1)

    /** إعادة ضبط — تُرسل بعد الصفحة (أو قبلها أحيانًا) */
    fun cmdReset(): ByteArray = buildCommand(0xD0A2)

    // ---------------------------------------------------------------
    // 4) بناء تدفّق الطباعة الكامل لصفحة واحدة
    // ---------------------------------------------------------------

    /**
     * يبني تسلسل الأوامر الكامل لطباعة صورة bitmap واحدة كصفحة.
     * ⚠️ بيانات الراستر نفسها (ضغط Hi-SCoA) غير منفّذة بعد — راجع
     * TODO أسفل الدالة. القيمة المُرجعة حاليًا فقط أوامر التحكم
     * (control commands) بدون بيانات البكسل الفعلية.
     */
    fun render(bitmap: Bitmap): ByteArray {
        val mono = ImageUtils.toMonochrome(bitmap)
        val lineSizeBytes = (mono.width + 7) / 8

        val params = PageParams(
            widthPixels = mono.width,
            heightPixels = mono.height,
            lineSizeBytes = lineSizeBytes,
            heightLines = mono.height
        )

        val out = ByteArrayOutputStream()
        out.write(cmdInitPage())
        out.write(cmdPageParams(params))

        // TODO: هنا المفروض تتضاف بيانات الراستر المضغوطة (Hi-SCoA)
        // بدل ما تتبعت البيانات خام. انظر SPECS section 3 لخوارزمية
        // الضغط الكاملة (LZ77 مبسّطة + Elias gamma coding).
        // out.write(compressHiScoa(mono.packedBits, lineSizeBytes))

        out.write(cmdReset())
        return out.toByteArray()
    }
}
