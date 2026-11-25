package com.yebente.wallet.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.data.local.PromiseEntity
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.offline.qr.QrCodes
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

data class ForkGossipView(val sender: String, val seq: Long, val frames: List<String>)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
  val promises: StateFlow<List<PromiseEntity>> = container.repository.recentPromises(200)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val blockedSenders: StateFlow<List<String>> = container.repository.blockedSenders()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _gossips = MutableStateFlow<List<ForkGossipView>>(emptyList())
  val forkGossips: StateFlow<List<ForkGossipView>> = _gossips

  init {
    refreshGossip()
  }

  fun refreshGossip() {
    viewModelScope.launch {
      val list = withContext(Dispatchers.IO) { container.repository.exportableForkGossip() }
      _gossips.value = list.map { gossip ->
        ForkGossipView(
          sender = gossip.first.sender.lowercase(),
          seq = gossip.first.seq,
          frames = PromiseCodec.encodeForkGossip(gossip),
        )
      }
    }
  }

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

    val gossips by vm.forkGossips.collectAsState()
    val gossipPages = remember { mutableStateMapOf<String, Int>() }
    gossips.forEach { gossip ->
      val key = "${gossip.sender}:${gossip.seq}"
      val page = (gossipPages[key] ?: 0).coerceIn(0, (gossip.frames.size - 1).coerceAtLeast(0))
      Text(
        text = "Proof ${gossip.sender.take(12)} seq ${gossip.seq}",
        fontFamily = Mono,
        fontSize = 11.sp,
        color = ObsidianPlum.TextSecondary,
      )
      val bitmap: Bitmap? = try {
        QrCodes.toBitmap(gossip.frames[page])
      } catch (_: Exception) {
        null
      }
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Double-spend proof",
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        )
      }
      if (gossip.frames.size > 1) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NeutralButtonInline(label = "Prev", onClick = {
            gossipPages[key] = (page - 1).coerceAtLeast(0)
          })
          Text(
            text = "Part ${page + 1} of ${gossip.frames.size}",
            fontFamily = Mono,
            fontSize = 12.sp,
            color = ObsidianPlum.TextMuted,
          )
          NeutralButtonInline(label = "Next", onClick = {
            gossipPages[key] = (page + 1).coerceAtMost(gossip.frames.size - 1)
          })
        }
      }
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

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
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

@Composable
private fun HistoryRow(row: PromiseEntity, onClick: () -> Unit) {
  val incoming = row.direction == "INBOUND"
  val title = when {
    incoming && row.state == "ACCEPTED" -> "Received"
    incoming && row.state == "RECEIPT_CONFIRMED" -> "Received Confirmed"
    incoming && row.state == "FORKED" -> "Double-spend Proof"
    incoming -> "Received Expired"
    row.state == "QUEUED" -> "Send Queued"
    row.state == "SETTLING" -> "Send Settling"
    row.state == "SETTLED" -> "Send Settled"
    row.state == "FAILED" -> "Send Failed"
    else -> "Send Expired"
  }
  val amount = if (row.state == "FORKED") 0L else if (incoming) row.sats else -row.sats
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
      color = if (incoming && row.state != "FORKED") ObsidianPlum.StatusSuccess else ObsidianPlum.TextPrimary,
    )
  }
}
