package com.yebente.wallet.util

object ScanPurposes {
  const val HANDSHAKE = "handshake"
  const val PROMISE = "promise"
  const val INVOICE = "invoice"
  const val RECEIPT = "receipt"

  fun resultKey(purpose: String): String = "scan_$purpose"
}
