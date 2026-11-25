package com.yebente.wallet.offline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object BleProtocol {
  val SERVICE_UUID: UUID = UUID.fromString("c0ffee00-59b1-4000-8000-000000000000")
  val CHAR_UUID: UUID = UUID.fromString("c0ffee01-59b1-4000-8000-000000000000")
  val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

  const val CHUNK_PAYLOAD = 180

  fun toChunks(payload: ByteArray): List<ByteArray> {
    val data = payload.toList().chunked(CHUNK_PAYLOAD).map { it.toByteArray() }
    val total = data.size
    return data.mapIndexed { index, part ->
      val header = byteArrayOf(
        (total shr 8).toByte(), total.toByte(),
        ((index + 1) shr 8).toByte(), (index + 1).toByte(),
      )
      header + part
    }
  }

  fun parseChunk(chunk: ByteArray): Triple<Int, Int, ByteArray>? {
    if (chunk.size < 4) return null
    val total = ((chunk[0].toInt() and 0xFF) shl 8) or (chunk[1].toInt() and 0xFF)
    val index = ((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)
    if (total !in 1..256 || index !in 1..total) return null
    return Triple(total, index, chunk.copyOfRange(4, chunk.size))
  }
}

@SuppressLint("MissingPermission")
class BlePromiseSender(private val context: Context) {
  private val manager = context.applicationContext
    .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val main = Handler(Looper.getMainLooper())
  private var server: BluetoothGattServer? = null
  private var advertising = false
  private val done = AtomicBoolean(false)

  suspend fun send(payload: ByteArray, timeoutMs: Long = 60_000): Result<Unit> {
    val adapter = manager.adapter ?: return Result.failure(IllegalStateException("No Bluetooth adapter."))
    if (!adapter.isEnabled) return Result.failure(IllegalStateException("Bluetooth is off."))
    if (payload.isEmpty() || payload.size > MAX_PAYLOAD) {
      return Result.failure(IllegalArgumentException("Payload size is out of range."))
    }
    return suspendCancellableCoroutine { cont ->
      val chunks = BleProtocol.toChunks(payload)
      var device: BluetoothDevice? = null
      var next = 0

      fun finish(result: Result<Unit>) {
        if (done.compareAndSet(false, true)) {
          cleanup()
          cont.resume(result)
        }
      }

      val timeout = Runnable { finish(Result.failure(IllegalStateException("No receiver found in time."))) }
      main.postDelayed(timeout, timeoutMs)
      cont.invokeOnCancellation {
        main.removeCallbacks(timeout)
        finish(Result.failure(IllegalStateException("Cancelled.")))
      }

      fun notifyNext() {
        val target = device
        val gatt = server
        if (target == null || gatt == null) return
        if (next >= chunks.size) {
          main.removeCallbacks(timeout)
          main.postDelayed({ finish(Result.success(Unit)) }, 400)
          return
        }
        val char = gatt.getService(BleProtocol.SERVICE_UUID)
          ?.getCharacteristic(BleProtocol.CHAR_UUID) ?: run {
            finish(Result.failure(IllegalStateException("GATT service missing.")))
            return
          }
        val chunk = chunks[next++]
        if (Build.VERSION.SDK_INT >= 33) {
          val code = gatt.notifyCharacteristicChanged(target, char, false, chunk)
          if (code != BluetoothGatt.GATT_SUCCESS) finish(Result.failure(IllegalStateException("Notify failed.")))
        } else {
          @Suppress("DEPRECATION")
          char.value = chunk
          @Suppress("DEPRECATION")
          if (!gatt.notifyCharacteristicChanged(target, char, false)) {
            finish(Result.failure(IllegalStateException("Notify failed.")))
          }
        }
      }

      val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
          if (newState == BluetoothGatt.STATE_CONNECTED) {
            device = d
          } else if (newState == BluetoothGatt.STATE_DISCONNECTED && next < chunks.size) {
            main.post { finish(Result.failure(IllegalStateException("Receiver disconnected."))) }
          }
        }

        override fun onDescriptorWriteRequest(
          d: BluetoothDevice,
          requestId: Int,
          descriptor: BluetoothGattDescriptor,
          preparedWrite: Boolean,
          responseNeeded: Boolean,
          offset: Int,
          value: ByteArray,
        ) {
          if (responseNeeded) {
            if (Build.VERSION.SDK_INT >= 33) {
              server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            } else {
              @Suppress("DEPRECATION")
              server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
          }
          if (descriptor.uuid == BleProtocol.CCCD_UUID) {
            main.post { notifyNext() }
          }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) {
          if (status != BluetoothGatt.GATT_SUCCESS) {
            main.post { finish(Result.failure(IllegalStateException("Chunk send failed."))) }
          } else {
            main.postDelayed({ notifyNext() }, 25)
          }
        }
      }

      try {
        val gattServer = manager.openGattServer(context.applicationContext, serverCallback)
          ?: run {
            finish(Result.failure(IllegalStateException("Could not open GATT server.")))
            return@suspendCancellableCoroutine
          }
        server = gattServer
        val characteristic = BluetoothGattCharacteristic(
          BleProtocol.CHAR_UUID,
          BluetoothGattCharacteristic.PROPERTY_NOTIFY,
          BluetoothGattCharacteristic.PERMISSION_READ,
        )
        characteristic.addDescriptor(
          BluetoothGattDescriptor(BleProtocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        val service = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer.addService(service)

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
          finish(Result.failure(IllegalStateException("BLE advertising is not supported.")))
          return@suspendCancellableCoroutine
        }
        val settings = AdvertiseSettings.Builder()
          .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
          .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
          .setConnectable(true)
          .build()
        val data = AdvertiseData.Builder()
          .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
          .build()
        advertising = true
        advertiser.startAdvertising(
          settings,
          data,
          object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
              main.post { finish(Result.failure(IllegalStateException("Advertising failed: $errorCode."))) }
            }
          },
        )
      } catch (_: SecurityException) {
        finish(Result.failure(IllegalStateException("Bluetooth permission is missing.")))
      }
    }
  }

  fun cancel() {
    cleanup()
  }

  private fun cleanup() {
    try {
      if (advertising) {
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        advertising = false
      }
      server?.close()
    } catch (_: SecurityException) {
    }
    server = null
  }

  companion object {
    private const val MAX_PAYLOAD = 8192
  }
}

@SuppressLint("MissingPermission")
class BlePromiseReceiver(private val context: Context) {
  private val manager = context.applicationContext
    .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val main = Handler(Looper.getMainLooper())
  private var gatt: BluetoothGatt? = null
  private var scanning = false
  private val done = AtomicBoolean(false)

  suspend fun receive(timeoutMs: Long = 60_000): Result<ByteArray> {
    val adapter = manager.adapter ?: return Result.failure(IllegalStateException("No Bluetooth adapter."))
    if (!adapter.isEnabled) return Result.failure(IllegalStateException("Bluetooth is off."))
    return suspendCancellableCoroutine { cont ->
      val parts = mutableMapOf<Int, ByteArray>()
      var expectedTotal = -1

      fun finish(result: Result<ByteArray>) {
        if (done.compareAndSet(false, true)) {
          cleanup()
          cont.resume(result)
        }
      }

      val timeout = Runnable { finish(Result.failure(IllegalStateException("No sender found in time."))) }
      main.postDelayed(timeout, timeoutMs)
      cont.invokeOnCancellation {
        main.removeCallbacks(timeout)
        finish(Result.failure(IllegalStateException("Cancelled.")))
      }

      val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
          if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (!g.requestMtu(247)) main.post { g.discoverServices() }
          } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            main.post { finish(Result.failure(IllegalStateException("Sender disconnected."))) }
          }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
          g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
          val char = g.getService(BleProtocol.SERVICE_UUID)?.getCharacteristic(BleProtocol.CHAR_UUID)
          if (char == null || !g.setCharacteristicNotification(char, true)) {
            main.post { finish(Result.failure(IllegalStateException("Sender has no payment service."))) }
            return
          }
          val cccd = char.getDescriptor(BleProtocol.CCCD_UUID)
          if (cccd == null) {
            main.post { finish(Result.failure(IllegalStateException("Sender setup failed."))) }
            return
          }
          if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
          } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
          }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
          @Suppress("DEPRECATION")
          handleValue(characteristic.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
          handleValue(value)
        }

        fun handleValue(value: ByteArray) {
          val parsed = BleProtocol.parseChunk(value)
          if (parsed == null) {
            main.post { finish(Result.failure(IllegalStateException("Sender sent a bad chunk."))) }
            return
          }
          val (total, index, payload) = parsed
          if (expectedTotal == -1) expectedTotal = total
          if (total != expectedTotal) {
            main.post { finish(Result.failure(IllegalStateException("Sender sent mixed transfers."))) }
            return
          }
          parts[index] = payload
          if (parts.size == expectedTotal) {
            val out = (1..expectedTotal).fold(ByteArray(0)) { acc, i -> acc + parts.getValue(i) }
            main.removeCallbacks(timeout)
            main.post { finish(Result.success(out)) }
          }
        }
      }

      val scanner = adapter.bluetoothLeScanner
      if (scanner == null) {
        finish(Result.failure(IllegalStateException("BLE scan is not supported.")))
        return@suspendCancellableCoroutine
      }
      val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
          try {
            scanner.stopScan(this)
          } catch (_: SecurityException) {
          }
          scanning = false
          gatt = result.device.connectGatt(context.applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
          if (gatt == null) {
            main.post { finish(Result.failure(IllegalStateException("Could not connect to sender."))) }
          }
        }

        override fun onScanFailed(errorCode: Int) {
          main.post { finish(Result.failure(IllegalStateException("Scan failed: $errorCode."))) }
        }
      }
      try {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(listOf(filter), scanSettings, callback)
      } catch (_: SecurityException) {
        finish(Result.failure(IllegalStateException("Bluetooth permission is missing.")))
      }
    }
  }

  fun cancel() {
    cleanup()
  }

  private fun cleanup() {
    try {
      if (scanning) {
        manager.adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
        scanning = false
      }
      gatt?.disconnect()
      gatt?.close()
    } catch (_: SecurityException) {
    }
    gatt = null
  }
}
