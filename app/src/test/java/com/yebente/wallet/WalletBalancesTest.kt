package com.yebente.wallet

import com.yebente.wallet.domain.model.WalletBalances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletBalancesTest {
  @Test
  fun `spendable adds anchor and inbound minus outbound`() {
    val balances = WalletBalances(anchorSats = 10_000, inboundAcceptedSats = 3_000, outboundPendingSats = 4_000)
    assertEquals(9_000, balances.spendableSats)
  }

  @Test
  fun `offline receive is spendable before settlement`() {
    val balances = WalletBalances(anchorSats = 0, inboundAcceptedSats = 2_500, outboundPendingSats = 0)
    assertEquals(2_500, balances.spendableSats)
    assertTrue(balances.canSpend(2_500))
  }

  @Test
  fun `spendable never goes below zero`() {
    val balances = WalletBalances(anchorSats = 1_000, inboundAcceptedSats = 0, outboundPendingSats = 5_000)
    assertEquals(0, balances.spendableSats)
    assertFalse(balances.canSpend(1))
  }

  @Test
  fun `cannot spend zero`() {
    val balances = WalletBalances(anchorSats = 1_000, inboundAcceptedSats = 0, outboundPendingSats = 0)
    assertFalse(balances.canSpend(0))
  }
}
