package com.yebente.wallet.sync

import com.yebente.wallet.data.remote.DisburseRequest
import com.yebente.wallet.data.remote.YebenteApi
import com.yebente.wallet.data.settings.NodeCredentials

class YebenteGatewayRail(
  private val settings: NodeCredentials,
  private val apiFor: (String) -> YebenteApi,
) : SettlementRail {

  override suspend fun payLightning(invoice: String, sats: Long): PayResult {
    val clean = invoice.trim().removePrefix("lightning:")
    if (clean.isBlank()) return PayResult.permanent("Invoice is empty.")
    val cfg = settings.current()
    if (!cfg.hasNode()) {
      return PayResult.permanent("Lightning node is not configured. Open Settings first.")
    }
    return try {
      val api = apiFor(cfg.backendUrl)
      val res = api.disburse(
        DisburseRequest(
          invoice = clean,
          satsAmount = sats,
          customNodeType = cfg.nodeType,
          customNodeUrl = cfg.nodeUrl,
          customNodeKey = settings.nodeKey(),
        ),
      )
      if (res.success && res.paymentHash.isNotBlank()) {
        PayResult(
          ok = true,
          paymentHash = res.paymentHash,
          preimage = res.preimage,
          feeSats = res.routingFeeSat,
        )
      } else {
        PayResult.permanent(res.error.ifBlank { "Gateway refused the payment." })
      }
    } catch (t: Exception) {
      PayResult.networkFailure(t)
    }
  }
}
