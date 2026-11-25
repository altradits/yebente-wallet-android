package com.yebente.wallet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [PromiseEntity::class, SenderHeadEntity::class, DepositEntity::class, TrustedSenderEntity::class, ForkProofEntity::class, OutboundHeadEntity::class],
  version = 5,
  exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
  abstract fun promises(): PromiseDao
  abstract fun senderHeads(): SenderHeadDao
  abstract fun deposits(): DepositDao
  abstract fun trusted(): TrustedSenderDao
  abstract fun forks(): ForkProofDao
  abstract fun outboundHeads(): OutboundHeadDao

  companion object {
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trusted_senders (sender TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, addedAt INTEGER NOT NULL)")
      }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fork_proofs (id TEXT NOT NULL PRIMARY KEY, sender TEXT NOT NULL, seq INTEGER NOT NULL, firstId TEXT NOT NULL, firstHash TEXT NOT NULL, secondId TEXT NOT NULL, secondHash TEXT NOT NULL, detectedAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE sender_heads ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trusted_senders ADD COLUMN maxSats INTEGER NOT NULL DEFAULT 200000")
      }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS outbound_heads (recipient TEXT NOT NULL PRIMARY KEY, lastSeq INTEGER NOT NULL, lastHash TEXT NOT NULL)")
      }
    }

    fun build(context: Context): WalletDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        WalletDatabase::class.java,
        "yebente-wallet.db",
      ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
  }
}
