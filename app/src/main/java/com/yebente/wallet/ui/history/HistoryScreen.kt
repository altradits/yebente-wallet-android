package com.yebente.wallet.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.data.local.PromiseEntity
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.StanHeader
import com.yebente.wallet.ui.components.YebenteButton
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import com.yebente.wallet.util.SatFormat
import com.yebente.wallet.util.TimeAgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
  val promises: StateFlow<List<PromiseEntity>> = container.repository.recentPromises(200)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val blockedSenders: StateFlow<List<String>> = container.repository.blockedSenders()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _settleStatus = MutableStateFlow("")
  val settleStatus: StateFlow<String> = _settleStatus
  private val _settleError = MutableStateFlow("")
  val settleError: StateFlow<String> = _settleError
  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy

  var selectedId by mutableStateOf<String?>(null)
    private set
  var invoiceText by mutableStateOf("")
    private set

  fun select(promiseId: String?) {
    selectedId = promiseId
    invoiceText = ""
    _settleStatus.value = ""
    _settleError.value = ""
  }

  fun onInvoiceChange(value: String) {
    invoiceText = value.trim()
  }

  fun onInvoiceScanned(code: String) {
    invoiceText = code.trim()
    _settleStatus.value = "Invoice scanned. Tap Pay Invoice."
  }

  fun paySelected() {
    val id = selectedId ?: return
    val invoice = invoiceText.trim().removePrefix("lightning:")
    if (invoice.isBlank()) {
      _settleError.value = "Paste or scan the recipient invoice first."
      return
    }
    _busy.value = true
    _settleError.value = ""
    _settleStatus.value = ""
    viewModelScope.launch {
      withContext(Dispatchers.IO) { container.repository.storeRecipientInvoice(id, invoice) }
      val result = withContext(Dispatchers.IO) {
        container.settlementEngine.settleOne(id, invoice)
      }
      _busy.value = false
      if (result.ok) {
        _settleStatus.value = "Settled on Lightning."
        selectedId = null
        invoiceText = ""
      } else {
        _settleError.value = result.error.ifBlank { "Payment failed." }
      }
    }
  }
}

@Composable
fun HistoryScreen(
  vm: HistoryViewModel,
  onBack: () -> Unit,
  onScanInvoice: () -> Unit,
) {
  val promises by vm.promises.collectAsState()
  val status by vm.settleStatus.collectAsState()
  val error by vm.settleError.collectAsState()
  val busy by vm.busy.collectAsState()
  val blocked by vm.blockedSenders.collectAsState()

  Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
    StanHeader(title = "History", onBack = onBack)

    if (blocked.isNotEmpty()) {
      Note(text = "${blocked.size} sender(s) blocked for double-spending.", error = true)
      Spacer(modifier = Modifier.height(8.dp))
    }

    if (vm.selectedId != null) {
      Text(
        text = "Settle this payment over Lightning now. The recipient invoice can be scanned.",
        fontFamily = Sans,
        fontSize = 13.sp,
        color = ObsidianPlum.TextMuted,
      )
      Spacer(modifier = Modifier.height(8.dp))
      TextField(
        value = vm.invoiceText,
        onValueChange = vm::onInvoiceChange,
        label = { Text("Recipient invoice", fontFamily = Sans) },
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = ObsidianPlum.BgInput,
          unfocusedContainerColor = ObsidianPlum.BgInput,
          focusedTextColor = ObsidianPlum.TextPrimary,
          unfocusedTextColor = ObsidianPlum.TextPrimary,
        ),
      )
      Spacer(modifier = Modifier.height(8.dp))
      YebenteButton(label = "Pay Invoice", onClick = vm::paySelected, enabled = !busy)
      NeutralButton(label = "Scan Invoice", onClick = onScanInvoice)
      NeutralButton(label = "Close", onClick = { vm.select(null) })
      Note(text = status)
      Note(text = error, error = true)
      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider(color = ObsidianPlum.BorderSubtle)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
      items(promises, key = { it.id }) { row ->
        HistoryRow(
          row = row,
          onClick = {
            if (row.direction == "OUTBOUND" && row.state == "QUEUED") vm.select(row.id)
          },
        )
        HorizontalDivider(color = ObsidianPlum.BorderSubtle.copy(alpha = 0.4f))
      }
    }
    if (promises.isEmpty()) {
      Text(
        text = "No payments yet.",
        fontFamily = Sans,
        fontSize = 13.sp,
        color = ObsidianPlum.TextMuted,
        modifier = Modifier.padding(top = 16.dp),
      )
    }
  }
}

@Composable
private fun HistoryRow(row: PromiseEntity, onClick: () -> Unit) {
  val incoming = row.direction == "INBOUND"
  val title = when {
    incoming && row.state == "ACCEPTED" -> "Received"
    incoming && row.state == "RECEIPT_CONFIRMED" -> "Received Confirmed"
    incoming -> "Received Expired"
    row.state == "QUEUED" -> "Send Queued"
    row.state == "SETTLING" -> "Send Settling"
    row.state == "SETTLED" -> "Send Settled"
    row.state == "FAILED" -> "Send Failed"
    else -> "Send Expired"
  }
  val amount = if (incoming) row.sats else -row.sats
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column {
      Text(
        text = title,
        fontFamily = Sans,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = ObsidianPlum.TextPrimary,
      )
      Text(
        text = TimeAgo.of(row.createdAt),
        fontFamily = Mono,
        fontSize = 11.sp,
        color = ObsidianPlum.TextMuted,
      )
    }
    Text(
      text = SatFormat.signedSats(amount) + " Sats",
      fontFamily = Mono,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      color = if (incoming) ObsidianPlum.StatusSuccess else ObsidianPlum.TextPrimary,
    )
  }
}
