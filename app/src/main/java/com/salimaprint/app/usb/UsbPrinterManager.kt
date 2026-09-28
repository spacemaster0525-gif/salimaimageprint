package com.salimaprint.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

class UsbPrinterManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.salimaprint.app.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = UsbConstants.USB_CLASS_PRINTER
        // Beaucoup d'imprimantes thermiques ESC/POS s'annoncent en classe
        // "Vendor Specific" (0xFF) plutôt qu'en classe Printer officielle.
        private const val USB_CLASS_VENDOR_SPECIFIC = 0xFF
        private const val GET_DEVICE_ID_REQUEST = 0x00
        private const val REQUEST_TYPE_CLASS_IN =
            UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_IN or 0x01

        private fun isPrinterLikeInterface(iface: UsbInterface): Boolean {
            if (iface.interfaceClass == USB_CLASS_PRINTER) return true
            if (iface.interfaceClass == USB_CLASS_VENDOR_SPECIFIC) {
                // On ne retient la classe vendor-specific que si l'interface
                // possède bien un endpoint de sortie utilisable pour écrire.
                return (0 until iface.endpointCount).any {
                    iface.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT
                }
            }
            return false
        }
    }

    data class PrinterHandle(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val endpointOut: UsbEndpoint,
        val endpointIn: UsbEndpoint? = null
    )

    fun listPrinters(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { i ->
                isPrinterLikeInterface(device.getInterface(i))
            }
        }
    }

    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) { }
                    onResult(granted)
                }
            }
        }

        val flags = PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_USB_PERMISSION)
            )
        }

        manager.requestPermission(device, pendingIntent)
    }

    fun open(device: UsbDevice): PrinterHandle? {
        // MF3010: الواجهة 0 = ماسح (vendor 0xFF، endpoints 0x03/0x84)،
        // الواجهة 1 = طابعة (class 7، endpoints 0x01/0x82). نفضّل class 7 أولاً.
        val all = (0 until device.interfaceCount).map { device.getInterface(it) }
        val printerInterface = all.firstOrNull { it.interfaceClass == USB_CLASS_PRINTER }
            ?: all.firstOrNull { isPrinterLikeInterface(it) }
            ?: return null

        val endpointOut = (0 until printerInterface.endpointCount)
            .map { printerInterface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            ?: return null

        val endpointIn = (0 until printerInterface.endpointCount)
            .map { printerInterface.getEndpoint(it) }
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }

        return PrinterHandle(device, printerInterface, endpointOut, endpointIn)
    }

    // Envoie une commande CAPT puis lit la reponse de l'imprimante.
    // Format de reponse (d'apres l'auteur du driver captdriver) :
    // cmd sur 2 octets, longueur totale sur 2 octets, puis les donnees.
    // Minimum 6 octets : un premier read() de 6 octets, puis un second
    // pour le reste. IMPORTANT : ne pas lire la reponse fait geler
    // l'imprimante. Retourne null en cas d'echec ou de timeout.
    @Volatile var lastError: String = ""

    fun sendCommandExpectReply(
        connection: UsbDeviceConnection,
        handle: PrinterHandle,
        command: ByteArray,
        timeoutMs: Int = 5000
    ): ByteArray? {
        lastError = ""
        val endpointIn = handle.endpointIn
        if (endpointIn == null) {
            lastError = "no IN endpoint"
            return null
        }
        if (!connection.claimInterface(handle.usbInterface, true)) {
            lastError = "claimInterface failed"
            return null
        }
        try {
            var offset = 0
            while (offset < command.size) {
                val len = minOf(4096, command.size - offset)
                val sent = connection.bulkTransfer(
                    handle.endpointOut, command, offset, len, timeoutMs
                )
                if (sent <= 0) {
                    lastError = "write failed code=$sent"
                    return null
                }
                offset += sent
            }

            // IMPORTANT : le buffer de lecture doit etre au moins aussi grand
            // que le paquet USB (sinon overflow => -1 sur Android). On lit
            // large, puis on interprete la longueur declaree dans l'en-tete.
            val mps = maxOf(endpointIn.maxPacketSize, 64)
            val bufSize = mps * 16
            val buf = ByteArray(bufSize)
            val n = connection.bulkTransfer(endpointIn, buf, 0, bufSize, timeoutMs)
            if (n < 4) {
                lastError = "read failed code=$n (mps=$mps)"
                return null
            }
            var total = n
            val declared =
                (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            val out = java.io.ByteArrayOutputStream()
            out.write(buf, 0, n)
            // completer si la reponse annoncee est plus longue que ce qui est recu
            var guard = 0
            while (total < declared && guard < 64) {
                val m = connection.bulkTransfer(endpointIn, buf, 0, bufSize, timeoutMs)
                if (m <= 0) break
                out.write(buf, 0, m)
                total += m
                guard++
            }
            return out.toByteArray()
        } catch (e: Exception) {
            lastError = "exception: ${e.message}"
            return null
        }
    }

    fun readDeviceId(connection: UsbDeviceConnection, iface: UsbInterface): String? {
        if (!connection.claimInterface(iface, true)) return null
        val buffer = ByteArray(1024)
        val length = connection.controlTransfer(
            REQUEST_TYPE_CLASS_IN,
            GET_DEVICE_ID_REQUEST,
            0,
            iface.id,
            buffer,
            buffer.size,
            5000
        )
        if (length < 2) return null
        return String(buffer, 2, length - 2, Charsets.US_ASCII)
    }

    fun sendRaw(
        connection: UsbDeviceConnection,
        handle: PrinterHandle,
        data: ByteArray
    ): Boolean {
        if (!connection.claimInterface(handle.usbInterface, true)) return false
        var offset = 0
        val chunkSize = 4096
        try {
            while (offset < data.size) {
                val len = minOf(chunkSize, data.size - offset)
                val sent = connection.bulkTransfer(
                    handle.endpointOut, data, offset, len, 15000
                )
                if (sent < 0) return false
                if (sent == 0) return false
                offset += sent
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    fun parseSupportedLanguages(deviceId: String): Set<String> {
        val cmdField = Regex("CMD:([^;]*);?", RegexOption.IGNORE_CASE)
            .find(deviceId)?.groupValues?.get(1) ?: return emptySet()
        return cmdField.split(",").map { it.trim().uppercase() }.toSet()
    }
}
