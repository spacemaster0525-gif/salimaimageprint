package com.nokoprint.app.usb

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
        const val ACTION_USB_PERMISSION = "com.nokoprint.app.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = UsbConstants.USB_CLASS_PRINTER
        private const val GET_DEVICE_ID_REQUEST = 0x00
        private const val REQUEST_TYPE_CLASS_IN =
            UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_IN or 0x01
    }

    data class PrinterHandle(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val endpointOut: UsbEndpoint
    )

    fun listPrinters(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == USB_CLASS_PRINTER
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
            .firstOrNull { it.interfaceClass == USB_CLASS_PRINTER }
            ?: return null

        val endpointOut = (0 until printerInterface.endpointCount)
            .map { printerInterface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            ?: return null

        return PrinterHandle(device, printerInterface, endpointOut)
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
        val chunkSize = 16 * 1024
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
