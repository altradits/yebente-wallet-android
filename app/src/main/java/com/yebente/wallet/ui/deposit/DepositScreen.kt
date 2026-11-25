package com.yebente.wallet.ui.deposit

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.data.repo.DepositView
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.offline.qr.QrCodes
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.StanHeader
import com.yebente.wallet.ui.components.YebenteButton
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DepositViewModel(private val container: AppContainer) : ViewModel() {
  val isOnline = container.connectivity.isOnline
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
  val deposits = container.repository.deposits()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  var amountText by mutableStateOf("")
    private set
  var memo by mutableStateOf("")
    private set
  var current by mutableStateOf<DepositView?>(null)
    private set
  var busy by mutableStateOf(false)
    private set
  var status by mutableStateOf("")
    private set
  var error by mutableStateOf("")
    private set

  fun onAmountChange(value: String) {
    amountText = value.filter { it.isDigit() }.take(12)
  }

  fun onMemoChange(value: String) {
    memo = value.take(120)
  }

  fun create() {
    val sats = amountText.toLongOrNull() ?: 0L
    if (sats <= 0) {
      error = "Enter an amount in sats."
      return
    }
    busy = true
    error = ""
    status = ""
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { container.repository.createDeposit(sats, memo) }
      busy = false
      result.fold(
        onSuccess = {
          current = it
          status = "Pay this invoice from any Lightning wallet. Watching for payment."
        },
        onFailure = { t -> error = t.message ?: "Could not create invoice." },
      )
    }
  }

  fun pollOnce() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { container.repository.pollOpenDeposits() }
    }
  }

  fun reset() {
    current = null
    amountText = ""
    memo = ""
    status = ""
    error = ""
  }
}

@Composable
fun DepositScreen(vm: DepositViewModel, onBack: () -> Unit) {
  val isOnline by vm.isOnline.collectAsState()
  val deposits by vm.deposits.collectAsState()
  val current = vm.current
  val settled = current != null && deposits.any { it.paymentHash == current.paymentHash && it.state == "SETTLED" }

  LaunchedEffect(current?.paymentHash) {
    if (current == null) return@LaunchedEffect
    repeat(60) {
      delay(5000)
      vm.pollOnce()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StanHeader(title = "Deposit", onBack = onBack)

    if (!isOnline) {
      Note(text = "Deposits need internet. Offline spending still works.", error = true)
    }
    if (current == null) {
      Text(
        text = "Create a Lightning invoice on your node. Paid invoices anchor your spendable balance.",
        fontFamily = Sans,
        fontSize = 13.sp,
        color = ObsidianPlum.TextMuted,
      )
      TextField(
        value = vm.amountText,
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
        value = vm.memo,
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
      YebenteButton(label = "Create Invoice", onClick = vm::create, enabled = !vm.busy && isOnline)
    } else {
      val bitmap: Bitmap? = try {
        QrCodes.toBitmap("lightning:" + current.invoice)
      } catch (_: Exception) {
        null
      }
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Deposit invoice",
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        )
      }
      SelectionContainer {
        Text(
          text = current.invoice,
          fontFamily = Mono,
          fontSize = 10.sp,
          color = ObsidianPlum.TextSecondary,
        )
      }
      if (settled) {
        Note(text = "Paid. Balance anchored.")
      } else {
        Note(text = "Waiting for payment. This screen checks every 5 seconds.")
      }
      NeutralButton(label = "New Deposit", onClick = vm::reset)
    }
    Note(text = vm.status)
    Note(text = vm.error, error = true)
    Spacer(modifier = Modifier.height(16.dp))
  }
}
