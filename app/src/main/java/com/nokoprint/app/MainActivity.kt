package com.nokoprint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nokoprint.app.render.EscPosRenderer
import com.nokoprint.app.render.PclRenderer
import com.nokoprint.app.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbPrinterManager
    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null
    private var currentHandle: UsbPrinterManager.PrinterHandle? = null
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
                else -> chooseAutoLanguage()
            }

            textStatus.text = "جارٍ الإرسال عبر $language ..."

            uiScope.launch {
                val success = withContext(Dispatchers.IO) {
                    printImage(device, imageUri, language)
                }
                textStatus.text = if (success)
                    "تم إرسال المهمة إلى الطابعة"
                else
                    "فشل الإرسال"
            }
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

        val payload: ByteArray = when (language) {
            "PCL" -> PclRenderer.render(bitmap)
            else -> EscPosRenderer.render(bitmap)
        }

        val connection = currentConnection ?: run {
            val androidUsbManager =
                getSystemService(Context.USB_SERVICE) as UsbManager
            androidUsbManager.openDevice(device) ?: return false
        }
        val handle = currentHandle ?: usbManager.open(device) ?: return false

        return usbManager.sendRaw(connection, handle, payload)
    }
}
