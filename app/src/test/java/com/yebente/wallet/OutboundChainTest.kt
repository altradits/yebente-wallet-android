package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.OutboundChain
import com.yebente.wallet.domain.protocol.PromiseCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundChainTest {
  private val sender = WalletIdentity.fromPrivateHex("12".repeat(32))
  private val alice = WalletIdentity.fromPrivateHex("13".repeat(32))
  private val bob = WalletIdentity.fromPrivateHex("14".repeat(32))
  private val now = 1_700_000_000_000L

  @Test
  fun `first promise to a new recipient starts at seq 1`() {
    assertEquals(Pair(1L, PromiseCodec.GENESIS_HASH), OutboundChain.next(null, null))
  }

  @Test
  fun `interleaved recipients each get gapless chains`() {
    val heads = mutableMapOf<String, Pair<Long, String>>()
    val seen = mutableMapOf<String, MutableList<OfflinePromise>>()

    fun sendTo(recipient: String, id: String): OfflinePromise {
      val head = heads[recipient]
      val (seq, prevHash) = OutboundChain.next(head?.first, head?.second)
      val promise = sender.signPromise(
        OfflinePromise(
          id = id,
          sender = sender.publicHex,
          recipient = recipient,
          sats = 1000,
          seq = seq,
          createdAt = now,
          expiresAt = now + 3_600_000,
          prevHash = prevHash,
          settleAddress = "bc1qtest",
        ),
      )
      heads[recipient] = Pair(seq, PromiseCodec.hashHexOf(promise))
      seen.getOrPut(recipient) { mutableListOf() }.add(promise)
      return promise
    }

    sendTo(alice.publicHex, "a1")
    sendTo(bob.publicHex, "b1")
    sendTo(alice.publicHex, "a2")
    sendTo(bob.publicHex, "b2")
    sendTo(alice.publicHex, "a3")

    val aliceSeqs = seen.getValue(alice.publicHex).map { it.seq }
    val bobSeqs = seen.getValue(bob.publicHex).map { it.seq }
    assertEquals(listOf(1L, 2L, 3L), aliceSeqs)
    assertEquals(listOf(1L, 2L), bobSeqs)

    for ((_, chain) in seen) {
      assertEquals(PromiseCodec.GENESIS_HASH, chain.first().prevHash)
      for (i in 1 until chain.size) {
        assertEquals(PromiseCodec.hashHexOf(chain[i - 1]), chain[i].prevHash)
      }
    }
  }
}
