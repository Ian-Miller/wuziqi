package io.github.ian_miller.wuziqi.data.local.dao

import androidx.room.*
import io.github.ian_miller.wuziqi.data.local.entity.GameRecord
import io.github.ian_miller.wuziqi.data.local.entity.GameResult
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import kotlinx.coroutines.flow.Flow

@Dao
interface GameRecordDao {
    @Query("SELECT * FROM game_records ORDER BY endTime DESC")
    fun getAllRecords(): Flow<List<GameRecord>>

    @Query("SELECT * FROM game_records WHERE playerId = :playerId ORDER BY endTime DESC")
    fun getRecordsByPlayer(playerId: Long): Flow<List<GameRecord>>

    @Query("SELECT * FROM game_records WHERE id = :id")
    suspend fun getRecordById(id: Long): GameRecord?

    @Insert
    suspend fun insertRecord(record: GameRecord): Long

    @Update
    suspend fun updateRecord(record: GameRecord)

    @Delete
    suspend fun deleteRecord(record: GameRecord)

    @Query("DELETE FROM game_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("SELECT COUNT(*) FROM game_records WHERE playerId = :playerId")
    suspend fun countByPlayer(playerId: Long): Int

    @Query("SELECT COUNT(*) FROM game_records WHERE playerId = :playerId AND result = :result")
    suspend fun countResults(playerId: Long, result: GameResult): Int

    @Query("""
        SELECT COUNT(*) FROM game_records 
        WHERE playerId = :playerId 
        AND gameMode = :gameMode
    """)
    suspend fun countGamesByMode(playerId: Long, gameMode: GameMode): Int

    @Query("""
        SELECT COUNT(*) FROM game_records 
        WHERE playerId = :playerId 
        AND gameMode = :gameMode
        AND result = :result
    """)
    suspend fun countResultsByMode(playerId: Long, gameMode: GameMode, result: GameResult): Int

    @Query("""
        SELECT COUNT(*) FROM game_records 
        WHERE playerId = :playerId 
        AND gameMode = :gameMode
        AND difficulty = :difficulty
    """)
    suspend fun countGamesByModeAndDifficulty(playerId: Long, gameMode: GameMode, difficulty: Difficulty): Int

    @Query("""
        SELECT COUNT(*) FROM game_records 
        WHERE playerId = :playerId 
        AND gameMode = :gameMode
        AND difficulty = :difficulty
        AND result = :result
    """)
    suspend fun countResultsByModeAndDifficulty(playerId: Long, gameMode: GameMode, difficulty: Difficulty, result: GameResult): Int
}