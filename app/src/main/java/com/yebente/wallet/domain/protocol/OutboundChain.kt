package com.yebente.wallet.domain.protocol

object OutboundChain {
  fun next(lastSeq: Long?, lastHash: String?): Pair<Long, String> {
    if (lastSeq == null || lastSeq <= 0 || lastHash.isNullOrBlank()) {
      return Pair(1L, PromiseCodec.GENESIS_HASH)
    }
    return Pair(lastSeq + 1, lastHash)
  }
}
