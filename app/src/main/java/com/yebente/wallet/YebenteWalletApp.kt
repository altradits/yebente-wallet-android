package com.yebente.wallet

import android.app.Application
import com.yebente.wallet.di.AppContainer

class YebenteWalletApp : Application() {
  val container by lazy { AppContainer(this) }

  override fun onCreate() {
    super.onCreate()
    container.startRebalanceOnReconnect()
  }
}
