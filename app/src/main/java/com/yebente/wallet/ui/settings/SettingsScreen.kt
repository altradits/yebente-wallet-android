package com.yebente.wallet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yebente.wallet.data.settings.NodeConfig
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.OptionTabs
import com.yebente.wallet.ui.components.StanHeader
import com.yebente.wallet.ui.components.YebenteButton
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
  val config = container.settings.config
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NodeConfig())

  val identity: String by lazy { container.repository.identityHex() }

  val trustedSenders = container.repository.trustedSenders()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _status = mutableStateOf("")
  val status: String by _status
  private val _error = mutableStateOf("")
  val error: String by _error

  fun save(backendUrl: String, nodeType: String, nodeUrl: String, nodeKey: String, lightning: String) {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          container.settings.saveConnection(backendUrl, nodeType, nodeUrl, nodeKey)
          container.settings.saveLightningAddress(lightning)
        }
        _status.value = "Settings saved."
        _error.value = ""
      } catch (t: Exception) {
        _error.value = t.message ?: "Could not save settings."
      }
    }
  }

  fun trust(sender: String, label: String) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { container.repository.trustSender(sender, label) }
      result.fold(
        onSuccess = {
          _status.value = "Sender trusted. Their inbound now counts as spendable."
          _error.value = ""
        },
        onFailure = { t -> _error.value = t.message ?: "Could not trust sender." },
      )
    }
  }

  fun untrust(sender: String) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { container.repository.untrustSender(sender) }
      _status.value = "Sender removed from trusted list."
      _error.value = ""
    }
  }

  fun wipe(onDone: () -> Unit) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { container.repository.wipe() }
      onDone()
    }
  }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onWiped: () -> Unit) {
  val config by vm.config.collectAsState()
  var backendUrl by mutableStateOf(config.backendUrl)
  var nodeUrl by mutableStateOf(config.nodeUrl)
  var nodeKey by mutableStateOf("")
  var lightning by mutableStateOf(config.lightningAddress)
  var selectedType by mutableIntStateOf(
    when (config.nodeType) {
      NodeConfig.NODE_PHOENIXD -> 1
      NodeConfig.NODE_LNBITS -> 2
      NodeConfig.NODE_LND -> 3
      else -> 0
    },
  )
  var wipeArmed by mutableStateOf(false)

  androidx.compose.runtime.LaunchedEffect(config.backendUrl, config.nodeUrl, config.lightningAddress) {
    backendUrl = config.backendUrl
    nodeUrl = config.nodeUrl
    lightning = config.lightningAddress
  }

  val nodeTypes = listOf("None", "Phoenixd", "LNbits", "LND")
  val nodeValues = listOf(NodeConfig.NODE_NONE, NodeConfig.NODE_PHOENIXD, NodeConfig.NODE_LNBITS, NodeConfig.NODE_LND)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StanHeader(title = "Settings", onBack = onBack)

    Text(text = "Gateway", fontFamily = Sans, fontSize = 13.sp, color = ObsidianPlum.TextMuted)
    SettingsField(value = backendUrl, label = "Backend URL", onChange = { backendUrl = it })

    Text(text = "Lightning node", fontFamily = Sans, fontSize = 13.sp, color = ObsidianPlum.TextMuted)
    OptionTabs(options = nodeTypes, selected = selectedType, onSelect = { selectedType = it })
    if (selectedType != 0) {
      SettingsField(value = nodeUrl, label = "Node URL", onChange = { nodeUrl = it })
      TextField(
        value = nodeKey,
        onValueChange = { nodeKey = it },
        label = { Text("Node key or password", fontFamily = Sans) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = ObsidianPlum.BgInput,
          unfocusedContainerColor = ObsidianPlum.BgInput,
          focusedTextColor = ObsidianPlum.TextPrimary,
          unfocusedTextColor = ObsidianPlum.TextPrimary,
        ),
      )
    }

    Text(text = "Receiving", fontFamily = Sans, fontSize = 13.sp, color = ObsidianPlum.TextMuted)
    SettingsField(value = lightning, label = "Lightning address (optional)", onChange = { lightning = it })

    YebenteButton(
      label = "Save Settings",
      onClick = { vm.save(backendUrl, nodeValues[selectedType], nodeUrl, nodeKey, lightning) },
    )
    Note(text = vm.status)
    Note(text = vm.error, error = true)

    Text(text = "Trusted senders", fontFamily = Sans, fontSize = 13.sp, color = ObsidianPlum.TextMuted)
    Text(
      text = "Inbound from trusted senders counts as spendable before settlement. Trust only people you know.",
      fontFamily = Mono,
      fontSize = 11.sp,
      color = ObsidianPlum.TextMuted,
    )
    val trusted by vm.trustedSenders.collectAsState()
    var trustKey by mutableStateOf("")
    var trustLabel by mutableStateOf("")
    SettingsField(value = trustKey, label = "Sender wallet key", onChange = { trustKey = it })
    SettingsField(value = trustLabel, label = "Label (optional)", onChange = { trustLabel = it })
    YebenteButton(
      label = "Trust Sender",
      onClick = {
        vm.trust(trustKey, trustLabel)
        trustKey = ""
        trustLabel = ""
      },
    )
    trusted.forEach { row ->
      Text(
        text = (row.label.ifBlank { "Sender" }) + " " + row.sender.take(24),
        fontFamily = Mono,
        fontSize = 11.sp,
        color = ObsidianPlum.TextSecondary,
      )
      NeutralButton(label = "Remove", onClick = { vm.untrust(row.sender) })
    }

    Text(text = "Wallet identity", fontFamily = Sans, fontSize = 13.sp, color = ObsidianPlum.TextMuted)
    Text(
      text = vm.identity,
      fontFamily = Mono,
      fontSize = 10.sp,
      color = ObsidianPlum.TextSecondary,
    )
    Text(
      text = "Offline caps: 50,000 sats per payment, 200,000 per sender, 500,000 inbound total.",
      fontFamily = Mono,
      fontSize = 11.sp,
      color = ObsidianPlum.TextMuted,
    )

    Spacer(modifier = Modifier.height(8.dp))
    YebenteButton(
      label = if (wipeArmed) "Tap Again to Wipe" else "Wipe Wallet",
      onClick = {
        if (wipeArmed) vm.wipe(onWiped) else wipeArmed = true
      },
      danger = true,
    )
    if (wipeArmed) {
      NeutralButton(label = "Cancel", onClick = { wipeArmed = false })
    }
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun SettingsField(value: String, label: String, onChange: (String) -> Unit) {
  TextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label, fontFamily = Sans) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
    colors = TextFieldDefaults.colors(
      focusedContainerColor = ObsidianPlum.BgInput,
      unfocusedContainerColor = ObsidianPlum.BgInput,
      focusedTextColor = ObsidianPlum.TextPrimary,
      unfocusedTextColor = ObsidianPlum.TextPrimary,
    ),
  )
}
