package com.yebente.wallet.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
  val status: String = "",
  val success: Boolean = true,
)

@Serializable
data class CreateInvoiceRequest(
  val satsAmount: Long,
  val memo: String = "",
  val customNodeType: String? = null,
  val customNodeUrl: String? = null,
  val customNodeKey: String? = null,
)

@Serializable
data class CreateInvoiceResponse(
  val success: Boolean = false,
  val configured: Boolean = false,
  val invoice: String = "",
  val paymentHash: String = "",
  val satsAmount: Long = 0L,
  val nodeType: String = "",
  val error: String = "",
)

@Serializable
data class InvoiceStatusResponse(
  val success: Boolean = false,
  val settled: Boolean = false,
  val paid: Boolean = false,
  val satsAmount: Long = 0L,
  val preimage: String = "",
  val error: String = "",
)

@Serializable
data class DisburseRequest(
  val invoice: String,
  val satsAmount: Long? = null,
  val destination: String = "",
  val customNodeType: String? = null,
  val customNodeUrl: String? = null,
  val customNodeKey: String? = null,
)

@Serializable
data class DisburseResponse(
  val success: Boolean = false,
  val settled: Boolean = false,
  val paymentHash: String = "",
  val preimage: String = "",
  val routingFeeSat: Long = 0L,
  val satsAmount: Long = 0L,
  val destination: String = "",
  val nodeType: String = "",
  val error: String = "",
)
