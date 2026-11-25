package com.yebente.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yebente.wallet.di.AppContainer
import com.yebente.wallet.ui.deposit.DepositViewModel
import com.yebente.wallet.ui.history.HistoryViewModel
import com.yebente.wallet.ui.home.HomeViewModel
import com.yebente.wallet.ui.receive.ReceiveViewModel
import com.yebente.wallet.ui.send.SendViewModel
import com.yebente.wallet.ui.settings.SettingsViewModel

@Suppress("UNCHECKED_CAST")
class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return when (modelClass) {
      HomeViewModel::class.java -> HomeViewModel(container)
      SendViewModel::class.java -> SendViewModel(container)
      ReceiveViewModel::class.java -> ReceiveViewModel(container)
      HistoryViewModel::class.java -> HistoryViewModel(container)
      SettingsViewModel::class.java -> SettingsViewModel(container)
      DepositViewModel::class.java -> DepositViewModel(container)
      else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    } as T
  }
}
