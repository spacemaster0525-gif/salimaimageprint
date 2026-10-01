package com.salimaprint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.salimaprint.app.network.LprPrintClient
import com.salimaprint.app.render.CaptRenderer
import com.salimaprint.app.render.EscPosRenderer
import com.salimaprint.app.render.PclRenderer
import com.salimaprint.app.usb.CaptSession
import com.salimaprint.app.usb.PrinterReplay
import com.salimaprint.app.render.BitmapPageSource
import com.salimaprint.app.render.DocxToHtml
import com.salimaprint.app.render.PageSource
import com.salimaprint.app.render.PdfPageSource
import com.salimaprint.app.render.WebToPdf
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.salimaprint.app.render.Mf3010Printer
import com.salimaprint.app.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbPrinterManager
    private var currentDevice: UsbDevice? = null
    @Volatile private var ditherEnabled = false
    private var currentConnection: UsbDeviceConnection? = null
    private var currentHandle: UsbPrinterManager.PrinterHandle? = null
    @Volatile private var lastCaptLog: String = ""
    private var detectedLanguages: Set<String> = emptySet()
    private var selectedImageUri: Uri? = null

    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            findViewById<TextView>(R.id.textSelectedFile).text =
                "Fichier: ${displayName(uri)}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = UsbPrinterManager(this)

        val textPrinterInfo = findViewById<TextView>(R.id.textPrinterInfo)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val radioGroup = findViewById<RadioGroup>(R.id.radioLanguage)

        findViewById<Button>(R.id.buttonDetect).setOnClickListener {
            val printers = usbManager.listPrinters()
            if (printers.isEmpty()) {
                textPrinterInfo.text =
                    "لم يتم العثور على طابعة USB متصلة"
                return@setOnClickListener
            }

            val device = printers.first()
            currentDevice = device

            usbManager.requestPermission(device) { granted ->
                if (!granted) {
                    textPrinterInfo.text = "تم رفض إذن الوصول إلى USB"
                    return@requestPermission
                }

                uiScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        openAndIdentify(device)
                    }
                    textPrinterInfo.text = result
                }
            }
        }

        findViewById<Button>(R.id.buttonPickImage).setOnClickListener {
            pickImageLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.buttonPrint).setOnClickListener {
            val device = currentDevice
            val fileUri = selectedImageUri

            if (device == null) {
                textStatus.text = "اكتشف الطابعة أولاً"
                return@setOnClickListener
            }
            if (fileUri == null) {
                textStatus.text = "اختر ملفاً أولاً"
                return@setOnClickListener
            }

            val language = selectedLanguage(radioGroup)
            ditherEnabled = findViewById<CheckBox>(R.id.checkDither).isChecked
            startPrint(device, language, textStatus) { loadSource(fileUri) }
        }

        findViewById<Button>(R.id.buttonPrintWeb).setOnClickListener {
            val device = currentDevice
            if (device == null) {
                textStatus.text = "اكتشف الطابعة أولاً"
                return@setOnClickListener
            }
            var url = findViewById<EditText>(R.id.editWebUrl).text.toString().trim()
            if (url.isEmpty()) {
                textStatus.text = "اكتب رابط الصفحة أولاً"
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            val language = selectedLanguage(radioGroup)
            ditherEnabled = findViewById<CheckBox>(R.id.checkDither).isChecked
            val finalUrl = url
            startPrint(device, language, textStatus) { urlToSource(finalUrl) }
        }

        // --------------------------------------------------------------
        // الطباعة عبر الشبكة (LPR) - جسر Windows، بديل عن USB المباشر
        // --------------------------------------------------------------
        val editServerIp = findViewById<EditText>(R.id.editServerIp)
        val editQueueName = findViewById<EditText>(R.id.editQueueName)
        val textLprStatus = findViewById<TextView>(R.id.textLprStatus)

        findViewById<Button>(R.id.buttonPrintLpr).setOnClickListener {
            val imageUri = selectedImageUri
            val host = editServerIp.text.toString().trim()
            val queue = editQueueName.text.toString().trim()

            if (imageUri == null) {
                textLprStatus.text = "اختر صورة ولاً"
                return@setOnClickListener
            }
            if (host.isEmpty() || queue.isEmpty()) {
                textLprStatus.text = "اكتب عنوان IP واسم مشاركة الطابعة"
                return@setOnClickListener
            }

            val language = when (radioGroup.checkedRadioButtonId) {
                R.id.radioPcl -> "PCL"
                R.id.radioEscPos -> "ESCPOS"
                else -> "PCL" // الأنسب افتراضيًا لأن Windows بيمرر البيانات خام للدرايفر
            }

            textLprStatus.text = "جارٍ الإرسال إلى $host ($queue) ..."

            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    printImageViaLpr(imageUri, host, queue, language)
                }
                textLprStatus.text = result
            }
        }
    }

    private fun selectedLanguage(radioGroup: RadioGroup): String =
        when (radioGroup.checkedRadioButtonId) {
            R.id.radioPcl -> "PCL"
            R.id.radioEscPos -> "ESCPOS"
            R.id.radioCapt -> "CAPT"
            R.id.radioMf3010 -> "MF3010"
            else -> chooseAutoLanguage()
        }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "file"
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val f = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("تعذرت قراءة الملف")
        return f
    }

    /** يحضّر مصدر صفحات من ملف: صورة، PDF، أو Word (.docx). */
    private suspend fun loadSource(uri: Uri): PageSource {
        val name = displayName(uri).lowercase()
        val mime = contentResolver.getType(uri) ?: ""
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") ->
                withContext(Dispatchers.IO) { PdfPageSource(copyToCache(uri, "input.pdf")) }

            name.endsWith(".docx") || mime.contains("wordprocessingml") -> {
                val conv = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { DocxToHtml.convert(it) }
                        ?: throw IllegalStateException("تعذرت قراءة الملف")
                }
                val pdf = suspendCancellableCoroutine<File> { cont ->
                    WebToPdf.convertHtml(this, conv.html, conv.marginsMils, File(cacheDir, "word.pdf")) { r ->
                        r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }
                }
                withContext(Dispatchers.IO) { PdfPageSource(pdf) }
            }

            name.endsWith(".doc") || mime == "application/msword" ->
                throw IllegalArgumentException("صيغة .doc القديمة غير مدعومة، احفظ الملف بصيغة .docx ثم أعد المحاولة")

            else -> withContext(Dispatchers.IO) {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException("تعذرت قراءة الصورة")
                BitmapPageSource(bmp)
            }
        }
    }

    /** يحمّل صفحة ويب ويحوّلها إلى PDF ثم إلى مصدر صفحات. */
    private suspend fun urlToSource(url: String): PageSource {
        val pdf = suspendCancellableCoroutine<File> { cont ->
            WebToPdf.convertUrl(this, url, File(cacheDir, "web.pdf")) { r ->
                r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        }
        return withContext(Dispatchers.IO) { PdfPageSource(pdf) }
    }

    /** يطبع كل صفحات المصدر واحدة تلو الأخرى، كل صفحة مهمة مستقلة. */
    private fun startPrint(
        device: UsbDevice,
        language: String,
        status: TextView,
        loader: suspend () -> PageSource
    ) {
        status.text = "جارٍ التحضير ..."
        uiScope.launch {
            var source: PageSource? = null
            try {
                val src = loader()
                source = src
                val n = src.count
                var done = 0
                var allOk = true
                for (i in 0 until n) {
                    status.text =
                        if (n > 1) "جارٍ طباعة الصفحة ${i + 1} من $n ..."
                        else "جارٍ الإرسال عبر $language ..."
                    val bmp = withContext(Dispatchers.IO) { src.render(i) }
                    val ok = try {
                        withContext(Dispatchers.IO) { printBitmap(device, bmp, language) }
                    } finally {
                        src.release(bmp)
                    }
                    if (!ok) { allOk = false; break }
                    done++
                }
                status.text = if (allOk) {
                    "تم إرسال المهمة إلى الطابعة" + (if (n > 1) " ($n صفحات)" else "") +
                        (if (language == "CAPT" && lastCaptLog.isNotEmpty()) "\n\n" + lastCaptLog else "")
                } else {
                    "فشل الإرسال" + (if (n > 1) " عند الصفحة ${done + 1}" else "") +
                        (if ((language == "CAPT" || language == "MF3010") && lastCaptLog.isNotEmpty())
                            "\n\n" + lastCaptLog else "")
                }
            } catch (e: OutOfMemoryError) {
                status.text = "الذاكرة غير كافية لهذا الملف"
            } catch (e: Exception) {
                status.text = "تعذّر التحضير: ${e.message}"
            } finally {
                source?.close()
            }
        }
    }

    /**
     * يرسم الصورة بصيغة PCL/ESC-POS (نفس المحرّكات المُستخدمة في مسار USB)
     * ثم يبعتها عبر بروتوكول LPR لجهاز Windows الوسيط. Windows بدوره
     * بيمرر البيانات لدرايفر Canon الرسمي المُركّب عليه (UFR II LT)
     * واللي بيتفاهم مع الطابعة مباشرة - فمفيش داعي لفك تشفير CAPT هنا.
     */
    private fun printImageViaLpr(
        imageUri: Uri,
        host: String,
        queueName: String,
        language: String
    ): String {
        val bitmap = contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return "تعذرت قراءة الصورة"

        val payload: ByteArray = when (language) {
            "PCL" -> PclRenderer.render(bitmap)
            else -> EscPosRenderer.render(bitmap)
        }

        return try {
            LprPrintClient(host = host, queueName = queueName).printRaw(
                data = payload,
                jobName = "SalimaPrint"
            )
            "تم إرسال المهمة بنجاح عبر LPR إلى $queueName"
        } catch (e: LprPrintClient.LprException) {
            "فشل الإرسال: ${e.message}"
        } catch (e: Exception) {
            "خطأ غير متوقع: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            currentHandle?.let {
                currentConnection?.releaseInterface(it.usbInterface)
            }
            currentConnection?.close()
        } catch (_: Exception) { }
    }

    private fun openAndIdentify(device: UsbDevice): String {
        try {
            currentHandle?.let {
                currentConnection?.releaseInterface(it.usbInterface)
            }
            currentConnection?.close()
        } catch (_: Exception) { }

        val androidUsbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = androidUsbManager.openDevice(device)
            ?: return "تعذر فتح اتصال مع الطابعة"

        val handle = usbManager.open(device) ?: run {
            connection.close()
            return "تعذر العثور على واجهة الطباعة"
        }

        currentConnection = connection
        currentHandle = handle

        val deviceId = usbManager.readDeviceId(connection, handle.usbInterface)
        if (deviceId == null) {
            detectedLanguages = emptySet()
            return "الطابعة: ${device.productName ?: device.deviceName}\n" +
                    "تعذرت قراءة هوية الطابعة"
        }

        detectedLanguages = usbManager.parseSupportedLanguages(deviceId)
        return "الطابعة: ${device.productName ?: device.deviceName}\n" +
                "Device ID: $deviceId"
    }

    private fun chooseAutoLanguage(): String {
        val d = currentDevice
        if (d != null && d.vendorId == 0x04A9 && d.productId == 0x2759) return "MF3010"
        return when {
            detectedLanguages.any { it.contains("PCL") } -> "PCL"
            detectedLanguages.any {
                it.contains("ESC") || it.contains("POS")
            } -> "ESCPOS"
            else -> "ESCPOS"
        }
    }

    private fun printBitmap(
        device: UsbDevice,
        bitmap: Bitmap,
        language: String
    ): Boolean {
        val connection = currentConnection ?: run {
            val androidUsbManager =
                getSystemService(Context.USB_SERVICE) as UsbManager
            androidUsbManager.openDevice(device) ?: return false
        }
        val handle = currentHandle ?: usbManager.open(device) ?: return false

        if (language == "MF3010") {
            val job = Mf3010Printer.buildJob(this, bitmap, ditherEnabled)
            val (ok, log) = PrinterReplay.sendJob(connection, handle, job)
            lastCaptLog = log
            return ok
        }

        if (language == "CAPT") {
            val (ok, log) = CaptSession.print(usbManager, connection, handle, bitmap)
            lastCaptLog = log
            return ok
        }

        val payload: ByteArray = when (language) {
            "PCL" -> PclRenderer.render(bitmap)
            else -> EscPosRenderer.render(bitmap)
        }

        return usbManager.sendRaw(connection, handle, payload)
    }
}
