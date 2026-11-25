package com.yebente.wallet.data.repo

import android.database.sqlite.SQLiteConstraintException
import com.yebente.wallet.crypto.AndroidSeedStore
import com.yebente.wallet.crypto.BitcoinAddresses
import com.yebente.wallet.crypto.WalletIdentity
import com.yebente.wallet.data.local.DepositEntity
import com.yebente.wallet.data.local.ForkProofEntity
import com.yebente.wallet.data.local.PromiseEntity
import com.yebente.wallet.data.local.SenderHeadEntity
import com.yebente.wallet.data.local.TrustedSenderEntity
import com.yebente.wallet.data.local.WalletDatabase
import com.yebente.wallet.data.remote.CreateInvoiceRequest
import com.yebente.wallet.data.remote.YebenteApi
import com.yebente.wallet.data.settings.NodeSettings
import com.yebente.wallet.domain.model.ForkGossip
import com.yebente.wallet.domain.model.OfflinePolicy
import com.yebente.wallet.domain.model.OfflinePromise
import com.yebente.wallet.domain.model.PromiseDirection
import com.yebente.wallet.domain.model.PromiseState
import com.yebente.wallet.domain.model.ReceiveHandshake
import com.yebente.wallet.domain.model.SenderHead
import com.yebente.wallet.domain.model.SettlementReceipt
import com.yebente.wallet.domain.model.WalletBalances
import com.yebente.wallet.domain.protocol.ForkDetector
import com.yebente.wallet.domain.protocol.PromiseCodec
import com.yebente.wallet.domain.protocol.PromiseValidator
import com.yebente.wallet.domain.protocol.TrustCaps
import com.yebente.wallet.domain.protocol.ValidationResult
import com.yebente.wallet.sync.DepositJob
import com.yebente.wallet.sync.DepositPoller
import com.yebente.wallet.sync.OutboundJob
import com.yebente.wallet.sync.SettlementStore
import com.yebente.wallet.util.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

data class DepositView(val invoice: String, val paymentHash: String, val sats: Long)

class WalletRepository(
  private val db: WalletDatabase,
  val settings: NodeSettings,
  private val seedStore: AndroidSeedStore,
  private val apiFor: (String) -> YebenteApi,
  private val policy: OfflinePolicy = OfflinePolicy(),
) : SettlementStore, DepositPoller {

  private fun identity(): WalletIdentity = WalletIdentity.fromSeed(seedStore.seedBytes())
  private fun addresses(): BitcoinAddresses = BitcoinAddresses(seedStore.getOrCreateSeed())
  private val random = SecureRandom()

  fun balances(): Flow<WalletBalances> = combine(
    settings.config.map { it.anchorSats },
    db.promises().inboundAcceptedSum(),
    db.promises().outboundPendingSum(),
    db.promises().trustedInboundSum(),
  ) { anchor, inbound, outbound, trusted ->
    WalletBalances(anchor, inbound, outbound, trusted)
  }

  fun recentPromises(limit: Int = 100): Flow<List<PromiseEntity>> = db.promises().recent(limit)

  fun outboundPendingCount(): Flow<Int> = db.promises().outboundPendingCount()

  fun deposits() = db.deposits().all()

  fun identityHex(): String = identity().publicHex

  fun seedWords(): List<String>? = seedStore.words()

  suspend fun restoreFromMnemonic(text: String): Result<Unit> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) {
      return Result.failure(IllegalArgumentException("Enter the recovery phrase."))
    }
    try {
      seedStore.setMnemonic(words)
    } catch (t: Exception) {
      return Result.failure(IllegalArgumentException("Invalid recovery phrase."))
    }
    db.promises().clear()
    db.senderHeads().clear()
    db.deposits().clear()
    db.trusted().clear()
    db.forks().clear()
    // Rebase note: also clear outboundHeads once per-recipient chains land.
    settings.resetChainState()
    return Result.success(Unit)
  }

  suspend fun myHandshake(): ReceiveHandshake {
    val index = settings.nextAddressIndex()
    val address = addresses().receiveAddress(index)
    val lightning = settings.current().lightningAddress
    return ReceiveHandshake(
      recipient = identityHex(),
      address = address,
      lightning = lightning,
    )
  }

  suspend fun createOutbound(handshake: ReceiveHandshake, sats: Long, memo: String): Result<OfflinePromise> {
    if (sats <= 0) return Result.failure(IllegalArgumentException("Amount must be positive."))
    if (!PromiseCodec.isHexKey(handshake.recipient, 33)) {
      return Result.failure(IllegalArgumentException("Recipient code is not a valid wallet key."))
    }
    if (handshake.recipient.equals(identityHex(), ignoreCase = true)) {
      return Result.failure(IllegalArgumentException("Cannot pay yourself."))
    }
    if (handshake.maxSats > 0 && sats > handshake.maxSats) {
      return Result.failure(IllegalArgumentException("Amount is above the recipient cap."))
    }
    val balances = balances().first()
    if (!balances.canSpend(sats)) {
      return Result.failure(IllegalArgumentException("Insufficient spendable balance."))
    }
    val cfg = settings.current()
    val now = System.currentTimeMillis()
    val unsigned = OfflinePromise(
      id = randomId(),
      sender = identityHex(),
      recipient = handshake.recipient,
      sats = sats,
      seq = cfg.outboundSeq + 1,
      createdAt = now,
      expiresAt = now + policy.maxAgeMs,
      prevHash = cfg.outboundHash,
      settleAddress = handshake.address,
      memo = memo.trim().take(120),
    )
    val signed = identity().signPromise(unsigned)
    return try {
      db.promises().insert(
        PromiseEntity(
          id = signed.id,
          direction = PromiseDirection.OUTBOUND.name,
          json = PromiseCodec.json.encodeToString(OfflinePromise.serializer(), signed),
          state = PromiseState.QUEUED.name,
          sats = signed.sats,
          counterparty = signed.recipient,
          seq = signed.seq,
          createdAt = signed.createdAt,
          updatedAt = now,
          recipientLightning = handshake.lightning,
        ),
      )
      settings.advanceOutboundChain(signed.seq, PromiseCodec.hashHexOf(signed))
      Result.success(signed)
    } catch (t: Exception) {
      Result.failure(t)
    }
  }

  fun forkProofs(): Flow<List<ForkProofEntity>> = db.forks().all()

  fun blockedSenders(): Flow<List<String>> = db.senderHeads().blockedSenders()

  suspend fun exportableForkGossip(): List<ForkGossip> {
    return db.forks().allOnce().mapNotNull { proof ->
      val a = db.promises().byId(proof.firstId) ?: return@mapNotNull null
      val b = db.promises().byId(proof.secondId) ?: return@mapNotNull null
      try {
        ForkGossip(
          first = PromiseCodec.json.decodeFromString(OfflinePromise.serializer(), a.json),
          second = PromiseCodec.json.decodeFromString(OfflinePromise.serializer(), b.json),
        )
      } catch (_: Exception) {
        null
      }
    }
  }

  suspend fun importForkGossip(gossip: ForkGossip): Boolean {
    if (!WalletIdentity.verifyPromise(gossip.first) || !WalletIdentity.verifyPromise(gossip.second)) {
      return false
    }
    val fork = ForkDetector.detect(gossip.first, gossip.second) ?: return false
    val now = System.currentTimeMillis()
    for (branch in listOf(gossip.first, gossip.second)) {
      try {
        db.promises().insert(
          PromiseEntity(
            id = branch.id,
            direction = PromiseDirection.INBOUND.name,
            json = PromiseCodec.json.encodeToString(OfflinePromise.serializer(), branch),
            state = PromiseState.FORKED.name,
            sats = branch.sats,
            counterparty = branch.sender,
            seq = branch.seq,
            createdAt = branch.createdAt,
            updatedAt = now,
          ),
        )
      } catch (_: SQLiteConstraintException) {
      }
    }
    db.forks().insert(
      ForkProofEntity(
        id = "${fork.sender}:${fork.seq}",
        sender = fork.sender,
        seq = fork.seq,
        firstId = fork.firstId,
        firstHash = fork.firstHash,
        secondId = fork.secondId,
        secondHash = fork.secondHash,
        detectedAt = now,
      ),
    )
    blockSender(fork.sender, gossip.first.seq, fork.firstHash)
    return true
  }

  suspend fun acceptInbound(promise: OfflinePromise): ValidationResult {
    val now = System.currentTimeMillis()
    val headRow = db.senderHeads().bySender(promise.sender.lowercase())
    val head = headRow?.let {
      SenderHead(it.sender, it.lastSeq, it.lastHash, it.outstandingSats, it.blocked)
    }
    val fork = checkFork(promise, now)
    if (fork != null) return fork
    val trustedCap = db.trusted().bySender(promise.sender.lowercase())?.maxSats
    val effectivePolicy = policy.copy(
      maxPerSenderSats = TrustCaps.effectivePerSenderCap(policy.maxPerSenderSats, trustedCap),
    )
    val globalPending = db.promises().inboundAcceptedSumOnce()
    val verdict = PromiseValidator.validateInbound(promise, head, globalPending, effectivePolicy, now)
    if (verdict is ValidationResult.Invalid) return verdict
    if (!promise.recipient.equals(identityHex(), ignoreCase = true)) {
      return ValidationResult.Invalid("NOT_FOR_ME", "Promise is addressed to another wallet.")
    }
    return try {
      db.promises().insert(
        PromiseEntity(
          id = promise.id,
          direction = PromiseDirection.INBOUND.name,
          json = PromiseCodec.json.encodeToString(OfflinePromise.serializer(), promise),
          state = PromiseState.ACCEPTED.name,
          sats = promise.sats,
          counterparty = promise.sender,
          seq = promise.seq,
          createdAt = promise.createdAt,
          updatedAt = now,
        ),
      )
      db.senderHeads().upsert(
        SenderHeadEntity(
          sender = promise.sender.lowercase(),
          lastSeq = promise.seq,
          lastHash = PromiseCodec.hashHexOf(promise),
          outstandingSats = (head?.outstandingSats ?: 0L) + promise.sats,
        ),
      )
      ValidationResult.Valid
    } catch (_: SQLiteConstraintException) {
      ValidationResult.Invalid("DUPLICATE", "Promise was already accepted.")
    }
  }

  fun trustedSenders(): Flow<List<TrustedSenderEntity>> = db.trusted().all()

  suspend fun isTrusted(sender: String): Boolean =
    db.trusted().isTrusted(sender.trim().lowercase()) != null

  suspend fun trustSender(sender: String, label: String, maxSats: Long = policy.maxPerSenderSats): Result<Unit> {
    val clean = sender.trim().lowercase()
    if (!PromiseCodec.isHexKey(clean, 33)) {
      return Result.failure(IllegalArgumentException("Not a valid wallet key."))
    }
    if (clean.equals(identityHex(), ignoreCase = true)) {
      return Result.failure(IllegalArgumentException("Cannot trust your own key."))
    }
    if (maxSats <= 0) {
      return Result.failure(IllegalArgumentException("Cap must be positive."))
    }
    db.trusted().upsert(TrustedSenderEntity(clean, label.trim().take(40), System.currentTimeMillis(), maxSats))
    return Result.success(Unit)
  }

  suspend fun setSenderCap(sender: String, maxSats: Long): Result<Unit> {
    if (maxSats <= 0) {
      return Result.failure(IllegalArgumentException("Cap must be positive."))
    }
    val clean = sender.trim().lowercase()
    val row = db.trusted().bySender(clean)
      ?: return Result.failure(IllegalArgumentException("Sender is not trusted."))
    db.trusted().upsert(row.copy(maxSats = maxSats))
    return Result.success(Unit)
  }

  suspend fun untrustSender(sender: String) {
    db.trusted().delete(sender.trim().lowercase())
  }

  private suspend fun checkFork(promise: OfflinePromise, now: Long): ValidationResult.Invalid? {
    if (promise.seq <= 0 || promise.id.isBlank()) return null
    val clash = db.promises().inboundBySenderSeq(promise.sender.lowercase(), promise.seq) ?: return null
    if (clash.id == promise.id) return null
    val existing = try {
      PromiseCodec.json.decodeFromString(OfflinePromise.serializer(), clash.json)
    } catch (_: Exception) {
      return null
    }
    val fork = ForkDetector.detect(existing, promise) ?: return null
    if (!WalletIdentity.verifyPromise(promise)) return null
    try {
      db.promises().insert(
        PromiseEntity(
          id = promise.id,
          direction = PromiseDirection.INBOUND.name,
          json = PromiseCodec.json.encodeToString(OfflinePromise.serializer(), promise),
          state = PromiseState.FORKED.name,
          sats = promise.sats,
          counterparty = promise.sender,
          seq = promise.seq,
          createdAt = promise.createdAt,
          updatedAt = now,
        ),
      )
    } catch (_: SQLiteConstraintException) {
    }
    db.forks().insert(
      ForkProofEntity(
        id = "${fork.sender}:${fork.seq}",
        sender = fork.sender,
        seq = fork.seq,
        firstId = fork.firstId,
        firstHash = fork.firstHash,
        secondId = fork.secondId,
        secondHash = fork.secondHash,
        detectedAt = now,
      ),
    )
    blockSender(fork.sender, existing.seq, fork.firstHash)
    return ValidationResult.Invalid("FORK_DETECTED", "Sender double-spent seq ${fork.seq} and is now blocked.")
  }

  private suspend fun blockSender(sender: String, seq: Long, hash: String) {
    val headRow = db.senderHeads().bySender(sender)
    if (headRow != null) {
      db.senderHeads().upsert(headRow.copy(blocked = true))
    } else {
      db.senderHeads().upsert(SenderHeadEntity(sender, seq, hash, 0L, blocked = true))
    }
    db.trusted().delete(sender)
  }

  suspend fun storeRecipientInvoice(promiseId: String, invoice: String) {
    db.promises().storeInvoice(promiseId, invoice.trim(), System.currentTimeMillis())
  }

  suspend fun promiseById(promiseId: String): PromiseEntity? = db.promises().byId(promiseId)

  suspend fun importReceipt(receipt: SettlementReceipt): Boolean {
    val row = db.promises().byId(receipt.promiseId) ?: return false
    if (row.direction != PromiseDirection.INBOUND.name || row.state != PromiseState.ACCEPTED.name) {
      return false
    }
    if (row.sats != receipt.sats || receipt.paymentHash.isBlank()) return false
    db.promises().updateState(
      row.id,
      PromiseState.RECEIPT_CONFIRMED.name,
      System.currentTimeMillis(),
      receipt.paymentHash + "|" + receipt.preimage,
    )
    return true
  }

  suspend fun exportableReceipts(): List<SettlementReceipt> {
    return db.promises().settledWithReceipts().mapNotNull { row ->
      val parts = row.settleRef.split("|")
      if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
      SettlementReceipt(
        promiseId = row.id,
        paymentHash = parts[0],
        preimage = parts.getOrElse(1) { "" },
        sats = row.sats,
        settledAt = row.updatedAt,
      )
    }
  }

  suspend fun createDeposit(sats: Long, memo: String): Result<DepositView> {
    val cfg = settings.current()
    if (!cfg.hasNode()) {
      return Result.failure(IllegalStateException("Configure your Lightning node in Settings first."))
    }
    if (sats <= 0) return Result.failure(IllegalArgumentException("Amount must be positive."))
    return try {
      val api = apiFor(cfg.backendUrl)
      val res = api.createInvoice(
        CreateInvoiceRequest(
          satsAmount = sats,
          memo = memo.trim().take(120).ifBlank { "YeBente Deposit" },
          customNodeType = cfg.nodeType,
          customNodeUrl = cfg.nodeUrl,
          customNodeKey = settings.nodeKey(),
        ),
      )
      if (!res.success || res.invoice.isBlank() || res.paymentHash.isBlank()) {
        return Result.failure(IllegalStateException(res.error.ifBlank { "Gateway gave no invoice." }))
      }
      db.deposits().insert(
        DepositEntity(
          paymentHash = res.paymentHash,
          invoice = res.invoice,
          sats = sats,
          state = "PENDING",
          createdAt = System.currentTimeMillis(),
          updatedAt = System.currentTimeMillis(),
        ),
      )
      Result.success(DepositView(res.invoice, res.paymentHash, sats))
    } catch (t: Exception) {
      Result.failure(t)
    }
  }

  override suspend fun pollOpenDeposits(): Int {
    val cfg = settings.current()
    if (!cfg.hasNode()) return 0
    val api = apiFor(cfg.backendUrl)
    var credited = 0
    for (deposit in db.deposits().openDeposits()) {
      val status = try {
        api.invoiceStatus(
          deposit.paymentHash,
          cfg.nodeType,
          cfg.nodeUrl,
          settings.nodeKey(),
        )
      } catch (_: Exception) {
        continue
      }
      if (status.success && (status.settled || status.paid)) {
        val amount = if (status.satsAmount > 0) status.satsAmount else deposit.sats
        db.deposits().markSettled(deposit.paymentHash, System.currentTimeMillis())
        settings.adjustAnchor(amount)
        credited++
      }
    }
    return credited
  }

  suspend fun pruneExpired(now: Long = System.currentTimeMillis()) {
    val candidates = db.promises().unsettledOutbound() +
      db.promises().recent(1000).first().filter { it.state == PromiseState.ACCEPTED.name }
    val expired = candidates.filter { row ->
      try {
        val promise = PromiseCodec.json.decodeFromString(OfflinePromise.serializer(), row.json)
        promise.expiresAt <= now
      } catch (_: Exception) {
        false
      }
    }
    if (expired.isEmpty()) return
    db.promises().markExpired(expired.map { it.id }, now)
    expired.filter { it.direction == PromiseDirection.INBOUND.name }.forEach { row ->
      val head = db.senderHeads().bySender(row.counterparty.lowercase()) ?: return@forEach
      db.senderHeads().upsert(head.copy(outstandingSats = maxOf(0L, head.outstandingSats - row.sats)))
    }
  }

  suspend fun wipe() {
    db.promises().clear()
    db.senderHeads().clear()
    db.deposits().clear()
    db.trusted().clear()
    db.forks().clear()
    settings.wipeSecrets()
    seedStore.wipe()
  }

  override suspend fun queuedOutbound(): List<OutboundJob> {
    return db.promises().queuedOutbound().map {
      OutboundJob(it.id, it.sats, it.storedInvoice, it.recipientLightning)
    }
  }

  override suspend fun jobById(promiseId: String): OutboundJob? {
    val row = db.promises().byId(promiseId) ?: return null
    if (row.direction != PromiseDirection.OUTBOUND.name) return null
    if (row.state != PromiseState.QUEUED.name && row.state != PromiseState.SETTLING.name) return null
    return OutboundJob(row.id, row.sats, row.storedInvoice, row.recipientLightning)
  }

  override suspend fun markSettling(promiseId: String) {
    val row = db.promises().byId(promiseId) ?: return
    db.promises().updateState(promiseId, PromiseState.SETTLING.name, System.currentTimeMillis(), row.settleRef)
  }

  override suspend fun markSettled(promiseId: String, paymentHash: String, preimage: String, feeSats: Long) {
    db.promises().updateState(
      promiseId,
      PromiseState.SETTLED.name,
      System.currentTimeMillis(),
      paymentHash + "|" + preimage,
    )
  }

  override suspend fun markFailed(promiseId: String, error: String) {
    db.promises().markFailed(promiseId, error.take(300), System.currentTimeMillis())
  }

  override suspend fun requeue(promiseId: String) {
    db.promises().requeue(promiseId, System.currentTimeMillis())
  }

  override suspend fun openDeposits(): List<DepositJob> {
    return db.deposits().openDeposits().map { DepositJob(it.paymentHash, it.sats) }
  }

  override suspend fun markDepositSettled(paymentHash: String, creditedSats: Long) {
    db.deposits().markSettled(paymentHash, System.currentTimeMillis())
    settings.adjustAnchor(creditedSats)
  }

  override suspend fun adjustAnchor(deltaSats: Long) {
    settings.adjustAnchor(deltaSats)
  }

  private fun randomId(): String {
    val bytes = ByteArray(8).also { random.nextBytes(it) }
    return bytes.toHex()
  }
}
