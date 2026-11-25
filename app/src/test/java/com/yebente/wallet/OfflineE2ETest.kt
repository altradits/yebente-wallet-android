package com.yebente.wallet

import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.data.remote.YebenteApiFactory
import com.yebente.wallet.data.settings.NodeConfig
import com.yebente.wallet.data.settings.NodeCredentials
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.OfflinePolicy
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.model.SettlementReceipt
import com.yebente.wallet.domain.model.WalletBalances
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.domain.protocol.PromiseValidator
import com.yebente.wallet.domain.protocol.ValidationResult
import com.yebente.wallet.offline.ble.BleProtocol
import com.yebente.wallet.offline.nfc.NfcHceResponder
import com.yebente.wallet.offline.nfc.NfcProtocol
import com.yebente.wallet.sync.DepositJob
import com.yebente.wallet.sync.LnurlPay
import com.yebente.wallet.sync.OkHttpLnurlPay
import com.yebente.wallet.sync.OutboundJob
import com.yebente.wallet.sync.SettlementEngine
import com.yebente.wallet.sync.SettlementStore
import com.yebente.wallet.sync.YebenteGatewayRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineE2ETest {
  private val sender = WalletIdentity.fromPrivateHex("0a".repeat(32))
  private val receiver = WalletIdentity.fromPrivateHex("0b".repeat(32))
  private val policy = OfflinePolicy()
  private val timings = LinkedHashMap<String, Long>()

  private suspend fun <T> timed(name: String, block: suspend () -> T): T {
    val start = System.nanoTime()
    val result = block()
    timings[name] = (System.nanoTime() - start) / 1_000_000
    return result
  }

  private class MemoryStore(jobs: List<OutboundJob>) : SettlementStore {
    val queue = jobs.toMutableList()
    var anchorDelta = 0L
    var settledRef = ""

    override suspend fun queuedOutbound(): List<OutboundJob> = queue.toList()
    override suspend fun jobById(promiseId: String): OutboundJob? = queue.firstOrNull { it.promiseId == promiseId }
    override suspend fun markSettling(promiseId: String) {}
    override suspend fun markSettled(promiseId: String, paymentHash: String, preimage: String, feeSats: Long) {
      settledRef = "$paymentHash|$preimage"
      queue.removeIf { it.promiseId == promiseId }
    }
    override suspend fun markFailed(promiseId: String, error: String) {
      queue.removeIf { it.promiseId == promiseId }
    }
    override suspend fun requeue(promiseId: String) {}
    override suspend fun openDeposits(): List<DepositJob> = emptyList()
    override suspend fun markDepositSettled(paymentHash: String, creditedSats: Long) {}
    override suspend fun adjustAnchor(deltaSats: Long) {
      anchorDelta += deltaSats
    }
  }

  private class Server(val http: MockWebServer, val disburseBodies: MutableList<String>) {
    val port: Int get() = http.port
  }

  private fun startServer(): Server {
    val bodies = mutableListOf<String>()
    val http = MockWebServer()
    http.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return MockResponse().setResponseCode(404)
        return when {
          path.startsWith("/api/lightning/disburse") -> {
            bodies.add(request.body.readUtf8())
            MockResponse()
              .addHeader("Content-Type", "application/json")
              .setBody("""{"success":true,"settled":true,"paymentHash":"ph-e2e-1","preimage":"pr-e2e-1","routingFeeSat":3,"satsAmount":5000,"nodeType":"lnbits"}""")
          }
          path == "/.well-known/lnurlp/sam" -> {
            MockResponse()
              .addHeader("Content-Type", "application/json")
              .setBody("""{"tag":"payRequest","callback":"http://127.0.0.1:${http.port}/lnurl/cb","minSendable":1000,"maxSendable":200000000,"metadata":"[]"}""")
          }
          path.startsWith("/lnurl/cb") -> {
            MockResponse()
              .addHeader("Content-Type", "application/json")
              .setBody("""{"pr":"lnbc50u1e2etestinvoice","routes":[]}""")
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
    }
    http.start()
    return Server(http, bodies)
  }

  @Test
  fun `offline send receive settle receipt in one pass`() = runTest {
    val server = startServer()
    try {
      val now = System.currentTimeMillis()

      val handshake = timed("handshake-codec") {
        val hs = ReceiveHandshake(
          recipient = receiver.publicHex,
          address = "bc1qe2ereceiveraddress",
          lightning = "sam@127.0.0.1",
        )
        val code = PromiseCodec.encodeHandshake(hs)
        assertTrue(code.startsWith("YBH1:"))
        val back = PromiseCodec.decodeHandshake(code)
        assertEquals(hs, back)
        back
      }

      val promise = timed("promise-sign") {
        val unsigned = OfflinePromise(
          id = "e2e-promise-1",
          sender = sender.publicHex,
          recipient = handshake.recipient,
          sats = 5000,
          seq = 1,
          createdAt = now - 1000,
          expiresAt = now + 3_600_000,
          prevHash = PromiseCodec.GENESIS_HASH,
          settleAddress = handshake.address,
          memo = "e2e",
        )
        sender.signPromise(unsigned)
      }

      val frames = timed("qr-send") { PromiseCodec.encodePromise(promise, maxChunkChars = 40) }
      assertTrue("expected multi-frame QR payload", frames.size > 1)
      val qrGot = timed("qr-receive") { PromiseCodec.decodePromise(frames.shuffled()) }
      assertEquals(promise, qrGot)

      val payload = frames.joinToString("\n").toByteArray(Charsets.UTF_8)
      val nfcGot = timed("nfc-roundtrip") {
        val select = NfcHceResponder.selectResponse(payload)
        assertTrue(NfcProtocol.isOk(select))
        val header = NfcProtocol.body(select)
        assertEquals(2, header.size)
        val total = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        assertEquals(payload.size, total)
        val out = ByteArray(total)
        var offset = 0
        while (offset < total) {
          val apdu = NfcProtocol.readApdu(offset)
          val parsedOffset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
          val chunk = NfcHceResponder.readResponse(payload, parsedOffset)
          assertTrue(NfcProtocol.isOk(chunk))
          val body = NfcProtocol.body(chunk)
          assertTrue(body.isNotEmpty())
          System.arraycopy(body, 0, out, offset, body.size)
          offset += body.size
        }
        out
      }
      assertTrue(payload.contentEquals(nfcGot))
      assertEquals(promise, PromiseCodec.decodePromise(String(nfcGot, Charsets.UTF_8).split("\n")))

      val bleGot = timed("ble-roundtrip") {
        val chunks = BleProtocol.toChunks(payload)
        assertTrue(chunks.isNotEmpty())
        val parts = mutableMapOf<Int, ByteArray>()
        var expected = -1
        for (chunk in chunks) {
          val (total, index, data) = BleProtocol.parseChunk(chunk) ?: error("bad chunk")
          if (expected == -1) expected = total
          assertEquals(expected, total)
          parts[index] = data
        }
        assertEquals(expected, parts.size)
        (1..expected).fold(ByteArray(0)) { acc, i -> acc + parts.getValue(i) }
      }
      assertTrue(payload.contentEquals(bleGot))
      assertEquals(promise, PromiseCodec.decodePromise(String(bleGot, Charsets.UTF_8).split("\n")))

      timed("offline-validate-spend") {
        val verdict = PromiseValidator.validateInbound(qrGot, null, 0, policy, now)
        assertEquals(ValidationResult.Valid, verdict)
        val receiverSide = WalletBalances(anchorSats = 20_000, inboundAcceptedSats = 5000, outboundPendingSats = 0)
        assertEquals(25_000, receiverSide.spendableSats)
        assertTrue(receiverSide.canSpend(25_000))
        val senderSide = WalletBalances(anchorSats = 20_000, inboundAcceptedSats = 0, outboundPendingSats = 5000)
        assertEquals(15_000, senderSide.spendableSats)
      }

      val report = timed("reconnect-rebalance") {
        withContext(Dispatchers.IO) {
          val creds = object : NodeCredentials {
            override suspend fun current(): NodeConfig {
              return NodeConfig(
                backendUrl = "http://127.0.0.1:${server.port}",
                nodeType = NodeConfig.NODE_LNBITS,
                nodeUrl = "http://127.0.0.1:${server.port}",
              ).withNodeKeyPresent(true)
            }

            override fun nodeKey(): String = "e2e-key"
          }
          val rail = YebenteGatewayRail(creds) { base -> YebenteApiFactory.create(base) }
          val lnurl: LnurlPay = OkHttpLnurlPay(
            OkHttpClient(),
            wellKnownUrl = { _, user -> "http://127.0.0.1:${server.port}/.well-known/lnurlp/$user" },
          )
          val store = MemoryStore(
            listOf(OutboundJob("e2e-promise-1", 5000, "", handshake.lightning)),
          )
          val engine = SettlementEngine(store, rail, lnurl)
          val rep = engine.settleAll()
          assertEquals(1, rep.settled)
          assertEquals(-5003, store.anchorDelta)
          assertEquals("ph-e2e-1|pr-e2e-1", store.settledRef)
          rep
        }
      }
      assertEquals(0, report.failed)
      assertTrue(server.disburseBodies.size == 1)
      assertTrue(server.disburseBodies[0].contains("lnbc50u1e2etestinvoice"))
      assertTrue(server.disburseBodies[0].contains("lnbits"))

      timed("receipt-sync") {
        val receipt = SettlementReceipt(
          promiseId = "e2e-promise-1",
          paymentHash = "ph-e2e-1",
          preimage = "pr-e2e-1",
          sats = 5000,
          settledAt = now,
        )
        val code = PromiseCodec.encodeReceipt(receipt)
        val back = PromiseCodec.decodeReceipt(code)
        assertEquals(receipt, back)
        assertEquals(promise.id, back.promiseId)
        assertEquals(promise.sats, back.sats)
      }

      val total = timings.values.sum()
      println("E2E leg timings (ms):")
      timings.forEach { (name, ms) -> println("  $name=$ms") }
      println("  total=$total")
      timings.forEach { (name, ms) ->
        assertTrue("leg $name too slow: ${ms}ms", ms < 15_000)
      }
      assertTrue("total too slow: ${total}ms", total < 120_000)
    } finally {
      server.http.shutdown()
    }
  }
}
