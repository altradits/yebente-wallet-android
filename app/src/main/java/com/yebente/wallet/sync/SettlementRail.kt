package com.yebente.wallet.sync

import java.io.IOException
import retrofit2.HttpException

data class PayResult(
  val ok: Boolean,
  val paymentHash: String = "",
  val preimage: String = "",
  val feeSats: Long = 0L,
  val error: String = "",
  val transient: Boolean = false,
) {
  companion object {
    fun permanent(error: String): PayResult = PayResult(ok = false, error = error)

    fun networkFailure(t: Throwable): PayResult {
      val transient = t is IOException || (t is HttpException && t.code() >= 500)
      val message = t.message ?: "Network failure"
      return PayResult(ok = false, error = message, transient = transient)
    }
  }
}

interface SettlementRail {
  suspend fun payLightning(invoice: String, sats: Long): PayResult
}
