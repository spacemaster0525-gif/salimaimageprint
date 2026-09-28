package com.salimaprint.app.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import kotlin.random.Random

/**
 * عميل بروتوكول LPR/LPD (RFC 1179) لإرسال مهام طباعة عبر الشبكة
 * إلى جهاز Windows مفعّل عليه "LPD Print Service" ومشاركة طابعة.
 *
 * الاستخدام:
 *   val client = LprPrintClient(host = "192.168.1.10", queueName = "CanonLBP6030")
 *   client.printRaw(payloadBytes)
 *
 * ملاحظات مهمة:
 * - المنفذ القياسي لـ LPD هو 515 (ثابت، لا يُغيّر عادة).
 * - Windows LPD يمرّر البيانات الخام (raw) للطابور المُشار إليه، والطابور
 *   بدوره يستخدم درايفر Canon الرسمي المُركّب على جهاز Windows نفسه —
 *   يعني البيانات المُرسلة من هنا لازم تكون بصيغة يفهمها الدرايفر
 *   (عادة Raw/PCL حسب إعداد الطابور في Windows: Printer Properties →
 *   Advanced → Print Processor → RAW).
 * - يجب تشغيل هذا الكلاس من Thread خلفي (ليس UI thread) — استخدم
 *   Coroutines أو AsyncTask من الكود المستدعي.
 */
class LprPrintClient(
    private val host: String,
    private val queueName: String,
    private val port: Int = 515,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000
) {

    /**
     * يرسل بيانات طباعة خام (raw bytes) إلى الطابعة عبر LPD.
     * @param data محتوى الطباعة (PCL, PostScript, أو raw bytes حسب إعداد الطابور)
     * @param jobName اسم المهمة كما سيظهر في طابور الطباعة على Windows
     * @param userName اسم المستخدم كما سيظهر في سجل الطباعة
     * @throws LprException عند فشل أي خطوة من بروتوكول LPD
     */
    @Throws(LprException::class)
    fun printRaw(
        data: ByteArray,
        jobName: String = "SalimaPrint_${System.currentTimeMillis()}",
        userName: String = "android"
    ) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // معرف مهمة عشوائي (3 أرقام حسب اصطلاح LPD الشائع)
            val jobId = (Random.nextInt(900) + 100).toString()
            val shortHost = android.os.Build.MODEL?.take(31)?.replace(" ", "_") ?: "android"

            // ---------------------------------------------------------
            // الخطوة 1: أمر "Receive a printer job" (كود 0x02) + اسم الطابور
            // ---------------------------------------------------------
            sendCommand(out, input, byte = 0x02, arg = "$queueName\n")

            // ---------------------------------------------------------
            // الخطوة 2: بناء "Control File" — بيانات وصفية عن المهمة
            // ---------------------------------------------------------
            val controlFileName = "cfA$jobId$shortHost"
            val dataFileName = "dfA$jobId$shortHost"

            val controlFileContent = buildString {
                append("H$shortHost\n")   // Host name
                append("P$userName\n")    // User name (Print job owner)
                append("J$jobName\n")     // Job name
                append("l$dataFileName\n") // Print file: "l" = طباعة raw بدون معالجة إضافية
                append("N$jobName\n")     // اسم الملف الأصلي (source filename)
                append("U$dataFileName\n") // Unlink (احذف الملف بعد الطباعة)
            }
            val controlBytes = controlFileContent.toByteArray(Charset.forName("US-ASCII"))

            // ---------------------------------------------------------
            // الخطوة 3: أمر "Receive control file" (كود 0x02) + الحجم والاسم
            // ---------------------------------------------------------
            sendCommand(out, input, byte = 0x02, arg = "${controlBytes.size} $controlFileName\n")
            out.write(controlBytes)
            out.write(0x00) // بايت النهاية الإلزامي
            out.flush()
            expectAck(input, "بعد إرسال control file")

            // ---------------------------------------------------------
            // الخطوة 4: أمر "Receive data file" (كود 0x03) + الحجم والاسم
            // ---------------------------------------------------------
            sendCommand(out, input, byte = 0x03, arg = "${data.size} $dataFileName\n")
            out.write(data)
            out.write(0x00) // بايت النهاية الإلزامي
            out.flush()
            expectAck(input, "بعد إرسال data file")

        } catch (e: LprException) {
            throw e
        } catch (e: Exception) {
            throw LprException("فشل الاتصال بـ $host:$port - ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * يرسل أمر LPD مؤلّف من بايت الكود + نص الوسيطة، وينتظر تأكيد الاستلام
     * (بايت 0x00 من الطرف الآخر). أي قيمة تانية تعتبر خطأ.
     */
    private fun sendCommand(
        out: DataOutputStream,
        input: DataInputStream,
        byte: Int,
        arg: String
    ) {
        out.write(byte)
        out.write(arg.toByteArray(Charset.forName("US-ASCII")))
        out.flush()
        expectAck(input, "بعد أمر 0x${byte.toString(16)}")
    }

    private fun expectAck(input: DataInputStream, context: String) {
        val ack = input.read()
        if (ack != 0x00) {
            throw LprException("الطابعة/الخادم رفض الطلب $context (رد=$ack). تأكد إن اسم الطابور واسم الجهاز صحيحين وخدمة LPD شغالة.")
        }
    }

    class LprException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
