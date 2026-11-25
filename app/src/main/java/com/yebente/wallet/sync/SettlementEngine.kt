package com.yebente.wallet.sync

data class SettlementReport(
  val settled: Int = 0,
  val failed: Int = 0,
  val skippedNoInvoice: Int = 0,
  val depositsCredited: Int = 0,
  val retryLater: Boolean = false,
)

interface DepositPoller {
  suspend fun pollOpenDeposits(): Int
}

class SettlementEngine(
  private val store: SettlementStore,
  private val rail: SettlementRail,
  private val lnurl: LnurlPay,
) {
  suspend fun settleAll(deposits: DepositPoller? = null): SettlementReport {
    var settled = 0
    var failed = 0
    var skipped = 0
    val credited = deposits?.pollOpenDeposits() ?: 0

    for (job in store.queuedOutbound()) {
      val invoice = job.storedInvoice.ifBlank { tryLnurl(job) }
      if (invoice.isBlank()) {
        skipped++
        continue
      }
      store.markSettling(job.promiseId)
      val result = runCatching { rail.payLightning(invoice, job.sats) }
        .getOrElse { PayResult.networkFailure(it) }
      if (result.ok) {
        store.markSettled(job.promiseId, result.paymentHash, result.preimage, result.feeSats)
        store.adjustAnchor(-(job.sats + result.feeSats))
        settled++
      } else if (result.transient) {
        store.requeue(job.promiseId)
        return SettlementReport(settled, failed, skipped, credited, retryLater = true)
      } else {
        store.markFailed(job.promiseId, result.error.ifBlank { "Payment failed." })
        failed++
      }
    }
    return SettlementReport(settled, failed, skipped, credited, retryLater = false)
  }

  suspend fun settleOne(promiseId: String, invoice: String): PayResult {
    val job = store.jobById(promiseId)
      ?: return PayResult.permanent("Promise is no longer queued.")
    val clean = invoice.trim().removePrefix("lightning:")
    if (clean.isBlank()) return PayResult.permanent("Invoice is empty.")
    store.markSettling(promiseId)
    val result = runCatching { rail.payLightning(clean, job.sats) }
      .getOrElse { PayResult.networkFailure(it) }
    if (result.ok) {
      store.markSettled(promiseId, result.paymentHash, result.preimage, result.feeSats)
      store.adjustAnchor(-(job.sats + result.feeSats))
    } else if (result.transient) {
      store.requeue(promiseId)
    } else {
      store.markFailed(promiseId, result.error.ifBlank { "Payment failed." })
    }
    return result
  }

  private suspend fun tryLnurl(job: OutboundJob): String {
    if (job.recipientLightning.isBlank()) return ""
    return try {
      lnurl.invoiceFor(job.recipientLightning, job.sats)
    } catch (_: LnurlException) {
      ""
    }
  }
}
