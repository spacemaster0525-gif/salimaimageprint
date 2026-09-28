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
        val printerInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { isPrinterLikeInterface(it) }
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

    /**
     * Envoie une commande CAPT puis lit la réponse de l'imprimante.
     * Format de réponse (d'après l'auteur du driver captdriver) :
     * [cmd(2)][longueur totale(2)][données...], minimum 6 octets, lus en
     * un premier read() de 6 octets puis un second pour le reste.
     * IMPORTANT : ne pas lire la réponse fait "geler" l'imprimante.
     * Retourne null en cas d'échec/timeout.
     */
    fun sendCommandExpectReply(
        connection: UsbDeviceConnection,
        handle: PrinterHandle,
        command: ByteArray,
        timeoutMs: Int = 5000
    ): ByteArray? {
        val endpointIn = handle.endpointIn ?: return null
        if (!connection.claimInterface(handle.usbInterface, true)) return null
        try {
            var offset = 0
            while (offset < command.size) {
                val len = minOf(4096, command.size - offset)
                val sent = connection.bulkTransfer(
                    handle.endpointOut, command, offset, len, timeoutMs
                )
                if (sent <= 0) return null
                offset += sent
            }

            val header = ByteArray(6)
            val n = connection.bulkTransfer(endpointIn, header, 0, 6, timeoutMs)
            if (n < 4) return null
            val declared =
                (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
            if (declared <= n) return header.copyOf(n)

            val remaining = declared - n
            val rest = ByteArray(remaining)
            val n2 = connection.bulkTransfer(endpointIn, rest, 0, remaining, timeoutMs)
            if (n2 <= 0) return header.copyOf(n)
            return header.copyOf(n) + rest.copyOf(n2)
        } catch (_: Exception) {
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
