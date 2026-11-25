package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.OfflinePolicy
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.SenderHead
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.domain.protocol.PromiseValidator
import com.yebente.wallet.domain.protocol.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseValidatorTest {
  private val sender = WalletIdentity.fromPrivateHex("01".repeat(32))
  private val recipient = WalletIdentity.fromPrivateHex("02".repeat(32))
  private val policy = OfflinePolicy(
    maxPromiseSats = 50_000,
    maxPerSenderSats = 200_000,
    maxInboundPendingSats = 500_000,
  )
  private val now = 1_700_000_000_000L

  private fun unsigned(seq: Long, sats: Long, prevHash: String): OfflinePromise {
    return OfflinePromise(
      id = "id$seq",
      sender = sender.publicHex,
      recipient = recipient.publicHex,
      sats = sats,
      seq = seq,
      createdAt = now - 1000,
      expiresAt = now + 3_600_000,
      prevHash = prevHash,
      settleAddress = "bc1qtest",
    )
  }

  @Test
  fun `first promise from new sender is valid`() {
    val signed = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val result = PromiseValidator.validateInbound(signed, null, 0, policy, now)
    assertEquals(ValidationResult.Valid, result)
  }

  @Test
  fun `chained second promise is valid`() {
    val first = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val head = SenderHead(sender.publicHex, 1, PromiseCodec.hashHexOf(first), 1000)
    val second = sender.signPromise(unsigned(2, 2000, head.lastHash))
    val result = PromiseValidator.validateInbound(second, head, 1000, policy, now)
    assertEquals(ValidationResult.Valid, result)
  }

  @Test
  fun `replayed sequence is rejected`() {
    val first = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val head = SenderHead(sender.publicHex, 1, PromiseCodec.hashHexOf(first), 1000)
    val replay = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val result = PromiseValidator.validateInbound(replay, head, 1000, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "SEQ_GAP")
  }

  @Test
  fun `sequence gap is rejected`() {
    val first = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val head = SenderHead(sender.publicHex, 1, PromiseCodec.hashHexOf(first), 1000)
    val third = sender.signPromise(unsigned(3, 1000, head.lastHash))
    val result = PromiseValidator.validateInbound(third, head, 1000, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "SEQ_GAP")
  }

  @Test
  fun `broken chain hash is rejected`() {
    val first = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val head = SenderHead(sender.publicHex, 1, PromiseCodec.hashHexOf(first), 1000)
    val bad = sender.signPromise(unsigned(2, 1000, "ff".repeat(32)))
    val result = PromiseValidator.validateInbound(bad, head, 1000, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "CHAIN_BREAK")
  }

  @Test
  fun `tampered amount breaks signature`() {
    val signed = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val tampered = signed.copy(sats = 9000)
    val result = PromiseValidator.validateInbound(tampered, null, 0, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "BAD_SIG")
  }

  @Test
  fun `wrong signer is rejected`() {
    val forged = recipient.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val result = PromiseValidator.validateInbound(forged, null, 0, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "BAD_SIG")
  }

  @Test
  fun `promise over cap is rejected`() {
    val signed = sender.signPromise(unsigned(1, 60_000, PromiseCodec.GENESIS_HASH))
    val result = PromiseValidator.validateInbound(signed, null, 0, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "OVER_PROMISE_CAP")
  }

  @Test
  fun `expired promise is rejected`() {
    val signed = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH).copy(expiresAt = now - 1))
    val result = PromiseValidator.validateInbound(signed, null, 0, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "EXPIRED")
  }

  @Test
  fun `sender cap is enforced`() {
    val first = sender.signPromise(unsigned(1, 1000, PromiseCodec.GENESIS_HASH))
    val head = SenderHead(sender.publicHex, 1, PromiseCodec.hashHexOf(first), 199_500)
    val second = sender.signPromise(unsigned(2, 1000, head.lastHash))
    val result = PromiseValidator.validateInbound(second, head, 199_500, policy, now)
    assertTrue(result is ValidationResult.Invalid && result.code == "OVER_SENDER_CAP")
  }
}
