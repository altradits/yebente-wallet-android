package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.ForkDetector
import com.yebente.wallet.domain.protocol.PromiseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ForkDetectorTest {
  private val sender = WalletIdentity.fromPrivateHex("0c".repeat(32))
  private val alice = WalletIdentity.fromPrivateHex("0d".repeat(32))
  private val bob = WalletIdentity.fromPrivateHex("0e".repeat(32))
  private val now = 1_700_000_000_000L

  private fun promise(id: String, recipient: String, sats: Long, seq: Long): OfflinePromise {
    return sender.signPromise(
      OfflinePromise(
        id = id,
        sender = sender.publicHex,
        recipient = recipient,
        sats = sats,
        seq = seq,
        createdAt = now - 1000,
        expiresAt = now + 3_600_000,
        prevHash = PromiseCodec.GENESIS_HASH,
        settleAddress = "bc1qtest",
      ),
    )
  }

  @Test
  fun `same seq different recipient is a fork`() {
    val first = promise("fork-a", alice.publicHex, 1000, 1)
    val second = promise("fork-b", bob.publicHex, 1000, 1)
    val fork = ForkDetector.detect(first, second)
    assertNotNull(fork)
    assertEquals(1L, fork!!.seq)
    assertEquals("fork-a", fork.firstId)
    assertEquals("fork-b", fork.secondId)
  }

  @Test
  fun `same promise twice is not a fork`() {
    val first = promise("fork-a", alice.publicHex, 1000, 1)
    assertNull(ForkDetector.detect(first, first))
  }

  @Test
  fun `adjacent seq is not a fork`() {
    val first = promise("fork-a", alice.publicHex, 1000, 1)
    val second = promise("fork-b", alice.publicHex, 1000, 2)
    assertNull(ForkDetector.detect(first, second))
  }
}
