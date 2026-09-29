package com.salimaprint.app.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import java.io.ByteArrayOutputStream

/**
 * نقل Canon LBP6030 (USB 04a9:2795) كما التقطه USBPcap من درايفر ويندوز.
 * كل إطار = ترويسة 6 بايت [type(2) len(2) flags(2)] (len يشمل الترويسة)
 * تُرسل كنقل bulk مستقل، ثم الحمولة كنقل bulk ثانٍ، ثم تُقرأ استجابة الطابعة من EP 0x82.
 *
 *   type 0x0000 : قناة التحكم (فتح 01 01 10 ff.. / إغلاق 02 01 10)
 *   type 0x0110 : قناة البيانات (قطع 8186 بايت، ردّ كل قطعة ack من 6 بايت)
 *   type 0x0220 : استعلام الحالة (سجل cd ca 10 04 00 1d، يتكرر كل ~1 ثانية)
 *
 * مطابقة لنموذج tools/lbp6030_tools.py المتحقق منه على التقاطات test1..4.
 */
object Lbp6030Transport {
    private const val CHUNK = 8186
    private const val TIMEOUT = 8000

    internal fun hdr(type: Int, len: Int, flags: Int) = byteArrayOf(
        (type shr 8).toByte(), type.toByte(),
        (len shr 8).toByte(), len.toByte(),
        (flags shr 8).toByte(), flags.toByte()
    )

    internal fun hex(b: ByteArray, n: Int = 24): String =
        b.take(n).joinToString(" ") { "%02x".format(it) } + if (b.size > n) " …(${b.size})" else ""

    internal fun pollPayload(seq: Int): ByteArray {
        val p = ByteArray(28)
        val head = intArrayOf(0xcd, 0xca, 0x10, 0x04, 0x00, 0x1d)
        for (i in head.indices) p[i] = head[i].toByte()
        p[6] = (seq shr 8).toByte(); p[7] = seq.toByte()
        p[9] = 0x08
        // 00 00 00 00 00 00 00 00 00 00 00 00 (بايت 10..21) ثم 02 59 00 0c 01 01
        p[22] = 0x02; p[23] = 0x59; p[24] = 0x00; p[25] = 0x0c; p[26] = 0x01; p[27] = 0x01
        return p
    }

    private class Io(val conn: UsbDeviceConnection, val h: UsbPrinterManager.PrinterHandle) {
        val inEp = h.endpointIn
        val log = StringBuilder()

        fun write(data: ByteArray, off: Int = 0, len: Int = data.size): Boolean {
            val n = conn.bulkTransfer(h.endpointOut, data, off, len, TIMEOUT)
            if (n != len) { log.append("  ✗ كتابة فشلت ($n من $len)\n"); return false }
            return true
        }

        /** يرسل إطاراً كاملاً: ترويسة ثم حمولة كنقلين منفصلين. */
        fun sendFrame(type: Int, flags: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): Boolean {
            if (!write(hdr(type, len + 6, flags))) return false
            return if (len > 0) write(payload, off, len) else true
        }

        /** يقرأ إطار استجابة: يجمع الحزم حتى يبلغ الطول المعلن في الترويسة. */
        fun readFrame(): ByteArray? {
            val ep = inEp ?: run { log.append("  ✗ لا يوجد IN endpoint\n"); return null }
            val bufSize = maxOf(ep.maxPacketSize, 512)
            val buf = ByteArray(bufSize)
            val out = ByteArrayOutputStream()
            var expected = -1
            var zeros = 0
            while (expected < 0 || out.size() < expected) {
                val n = conn.bulkTransfer(ep, buf, 0, bufSize, TIMEOUT)
                if (n < 0) { log.append("  ✗ قراءة فشلت (code=$n، تم استلام ${out.size()})\n"); return null }
                if (n == 0) { if (++zeros > 4) { log.append("  ✗ قراءة صفرية متكررة\n"); return null }; continue }
                out.write(buf, 0, n)
                if (expected < 0 && out.size() >= 4) {
                    val a = out.toByteArray()
                    expected = ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF)
                }
            }
            return out.toByteArray()
        }

        fun exchange(type: Int, flags: Int, payload: ByteArray, what: String): ByteArray? {
            if (!sendFrame(type, flags, payload)) { log.append("  ✗ فشل عند: $what\n"); return null }
            val r = readFrame()
            if (r == null) log.append("  ✗ لا ردّ عند: $what\n")
            return r
        }
    }

    /**
     * يعيد إرسال مهمة ملتقطة (سجلات cd ca) كما يفعل درايفر ويندوز.
     * @param pollsBefore عدد استعلامات الحالة قبل المهمة (الالتقاط: 4)
     * @param pollsAfter  عدد استعلامات الحالة بعد المهمة، مرة كل ثانية (الالتقاط: ~10)
     */
    fun sendJob(
        conn: UsbDeviceConnection,
        handle: UsbPrinterManager.PrinterHandle,
        job: ByteArray,
        pollsBefore: Int = 4,
        pollsAfter: Int = 10
    ): Pair<Boolean, String> {
        val io = Io(conn, handle)
        val log = io.log
        log.append("interface=${handle.usbInterface.id} class=${handle.usbInterface.interfaceClass} ")
        log.append("OUT=0x%02X IN=%s\n".format(handle.endpointOut.address,
            handle.endpointIn?.let { "0x%02X".format(it.address) } ?: "لا يوجد"))
        log.append("job: ${job.size} بايت\n")
        if (!conn.claimInterface(handle.usbInterface, true)) {
            return Pair(false, log.append("claimInterface فشل").toString())
        }
        var seq = 0x29

        fun poll(tag: String): Boolean {
            val r = io.exchange(0x0220, 0x0102, pollPayload(seq++), "poll $tag") ?: return false
            log.append("  poll $tag ← ${r.size}B: ${hex(r.copyOfRange(minOf(6, r.size), r.size), 20)}\n")
            return true
        }

        // 1) استعلامات الحالة الأولى
        log.append("1) استعلام الحالة\n")
        for (i in 0 until pollsBefore) {
            if (!poll("#${i + 1}")) return Pair(false, log.toString())
            if (i < pollsBefore - 1) Thread.sleep(1000)
        }

        // 2) فتح قناة البيانات
        log.append("2) فتح القناة\n")
        val open = byteArrayOf(0x01, 0x01, 0x10, -1, -1, -1, -1, -1, -1)
        val ro = io.exchange(0x0000, 0x0100, open, "open") ?: return Pair(false, log.toString())
        log.append("  ← ${hex(ro)}\n")

        // 3) إرسال المهمة على قطع 8186 بايت مع ack لكل قطعة
        log.append("3) إرسال المهمة\n")
        var off = 0
        var chunkNo = 0
        while (off < job.size) {
            val len = minOf(CHUNK, job.size - off)
            if (!io.sendFrame(0x0110, 0x0100, job, off, len)) {
                log.append("  ✗ فشل عند القطعة ${chunkNo + 1} (offset=$off)\n")
                return Pair(false, log.toString())
            }
            val ack = io.readFrame()
            if (ack == null) {
                log.append("  ✗ لا ack للقطعة ${chunkNo + 1}\n")
                return Pair(false, log.toString())
            }
            log.append("  قطعة ${++chunkNo}: $len بايت ← ack ${hex(ack)}\n")
            off += len
        }

        // 4) إغلاق القناة
        log.append("4) إغلاق القناة\n")
        val rc = io.exchange(0x0000, 0x0100, byteArrayOf(0x02, 0x01, 0x10), "close")
            ?: return Pair(false, log.toString())
        log.append("  ← ${hex(rc)}\n")

        // 5) متابعة الحالة أثناء الطباعة
        log.append("5) حالة الطباعة\n")
        for (i in 0 until pollsAfter) {
            Thread.sleep(1000)
            if (!poll("#${pollsBefore + i + 1}")) break
        }
        log.append("انتهى. راقب الطابعة.")
        return Pair(true, log.toString())
    }

    fun sendAsset(
        context: Context,
        conn: UsbDeviceConnection,
        handle: UsbPrinterManager.PrinterHandle,
        assetName: String
    ): Pair<Boolean, String> {
        val job = try {
            context.assets.open(assetName).use { it.readBytes() }
        } catch (e: Exception) {
            return Pair(false, "تعذّرت قراءة $assetName: ${e.message}")
        }
        return sendJob(conn, handle, job)
    }
}
