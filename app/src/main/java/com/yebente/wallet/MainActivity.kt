package com.yebente.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.ui.VmFactory
import com.yebente.wallet.ui.deposit.DepositScreen
import com.yebente.wallet.ui.deposit.DepositViewModel
import com.yebente.wallet.ui.history.HistoryScreen
import com.yebente.wallet.ui.history.HistoryViewModel
import com.yebente.wallet.ui.home.HomeScreen
import com.yebente.wallet.ui.home.HomeViewModel
import com.yebente.wallet.ui.receive.ReceiveScreen
import com.yebente.wallet.ui.receive.ReceiveViewModel
import com.yebente.wallet.ui.scan.ScannerScreen
import com.yebente.wallet.ui.send.SendScreen
import com.yebente.wallet.ui.send.SendViewModel
import com.yebente.wallet.ui.settings.SettingsScreen
import com.yebente.wallet.ui.settings.SettingsViewModel
import com.yebente.wallet.ui.theme.YeBenteTheme
import com.yebente.wallet.util.ScanPurposes

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val container = (application as YebenteWalletApp).container
    val factory = VmFactory(container)
    setContent {
      YeBenteTheme {
        YeBenteNav(factory)
      }
    }
  }
}

private fun blePermissions(): List<String> {
  return if (Build.VERSION.SDK_INT >= 31) {
    listOf(
      Manifest.permission.BLUETOOTH_SCAN,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.BLUETOOTH_ADVERTISE,
    )
  } else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
  }
}

@Composable
private fun rememberBleGate(): (onGranted: () -> Unit) -> Unit {
  val context = LocalContext.current
  var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { grants ->
    if (grants.values.all { it }) pending?.invoke()
    pending = null
  }
  return remember {
    { onGranted ->
      val missing = blePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
      }
      if (missing.isEmpty()) {
        onGranted()
      } else {
        pending = onGranted
        launcher.launch(missing.toTypedArray())
      }
    }
  }
}

@Composable
private fun YeBenteNav(factory: VmFactory) {
  val nav = rememberNavController()
  NavHost(navController = nav, startDestination = "home") {
    composable("home") {
      val vm: HomeViewModel = viewModel(factory = factory)
      HomeScreen(
        vm = vm,
        onSend = { nav.navigate("send") },
        onReceive = { nav.navigate("receive") },
        onDeposit = { nav.navigate("deposit") },
        onHistory = { nav.navigate("history") },
        onSettings = { nav.navigate("settings") },
      )
    }
    composable("send") { entry ->
      val vm: SendViewModel = viewModel(factory = factory)
      val key = ScanPurposes.resultKey(ScanPurposes.HANDSHAKE)
      val result by entry.savedStateHandle.getStateFlow<String?>(key, null).collectAsState()
      LaunchedEffect(result) {
        result?.let {
          vm.onHandshakeCode(it)
          entry.savedStateHandle.remove<String>(key)
        }
      }
      SendScreen(
        vm = vm,
        onBack = { nav.popBackStack() },
        onScanHandshake = { nav.navigate("scan/${ScanPurposes.HANDSHAKE}") },
        onRequestBle = rememberBleGate(),
      )
    }
    composable("receive") { entry ->
      val vm: ReceiveViewModel = viewModel(factory = factory)
      val key = ScanPurposes.resultKey(ScanPurposes.PROMISE)
      val result by entry.savedStateHandle.getStateFlow<String?>(key, null).collectAsState()
      LaunchedEffect(result) {
        result?.let {
          vm.ingestScanned(it)
          entry.savedStateHandle.remove<String>(key)
        }
      }
      ReceiveScreen(
        vm = vm,
        onBack = { nav.popBackStack() },
        onScanPayment = { nav.navigate("scan/${ScanPurposes.PROMISE}") },
        onRequestBle = rememberBleGate(),
      )
    }
    composable("history") { entry ->
      val vm: HistoryViewModel = viewModel(factory = factory)
      val key = ScanPurposes.resultKey(ScanPurposes.INVOICE)
      val result by entry.savedStateHandle.getStateFlow<String?>(key, null).collectAsState()
      LaunchedEffect(result) {
        result?.let {
          vm.onInvoiceScanned(it)
          entry.savedStateHandle.remove<String>(key)
        }
      }
      HistoryScreen(
        vm = vm,
        onBack = { nav.popBackStack() },
        onScanInvoice = { nav.navigate("scan/${ScanPurposes.INVOICE}") },
      )
    }
    composable("settings") {
      val vm: SettingsViewModel = viewModel(factory = factory)
      SettingsScreen(
        vm = vm,
        onBack = { nav.popBackStack() },
        onIdentityReset = { nav.popBackStack("home", inclusive = false) },
      )
    }
    composable("deposit") {
      val vm: DepositViewModel = viewModel(factory = factory)
      DepositScreen(vm = vm, onBack = { nav.popBackStack() })
    }
    composable("scan/{purpose}") { entry ->
      val purpose = entry.arguments?.getString("purpose").orEmpty()
      ScannerScreen(
        purpose = purpose,
        onBack = { nav.popBackStack() },
        onCode = { code ->
          nav.previousBackStackEntry?.savedStateHandle
            ?.set(ScanPurposes.resultKey(purpose), code)
          nav.popBackStack()
        },
      )
    }
  }
}
