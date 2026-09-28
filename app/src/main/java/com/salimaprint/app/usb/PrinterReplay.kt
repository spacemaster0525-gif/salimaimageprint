package com.salimaprint.app.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * إعادة إرسال مهمة الطباعة الملتقطة (USBPcap) كما هي إلى الطابعة.
 * الالتقاط: 53017 بايت على bulk OUT للـ endpoint 0x01 (الواجهة 1، class 7)
 * بدون أي قراءة من الطابعة أثناء المهمة.
 */
object PrinterReplay {

    fun replayAsset(
        context: Context,
        usb: UsbPrinterManager,
        connection: UsbDeviceConnection,
        handle: UsbPrinterManager.PrinterHandle,
        assetName: String = "job.bin"
    ): String {
        val log = StringBuilder()
        val ep = handle.endpointOut
        val iface = handle.usbInterface
        log.append("interface=${iface.id} class=${iface.interfaceClass} ")
        log.append("EP out=0x%02X".format(ep.address)).append('\n')

        // تحقق سريع: يجب أن تكون الواجهة الصحيحة (class 7) والـ endpoint 0x01
        if (iface.interfaceClass != UsbConstants.USB_CLASS_PRINTER || ep.address != 0x01) {
            log.append("تحذير: ليست الواجهة/الـ endpoint المتوقعين (class 7 / 0x01)\n")
        }

        val data = try {
            context.assets.open(assetName).use { it.readBytes() }
        } catch (e: Exception) {
            return log.append("تعذّرت قراءة $assetName: ${e.message}").toString()
        }
        log.append("job: ${data.size} بايت\n")

        if (!connection.claimInterface(iface, true)) {
            return log.append("claimInterface فشل").toString()
        }

        var offset = 0
        val chunk = 16 * 1024
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val sent = connection.bulkTransfer(ep, data, offset, len, 15000)
            if (sent <= 0) {
                return log.append("فشل الإرسال عند $offset/${data.size} (code=$sent)").toString()
            }
            offset += sent
        }
        log.append("أُرسل كاملاً (${data.size} بايت). انتظر الطباعة.")
        return log.toString()
    }
}
