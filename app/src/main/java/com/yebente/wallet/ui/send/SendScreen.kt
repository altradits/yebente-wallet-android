package com.yebente.wallet.ui.send

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.protocol.PromiseCodec
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

data class SendUiState(
  val handshake: ReceiveHandshake? = null,
  val amountText: String = "",
  val memo: String = "",
  val promise: OfflinePromise? = null,
  val frames: List<String> = emptyList(),
  val frameIndex: Int = 0,
  val busy: Boolean = false,
  val status: String = "",
  val error: String = "",
)

class SendViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(SendUiState())
  val state: StateFlow<SendUiState> = _state

  private var reader: NfcPromiseReader? = null
  private var job: Job? = null

  fun onHandshakeCode(text: String) {
    try {
      val handshake = PromiseCodec.decodeHandshake(text)
      _state.value = _state.value.copy(handshake = handshake, status = "Recipient code accepted.", error = "")
    } catch (t: Exception) {
      _state.value = _state.value.copy(error = t.message ?: "Bad recipient code.")
    }
  }

  fun onAmountChange(value: String) {
    _state.value = _state.value.copy(amountText = value.filter { it.isDigit() }.take(12))
  }

  fun onMemoChange(value: String) {
    _state.value = _state.value.copy(memo = value.take(120))
  }

  fun createPromise() {
    val current = _state.value
    val handshake = current.handshake ?: run {
      _state.value = current.copy(error = "Read the recipient code first.")
      return
    }
    val sats = current.amountText.toLongOrNull() ?: 0L
    if (sats <= 0) {
      _state.value = current.copy(error = "Enter an amount in sats.")
      return
    }
    _state.value = current.copy(busy = true, error = "", status = "")
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        container.repository.createOutbound(handshake, sats, current.memo)
      }
      result.fold(
        onSuccess = { promise ->
          val frames = PromiseCodec.encodePromise(promise)
          _state.value = _state.value.copy(
            busy = false,
            promise = promise,
            frames = frames,
            frameIndex = 0,
            status = "Payment created. Show it to the recipient.",
          )
        },
        onFailure = { t ->
          _state.value = _state.value.copy(busy = false, error = t.message ?: "Could not create payment.")
        },
      )
    }
  }

  fun nextFrame() {
    val current = _state.value
    if (current.frameIndex + 1 < current.frames.size) {
      _state.value = current.copy(frameIndex = current.frameIndex + 1)
    }
  }

  fun prevFrame() {
    val current = _state.value
    if (current.frameIndex > 0) {
      _state.value = current.copy(frameIndex = current.frameIndex - 1)
    }
  }

  fun readHandshakeNfc(activity: Activity) {
    stopTransfers()
    val r = NfcPromiseReader(activity)
    reader = r
    if (!r.isAvailable()) {
      _state.value = _state.value.copy(error = "This device has no NFC.")
      return
    }
    _state.value = _state.value.copy(status = "Hold the phones together to read the code.", error = "")
    r.start(
      onPayload = { bytes ->
        stopTransfers()
        onHandshakeCode(String(bytes, Charsets.UTF_8).trim())
      },
      onError = { message ->
        _state.value = _state.value.copy(error = message, status = "")
      },
    )
  }

  fun readHandshakeBle() {
    stopTransfers()
    _state.value = _state.value.copy(busy = true, status = "Looking for the recipient phone.", error = "")
    job = viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        com.yebente.wallet.offline.ble.BlePromiseReceiver(containerContext()).receive()
      }
      result.fold(
        onSuccess = { bytes ->
          _state.value = _state.value.copy(busy = false, status = "")
          onHandshakeCode(String(bytes, Charsets.UTF_8).trim())
        },
        onFailure = { t ->
          _state.value = _state.value.copy(busy = false, status = "", error = t.message ?: "Bluetooth read failed.")
        },
      )
    }
  }

  fun transmitNfc() {
    val payload = payloadBytes() ?: return
    HcePayload.data = payload
    _state.value = _state.value.copy(status = "Hold the phones together to send.", error = "")
  }

  fun transmitBle() {
    val payload = payloadBytes() ?: return
    stopTransfers()
    _state.value = _state.value.copy(busy = true, status = "Waiting for the recipient phone.", error = "")
    job = viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        BlePromiseSender(containerContext()).send(payload)
      }
      result.fold(
        onSuccess = {
          _state.value = _state.value.copy(busy = false, status = "Sent over Bluetooth. It settles on reconnect.")
        },
        onFailure = { t ->
          _state.value = _state.value.copy(busy = false, status = "", error = t.message ?: "Bluetooth send failed.")
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

  private fun payloadBytes(): ByteArray? {
    val frames = _state.value.frames
    if (frames.isEmpty()) {
      _state.value = _state.value.copy(error = "Create the payment first.")
      return null
    }
    return frames.joinToString("\n").toByteArray(Charsets.UTF_8)
  }

  private fun containerContext(): android.content.Context = container.appContext

  override fun onCleared() {
    stopTransfers()
  }
}

@Composable
fun SendScreen(
  vm: SendViewModel,
  onBack: () -> Unit,
  onScanHandshake: () -> Unit,
  onRequestBle: (onGranted: () -> Unit) -> Unit,
) {
  val state by vm.state.collectAsState()
  val context = LocalContext.current

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
    StanHeader(title = "Send Sats", onBack = onBack)

    if (state.handshake == null) {
      Text(
        text = "Read the recipient code first. No internet needed.",
        fontFamily = Sans,
        fontSize = 13.sp,
        color = ObsidianPlum.TextMuted,
      )
      YebenteButton(label = "Scan Code", onClick = onScanHandshake)
      NeutralButton(label = "Tap to Read", onClick = {
        (context as? Activity)?.let { vm.readHandshakeNfc(it) }
      })
      NeutralButton(label = "Find with Bluetooth", onClick = {
        onRequestBle { vm.readHandshakeBle() }
      })
    } else if (state.promise == null) {
      ReadoutRow(label = "Recipient", value = state.handshake!!.recipient.take(16))
      if (state.handshake!!.lightning.isNotBlank()) {
        ReadoutRow(label = "Lightning", value = state.handshake!!.lightning)
      }
      TextField(
        value = state.amountText,
        onValueChange = vm::onAmountChange,
        label = { Text("Amount in sats", fontFamily = Sans) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = ObsidianPlum.BgInput,
          unfocusedContainerColor = ObsidianPlum.BgInput,
          focusedTextColor = ObsidianPlum.TextPrimary,
          unfocusedTextColor = ObsidianPlum.TextPrimary,
        ),
      )
      TextField(
        value = state.memo,
        onValueChange = vm::onMemoChange,
        label = { Text("Note (optional)", fontFamily = Sans) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = ObsidianPlum.BgInput,
          unfocusedContainerColor = ObsidianPlum.BgInput,
          focusedTextColor = ObsidianPlum.TextPrimary,
          unfocusedTextColor = ObsidianPlum.TextPrimary,
        ),
      )
      YebenteButton(label = "Create Payment", onClick = vm::createPromise, enabled = !state.busy)
    } else {
      val bitmap: Bitmap? = try {
        QrCodes.toBitmap(state.frames[state.frameIndex])
      } catch (_: Exception) {
        null
      }
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Payment code",
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        )
      }
      if (state.frames.size > 1) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NeutralButtonInline(label = "Prev", onClick = vm::prevFrame)
          Text(
            text = "Part ${state.frameIndex + 1} of ${state.frames.size}",
            fontFamily = Mono,
            fontSize = 12.sp,
            color = ObsidianPlum.TextMuted,
          )
          NeutralButtonInline(label = "Next", onClick = vm::nextFrame)
        }
      }
      NeutralButton(label = "Send with NFC", onClick = vm::transmitNfc)
      NeutralButton(label = "Send with Bluetooth", onClick = {
        onRequestBle { vm.transmitBle() }
      })
    }

    Note(text = state.status)
    Note(text = state.error, error = true)
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun NeutralButtonInline(label: String, onClick: () -> Unit) {
  androidx.compose.material3.Button(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
      containerColor = ObsidianPlum.BgButtonNeutral,
      contentColor = ObsidianPlum.TextPrimary,
    ),
  ) {
    Text(text = label, fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
  }
}
