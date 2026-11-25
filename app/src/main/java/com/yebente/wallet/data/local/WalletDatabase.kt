package com.yebente.wallet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [PromiseEntity::class, SenderHeadEntity::class, DepositEntity::class, TrustedSenderEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
  abstract fun promises(): PromiseDao
  abstract fun senderHeads(): SenderHeadDao
  abstract fun deposits(): DepositDao
  abstract fun trusted(): TrustedSenderDao

  companion object {
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trusted_senders (sender TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, addedAt INTEGER NOT NULL)")
      }
    }

    fun build(context: Context): WalletDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        WalletDatabase::class.java,
        "yebente-wallet.db",
      ).addMigrations(MIGRATION_1_2).build()
    }
  }
}
