package com.salimaprint.app.usb

import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * يتحقق من أن job.bin مأخوذ من أحد ملفات USBPcap المرفقة.
 *
 * لا يحتاج هذا الفحص إلى مكتبة PCAP خارجية: يقرأ Enhanced Packet Blocks
 * من PCAPNG، ثم يستخرج حمولة USBPcap التي تحتوي على تدفق Canon CD CA 10.
 */
object ReplayVerifier {

    private val captureAssets = listOf(
        "TEST1_1790585237179.pcapng",
        "TEST2_1790585237181.pcapng",
        "TEST3_1790585237181.pcapng",
        "TEST4_1790585237181.pcapng"
    )

    private val cdcaMarker = byteArrayOf(0xCD.toByte(), 0xCA.toByte(), 0x10.toByte())

    data class CaptureResult(
        val name: String,
        val streamBytes: Int,
        val frameCount: Int,
        val commonPrefixBytes: Int,
        val exactMatch: Boolean,
        val error: String? = null
    )

    data class VerificationReport(
        val jobBytes: Int,
        val jobFrameCount: Int,
        val captures: List<CaptureResult>
    ) {
        val exactMatch: Boolean
            get() = captures.any { it.exactMatch }
    }

    fun verify(context: Context, jobAssetName: String = "job.bin"): VerificationReport {
        val job = context.assets.open(jobAssetName).use { it.readBytes() }
        return VerificationReport(
            jobBytes = job.size,
            jobFrameCount = countOccurrences(job, cdcaMarker),
            captures = captureAssets.map { captureName ->
                verifyCapture(context, captureName, job)
            }
        )
    }

    fun format(report: VerificationReport): String {
        val out = StringBuilder()
        out.append("فحص job.bin مقابل ملفات PCAP:\n")
        out.append("job.bin: ${report.jobBytes} بايت، ")
            .append("${report.jobFrameCount} إطارًا CD CA 10\n")

        report.captures.forEach { capture ->
            out.append("- ${capture.name}: ")
            if (capture.error != null) {
                out.append("تعذر الفحص: ${capture.error}\n")
                return@forEach
            }
            out.append("${capture.streamBytes} بايت، ")
                .append("${capture.frameCount} إطارًا، ")
                .append(
                    if (capture.exactMatch) {
                        "مطابقة كاملة"
                    } else {
                        "لا تطابق كامل، بداية مشتركة ${capture.commonPrefixBytes} بايت"
                    }
                )
                .append('\n')
        }

        if (report.exactMatch) {
            out.append("نتيجة الفحص: يمكن اعتبار job.bin إعادة مطابقة لأحد الاختبارات.")
        } else {
            out.append(
                "نتيجة الفحص: لا توجد مطابقة كاملة. سيتم إرسال job.bin كما هو، " +
                    "وليس إعادة تشغيل أي PCAP من هذه الاختبارات."
            )
        }
        return out.toString()
    }

    private fun verifyCapture(
        context: Context,
        captureName: String,
        job: ByteArray
    ): CaptureResult {
        return try {
            val pcap = context.assets.open(captureName).use { it.readBytes() }
            val stream = extractCanonStream(pcap)
            val prefix = commonPrefix(job, stream)
            CaptureResult(
                name = captureName,
                streamBytes = stream.size,
                frameCount = countOccurrences(stream, cdcaMarker),
                commonPrefixBytes = prefix,
                exactMatch = job.contentEquals(stream)
            )
        } catch (e: Exception) {
            CaptureResult(
                name = captureName,
                streamBytes = 0,
                frameCount = 0,
                commonPrefixBytes = 0,
                exactMatch = false,
                error = e.message ?: "خطأ غير معروف"
            )
        }
    }

    private fun extractCanonStream(pcapng: ByteArray): ByteArray {
        val packets = ByteArrayOutputStream()
        var offset = 0

        while (offset + 12 <= pcapng.size) {
            val blockType = readU32LE(pcapng, offset)
            val blockLength = readU32LE(pcapng, offset + 4)
            if (blockLength < 12 || offset + blockLength > pcapng.size) {
                break
            }

            if (blockType == 0x00000006 && blockLength >= 32) {
                val packetStart = offset + 8
                val capturedLength = readU32LE(pcapng, packetStart + 12)
                val packetDataStart = packetStart + 20
                val packetDataEnd = packetDataStart + capturedLength

                if (capturedLength >= 27 && packetDataEnd <= offset + blockLength - 4) {
                    val packet = pcapng.copyOfRange(packetDataStart, packetDataEnd)
                    val usbHeaderLength = readU16LE(packet, 0)
                    val usbDataLength = readU32LE(packet, 23)
                    val usbDataStart = usbHeaderLength
                    val usbDataEnd = minOf(
                        packet.size.toLong(),
                        usbDataStart.toLong() + usbDataLength.toLong()
                    ).toInt()

                    if (usbHeaderLength in 27..packet.size && usbDataEnd > usbDataStart) {
                        val payload = packet.copyOfRange(usbDataStart, usbDataEnd)
                        val marker = indexOf(payload, cdcaMarker)
                        if (marker >= 0) {
                            packets.write(payload, marker, payload.size - marker)
                        }
                    }
                }
            }
            offset += blockLength
        }

        return packets.toByteArray()
    }

    private fun commonPrefix(first: ByteArray, second: ByteArray): Int {
        val limit = minOf(first.size, second.size)
        var index = 0
        while (index < limit && first[index] == second[index]) {
            index++
        }
        return index
    }

    private fun countOccurrences(data: ByteArray, marker: ByteArray): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = indexOf(data, marker, offset)
            if (found < 0) return count
            count++
            offset = found + marker.size
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty()) return start.coerceAtMost(data.size)
        if (start < 0 || start + needle.size > data.size) return -1
        for (candidate in start..(data.size - needle.size)) {
            var matches = true
            for (index in needle.indices) {
                if (data[candidate + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return candidate
        }
        return -1
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}