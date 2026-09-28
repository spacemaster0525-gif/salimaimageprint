package com.salimaprint.app.render

import java.io.ByteArrayOutputStream

/**
 * تطبيق كامل لخوارزمية ضغط Hi-SCoA المستخدمة في بروتوكول Canon CAPT.
 *
 * المرجع الوحيد المتاح علنًا لهذه الخوارزمية هو ملف SPECS من مشروع
 * captdriver مفتوح المصدر (GPLv3):
 * https://github.com/agalakhov/captdriver/blob/master/SPECS (قسم 3)
 *
 * ⚠️ نطاق هذا التطبيق (اختيار هندسي واعٍ لتقليل مخاطر الأخطاء):
 * - يُستخدم فقط LONGREP0 (نسخ من نفس عمود السطر السابق، L0=0) و
 *   LONGREP3 (تكرار أفقي قصير المسافة، L3=1) — وهما كافيان لضغط جيد
 *   جدًا لصور المستندات (نص/خطوط بيضاء وسوداء).
 * - لا يُستخدم أمر PREFIX (لتوسيع الأرقام >127) — بدلًا من ذلك تُقسّم
 *   السلاسل الطويلة إلى عدة أوامر LONGREP متتالية (كل واحد حتى 127
 *   بايت)، وهذا صحيح تمامًا لأن كل أمر يعيد حساب موضعه بالنسبة
 *   لموضع الإخراج الحالي.
 * - لا تُستخدم LONGREP2/4/5 (المواضع L2/L4/L5) — تبسيط آمن، القيم
 *   المُرسلة لهذه الثوابت في أمر 0xD0A4 لن تُستخدم فعليًا بما أننا
 *   لا نصدر هذه الأوامر.
 * - تم تجنّب عمدًا "order 0" في ترميز الأرقام (الحالات الخاصة للأرقام
 *   0/1/2/3) لأن نص SPECS نفسه يحتوي غموضًا فيها لم يُحسم حتى في
 *   المشروع الأصلي. لا نصدر LONGREP أبدًا لطول تطابق أقل من 4 بايت؛
 *   السلاسل الأقصر تُرمّز كـ REPBYTE أو BYTE/ZEROBYTE (دائمًا صحيحة).
 */
object HiScoaEncoder {

    /** كاتب بتّات: MSB أولًا، محاذاة لـ 4 بايت بـ 1s، ثم XOR بـ 0x43 */
    private class BitWriter {
        private val bytes = ArrayList<Int>()
        private var cur = 0
        private var nBits = 0

        fun writeBit(bit: Int) {
            cur = (cur shl 1) or (bit and 1)
            nBits++
            if (nBits == 8) {
                bytes.add(cur and 0xFF)
                cur = 0
                nBits = 0
            }
        }

        fun writeBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                writeBit((value ushr i) and 1)
            }
        }

        fun finish(): ByteArray {
            // إكمال آخر بايت جزئي بـ 1s
            while (nBits != 0) writeBit(1)
            // محاذاة كامل التدفق لحدود 4 بايت بـ 1s إضافية
            while (bytes.size % 4 != 0) bytes.add(0xFF)
            // تشويش XOR بـ 0x43 (إلزامي حسب SPECS قسم 3.1)
            return ByteArray(bytes.size) { (bytes[it] xor 0x43).toByte() }
        }
    }

    /** مخزن 16 بايت (كومة LIFO) لأمر REPBYTE */
    private class Stash {
        private val items = ArrayDeque<Int>() // العنصر الأول = index 0 = الأحدث

        fun findIndex(v: Int): Int {
            var idx = 0
            for (x in items) {
                if (x == v) return idx
                idx++
            }
            return -1
        }

        fun push(v: Int) {
            items.addFirst(v)
            while (items.size > 16) items.removeLast()
        }

        fun moveToFront(idx: Int) {
            val v = items.removeAt(idx)
            items.addFirst(v)
        }
    }

    // ---- ترميز أوامر الأوبكود (حسب جدول SPECS قسم 3.2) ----
    private fun emitByte(w: BitWriter, value: Int) {
        w.writeBits(0b1101, 4)       // "110" + subcommand "1"
        w.writeBits(value and 0xFF, 8)
    }

    private fun emitZeroByte(w: BitWriter) {
        w.writeBits(0b11111101, 8)   // "1111110" + subcommand "1"
    }

    private fun emitRepByte(w: BitWriter, stashIndex: Int) {
        w.writeBit(1); w.writeBit(0) // "10"
        val i = 15 - stashIndex      // الصيغة في SPECS: index = 15 - i
        w.writeBits(i, 4)
    }

    private fun emitLongRep0(w: BitWriter, number: Int) {
        w.writeBit(0)                // "0"
        encodeEliasNumber(w, number)
    }

    private fun emitLongRep3(w: BitWriter, number: Int) {
        w.writeBits(0b1110, 4)       // "1110"
        encodeEliasNumber(w, number)
    }

    private fun emitEnd(w: BitWriter, endOfPage: Boolean) {
        w.writeBits(0b11111110, 8)   // "11111110"
        w.writeBits(if (endOfPage) 0b01 else 0b00, 2)
    }

    /**
     * ترميز عدد (طول نسخ) بترميز Elias gamma المعدّل حسب SPECS قسم 3.3.
     * مُقيَّد عمدًا للمجال من 4 إلى 127 (order من 1 إلى 5) لتفادي غموض order=0.
     */
    private fun encodeEliasNumber(w: BitWriter, number: Int) {
        require(number in 4..127) { "number خارج المجال المدعوم: $number" }
        var order = 1
        while (number > (1 shl (order + 2)) - 1) order++
        val n = (1 shl (order + 2)) - 1 - number
        repeat(order) { w.writeBit(1) }
        w.writeBit(0)
        w.writeBits(n, order + 1)
    }

    /**
     * يضغط صورة أحادية اللون مُعبّأة بالبت (packed 1bpp) إلى تدفق Hi-SCoA.
     *
     * @param packed بيانات الصورة، سطرًا بعد سطر، كل سطر lineSizeBytes بايت
     * @param lineSizeBytes عدد البايتات في السطر الواحد
     * @param isLastBand إذا كان هذا آخر (أو الوحيد) نطاق (band) في الصفحة
     */
    fun compress(packed: ByteArray, lineSizeBytes: Int, isLastBand: Boolean = true): ByteArray {
        val w = BitWriter()
        val stash = Stash()
        val n = packed.size
        val maxRun = 127
        var i = 0

        while (i < n) {
            val lineStart = (i / lineSizeBytes) * lineSizeBytes
            val col = i - lineStart
            val remainingInLine = lineSizeBytes - col

            var matchPos0 = 0
            if (i >= lineSizeBytes) {
                var k = 0
                while (k < remainingInLine && packed[i + k] == packed[i + k - lineSizeBytes]) k++
                matchPos0 = k
            }

            var matchPos3 = 0
            if (i >= 1) {
                val ref = packed[i - 1]
                var k = 0
                while (k < remainingInLine && packed[i + k] == ref) k++
                matchPos3 = k
            }

            val bestPos0 = minOf(matchPos0, maxRun)
            val bestPos3 = minOf(matchPos3, maxRun)

            when {
                bestPos0 >= 4 && bestPos0 >= bestPos3 -> {
                    emitLongRep0(w, bestPos0)
                    i += bestPos0
                }
                bestPos3 >= 4 -> {
                    emitLongRep3(w, bestPos3)
                    i += bestPos3
                }
                else -> {
                    val b = packed[i].toInt() and 0xFF
                    val stashIdx = stash.findIndex(b)
                    when {
                        stashIdx != -1 -> {
                            emitRepByte(w, stashIdx)
                            stash.moveToFront(stashIdx)
                        }
                        b == 0 -> {
                            emitZeroByte(w)
                            stash.push(b)
                        }
                        else -> {
                            emitByte(w, b)
                            stash.push(b)
                        }
                    }
                    i += 1
                }
            }
        }

        emitEnd(w, endOfPage = isLastBand)
        return w.finish()
    }
}
