package com.salimaprint.app.usb

import android.hardware.usb.UsbDeviceConnection

/** إرسال مهمة طباعة جاهزة (bulk OUT) إلى الطابعة. */
object PrinterReplay {

    /** يرسل مهمة جاهزة إلى الـ endpoint الخارجي للطابعة. يعيد (نجاح، تقرير عند الفشل). */
    fun sendJob(
        connection: UsbDeviceConnection,
        handle: UsbPrinterManager.PrinterHandle,
        data: ByteArray
    ): Pair<Boolean, String> {
        val ep = handle.endpointOut
        val iface = handle.usbInterface
        if (!connection.claimInterface(iface, true)) {
            return Pair(false, "claimInterface فشل")
        }
        var offset = 0
        val chunk = 16 * 1024
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            // مهلة طويلة: الطابعة قد لا تستقبل الصفحة التالية قبل أن تنتهي من طباعة السابقة
            val sent = connection.bulkTransfer(ep, data, offset, len, 60000)
            if (sent <= 0) {
                return Pair(false, "فشل الإرسال عند $offset/${data.size} (code=$sent)")
            }
            offset += sent
        }
        return Pair(true, "")
    }
}
