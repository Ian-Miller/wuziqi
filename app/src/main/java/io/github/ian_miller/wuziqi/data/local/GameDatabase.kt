package io.github.ian_miller.wuziqi.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import io.github.ian_miller.wuziqi.data.local.dao.GameRecordDao
import io.github.ian_miller.wuziqi.data.local.dao.PlayerDao
import io.github.ian_miller.wuziqi.data.local.entity.GameRecord
import io.github.ian_miller.wuziqi.data.local.entity.Player

@Database(
    entities = [Player::class, GameRecord::class],
    version = 5,  // 增加版本号：添加 MASTER 难度支持
    exportSchema = false
)
abstract class GameDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun gameRecordDao(): GameRecordDao

    companion object {
        @Volatile
        private var INSTANCE: GameDatabase? = null

        fun getInstance(context: Context): GameDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GameDatabase::class.java,
                    "gomoku_database"
                )
                    .fallbackToDestructiveMigration(true) // 游戏记录丢失可接受，自动重建
                    .allowMainThreadQueries()
                    .build().also { INSTANCE = it }
            }
        }
    }
}