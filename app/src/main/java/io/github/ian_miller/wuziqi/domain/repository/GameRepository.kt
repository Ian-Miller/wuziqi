package io.github.ian_miller.wuziqi.domain.repository

import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlinx.coroutines.flow.Flow

/**
 * 游戏数据仓库接口
 */
interface GameRepository {
    // 玩家相关
    suspend fun createPlayer(name: String): Long
    suspend fun updatePlayer(player: Player)
    suspend fun getPlayer(id: Long): Player?
    suspend fun getAllPlayers(): List<Player>
    fun observeAllPlayers(): Flow<List<Player>>
    fun observePlayersSortedByGames(mode: GameMode): Flow<List<Player>>

    // 游戏记录相关
    suspend fun saveGameRecord(
        playerId: Long,
        opponentId: Long?,
        gameMode: GameMode,
        difficulty: Difficulty? = null,
        result: GameResult,
        boardSnapshot: String,
        moves: Int
    ): Long

    suspend fun getGameRecordsByPlayer(playerId: Long): List<GameRecord>
    fun observeGameRecordsByPlayer(playerId: Long): Flow<List<GameRecord>>

    // 胜率统计
    suspend fun getWinRate(playerId: Long, gameMode: GameMode? = null, difficulty: Difficulty? = null): Double
    suspend fun getTotalGames(playerId: Long, gameMode: GameMode? = null, difficulty: Difficulty? = null): Int
    suspend fun getWins(playerId: Long, gameMode: GameMode? = null, difficulty: Difficulty? = null): Int
    suspend fun getLosses(playerId: Long, gameMode: GameMode? = null, difficulty: Difficulty? = null): Int
}

// 为简化，定义本地数据类（与实体类似但属于领域层）
data class Player(
    val id: Long,
    val name: String,
    val avatarUri: String? = null,
    val createdAt: Long
)

data class GameRecord(
    val id: Long,
    val playerId: Long,
    val opponentId: Long?,
    val gameMode: GameMode,
    val difficulty: Difficulty? = null,
    val result: GameResult,
    val boardSnapshot: String,
    val startTime: Long,
    val endTime: Long,
    val moves: Int
)

enum class GameResult {
    WIN, LOSE, DRAW
}