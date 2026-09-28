package com.salimaprint.app.usb

import android.graphics.Bitmap
import android.hardware.usb.UsbDeviceConnection
import com.salimaprint.app.render.CaptRenderer

/**
 * جلسة طباعة CAPT ثنائية الاتجاه (تجريبية).
 * التسلسل مأخوذ من تحليل عكسي منشور (captdriver / linux.org.ru).
 * قراءة رد كل أمر إلزامية وإلا "تتجمّد" الطابعة.
 * تُرجع تقريرًا نصيًا بالخطوة التي نجحت/فشلت لتسهيل التشخيص.
 */
object CaptSession {

    private fun hex(b: ByteArray?, max: Int = 12): String =
        if (b == null) "لا رد"
        else b.take(max).joinToString(" ") { "%02X".format(it) }

    /** رمز الإرجاع = البايتان 4-5 من الرد */
    private fun returnCode(reply: ByteArray?): Int? =
        if (reply != null && reply.size >= 6)
            (reply[4].toInt() and 0xFF) or ((reply[5].toInt() and 0xFF) shl 8)
        else null

    fun print(
        usb: UsbPrinterManager,
        connection: UsbDeviceConnection,
        handle: UsbPrinterManager.PrinterHandle,
        bitmap: Bitmap
    ): Pair<Boolean, String> {
        val log = StringBuilder()

        if (handle.endpointIn == null) {
            return false to "لا يوجد endpoint للقراءة (IN) — لا يمكن قراءة ردود الطابعة"
        }

        // خطوات تتطلب رد ناجحًا (وإلا نتوقف)
        val mandatory = listOf(
            "A1A1 بدء العمل" to CaptRenderer.cmdStartOfWork(),
            "A0A8 حالة موسعة" to CaptRenderer.cmdGetExtendedStatus(),
            "A0A1 حالة المهمة" to CaptRenderer.cmdGetPrinterStatus()
        )
        for ((name, cmd) in mandatory) {
            val r = usb.sendCommandExpectReply(connection, handle, cmd)
            log.append("$name: ${hex(r)}\n")
            if (r == null) return false to log.append("فشل عند: $name").toString()
        }

        // خطوات "تفعيل" — نكمل حتى لو لم يأتِ رد (بحسب المصدر غيابها لا يمنع الطباعة)
        val optional = listOf(
            "A3A2" to CaptRenderer.cmdA3A2(),
            "E0A3" to CaptRenderer.cmdStart1(),
            "E0A2" to CaptRenderer.cmdStart2(),
            "E0A4" to CaptRenderer.cmdStart3(),
            "E0A5" to CaptRenderer.cmdE0A5()
        )
        for ((name, cmd) in optional) {
            val r = usb.sendCommandExpectReply(connection, handle, cmd)
            log.append("$name: ${hex(r)}\n")
        }

        val page = CaptRenderer.buildPage(bitmap)

        // إعدادات الصفحة (0xD0A9) — بدون رد بحسب المصدر
        if (!usb.sendRaw(connection, handle, page.setParms))
            return false to log.append("فشل إرسال D0A9 (إعدادات الصفحة)").toString()
        log.append("D0A9 أُرسل (${page.setParms.size} بايت)\n")

        // انتظار جاهزية المخزن (E0A0: bit 0x0008 = ممتلئ)
        repeat(30) {
            val r = usb.sendCommandExpectReply(connection, handle, CaptRenderer.cmdCheckReady())
            val code = returnCode(r)
            if (code == null || (code and 0x0008) == 0) return@repeat
            Thread.sleep(1000)
        }

        // البيانات المضغوطة (0xC0A0) — بدون رد
        if (!usb.sendRaw(connection, handle, page.printData))
            return false to log.append("فشل إرسال C0A0 (${page.printData.size} بايت)").toString()
        log.append("C0A0 أُرسل (${page.printData.size} بايت)\n")

        val end = usb.sendCommandExpectReply(connection, handle, CaptRenderer.cmdEndOfLoad())
        log.append("C0A4 نهاية التحميل: ${hex(end)}\n")

        val go = usb.sendCommandExpectReply(connection, handle, CaptRenderer.cmdEnablePrint())
        log.append("E0A7 تفعيل الطباعة: ${hex(go)}\n")

        return true to log.toString()
    }
}
