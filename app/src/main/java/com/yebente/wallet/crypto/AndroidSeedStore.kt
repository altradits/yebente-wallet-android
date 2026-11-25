package com.yebente.wallet.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bitcoinj.wallet.DeterministicSeed
import java.security.SecureRandom

class AndroidSeedStore(context: Context) {
  private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
    context.applicationContext,
    PREFS_NAME,
    masterKey(context.applicationContext),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )

  fun hasSeed(): Boolean = prefs.contains(KEY_MNEMONIC)

  fun getOrCreateSeed(): DeterministicSeed {
    val existing = prefs.getString(KEY_MNEMONIC, null)
    if (!existing.isNullOrBlank()) {
      return BitcoinAddresses.seedFromMnemonic(existing.split(" "))
    }
    val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val words = BitcoinAddresses.newMnemonic(entropy)
    prefs.edit().putString(KEY_MNEMONIC, words.joinToString(" ")).apply()
    return BitcoinAddresses.seedFromMnemonic(words)
  }

  fun seedBytes(): ByteArray = getOrCreateSeed().seedBytes ?: ByteArray(0)

  fun wipe() {
    prefs.edit().clear().apply()
  }

  private fun masterKey(context: Context): MasterKey {
    return MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }

  companion object {
    private const val PREFS_NAME = "yebente_encrypted_prefs"
    private const val KEY_MNEMONIC = "wallet_mnemonic"
  }
}
