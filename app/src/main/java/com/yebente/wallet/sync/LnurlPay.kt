package com.yebente.wallet.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class LnurlException(message: String, val transient: Boolean) : IOException(message)

interface LnurlPay {
  suspend fun invoiceFor(lightningAddress: String, sats: Long): String
}

class OkHttpLnurlPay(
  private val client: OkHttpClient,
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val wellKnownUrl: (domain: String, user: String) -> String =
    { domain, user -> "https://$domain/.well-known/lnurlp/$user" },
) : LnurlPay {

  override suspend fun invoiceFor(lightningAddress: String, sats: Long): String {
    val clean = lightningAddress.trim()
    val parts = clean.split("@")
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw LnurlException("Bad Lightning address.", transient = false)
    }
    val msats = sats * 1000
    val wellKnown = wellKnownUrl(parts[1], parts[0])
    val meta = json.parseToJsonElement(get(wellKnown)).jsonObject
    if (meta["tag"]?.jsonPrimitive?.content != "payRequest") {
      throw LnurlException("Service is not an LNURL-pay endpoint.", transient = false)
    }
    val minSendable = meta["minSendable"]?.jsonPrimitive?.long ?: 0L
    val maxSendable = meta["maxSendable"]?.jsonPrimitive?.long ?: 0L
    if (msats < minSendable || msats > maxSendable) {
      throw LnurlException("Amount is outside the recipient LNURL range.", transient = false)
    }
    val callback = meta["callback"]?.jsonPrimitive?.content.orEmpty()
    val callbackUrl = callback.toHttpUrlOrNull()
      ?: throw LnurlException("Bad LNURL callback URL.", transient = false)
    val loopback = callbackUrl.host == "localhost" || callbackUrl.host == "127.0.0.1"
    if (!callbackUrl.isHttps && !loopback) {
      throw LnurlException("LNURL callback must use https.", transient = false)
    }
    val invoiceUrl = callbackUrl.newBuilder().addQueryParameter("amount", msats.toString()).build()
    val invoiceRes = json.parseToJsonElement(get(invoiceUrl.toString())).jsonObject
    val invoice = invoiceRes["pr"]?.jsonPrimitive?.content.orEmpty()
    if (invoice.isBlank()) {
      val reason = invoiceRes["reason"]?.jsonPrimitive?.content ?: "LNURL service gave no invoice."
      throw LnurlException(reason, transient = false)
    }
    return invoice
  }

  private fun get(url: String): String {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { res ->
      val body = res.body?.string().orEmpty()
      if (!res.isSuccessful) {
        throw LnurlException("LNURL request failed: HTTP ${res.code}.", transient = res.code >= 500)
      }
      return body
    }
  }
}
