package com.yebente.wallet.offline.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.nfc.cardemulation.HostApduService
import java.io.IOException

object NfcProtocol {
  val AID = byteArrayOf(0xF0.toByte(), 0x59, 0x42, 0x31, 0x01)

  fun selectApdu(): ByteArray =
    byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

  fun readApdu(offset: Int, length: Int = 0): ByteArray =
    byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), length.toByte())

  fun isOk(response: ByteArray): Boolean =
    response.size >= 2 &&
      response[response.size - 2] == 0x90.toByte() &&
      response[response.size - 1] == 0x00.toByte()

  fun body(response: ByteArray): ByteArray = response.copyOf(response.size - 2)
}

object HcePayload {
  @Volatile
  var data: ByteArray? = null
}

object NfcHceResponder {
  const val CHUNK = 240

  fun status(sw1: Int, sw2: Int): ByteArray = byteArrayOf(sw1.toByte(), sw2.toByte())

  fun selectResponse(payload: ByteArray?): ByteArray {
    val data = payload ?: return status(0x6A, 0x82)
    if (data.size > 0xFFFF) return status(0x67, 0x00)
    val len = byteArrayOf((data.size shr 8).toByte(), data.size.toByte())
    return len + status(0x90, 0x00)
  }

  fun readResponse(payload: ByteArray?, offset: Int): ByteArray {
    val data = payload ?: return status(0x6A, 0x82)
    if (offset > data.size) return status(0x6B, 0x00)
    val end = minOf(data.size, offset + CHUNK)
    return data.copyOfRange(offset, end) + status(0x90, 0x00)
  }
}

class HcePromiseService : HostApduService() {
  override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
    if (commandApdu == null || commandApdu.size < 4) return NfcHceResponder.status(0x67, 0x00)
    if (commandApdu[1] == 0xA4.toByte()) {
      return NfcHceResponder.selectResponse(HcePayload.data)
    }
    if (commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xB0.toByte() && commandApdu.size >= 4) {
      val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or (commandApdu[3].toInt() and 0xFF)
      return NfcHceResponder.readResponse(HcePayload.data, offset)
    }
    return NfcHceResponder.status(0x6D, 0x00)
  }

  override fun onDeactivated(reason: Int) {
    HcePayload.data = null
  }
}

class NfcPromiseReader(private val activity: Activity) {
  private val main = Handler(Looper.getMainLooper())
  private var active = false

  fun isAvailable(): Boolean = NfcAdapter.getDefaultAdapter(activity) != null

  fun start(onPayload: (ByteArray) -> Unit, onError: (String) -> Unit) {
    val adapter = NfcAdapter.getDefaultAdapter(activity)
    if (adapter == null) {
      onError("This device has no NFC.")
      return
    }
    if (active) return
    active = true
    val flags = NfcAdapter.FLAG_READER_NFC_A or
      NfcAdapter.FLAG_READER_NFC_B or
      NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    val options = Bundle().apply {
      putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
    }
    adapter.enableReaderMode(activity, { tag -> readTag(tag, onPayload, onError) }, flags, options)
  }

  fun stop() {
    if (!active) return
    active = false
    NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
  }

  private fun readTag(tag: Tag?, onPayload: (ByteArray) -> Unit, onError: (String) -> Unit) {
    fun fail(message: String) = main.post { if (active) onError(message) }
    val iso = if (tag == null) null else IsoDep.get(tag)
    if (iso == null) {
      fail("No sender found. Hold the phones together.")
      return
    }
    try {
      iso.connect()
      iso.timeout = 5000
      val select = iso.transceive(NfcProtocol.selectApdu())
      if (!NfcProtocol.isOk(select)) {
        fail("Sender did not answer.")
        return
      }
      val header = NfcProtocol.body(select)
      if (header.size != 2) {
        fail("Sender sent a bad header.")
        return
      }
      val total = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
      if (total <= 0 || total > MAX_PAYLOAD) {
        fail("Sender sent a bad size.")
        return
      }
      val out = ByteArray(total)
      var offset = 0
      while (offset < total) {
        val chunk = iso.transceive(NfcProtocol.readApdu(offset))
        if (!NfcProtocol.isOk(chunk)) {
          fail("Transfer failed partway.")
          return
        }
        val body = NfcProtocol.body(chunk)
        if (body.isEmpty()) {
          fail("Transfer failed partway.")
          return
        }
        System.arraycopy(body, 0, out, offset, body.size)
        offset += body.size
      }
      try {
        iso.close()
      } catch (_: IOException) {
      }
      main.post { if (active) onPayload(out) }
    } catch (_: IOException) {
      fail("Connection broke. Hold the phones steady and retry.")
    } catch (_: SecurityException) {
      fail("NFC permission is missing.")
    }
  }

  companion object {
    private const val MAX_PAYLOAD = 8192
  }
}
