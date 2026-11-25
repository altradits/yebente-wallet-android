package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.PromiseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityDerivationTest {
  @Test
  fun `same seed derives same key`() {
    val seed = ByteArray(32) { it.toByte() }
    assertEquals(WalletIdentity.fromSeed(seed).publicHex, WalletIdentity.fromSeed(seed).publicHex)
  }

  @Test
  fun `different seeds derive different keys`() {
    val a = WalletIdentity.fromSeed(ByteArray(32) { 0x01 })
    val b = WalletIdentity.fromSeed(ByteArray(32) { 0x02 })
    assertNotEquals(a.publicHex, b.publicHex)
  }

  @Test
  fun `derived key signs and verifies promises`() {
    val identity = WalletIdentity.fromSeed(ByteArray(64) { (it * 7).toByte() })
    assertEquals(66, identity.publicHex.length)
    val now = 1_700_000_000_000L
    val promise = OfflinePromise(
      id = "id1",
      sender = identity.publicHex,
      recipient = WalletIdentity.fromPrivateHex("02".repeat(32)).publicHex,
      sats = 1000,
      seq = 1,
      createdAt = now - 1000,
      expiresAt = now + 3_600_000,
      prevHash = PromiseCodec.GENESIS_HASH,
      settleAddress = "bc1qtest",
    )
    assertTrue(WalletIdentity.verifyPromise(identity.signPromise(promise)))
  }
}
