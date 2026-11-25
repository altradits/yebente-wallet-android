package com.yebente.wallet.sync

data class OutboundJob(
  val promiseId: String,
  val sats: Long,
  val storedInvoice: String,
  val recipientLightning: String,
)

data class DepositJob(
  val paymentHash: String,
  val sats: Long,
)

interface SettlementStore {
  suspend fun queuedOutbound(): List<OutboundJob>
  suspend fun jobById(promiseId: String): OutboundJob?
  suspend fun markSettling(promiseId: String)
  suspend fun markSettled(promiseId: String, paymentHash: String, preimage: String, feeSats: Long)
  suspend fun markFailed(promiseId: String, error: String)
  suspend fun requeue(promiseId: String)
  suspend fun openDeposits(): List<DepositJob>
  suspend fun markDepositSettled(paymentHash: String, creditedSats: Long)
  suspend fun adjustAnchor(deltaSats: Long)
}
