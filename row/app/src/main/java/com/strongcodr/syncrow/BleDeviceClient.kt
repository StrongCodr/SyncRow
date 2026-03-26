package com.strongcodr.syncrow

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class BleDeviceClient(private val context: Context) {

    private val tag = "BleDeviceClient"

    private var gatt: BluetoothGatt? = null
    private var sampleCallback: ((Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var disconnectCallback: (() -> Unit)? = null

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(
        deviceAddress: String,
        onSample: (
            ax: Float,
            ay: Float,
            az: Float,
            wx: Float,
            wy: Float,
            wz: Float,
            roll: Float,
            pitch: Float,
            yaw: Float
        ) -> Unit,
        onStatus: (String) -> Unit,
        onDisconnected: (() -> Unit)? = null,
        onConnectionStateChange: ((status: Int, newState: Int) -> Unit)? = null
    ) {
        val macLog = bleMacForLog(deviceAddress)
        val scanPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasConnectPermission()) {
            val msg = "Missing BLUETOOTH_CONNECT permission for $macLog"
            if (DEBUG_BLE) Log.e(tag, "$msg sdk=${Build.VERSION.SDK_INT} scanPerm=$scanPermGranted")
            onStatus(msg)
            return
        }

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val adapterEnabled = adapter?.isEnabled == true
        val device = adapter?.getRemoteDevice(deviceAddress)

        if (device == null) {
            val msg = "Device not found for address $macLog"
            if (DEBUG_BLE) Log.e(tag, "$msg sdk=${Build.VERSION.SDK_INT} adapterEnabled=$adapterEnabled")
            onStatus(msg)
            return
        }

        sampleCallback = onSample
        statusCallback = onStatus
        disconnectCallback = onDisconnected
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null

        if (DEBUG_BLE) {
            Log.d(
                tag,
                "Connect attempt mac=$macLog sdk=${Build.VERSION.SDK_INT} " +
                    "adapterEnabled=$adapterEnabled connectPerm=${hasConnectPermission()} scanPerm=$scanPermGranted"
            )
        }
        onStatus("Connecting to $deviceAddress ...")

        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                onConnectionStateChange?.invoke(status, newState)
                if (DEBUG_BLE) {
                    Log.d(
                        tag,
                        "GATT state mac=$macLog status=$status(${gattStatusToReason(status)}) " +
                            "newState=$newState(${stateToName(newState)})"
                    )
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // error case
                    statusCallback?.invoke("GATT error on $macLog status=$status (${gattStatusToReason(status)})")
                    disconnectCallback?.invoke()
                    gatt.close()
                    this@BleDeviceClient.gatt = null
                    return
                }

                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        statusCallback?.invoke("Connected to $macLog, discovering services...")
                        gatt.discoverServices()
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        statusCallback?.invoke("Disconnected from $macLog")
                        disconnectCallback?.invoke()
                        gatt.close()
                        this@BleDeviceClient.gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    statusCallback?.invoke("Service discovery failed on $macLog status=$status (${gattStatusToReason(status)})")
                    if (DEBUG_BLE) Log.e(tag, "Service discovery failed mac=$macLog status=$status(${gattStatusToReason(status)})")
                    return
                }

                val services = gatt.services
                if (services.isEmpty()) {
                    statusCallback?.invoke("No services found on $macLog")
                    if (DEBUG_BLE) Log.e(tag, "No services found mac=$macLog")
                    return
                }

                // Find first NOTIFY characteristic
                for (service in services) {
                    for (ch in service.characteristics) {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            statusCallback?.invoke("Subscribing on $macLog to ${ch.uuid}")
                            enableNotifications(gatt, ch)
                            return
                        }
                    }
                }

                statusCallback?.invoke("No notifiable characteristics found on $macLog")
                if (DEBUG_BLE) Log.e(tag, "No notifiable characteristics mac=$macLog")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicChanged(gatt, characteristic)

                val bytes = characteristic.value ?: return

                // Expect WIT 20-byte packet: 0x55 0x61 ...
                if (bytes.size < 20 || bytes[0] != 0x55.toByte()) return
                val flag = bytes[1].toUByte().toInt()
                if (flag != 0x61) return

                fun s16(lo: Byte, hi: Byte): Int {
                    return ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()
                }

                val axRaw = s16(bytes[2], bytes[3])
                val ayRaw = s16(bytes[4], bytes[5])
                val azRaw = s16(bytes[6], bytes[7])
                val wxRaw = s16(bytes[8], bytes[9])
                val wyRaw = s16(bytes[10], bytes[11])
                val wzRaw = s16(bytes[12], bytes[13])
                val rollRaw  = s16(bytes[14], bytes[15])
                val pitchRaw = s16(bytes[16], bytes[17])
                val yawRaw   = s16(bytes[18], bytes[19])

                val ax = axRaw / 32768.0f * 16f
                val ay = ayRaw / 32768.0f * 16f
                val az = azRaw / 32768.0f * 16f

                // Gyro uses +/-2000 dps scale for WIT packets.
                val wx = wxRaw / 32768.0f * 2000f
                val wy = wyRaw / 32768.0f * 2000f
                val wz = wzRaw / 32768.0f * 2000f
                val roll  = rollRaw  / 32768.0f * 180f
                val pitch = pitchRaw / 32768.0f * 180f
                val yaw   = yawRaw   / 32768.0f * 180f

                sampleCallback?.invoke(ax, ay, az, wx, wy, wz, roll, pitch, yaw)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (DEBUG_BLE) {
            Log.d(
                tag,
                "setCharacteristicNotification mac=${bleMacForLog(gatt.device?.address)} ok=$ok char=${characteristic.uuid}"
            )
        }

        val cccd = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        )
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeOk = gatt.writeDescriptor(cccd)
            if (DEBUG_BLE) Log.d(tag, "writeDescriptor CCCD mac=${bleMacForLog(gatt.device?.address)} ok=$writeOk")
        } else {
            if (DEBUG_BLE) {
                Log.e(tag, "Missing CCCD descriptor mac=${bleMacForLog(gatt.device?.address)} char=${characteristic.uuid}")
            }
        }
    }

    fun disconnect() {
        if (DEBUG_BLE) Log.d(tag, "disconnect requested mac=${bleMacForLog(gatt?.device?.address)}")
        gatt?.close()
        gatt = null
    }

    private fun stateToName(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN"
        }
    }

    private fun gattStatusToReason(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            8 -> "GATT_CONN_TIMEOUT"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR_133"
            257 -> "GATT_CONN_CANCEL"
            else -> "UNKNOWN_GATT_STATUS"
        }
    }
}
