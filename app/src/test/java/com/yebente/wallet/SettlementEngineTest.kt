package com.yebente.wallet

import com.yebente.wallet.sync.DepositJob
import com.yebente.wallet.sync.LnurlException
import com.yebente.wallet.sync.LnurlPay
import com.yebente.wallet.sync.OutboundJob
import com.yebente.wallet.sync.PayResult
import com.yebente.wallet.sync.SettlementEngine
import com.yebente.wallet.sync.SettlementRail
import com.yebente.wallet.sync.SettlementStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettlementEngineTest {
  private class FakeStore(jobs: List<OutboundJob>) : SettlementStore {
    val queue = jobs.toMutableList()
    val settled = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val requeued = mutableListOf<String>()
    var anchorDelta = 0L

    override suspend fun queuedOutbound(): List<OutboundJob> = queue.toList()
    override suspend fun jobById(promiseId: String): OutboundJob? = queue.firstOrNull { it.promiseId == promiseId }
    override suspend fun markSettling(promiseId: String) {}
    override suspend fun markSettled(promiseId: String, paymentHash: String, preimage: String, feeSats: Long) {
      settled.add(promiseId)
      queue.removeIf { it.promiseId == promiseId }
    }
    override suspend fun markFailed(promiseId: String, error: String) {
      failed.add(promiseId)
      queue.removeIf { it.promiseId == promiseId }
    }
    override suspend fun requeue(promiseId: String) {
      requeued.add(promiseId)
    }
    override suspend fun openDeposits(): List<DepositJob> = emptyList()
    override suspend fun markDepositSettled(paymentHash: String, creditedSats: Long) {}
    override suspend fun adjustAnchor(deltaSats: Long) {
      anchorDelta += deltaSats
    }
  }

  private class FakeRail(private val behavior: (String) -> PayResult) : SettlementRail {
    val paid = mutableListOf<String>()
    override suspend fun payLightning(invoice: String, sats: Long): PayResult {
      paid.add(invoice)
      return behavior(invoice)
    }
  }

  private class FakeLnurl(private val invoice: String?) : LnurlPay {
    override suspend fun invoiceFor(lightningAddress: String, sats: Long): String {
      return invoice ?: throw LnurlException("No LNURL.", transient = false)
    }
  }

  @Test
  fun `settles stored invoices oldest first`() = runTest {
    val store = FakeStore(
      listOf(
        OutboundJob("a", 1000, "inv-a", ""),
        OutboundJob("b", 2000, "inv-b", ""),
      ),
    )
    val rail = FakeRail { PayResult(ok = true, paymentHash = "h", preimage = "p", feeSats = 2) }
    val engine = SettlementEngine(store, rail, FakeLnurl(null))

    val report = engine.settleAll()

    assertEquals(2, report.settled)
    assertEquals(listOf("inv-a", "inv-b"), rail.paid)
    assertEquals(-(3000 + 4), store.anchorDelta)
    assertFalse(report.retryLater)
  }

  @Test
  fun `uses lnurl when no stored invoice`() = runTest {
    val store = FakeStore(listOf(OutboundJob("a", 1000, "", "sam@ln.example")))
    val rail = FakeRail { PayResult(ok = true, paymentHash = "h") }
    val engine = SettlementEngine(store, rail, FakeLnurl("inv-lnurl"))

    val report = engine.settleAll()

    assertEquals(1, report.settled)
    assertEquals(listOf("inv-lnurl"), rail.paid)
  }

  @Test
  fun `skips jobs with no invoice source`() = runTest {
    val store = FakeStore(listOf(OutboundJob("a", 1000, "", "")))
    val rail = FakeRail { PayResult(ok = true, paymentHash = "h") }
    val engine = SettlementEngine(store, rail, FakeLnurl(null))

    val report = engine.settleAll()

    assertEquals(0, report.settled)
    assertEquals(1, report.skippedNoInvoice)
    assertTrue(rail.paid.isEmpty())
  }

  @Test
  fun `transient failure requeues and stops`() = runTest {
    val store = FakeStore(
      listOf(
        OutboundJob("a", 1000, "inv-a", ""),
        OutboundJob("b", 2000, "inv-b", ""),
      ),
    )
    val rail = FakeRail { PayResult.networkFailure(IOException("down")) }
    val engine = SettlementEngine(store, rail, FakeLnurl(null))

    val report = engine.settleAll()

    assertEquals(0, report.settled)
    assertEquals(listOf("a"), store.requeued)
    assertEquals(listOf("inv-a"), rail.paid)
    assertTrue(report.retryLater)
  }

  @Test
  fun `permanent failure marks failed and continues`() = runTest {
    val store = FakeStore(
      listOf(
        OutboundJob("a", 1000, "inv-bad", ""),
        OutboundJob("b", 2000, "inv-good", ""),
      ),
    )
    val rail = FakeRail {
      if (it == "inv-bad") PayResult.permanent("No route.") else PayResult(ok = true, paymentHash = "h")
    }
    val engine = SettlementEngine(store, rail, FakeLnurl(null))

    val report = engine.settleAll()

    assertEquals(1, report.settled)
    assertEquals(1, report.failed)
    assertEquals(listOf("a"), store.failed)
    assertEquals(listOf("b"), store.settled)
    assertFalse(report.retryLater)
  }
}
