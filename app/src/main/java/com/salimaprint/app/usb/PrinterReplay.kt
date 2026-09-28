package com.salimaprint.app.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * إعادة إرسال مهمة الطباعة الملتقطة (USBPcap) كما هي إلى الطابعة.
 * الالتقاط: 53017 بايت على bulk OUT للـ endpoint 0x01 (الواجهة 1، class 7)
 * ينتهي بنقل ZLP مثل الالتقاط الأصلي، لأن بعض طابعات Canon
 * لا تبدأ معالجة المهمة قبل استلام نهاية النقل هذه.
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

        if (!usb.sendRaw(
                connection,
                handle,
                data,
                finishWithZeroLengthPacket = true
            )
        ) {
            return log.append(
                "فشل الإرسال: ${usb.lastError}"
            ).toString()
        }

        log.append(
            "أُرسل كاملاً (${data.size} بايت) مع ZLP. انتظر الطباعة."
        )
        return log.toString()
    }
}
