package com.yebente.wallet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PromiseDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(promise: PromiseEntity)

  @Query("SELECT * FROM promises WHERE id = :id LIMIT 1")
  suspend fun byId(id: String): PromiseEntity?

  @Query("SELECT * FROM promises WHERE direction = :direction ORDER BY createdAt DESC")
  fun byDirection(direction: String): Flow<List<PromiseEntity>>

  @Query("SELECT * FROM promises ORDER BY createdAt DESC LIMIT :limit")
  fun recent(limit: Int): Flow<List<PromiseEntity>>

  @Query("SELECT * FROM promises WHERE direction = 'OUTBOUND' AND state = 'QUEUED' ORDER BY createdAt ASC")
  suspend fun queuedOutbound(): List<PromiseEntity>

  @Query("SELECT * FROM promises WHERE direction = 'OUTBOUND' AND state IN ('QUEUED','SETTLING') ORDER BY createdAt ASC")
  suspend fun unsettledOutbound(): List<PromiseEntity>

  @Query("SELECT COALESCE(SUM(sats),0) FROM promises WHERE direction = 'INBOUND' AND state = 'ACCEPTED'")
  fun inboundAcceptedSum(): Flow<Long>

  @Query("SELECT COALESCE(SUM(sats),0) FROM promises WHERE direction = 'INBOUND' AND state = 'ACCEPTED'")
  suspend fun inboundAcceptedSumOnce(): Long

  @Query("SELECT COALESCE(SUM(p.sats),0) FROM promises p WHERE p.direction = 'INBOUND' AND p.state = 'ACCEPTED' AND lower(p.counterparty) IN (SELECT sender FROM trusted_senders)")
  fun trustedInboundSum(): Flow<Long>

  @Query("SELECT COALESCE(SUM(p.sats),0) FROM promises p WHERE p.direction = 'INBOUND' AND p.state = 'ACCEPTED' AND lower(p.counterparty) IN (SELECT sender FROM trusted_senders)")
  suspend fun trustedInboundSumOnce(): Long

  @Query("SELECT COALESCE(SUM(sats),0) FROM promises WHERE direction = 'OUTBOUND' AND state IN ('QUEUED','SETTLING')")
  fun outboundPendingSum(): Flow<Long>

  @Query("SELECT COUNT(*) FROM promises WHERE direction = 'OUTBOUND' AND state IN ('QUEUED','SETTLING')")
  fun outboundPendingCount(): Flow<Int>

  @Query("UPDATE promises SET state = :state, updatedAt = :now, error = '', settleRef = :settleRef WHERE id = :id")
  suspend fun updateState(id: String, state: String, now: Long, settleRef: String)

  @Query("UPDATE promises SET state = 'FAILED', updatedAt = :now, error = :error WHERE id = :id")
  suspend fun markFailed(id: String, error: String, now: Long)

  @Query("UPDATE promises SET state = 'QUEUED', updatedAt = :now WHERE id = :id AND state = 'SETTLING'")
  suspend fun requeue(id: String, now: Long)

  @Query("UPDATE promises SET storedInvoice = :invoice, updatedAt = :now WHERE id = :id")
  suspend fun storeInvoice(id: String, invoice: String, now: Long)

  @Query("UPDATE promises SET state = 'EXPIRED', updatedAt = :now WHERE state IN ('QUEUED','ACCEPTED') AND id IN (:ids)")
  suspend fun markExpired(ids: List<String>, now: Long)

  @Query("SELECT * FROM promises WHERE direction = 'INBOUND' AND lower(counterparty) = :sender AND seq = :seq LIMIT 1")
  suspend fun inboundBySenderSeq(sender: String, seq: Long): PromiseEntity?

  @Query("SELECT * FROM promises WHERE direction = 'OUTBOUND' AND state = 'SETTLED' AND settleRef != ''")
  suspend fun settledWithReceipts(): List<PromiseEntity>

  @Query("DELETE FROM promises")
  suspend fun clear()
}

@Dao
interface SenderHeadDao {
  @Query("SELECT * FROM sender_heads WHERE sender = :sender LIMIT 1")
  suspend fun bySender(sender: String): SenderHeadEntity?

  @Query("SELECT sender FROM sender_heads WHERE blocked = 1")
  fun blockedSenders(): Flow<List<String>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(head: SenderHeadEntity)

  @Query("DELETE FROM sender_heads")
  suspend fun clear()
}

@Dao
interface ForkProofDao {
  @Query("SELECT * FROM fork_proofs ORDER BY detectedAt DESC")
  fun all(): Flow<List<ForkProofEntity>>

  @Query("SELECT * FROM fork_proofs ORDER BY detectedAt DESC")
  suspend fun allOnce(): List<ForkProofEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(proof: ForkProofEntity)

  @Query("DELETE FROM fork_proofs")
  suspend fun clear()
}

@Dao
interface TrustedSenderDao {
  @Query("SELECT * FROM trusted_senders ORDER BY addedAt DESC")
  fun all(): Flow<List<TrustedSenderEntity>>

  @Query("SELECT sender FROM trusted_senders WHERE sender = :sender LIMIT 1")
  suspend fun isTrusted(sender: String): String?

  @Query("SELECT * FROM trusted_senders WHERE sender = :sender LIMIT 1")
  suspend fun bySender(sender: String): TrustedSenderEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(sender: TrustedSenderEntity)

  @Query("DELETE FROM trusted_senders WHERE sender = :sender")
  suspend fun delete(sender: String)

  @Query("DELETE FROM trusted_senders")
  suspend fun clear()
}

@Dao
interface DepositDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(deposit: DepositEntity)

  @Query("SELECT * FROM deposits WHERE state = 'PENDING' ORDER BY createdAt ASC")
  suspend fun openDeposits(): List<DepositEntity>

  @Query("SELECT * FROM deposits ORDER BY createdAt DESC")
  fun all(): Flow<List<DepositEntity>>

  @Query("UPDATE deposits SET state = 'SETTLED', updatedAt = :now WHERE paymentHash = :paymentHash")
  suspend fun markSettled(paymentHash: String, now: Long)

  @Query("DELETE FROM deposits")
  suspend fun clear()
}
