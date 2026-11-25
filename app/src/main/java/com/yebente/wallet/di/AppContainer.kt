package com.yebente.wallet.di

import android.content.Context
import com.yebente.wallet.crypto.AndroidSeedStore
import com.yebente.wallet.data.local.WalletDatabase
import com.yebente.wallet.data.remote.YebenteApiFactory
import com.yebente.wallet.data.repo.WalletRepository
import com.yebente.wallet.data.settings.NodeSettings
import com.yebente.wallet.sync.ConnectivityMonitor
import com.yebente.wallet.sync.OkHttpLnurlPay
import com.yebente.wallet.sync.RebalanceWorker
import com.yebente.wallet.sync.SettlementEngine
import com.yebente.wallet.sync.YebenteGatewayRail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
  val appContext: Context = context.applicationContext

  val settings = NodeSettings(appContext)
  val seedStore = AndroidSeedStore(appContext)
  val database = WalletDatabase.build(appContext)
  val okHttp = YebenteApiFactory.okHttp()

  val repository = WalletRepository(
    db = database,
    settings = settings,
    seedStore = seedStore,
    apiFor = { baseUrl -> YebenteApiFactory.create(baseUrl, okHttp) },
  )

  val settlementEngine = SettlementEngine(
    store = repository,
    rail = YebenteGatewayRail(settings) { baseUrl -> YebenteApiFactory.create(baseUrl, okHttp) },
    lnurl = OkHttpLnurlPay(okHttp),
  )

  val connectivity = ConnectivityMonitor(appContext)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun startRebalanceOnReconnect() {
    RebalanceWorker.schedulePeriodic(appContext)
    scope.launch {
      connectivity.isOnline.drop(1).collect { online ->
        if (online) RebalanceWorker.rebalanceNow(appContext)
      }
    }
  }
}
