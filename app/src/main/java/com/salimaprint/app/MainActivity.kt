package com.salimaprint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.salimaprint.app.network.LprPrintClient
import com.salimaprint.app.render.CaptRenderer
import com.salimaprint.app.render.EscPosRenderer
import com.salimaprint.app.render.PclRenderer
import com.salimaprint.app.usb.CaptSession
import com.salimaprint.app.usb.PrinterReplay
import com.salimaprint.app.usb.Lbp6030Transport
import com.salimaprint.app.usb.Lbp6030Diag
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
                "Fichier: ${uri.lastPathSegment}"
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
            pickImageLauncher.launch("image/*")
        }

        findViewById<Spinner>(R.id.spinnerReplay).adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("test1 — rectangle noir", "test2 — rectangle large", "test3 — rectangle haut", "test4 — barre noire", "test5 — page blanche", "test6 — une ligne 1px")
        )
        findViewById<Button>(R.id.buttonReplay).setOnClickListener {
            val device = currentDevice
            if (device == null) {
                textStatus.text = "اكتشف الطابعة أولاً"
                return@setOnClickListener
            }
            val spinner = findViewById<Spinner>(R.id.spinnerReplay)
            val asset = "lbp6030_test${spinner.selectedItemPosition + 1}.bin"
            textStatus.text = "جارٍ إرسال $asset (LBP6030) ..."
            uiScope.launch {
                val report = withContext(Dispatchers.IO) {
                    val androidUsbManager =
                        getSystemService(Context.USB_SERVICE) as UsbManager
                    val connection = currentConnection
                        ?: androidUsbManager.openDevice(device)
                    val handle = currentHandle ?: usbManager.open(device)
                    if (connection == null || handle == null) "تعذّر فتح الجهاز"
                    else Lbp6030Transport.sendAsset(this@MainActivity, connection, handle, asset).second
                }
                textStatus.text = report
            }
        }

        findViewById<Button>(R.id.buttonDiag).setOnClickListener {
            val device = currentDevice
            if (device == null) {
                textStatus.text = "اكتشف الطابعة أولاً"
                return@setOnClickListener
            }
            textStatus.text = "جارٍ التشخيص ..."
            uiScope.launch {
                val report = withContext(Dispatchers.IO) {
                    val androidUsbManager =
                        getSystemService(Context.USB_SERVICE) as UsbManager
                    val connection = currentConnection
                        ?: androidUsbManager.openDevice(device)
                    val handle = currentHandle ?: usbManager.open(device)
                    if (connection == null || handle == null) "تعذّر فتح الجهاز"
                    else Lbp6030Diag.run(connection, handle)
                }
                textStatus.text = report
            }
        }

        findViewById<Button>(R.id.buttonPrint).setOnClickListener {
            val device = currentDevice
            val imageUri = selectedImageUri

            if (device == null) {
                textStatus.text = "اكتشف الطابعة ولاً"
                return@setOnClickListener
            }
            if (imageUri == null) {
                textStatus.text = "اختر صورة ولاً"
                return@setOnClickListener
            }

            val language = when (radioGroup.checkedRadioButtonId) {
                R.id.radioPcl -> "PCL"
                R.id.radioEscPos -> "ESCPOS"
                R.id.radioCapt -> "CAPT"
                R.id.radioMf3010 -> "MF3010"
                else -> chooseAutoLanguage()
            }

            ditherEnabled = findViewById<CheckBox>(R.id.checkDither).isChecked
            textStatus.text = "جارٍ الإرسال عبر $language ..."

            uiScope.launch {
                val success = withContext(Dispatchers.IO) {
                    printImage(device, imageUri, language)
                }
                val base = if (success)
                    "تم إرسال المهمة إلى الطابعة"
                else
                    "فشل الإرسال"
                textStatus.text =
                    if ((language == "CAPT" || language == "MF3010") && lastCaptLog.isNotEmpty())
                        base + "\n\n" + lastCaptLog
                    else base
            }
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
                "اللغات: ${detectedLanguages.ifEmpty { setOf("غير معروفة") }}\n" +
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

    private fun printImage(
        device: UsbDevice,
        imageUri: Uri,
        language: String
    ): Boolean {
        val bitmap = contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return false

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
