package com.yebente.wallet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [PromiseEntity::class, SenderHeadEntity::class, DepositEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
  abstract fun promises(): PromiseDao
  abstract fun senderHeads(): SenderHeadDao
  abstract fun deposits(): DepositDao

  companion object {
    fun build(context: Context): WalletDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        WalletDatabase::class.java,
        "yebente-wallet.db",
      ).build()
    }
  }
}
