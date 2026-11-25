package com.yebente.wallet.domain.protocol

import com.yebente.wallet.domain.model.OfflinePromise

object ForkDetector {
  data class Fork(
    val sender: String,
    val seq: Long,
    val firstId: String,
    val firstHash: String,
    val secondId: String,
    val secondHash: String,
  )

  fun detect(existing: OfflinePromise, incoming: OfflinePromise): Fork? {
    if (!existing.sender.equals(incoming.sender, ignoreCase = true)) return null
    if (existing.seq != incoming.seq) return null
    if (existing.id == incoming.id) return null
    val firstHash = PromiseCodec.hashHexOf(existing)
    val secondHash = PromiseCodec.hashHexOf(incoming)
    if (firstHash.equals(secondHash, ignoreCase = true)) return null
    return Fork(
      sender = existing.sender.lowercase(),
      seq = existing.seq,
      firstId = existing.id,
      firstHash = firstHash,
      secondId = incoming.id,
      secondHash = secondHash,
    )
  }
}
