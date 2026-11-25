package com.yebente.wallet.domain.model

import kotlinx.serialization.Serializable

@JvmInline
value class Sats(val value: Long) {
  init {
    require(value >= 0) { "Sats must be non-negative" }
  }
}

enum class TransportKind {
  QR, NFC, BLE;

  companion object {
    fun parse(raw: String): TransportKind? =
      entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
  }
}

enum class PromiseDirection { OUTBOUND, INBOUND }

enum class PromiseState {
  QUEUED,
  SETTLING,
  SETTLED,
  FAILED,
  EXPIRED,
  ACCEPTED,
  RECEIPT_CONFIRMED,
}

@Serializable
data class OfflinePromise(
  val v: Int = 1,
  val id: String,
  val sender: String,
  val recipient: String,
  val sats: Long,
  val seq: Long,
  val createdAt: Long,
  val expiresAt: Long,
  val prevHash: String,
  val settleAddress: String = "",
  val memo: String = "",
  val sig: String = "",
)

@Serializable
data class ReceiveHandshake(
  val v: Int = 1,
  val recipient: String,
  val address: String,
  val lightning: String = "",
  val transports: List<String> = listOf("QR", "NFC", "BLE"),
  val maxSats: Long = 0L,
)

@Serializable
data class SettlementReceipt(
  val v: Int = 1,
  val promiseId: String,
  val paymentHash: String,
  val preimage: String = "",
  val sats: Long,
  val settledAt: Long,
)

data class WalletBalances(
  val anchorSats: Long,
  val inboundAcceptedSats: Long,
  val outboundPendingSats: Long,
  val trustedInboundSats: Long = 0L,
) {
  val spendableSats: Long
    get() = maxOf(0L, anchorSats + trustedInboundSats - outboundPendingSats)

  val pendingInboundSats: Long
    get() = maxOf(0L, inboundAcceptedSats - trustedInboundSats)

  fun canSpend(amountSats: Long): Boolean = amountSats > 0 && amountSats <= spendableSats
}

data class OfflinePolicy(
  val maxPromiseSats: Long = 50_000L,
  val maxPerSenderSats: Long = 200_000L,
  val maxInboundPendingSats: Long = 500_000L,
  val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
  val clockSkewMs: Long = 5L * 60 * 1000,
)

data class SenderHead(
  val sender: String,
  val lastSeq: Long,
  val lastHash: String,
  val outstandingSats: Long,
  val blocked: Boolean = false,
)
