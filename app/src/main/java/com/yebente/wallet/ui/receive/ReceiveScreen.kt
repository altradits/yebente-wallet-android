package com.yebente.wallet.ui.receive

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.domain.protocol.ValidationResult
import com.yebente.wallet.offline.ble.BlePromiseReceiver
import com.yebente.wallet.offline.ble.BlePromiseSender
import com.yebente.wallet.offline.nfc.HcePayload
import com.yebente.wallet.offline.nfc.NfcPromiseReader
import com.yebente.wallet.offline.qr.QrCodes
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.ReadoutRow
import com.yebente.wallet.ui.components.StanHeader
import com.yebente.wallet.ui.components.YebenteButton
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReceiveUiState(
  val handshake: ReceiveHandshake? = null,
  val handshakeCode: String = "",
  val busy: Boolean = false,
  val status: String = "",
  val error: String = "",
)

class ReceiveViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(ReceiveUiState())
  val state: StateFlow<ReceiveUiState> = _state

  private var reader: NfcPromiseReader? = null
  private var job: Job? = null

  fun loadHandshake() {
    if (_state.value.handshake != null) return
    viewModelScope.launch {
      try {
        val handshake = withContext(Dispatchers.IO) { container.repository.myHandshake() }
        _state.value = _state.value.copy(
          handshake = handshake,
          handshakeCode = PromiseCodec.encodeHandshake(handshake),
        )
      } catch (t: Exception) {
        _state.value = _state.value.copy(error = t.message ?: "Could not build receive code.")
      }
    }
  }

  fun ingestScanned(text: String) {
    val clean = text.trim()
    viewModelScope.launch {
      try {
        if (clean.startsWith(PromiseCodec.RECEIPT_PREFIX + ":")) {
          val receipt = withContext(Dispatchers.Default) { PromiseCodec.decodeReceipt(clean) }
          val ok = withContext(Dispatchers.IO) { container.repository.importReceipt(receipt) }
          _state.value = if (ok) {
            _state.value.copy(status = "Receipt confirmed for ${receipt.sats} sats.", error = "")
          } else {
            _state.value.copy(error = "Receipt did not match any pending payment.")
          }
          return@launch
        }
        val frames = clean.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val promise = withContext(Dispatchers.Default) { PromiseCodec.decodePromise(frames) }
        val verdict = withContext(Dispatchers.IO) { container.repository.acceptInbound(promise) }
        _state.value = when (verdict) {
          is ValidationResult.Valid -> _state.value.copy(
            status = "Received ${promise.sats} sats. Spendable now, settles on reconnect.",
            error = "",
          )
          is ValidationResult.Invalid -> _state.value.copy(error = verdict.reason, status = "")
        }
      } catch (t: Exception) {
        _state.value = _state.value.copy(error = t.message ?: "Could not read that code.", status = "")
      }
    }
  }

  fun shareHandshakeNfc() {
    val code = _state.value.handshakeCode
    if (code.isBlank()) {
      _state.value = _state.value.copy(error = "Receive code is not ready yet.")
      return
    }
    HcePayload.data = code.toByteArray(Charsets.UTF_8)
    _state.value = _state.value.copy(status = "Hold the phones together to share your code.", error = "")
  }

  fun shareHandshakeBle() {
    val code = _state.value.handshakeCode
    if (code.isBlank()) {
      _state.value = _state.value.copy(error = "Receive code is not ready yet.")
      return
    }
    stopTransfers()
    _state.value = _state.value.copy(busy = true, status = "Waiting for the sender phone.", error = "")
    job = viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        BlePromiseSender(appContext()).send(code.toByteArray(Charsets.UTF_8))
      }
      result.fold(
        onSuccess = {
          _state.value = _state.value.copy(busy = false, status = "Code shared over Bluetooth.")
        },
        onFailure = { t ->
          _state.value = _state.value.copy(busy = false, status = "", error = t.message ?: "Bluetooth share failed.")
        },
      )
    }
  }

  fun receiveNfc(activity: Activity) {
    stopTransfers()
    val r = NfcPromiseReader(activity)
    reader = r
    if (!r.isAvailable()) {
      _state.value = _state.value.copy(error = "This device has no NFC.")
      return
    }
    _state.value = _state.value.copy(status = "Hold the phones together to receive.", error = "")
    r.start(
      onPayload = { bytes ->
        ingestScanned(String(bytes, Charsets.UTF_8))
      },
      onError = { message ->
        _state.value = _state.value.copy(error = message, status = "")
      },
    )
  }

  fun receiveBle() {
    stopTransfers()
    _state.value = _state.value.copy(busy = true, status = "Looking for the sender phone.", error = "")
    job = viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { BlePromiseReceiver(appContext()).receive() }
      result.fold(
        onSuccess = { bytes ->
          _state.value = _state.value.copy(busy = false, status = "")
          ingestScanned(String(bytes, Charsets.UTF_8))
        },
        onFailure = { t ->
          _state.value = _state.value.copy(busy = false, status = "", error = t.message ?: "Bluetooth receive failed.")
        },
      )
    }
  }

  fun stopTransfers() {
    reader?.stop()
    reader = null
    job?.cancel()
    job = null
    if (_state.value.busy) _state.value = _state.value.copy(busy = false, status = "")
  }

  private fun appContext(): android.content.Context = container.appContext

  override fun onCleared() {
    stopTransfers()
  }
}

@Composable
fun ReceiveScreen(
  vm: ReceiveViewModel,
  onBack: () -> Unit,
  onScanPayment: () -> Unit,
  onRequestBle: (onGranted: () -> Unit) -> Unit,
) {
  val state by vm.state.collectAsState()
  val context = LocalContext.current

  LaunchedEffect(Unit) { vm.loadHandshake() }
  DisposableEffect(Unit) {
    onDispose { vm.stopTransfers() }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StanHeader(title = "Receive Sats", onBack = onBack)

    Text(
      text = "Your code",
      fontFamily = Sans,
      fontSize = 13.sp,
      color = ObsidianPlum.TextMuted,
    )
    if (state.handshakeCode.isNotBlank()) {
      val bitmap: Bitmap? = try {
        QrCodes.toBitmap(state.handshakeCode)
      } catch (_: Exception) {
        null
      }
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Receive code",
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        )
      }
      state.handshake?.let { handshake ->
        ReadoutRow(label = "On-chain", value = handshake.address.take(20) + "...")
        if (handshake.lightning.isNotBlank()) {
          ReadoutRow(label = "Lightning", value = handshake.lightning)
        }
      }
      NeutralButton(label = "Share Code with NFC", onClick = vm::shareHandshakeNfc)
      NeutralButton(label = "Share Code with Bluetooth", onClick = {
        onRequestBle { vm.shareHandshakeBle() }
      })
    }

    Text(
      text = "Accept a payment",
      fontFamily = Sans,
      fontSize = 13.sp,
      color = ObsidianPlum.TextMuted,
    )
    YebenteButton(label = "Scan Payment", onClick = onScanPayment)
    NeutralButton(label = "Tap to Receive", onClick = {
      (context as? Activity)?.let { vm.receiveNfc(it) }
    })
    NeutralButton(label = "Find with Bluetooth", onClick = {
      onRequestBle { vm.receiveBle() }
    })

    Text(
      text = "Accepted payments are spendable at once and settle on reconnect.",
      fontFamily = Mono,
      fontSize = 11.sp,
      color = ObsidianPlum.TextMuted,
    )
    Note(text = state.status)
    Note(text = state.error, error = true)
    Spacer(modifier = Modifier.height(16.dp))
  }
}
