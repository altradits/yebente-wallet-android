package com.yebente.wallet.crypto

import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.util.hexToBytes
import com.yebente.wallet.util.toHex
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import java.math.BigInteger
import java.security.MessageDigest

class WalletIdentity private constructor(val key: ECKey) {
  val publicHex: String = key.pubKey.toHex()

  fun signPromise(promise: OfflinePromise): OfflinePromise {
    val hash = Sha256Hash.of(PromiseCodec.signableHash(unsigned(promise)))
    val sig = key.sign(hash)
    return promise.copy(sig = encodeSignature(sig))
  }

  fun signBytes(data: ByteArray): String {
    val hash = Sha256Hash.of(MessageDigest.getInstance("SHA-256").digest(data))
    return encodeSignature(key.sign(hash))
  }

  companion object {
    private const val IDENTITY_TAG = "yebente-identity-v1"

    fun generate(): WalletIdentity = WalletIdentity(ECKey())

    fun fromPrivateHex(hex: String): WalletIdentity {
      val priv = BigInteger(1, hex.hexToBytes())
      return WalletIdentity(ECKey.fromPrivate(priv))
    }

    fun fromSeed(seed: ByteArray): WalletIdentity {
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(seed)
      digest.update(IDENTITY_TAG.toByteArray(Charsets.UTF_8))
      val priv = BigInteger(1, digest.digest())
      return WalletIdentity(ECKey.fromPrivate(priv))
    }

    fun verifyPromise(promise: OfflinePromise): Boolean {
      return try {
        if (promise.sig.isBlank()) return false
        val sig = decodeSignature(promise.sig)
        val pub = ECKey.fromPublicOnly(promise.sender.hexToBytes())
        val hash = Sha256Hash.of(PromiseCodec.signableHash(unsigned(promise)))
        pub.verify(hash, sig)
      } catch (_: Exception) {
        false
      }
    }

    private fun unsigned(promise: OfflinePromise): OfflinePromise = promise.copy(sig = "")

    private fun encodeSignature(sig: ECKey.ECDSASignature): String {
      return sig.r.toFixedBytes() + sig.s.toFixedBytes()
    }

    private fun decodeSignature(hex: String): ECKey.ECDSASignature {
      val bytes = hex.hexToBytes()
      require(bytes.size == 64) { "Signature must be 64 bytes" }
      val r = BigInteger(1, bytes.copyOfRange(0, 32))
      val s = BigInteger(1, bytes.copyOfRange(32, 64))
      return ECKey.ECDSASignature(r, s)
    }

    private fun BigInteger.toFixedBytes(): String {
      val raw = toByteArray().takeLast(32).toByteArray()
      val padded = ByteArray(32)
      System.arraycopy(raw, 0, padded, 32 - raw.size, raw.size)
      return padded.toHex()
    }
  }
}
