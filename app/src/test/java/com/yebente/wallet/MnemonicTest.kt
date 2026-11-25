package com.yebente.wallet

import com.yebente.wallet.crypto.BitcoinAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MnemonicTest {
  @Test
  fun `known valid phrase passes checksum`() {
    val words = List(11) { "abandon" } + "about"
    val seed = BitcoinAddresses.seedFromMnemonic(words)
    assertEquals(words, seed.mnemonicCode)
  }

  @Test
  fun `bad checksum is rejected`() {
    assertThrows(Exception::class.java) {
      BitcoinAddresses.seedFromMnemonic(List(12) { "abandon" })
    }
  }

  @Test
  fun `unknown word is rejected`() {
    assertThrows(Exception::class.java) {
      BitcoinAddresses.seedFromMnemonic(List(11) { "abandon" } + "notaword")
    }
  }
}
