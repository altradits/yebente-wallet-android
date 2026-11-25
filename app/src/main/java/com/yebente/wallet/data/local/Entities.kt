package com.yebente.wallet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "promises")
data class PromiseEntity(
  @PrimaryKey val id: String,
  val direction: String,
  val json: String,
  val state: String,
  val sats: Long,
  val counterparty: String,
  val seq: Long,
  val createdAt: Long,
  val updatedAt: Long,
  val settleRef: String = "",
  val storedInvoice: String = "",
  val recipientLightning: String = "",
  val error: String = "",
)

@Entity(tableName = "sender_heads")
data class SenderHeadEntity(
  @PrimaryKey val sender: String,
  val lastSeq: Long,
  val lastHash: String,
  val outstandingSats: Long,
  val blocked: Boolean = false,
)

@Entity(tableName = "fork_proofs")
data class ForkProofEntity(
  @PrimaryKey val id: String,
  val sender: String,
  val seq: Long,
  val firstId: String,
  val firstHash: String,
  val secondId: String,
  val secondHash: String,
  val detectedAt: Long,
)

@Entity(tableName = "trusted_senders")
data class TrustedSenderEntity(
  @PrimaryKey val sender: String,
  val label: String,
  val addedAt: Long,
)

@Entity(tableName = "deposits")
data class DepositEntity(
  @PrimaryKey val paymentHash: String,
  val invoice: String,
  val sats: Long,
  val state: String,
  val createdAt: Long,
  val updatedAt: Long,
)
