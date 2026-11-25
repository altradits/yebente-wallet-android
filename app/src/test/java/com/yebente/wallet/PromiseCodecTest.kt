package com.yebente.wallet

import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.model.SettlementReceipt
import com.yebente.wallet.domain.protocol.PromiseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseCodecTest {
  private val promise = OfflinePromise(
    id = "abc123",
    sender = "02" + "11".repeat(32),
    recipient = "03" + "22".repeat(32),
    sats = 5000,
    seq = 1,
    createdAt = 1_700_000_000_000,
    expiresAt = 1_700_060_480_000,
    prevHash = PromiseCodec.GENESIS_HASH,
    settleAddress = "bc1qtest",
    memo = "milk",
    sig = "aa".repeat(64),
  )

  @Test
  fun `promise envelope round trips`() {
    val frames = PromiseCodec.encodePromise(promise)
    assertEquals(1, frames.size)
    assertTrue(frames[0].startsWith("YB1:1:1:"))
    assertEquals(promise, PromiseCodec.decodePromise(frames))
  }

  @Test
  fun `promise frames survive tiny chunks in any order`() {
    val frames = PromiseCodec.encodePromise(promise, maxChunkChars = 40)
    assertTrue(frames.size > 1)
    val shuffled = frames.shuffled()
    assertEquals(promise, PromiseCodec.decodePromise(shuffled))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `missing frames are rejected`() {
    val frames = PromiseCodec.encodePromise(promise, maxChunkChars = 40)
    PromiseCodec.decodePromise(frames.dropLast(1))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `wrong prefix is rejected`() {
    PromiseCodec.decodePromiseSingle("ZZ9:1:1:payload")
  }

  @Test
  fun `hash is stable and changes with amount`() {
    val a = PromiseCodec.hashHexOf(promise)
    val b = PromiseCodec.hashHexOf(promise.copy(sats = 5001))
    assertEquals(64, a.length)
    assertTrue(a != b)
  }

  @Test
  fun `canonical form ignores signature`() {
    val a = PromiseCodec.canonical(promise)
    val b = PromiseCodec.canonical(promise.copy(sig = "bb".repeat(64)))
    assertEquals(a.toList(), b.toList())
  }

  @Test
  fun `handshake round trips`() {
    val handshake = ReceiveHandshake(
      recipient = "02" + "33".repeat(32),
      address = "bc1qreceiver",
      lightning = "stan@getalby.com",
    )
    val code = PromiseCodec.encodeHandshake(handshake)
    assertTrue(code.startsWith("YBH1:"))
    assertEquals(handshake, PromiseCodec.decodeHandshake(code))
  }

  @Test
  fun `receipt round trips`() {
    val receipt = SettlementReceipt(
      promiseId = "abc123",
      paymentHash = "hash1",
      preimage = "pre1",
      sats = 5000,
      settledAt = 1_700_000_000_001,
    )
    val code = PromiseCodec.encodeReceipt(receipt)
    assertTrue(code.startsWith("YBR1:"))
    assertEquals(receipt, PromiseCodec.decodeReceipt(code))
  }
}
