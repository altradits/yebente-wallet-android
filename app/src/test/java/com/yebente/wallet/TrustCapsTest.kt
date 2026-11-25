package com.yebente.wallet

import com.yebente.wallet.domain.protocol.TrustCaps
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustCapsTest {
  @Test
  fun `untrusted sender keeps policy cap`() {
    assertEquals(200_000L, TrustCaps.effectivePerSenderCap(200_000L, null))
  }

  @Test
  fun `trusted cap below policy wins`() {
    assertEquals(50_000L, TrustCaps.effectivePerSenderCap(200_000L, 50_000L))
  }

  @Test
  fun `trusted cap above policy wins`() {
    assertEquals(500_000L, TrustCaps.effectivePerSenderCap(200_000L, 500_000L))
  }

  @Test
  fun `non-positive trusted cap falls back to policy`() {
    assertEquals(200_000L, TrustCaps.effectivePerSenderCap(200_000L, 0L))
    assertEquals(200_000L, TrustCaps.effectivePerSenderCap(200_000L, -5L))
  }
}
