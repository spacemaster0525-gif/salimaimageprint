package com.salimaprint.app.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * محرّك بناء أوامر بروتوكول Canon CAPT (Canon Advanced Printing Technology).
 *
 * مرجع البروتوكول العام (GPLv3):
 * https://github.com/agalakhov/captdriver/blob/master/SPECS
 *
 * ⚠️ حالة هذا الملف بعد التحديث:
 * - قسم 3 (خوارزمية ضغط Hi-SCoA) مُطبَّق بالكامل الآن في HiScoaEncoder.kt
 *   وبيانات الصورة الفعلية تُرسل ضمن التدفق (لم تعد أوامر تحكم فارغة).
 * - القيم الخاصة بطراز الطابعة تحديدًا (بايتات "model magic" في
 *   cmdPageParams، بايت نوع الورق، إلخ) لا تزال Placeholders غير
 *   مؤكدة لطراز LBP6030B تحديدًا — SPECS نفسه يعلّم أغلبها بـ "؟"
 *   حتى لموديلات أخرى موثّقة (lbp2900/lbp3000/lbp3010). يجب تأكيدها
 *   عبر التقاط USB حقيقي (usbmon) قبل الاعتماد عليها في الإنتاج.
 * - إطار إرسال بيانات الـ band المضغوطة نفسها (هل تُرسل كتدفق USB
 *   خام مباشر بعد أوامر 0xD0xx كما هو مُطبَّق هنا، أم ضمن حزمة أخرى
 *   بترويسة إضافية) غير موثّق صراحة في SPECS العام المتاح؛ الكود
 *   الأصلي لمشروع captdriver يُخفي هذا التفصيل خلف دالة send_band()
 *   خاصة بكل طراز طابعة (غير منشورة للطرز الحديثة). التطبيق هنا
 *   يفترض إرسالًا مباشرًا (الأكثر ترجيحًا لأن Hi-SCoA يحمل علاماته
 *   الخاصة لنهاية النطاق/الصفحة ضمن التدفق نفسه) — يُنصح بالتحقق
 *   من هذه النقطة تحديدًا عبر التقاط حقيقي إن لم تطبع الصفحة بعد.
 */
object CaptRenderer {

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
    // أوامر الحالة/التعريف (0xA0/0xA1) — بدون بيانات، لها رد
    // ---------------------------------------------------------------
    fun cmdGetStatus(): ByteArray = buildCommand(0xA0A0)
    fun cmdGetPrinterStatus(): ByteArray = buildCommand(0xA0A1)
    fun cmdGetExtendedStatus(): ByteArray = buildCommand(0xA0A8)
    fun cmdGetDeviceId(): ByteArray = buildCommand(0xA1A0)
    fun cmdGetCapabilities(): ByteArray = buildCommand(0xA1A1)

    // ---------------------------------------------------------------
    // معاملات الصفحة (0xD0A0)
    // ---------------------------------------------------------------
    data class PageParams(
        val widthPixels: Int,
        val heightPixels: Int,
        val lineSizeBytes: Int,
        val heightLines: Int,
        val marginWidth: Int = 0,
        val marginHeight: Int = 0,
        val tonerDensity: Int = 0x1c // TODO: تأكيد القيمة الفعلية للطابعة
    )

    fun cmdPageParams(params: PageParams): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16LE(out, 0x0000)
        writeU16LE(out, 0x0000)          // TODO: "model magic" الخاص بالطراز
        writeU16LE(out, 0x0002)          // مرتبط بحجم الصفحة (A4 هنا)
        writeU16LE(out, 0x0000)
        out.write(byteArrayOf(
            params.tonerDensity.toByte(), params.tonerDensity.toByte(),
            params.tonerDensity.toByte(), params.tonerDensity.toByte()
        ))
        out.write(0x00)                  // نوع الورق: عادي (TODO: تأكيد)
        out.write(0x11)                  // TODO: مرتبط بحجم الصفحة
        writeU16LE(out, 0x0004)
        out.write(0x00); out.write(0x01); out.write(0x01); out.write(0x02)
        out.write(0x00)                  // توفير الحبر: لا
        writeU16LE(out, 0x0000)
        writeU16LE(out, params.marginHeight)
        writeU16LE(out, params.marginWidth)
        writeU16LE(out, params.lineSizeBytes)
        writeU16LE(out, params.heightLines)
        writeU16LE(out, params.widthPixels)
        writeU16LE(out, params.heightPixels)
        return buildCommand(0xD0A0, out.toByteArray())
    }

    fun cmdInitPage(): ByteArray = buildCommand(0xD0A1)
    fun cmdReset(): ByteArray = buildCommand(0xD0A2)

    /**
     * معاملات ضغط Hi-SCoA (0xD0A4). إلزامي قبل إرسال بيانات مضغوطة.
     * القيم L0=0 (ثابت)، L2=-7، L3=1، L5=4 تُطابق محرك HiScoaEncoder
     * الذي لا يستخدم فعليًا سوى POS0 (L0) وPOS3 (L3). L2/L4/L5 هنا
     * لملء بنية الأمر فقط وليست مستخدمة فعليًا من طرفنا.
     */
    fun cmdCompressionParams(l3: Int = 1, l5: Int = 4, l2: Int = -7): ByteArray {
        val payload = byteArrayOf(
            l3.toByte(),   // L3 (موجب)
            l5.toByte(),   // L5 (موجب)
            0x01,          // flag
            0x01,          // bpp = 1 (أبيض/أسود)
            0x00,          // L0 (ثابت صفر)
            l2.toByte(),   // L2 (سالب)
            0x00, 0x00     // L4 (int16 LE) — غير مستخدم هنا
        )
        return buildCommand(0xD0A4, payload)
    }

    /**
     * يبني تسلسل الأوامر الكامل + البيانات المضغوطة لطباعة صورة bitmap
     * واحدة كصفحة واحدة (نطاق/band واحد يغطي كامل الصفحة).
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

        val compressed = HiScoaEncoder.compress(
            packed = mono.packedBits,
            lineSizeBytes = lineSizeBytes,
            isLastBand = true // نطاق واحد فقط = كامل الصفحة
        )

        val out = ByteArrayOutputStream()
        out.write(cmdPageParams(params))
        out.write(cmdInitPage())
        out.write(cmdCompressionParams())
        out.write(compressed)          // بيانات الصورة المضغوطة فعليًا
        out.write(cmdReset())
        return out.toByteArray()
    }
}
