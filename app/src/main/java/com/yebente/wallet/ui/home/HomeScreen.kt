package com.yebente.wallet.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.domain.model.WalletBalances
import com.yebente.wallet.sync.RebalanceWorker
import com.yebente.wallet.ui.components.AmountText
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.ReadoutRow
import com.yebente.wallet.ui.components.YebenteButton
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(container: AppContainer) : ViewModel() {
  private val repository = container.repository

  val balances = repository.balances()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WalletBalances(0, 0, 0))
  val isOnline = container.connectivity.isOnline
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
  val pendingCount = repository.outboundPendingCount()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
  val nodeReady = container.settings.config.map { it.hasNode() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  fun syncNow(context: Context) {
    RebalanceWorker.rebalanceNow(context)
  }
}

@Composable
fun HomeScreen(
  vm: HomeViewModel,
  onSend: () -> Unit,
  onReceive: () -> Unit,
  onDeposit: () -> Unit,
  onHistory: () -> Unit,
  onSettings: () -> Unit,
) {
  val balances by vm.balances.collectAsState()
  val isOnline by vm.isOnline.collectAsState()
  val pendingCount by vm.pendingCount.collectAsState()
  val nodeReady by vm.nodeReady.collectAsState()
  val context = LocalContext.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "YeBente",
      fontFamily = Sans,
      fontWeight = FontWeight.Bold,
      fontSize = 20.sp,
      color = ObsidianPlum.TextPrimary,
    )
    Text(
      text = if (isOnline) "Online" else "Offline",
      fontFamily = Mono,
      fontSize = 12.sp,
      color = if (isOnline) ObsidianPlum.StatusSuccess else ObsidianPlum.TextSecondary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    AmountText(sats = balances.spendableSats, large = true)
    Spacer(modifier = Modifier.height(8.dp))
    ReadoutRow(label = "Settled anchor", value = "${balances.anchorSats} Sats")
    ReadoutRow(label = "Inbound pending", value = "${balances.inboundAcceptedSats} Sats")
    ReadoutRow(label = "Outbound queued", value = "${balances.outboundPendingSats} Sats")
    if (pendingCount > 0) {
      ReadoutRow(label = "Payments to settle", value = pendingCount.toString())
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (!nodeReady) {
      Note(text = "Lightning node not set. Offline works, settle needs Settings.")
    }
    YebenteButton(label = "Send Sats", onClick = onSend)
    YebenteButton(label = "Receive Sats", onClick = onReceive)
    NeutralButton(label = "Deposit", onClick = onDeposit)
    NeutralButton(label = "History", onClick = onHistory)
    NeutralButton(label = "Sync Now", onClick = { vm.syncNow(context) })
    NeutralButton(label = "Settings", onClick = onSettings)
  }
}
