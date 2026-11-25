package com.yebente.wallet.domain.protocol

object TrustCaps {
  fun effectivePerSenderCap(policyCap: Long, trustedCap: Long?): Long {
    if (trustedCap == null || trustedCap <= 0) return policyCap
    return trustedCap
  }
}
