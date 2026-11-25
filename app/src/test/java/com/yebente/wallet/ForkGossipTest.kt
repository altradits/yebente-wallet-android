package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.ForkGossip
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.ForkDetector
import com.yebente.wallet.domain.protocol.PromiseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkGossipTest {
  private val sender = WalletIdentity.fromPrivateHex("0f".repeat(32))
  private val alice = WalletIdentity.fromPrivateHex("10".repeat(32))
  private val bob = WalletIdentity.fromPrivateHex("11".repeat(32))
  private val now = 1_700_000_000_000L

  private fun promise(id: String, recipient: String, seq: Long): OfflinePromise {
    return sender.signPromise(
      OfflinePromise(
        id = id,
        sender = sender.publicHex,
        recipient = recipient,
        sats = 1000,
        seq = seq,
        createdAt = now - 1000,
        expiresAt = now + 3_600_000,
        prevHash = PromiseCodec.GENESIS_HASH,
        settleAddress = "bc1qtest",
      ),
    )
  }

  @Test
  fun `gossip roundtrips through frames in any order`() {
    val gossip = ForkGossip(first = promise("g-a", alice.publicHex, 1), second = promise("g-b", bob.publicHex, 1))
    val frames = PromiseCodec.encodeForkGossip(gossip, maxChunkChars = 40)
    assertTrue(frames.size > 1)
    assertTrue(frames.all { it.startsWith("YBF1:") })
    val back = PromiseCodec.decodeForkGossip(frames.shuffled())
    assertEquals(gossip, back)
  }

  @Test
  fun `gossipped branches verify and detect the fork`() {
    val gossip = ForkGossip(first = promise("g-a", alice.publicHex, 1), second = promise("g-b", bob.publicHex, 1))
    val back = PromiseCodec.decodeForkGossip(PromiseCodec.encodeForkGossip(gossip))
    assertTrue(WalletIdentity.verifyPromise(back.first))
    assertTrue(WalletIdentity.verifyPromise(back.second))
    assertNotNull(ForkDetector.detect(back.first, back.second))
  }

  @Test
  fun `tampered gossip branch fails verification`() {
    val gossip = ForkGossip(first = promise("g-a", alice.publicHex, 1), second = promise("g-b", bob.publicHex, 1))
    val tampered = gossip.copy(second = gossip.second.copy(sats = 9999))
    assertFalse(WalletIdentity.verifyPromise(tampered.second))
  }
}
