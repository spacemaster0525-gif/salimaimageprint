package com.salimaprint.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer

/**
 * تشخيص "لا ردّ": يجرّب عدة طرق لإرسال استعلام حالة واحد (لا يطبع شيئاً)
 * ويُبلغ أي طريقة تُنتج ردّاً من الطابعة على EP 0x82.
 */
object Lbp6030Diag {
    private const val T = 3000

    private fun frame(seq: Int): ByteArray {
        val p = Lbp6030Transport.pollPayload(seq)
        return Lbp6030Transport.hdr(0x0220, p.size + 6, 0x0102) + p
    }

    private fun drain(conn: UsbDeviceConnection, h: UsbPrinterManager.PrinterHandle, log: StringBuilder) {
        val ep = h.endpointIn ?: return
        val buf = ByteArray(1024)
        var total = 0
        for (i in 0 until 8) {
            val n = conn.bulkTransfer(ep, buf, 0, buf.size, 250)
            if (n <= 0) break
            total += n
            log.append("    بيانات معلّقة: ${Lbp6030Transport.hex(buf.copyOf(n), 16)}\n")
        }
        if (total == 0) log.append("    (لا بيانات معلّقة على IN)\n")
    }

    /** يقرأ بـ bulkTransfer. يعيد وصف النتيجة ونجاحها. */
    private fun readBulk(conn: UsbDeviceConnection, h: UsbPrinterManager.PrinterHandle): Pair<Boolean, String> {
        val ep = h.endpointIn ?: return Pair(false, "لا IN endpoint")
        val buf = ByteArray(1024)
        val n = conn.bulkTransfer(ep, buf, 0, buf.size, T)
        return if (n > 0) Pair(true, "← $n بايت: ${Lbp6030Transport.hex(buf.copyOf(n), 16)}")
        else Pair(false, "قراءة فشلت code=$n")
    }

    /** يقرأ عبر UsbRequest (يحتاج API 26+ لمهلة الانتظار). */
    private fun readRequest(conn: UsbDeviceConnection, h: UsbPrinterManager.PrinterHandle): Pair<Boolean, String> {
        val ep = h.endpointIn ?: return Pair(false, "لا IN endpoint")
        if (Build.VERSION.SDK_INT < 26) return Pair(false, "تخطّي (API < 26)")
        val req = UsbRequest()
        try {
            if (!req.initialize(conn, ep)) return Pair(false, "UsbRequest.initialize فشل")
            val bb = ByteBuffer.allocate(1024)
            if (!req.queue(bb)) return Pair(false, "queue فشل")
            val done = conn.requestWait(T.toLong())
            if (done == null) { req.cancel(); return Pair(false, "انتهت المهلة بلا ردّ") }
            val n = bb.position()
            return Pair(n > 0, "← $n بايت: ${Lbp6030Transport.hex(bb.array().copyOf(n), 16)}")
        } catch (e: Exception) {
            return Pair(false, "استثناء ${e.message}")
        } finally {
            try { req.close() } catch (_: Exception) {}
        }
    }

    fun run(conn: UsbDeviceConnection, h: UsbPrinterManager.PrinterHandle): String {
        val log = StringBuilder()
        val iface = h.usbInterface
        log.append("الجهاز %04x:%04x  interface=%d alt=%d class=%d/%d/%d\n".format(
            h.device.vendorId, h.device.productId, iface.id, iface.alternateSetting,
            iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
        for (i in 0 until iface.endpointCount) {
            val e = iface.getEndpoint(i)
            log.append("  EP 0x%02X %s mps=%d\n".format(e.address,
                if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", e.maxPacketSize))
        }
        val claimed = conn.claimInterface(iface, true)
        log.append("claimInterface(force) = $claimed\n")
        if (!claimed) return log.append("لا يمكن المتابعة").toString()

        // معلومات الفئة (لا تؤثر على الطباعة)
        val idBuf = ByteArray(512)
        val idLen = conn.controlTransfer(0xA1, 0, 0, (iface.id shl 8) or iface.alternateSetting, idBuf, idBuf.size, T)
        log.append("GET_DEVICE_ID → $idLen ${if (idLen > 2) String(idBuf, 2, idLen - 2, Charsets.US_ASCII) else ""}\n")
        val ps = ByteArray(1)
        val psLen = conn.controlTransfer(0xA1, 1, 0, iface.id, ps, 1, T)
        log.append("GET_PORT_STATUS → $psLen ${if (psLen == 1) "0x%02X".format(ps[0]) else ""}\n\n")

        var seq = 0x29
        fun attempt(name: String, prep: () -> Unit, send: () -> Boolean, read: () -> Pair<Boolean, String>): Boolean {
            log.append("[$name]\n")
            try { prep() } catch (e: Exception) { log.append("    prep استثناء: ${e.message}\n") }
            if (!send.invoke()) { log.append("    ✗ الكتابة فشلت\n"); return false }
            val (ok, msg) = read()
            log.append("    ${if (ok) "✓" else "✗"} $msg\n")
            return ok
        }
        val out = h.endpointOut
        fun w(b: ByteArray) = conn.bulkTransfer(out, b, 0, b.size, T) == b.size

        var winner: String? = null
        fun tryIt(name: String, prep: () -> Unit, send: (ByteArray) -> Boolean, read: () -> Pair<Boolean, String>) {
            if (winner != null) return
            val f = frame(seq)
            if (attempt(name, { prep(); drain(conn, h, log) }, { send(f) }, read)) winner = name
            seq++
        }

        tryIt("1: ترويسة ثم حمولة، قراءة bulk", {}, { f -> w(f.copyOfRange(0, 6)) && w(f.copyOfRange(6, f.size)) }, { readBulk(conn, h) })
        tryIt("2: إطار واحد مدموج، قراءة bulk", {}, { f -> w(f) }, { readBulk(conn, h) })
        tryIt("3: ترويسة ثم حمولة، قراءة UsbRequest", {}, { f -> w(f.copyOfRange(0, 6)) && w(f.copyOfRange(6, f.size)) }, { readRequest(conn, h) })
        tryIt("4: بعد SOFT_RESET", {
            val r = conn.controlTransfer(0x21, 2, 0, iface.id, null, 0, T)
            log.append("    SOFT_RESET → $r\n"); Thread.sleep(500)
        }, { f -> w(f.copyOfRange(0, 6)) && w(f.copyOfRange(6, f.size)) }, { readBulk(conn, h) })
        tryIt("5: بعد setInterface + clearHalt", {
            log.append("    setInterface → ${conn.setInterface(iface)}\n")
            // CLEAR_FEATURE(ENDPOINT_HALT) على الـ endpoints
            for (ep in listOfNotNull(h.endpointIn, h.endpointOut)) {
                val r = conn.controlTransfer(0x02, 1, 0, ep.address, null, 0, T)
                log.append("    clearHalt 0x%02X → %d\n".format(ep.address, r))
            }
        }, { f -> w(f.copyOfRange(0, 6)) && w(f.copyOfRange(6, f.size)) }, { readBulk(conn, h) })
        tryIt("6: إعادة claim كاملة", {
            conn.releaseInterface(iface); Thread.sleep(300)
            log.append("    claim → ${conn.claimInterface(iface, true)}\n")
        }, { f -> w(f.copyOfRange(0, 6)) && w(f.copyOfRange(6, f.size)) }, { readBulk(conn, h) })

        log.append('\n')
        log.append(if (winner != null) "النتيجة: الطريقة الناجحة = $winner"
        else "النتيجة: لا ردّ في أي طريقة. أرسل لي هذا التقرير كاملاً.")
        return log.toString()
    }
}
