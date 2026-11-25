package com.yebente.wallet.domain.protocol

import com.yebente.wallet.domain.model.ForkGossip
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.model.SettlementReceipt
import com.yebente.wallet.util.hexToBytes
import com.yebente.wallet.util.toHex
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

object PromiseCodec {
  const val PROTOCOL_VERSION = 1
  const val PROMISE_PREFIX = "YB1"
  const val HANDSHAKE_PREFIX = "YBH1"
  const val RECEIPT_PREFIX = "YBR1"
  const val FORK_PREFIX = "YBF1"
  const val GENESIS_HASH = "GENESIS"

  val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
  private val b64Decoder = Base64.getUrlDecoder()

  fun canonical(promise: OfflinePromise): ByteArray {
    val fields = listOf(
      PROMISE_PREFIX,
      promise.v.toString(),
      promise.id,
      promise.sender.lowercase(),
      promise.recipient.lowercase(),
      promise.sats.toString(),
      promise.seq.toString(),
      promise.createdAt.toString(),
      promise.expiresAt.toString(),
      promise.prevHash.lowercase(),
      promise.settleAddress,
      promise.memo,
    )
    return fields.joinToString("|").toByteArray(Charsets.UTF_8)
  }

  fun hashOf(promise: OfflinePromise): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(canonical(promise))
  }

  fun hashHexOf(promise: OfflinePromise): String = hashOf(promise).toHex()

  fun signableHash(promise: OfflinePromise): ByteArray = hashOf(promise)

  fun encodePromise(promise: OfflinePromise, maxChunkChars: Int = 1200): List<String> {
    val payload = b64Encoder.encodeToString(json.encodeToString(OfflinePromise.serializer(), promise).toByteArray(Charsets.UTF_8))
    return frame(PROMISE_PREFIX, payload, maxChunkChars)
  }

  fun decodePromise(frames: List<String>): OfflinePromise {
    val payload = unframe(PROMISE_PREFIX, frames)
    val raw = String(b64Decoder.decode(payload), Charsets.UTF_8)
    return json.decodeFromString(OfflinePromise.serializer(), raw)
  }

  fun decodePromiseSingle(text: String): OfflinePromise = decodePromise(listOf(text.trim()))

  fun encodeHandshake(handshake: ReceiveHandshake): String {
    val payload = b64Encoder.encodeToString(json.encodeToString(ReceiveHandshake.serializer(), handshake).toByteArray(Charsets.UTF_8))
    return "$HANDSHAKE_PREFIX:$payload"
  }

  fun decodeHandshake(text: String): ReceiveHandshake {
    val clean = text.trim()
    val prefix = "$HANDSHAKE_PREFIX:"
    require(clean.startsWith(prefix)) { "Not a YeBente handshake code" }
    val raw = String(b64Decoder.decode(clean.removePrefix(prefix)), Charsets.UTF_8)
    return json.decodeFromString(ReceiveHandshake.serializer(), raw)
  }

  fun encodeReceipt(receipt: SettlementReceipt): String {
    val payload = b64Encoder.encodeToString(json.encodeToString(SettlementReceipt.serializer(), receipt).toByteArray(Charsets.UTF_8))
    return "$RECEIPT_PREFIX:$payload"
  }

  fun decodeReceipt(text: String): SettlementReceipt {
    val clean = text.trim()
    val prefix = "$RECEIPT_PREFIX:"
    require(clean.startsWith(prefix)) { "Not a YeBente receipt code" }
    val raw = String(b64Decoder.decode(clean.removePrefix(prefix)), Charsets.UTF_8)
    return json.decodeFromString(SettlementReceipt.serializer(), raw)
  }

  fun encodeForkGossip(gossip: ForkGossip, maxChunkChars: Int = 1200): List<String> {
    val payload = b64Encoder.encodeToString(json.encodeToString(ForkGossip.serializer(), gossip).toByteArray(Charsets.UTF_8))
    return frame(FORK_PREFIX, payload, maxChunkChars)
  }

  fun decodeForkGossip(frames: List<String>): ForkGossip {
    val payload = unframe(FORK_PREFIX, frames)
    val raw = String(b64Decoder.decode(payload), Charsets.UTF_8)
    return json.decodeFromString(ForkGossip.serializer(), raw)
  }

  fun frame(prefix: String, payload: String, maxChunkChars: Int): List<String> {
    require(maxChunkChars > 0) { "maxChunkChars must be positive" }
    val chunks = payload.chunked(maxChunkChars)
    val total = chunks.size
    return chunks.mapIndexed { index, chunk -> "$prefix:$total:${index + 1}:$chunk" }
  }

  fun unframe(prefix: String, frames: List<String>): String {
    require(frames.isNotEmpty()) { "No frames to decode" }
    val parsed = frames.map { parseFrame(prefix, it) }
    val total = parsed.first().total
    require(total in 1..64) { "Bad frame total: $total" }
    require(parsed.all { it.total == total }) { "Mixed frame totals" }
    val byIndex = parsed.associateBy { it.index }
    require(byIndex.size == total) { "Missing frames: have ${byIndex.size} of $total" }
    return (1..total).joinToString("") { byIndex.getValue(it).payload }
  }

  private data class Frame(val total: Int, val index: Int, val payload: String)

  private fun parseFrame(prefix: String, text: String): Frame {
    val clean = text.trim()
    val parts = clean.split(":", limit = 4)
    require(parts.size == 4 && parts[0] == prefix) { "Bad frame prefix" }
    val total = parts[1].toIntOrNull()
    val index = parts[2].toIntOrNull()
    require(total != null && index != null && index in 1..total) { "Bad frame numbers" }
    return Frame(total, index, parts[3])
  }

  fun isHexKey(value: String, expectedBytes: Int): Boolean {
    return try {
      value.hexToBytes().size == expectedBytes
    } catch (_: IllegalArgumentException) {
      false
    }
  }
}
