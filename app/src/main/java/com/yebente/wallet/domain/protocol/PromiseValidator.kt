package com.yebente.wallet.domain.protocol

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.domain.model.OfflinePolicy
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.SenderHead

sealed interface ValidationResult {
  data object Valid : ValidationResult
  data class Invalid(val code: String, val reason: String) : ValidationResult
}

object PromiseValidator {
  fun validateStructural(
    promise: OfflinePromise,
    policy: OfflinePolicy,
    nowMs: Long,
  ): ValidationResult {
    if (promise.v != PromiseCodec.PROTOCOL_VERSION) {
      return ValidationResult.Invalid("BAD_VERSION", "Unsupported promise version ${promise.v}.")
    }
    if (promise.id.isBlank()) {
      return ValidationResult.Invalid("BAD_ID", "Promise id is missing.")
    }
    if (!PromiseCodec.isHexKey(promise.sender, 33)) {
      return ValidationResult.Invalid("BAD_SENDER", "Sender key is not a compressed secp256k1 public key.")
    }
    if (!PromiseCodec.isHexKey(promise.recipient, 33)) {
      return ValidationResult.Invalid("BAD_RECIPIENT", "Recipient key is not a compressed secp256k1 public key.")
    }
    if (promise.sender.equals(promise.recipient, ignoreCase = true)) {
      return ValidationResult.Invalid("SELF_PAYMENT", "Sender and recipient must differ.")
    }
    if (promise.sats <= 0) {
      return ValidationResult.Invalid("BAD_AMOUNT", "Amount must be positive.")
    }
    if (promise.sats > policy.maxPromiseSats) {
      return ValidationResult.Invalid("OVER_PROMISE_CAP", "Amount is above the offline promise cap.")
    }
    if (promise.seq <= 0) {
      return ValidationResult.Invalid("BAD_SEQ", "Sequence number must start at 1.")
    }
    if (promise.createdAt <= 0 || promise.createdAt > nowMs + policy.clockSkewMs) {
      return ValidationResult.Invalid("NOT_YET_VALID", "Promise timestamp is in the future.")
    }
    if (promise.expiresAt <= nowMs) {
      return ValidationResult.Invalid("EXPIRED", "Promise is expired.")
    }
    if (promise.expiresAt - promise.createdAt > policy.maxAgeMs) {
      return ValidationResult.Invalid("OVER_MAX_AGE", "Promise lifetime is above the allowed window.")
    }
    return ValidationResult.Valid
  }

  fun validateInbound(
    promise: OfflinePromise,
    head: SenderHead?,
    globalInboundPendingSats: Long,
    policy: OfflinePolicy,
    nowMs: Long,
  ): ValidationResult {
    val structural = validateStructural(promise, policy, nowMs)
    if (structural is ValidationResult.Invalid) return structural

    if (!WalletIdentity.verifyPromise(promise)) {
      return ValidationResult.Invalid("BAD_SIG", "Signature does not match the sender key.")
    }

    if (head == null) {
      if (promise.seq != 1L) {
        return ValidationResult.Invalid("SEQ_GAP", "First promise from this sender must use seq 1.")
      }
      if (!promise.prevHash.equals(PromiseCodec.GENESIS_HASH, ignoreCase = true)) {
        return ValidationResult.Invalid("CHAIN_BREAK", "First promise must link to GENESIS.")
      }
    } else {
      if (promise.seq != head.lastSeq + 1) {
        return ValidationResult.Invalid("SEQ_GAP", "Expected seq ${head.lastSeq + 1} from this sender.")
      }
      if (!promise.prevHash.equals(head.lastHash, ignoreCase = true)) {
        return ValidationResult.Invalid("CHAIN_BREAK", "Promise does not chain to the last accepted promise.")
      }
      if (head.outstandingSats + promise.sats > policy.maxPerSenderSats) {
        return ValidationResult.Invalid("OVER_SENDER_CAP", "Sender is above the trusted offline cap.")
      }
    }

    if (globalInboundPendingSats + promise.sats > policy.maxInboundPendingSats) {
      return ValidationResult.Invalid("OVER_GLOBAL_CAP", "Wallet is above the inbound offline cap.")
    }
    return ValidationResult.Valid
  }
}
