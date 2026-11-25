package com.yebente.wallet.crypto

import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.util.hexToBytes
import com.yebente.wallet.util.toHex
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    private const val IDENTITY_SALT = "yebente-identity-hkdf-v1"

    fun generate(): WalletIdentity = WalletIdentity(ECKey())

    fun fromPrivateHex(hex: String): WalletIdentity {
      val priv = BigInteger(1, hex.hexToBytes())
      return WalletIdentity(ECKey.fromPrivate(priv))
    }

    fun fromSeed(seed: ByteArray): WalletIdentity {
      var counter = 0
      while (true) {
        val info = "$IDENTITY_TAG/$counter".toByteArray(Charsets.UTF_8)
        val okm = hkdfSha256(seed, IDENTITY_SALT.toByteArray(Charsets.UTF_8), info, 32)
        try {
          return WalletIdentity(ECKey.fromPrivate(BigInteger(1, okm)))
        } catch (_: Exception) {
          counter++
        }
      }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
      val extract = Mac.getInstance("HmacSHA256")
      extract.init(SecretKeySpec(salt, "HmacSHA256"))
      val prk = extract.doFinal(ikm)
      val out = ByteArrayOutputStream()
      var previous = ByteArray(0)
      var counter = 1
      while (out.size() < length) {
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(previous)
        expand.update(info)
        expand.update(counter.toByte())
        previous = expand.doFinal()
        out.write(previous)
        counter++
      }
      return out.toByteArray().copyOf(length)
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
