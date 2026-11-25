package com.yebente.wallet.crypto

import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.core.SegwitAddress

class BitcoinAddresses(seed: DeterministicSeed) {
  private val chain: DeterministicKeyChain =
    DeterministicKeyChain.builder().seed(seed).build()

  fun receiveAddress(index: Int): String {
    require(index >= 0) { "Address index must be non-negative" }
    val path = listOf(
      ChildNumber(84, true),
      ChildNumber(0, true),
      ChildNumber(0, true),
      ChildNumber.ZERO,
      ChildNumber(index),
    )
    val key = chain.getKeyByPath(path, true)
    return SegwitAddress.fromKey(MainNetParams.get(), key).toBech32()
  }

  companion object {
    fun seedFromMnemonic(words: List<String>): DeterministicSeed {
      MnemonicCode.INSTANCE.check(words)
      val seedBytes = MnemonicCode.toSeed(words, "")
      return DeterministicSeed(words, seedBytes, "", creationTime())
    }

    fun newMnemonic(entropy: ByteArray): List<String> {
      require(entropy.size == 16 || entropy.size == 32) { "Entropy must be 16 or 32 bytes" }
      return MnemonicCode.INSTANCE.toMnemonic(entropy)
    }

    private fun creationTime(): Long = System.currentTimeMillis() / 1000
  }
}
