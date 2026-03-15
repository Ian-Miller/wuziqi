package io.github.ian_miller.wuziqi.data.repository

import io.github.ian_miller.wuziqi.data.local.GameDatabase
import io.github.ian_miller.wuziqi.data.local.entity.GameRecord as GameRecordEntity
import io.github.ian_miller.wuziqi.data.local.entity.Player as PlayerEntity
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.repository.GameRecord
import io.github.ian_miller.wuziqi.domain.repository.GameRepository
import io.github.ian_miller.wuziqi.domain.repository.GameResult
import io.github.ian_miller.wuziqi.domain.repository.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GameRepositoryImpl @Inject constructor(
    private val database: GameDatabase
) : GameRepository {
    private val playerDao = database.playerDao()
    private val gameRecordDao = database.gameRecordDao()

    override suspend fun createPlayer(name: String): Long {
        val player = PlayerEntity(name = name)
        return playerDao.insertPlayer(player)
    }

    override suspend fun updatePlayer(player: Player) {
        val entity = PlayerEntity(
            id = player.id,
            name = player.name,
            avatarUri = player.avatarUri,
            createdAt = player.createdAt
        )
        playerDao.updatePlayer(entity)
    }

    override suspend fun getPlayer(id: Long): Player? {
        return playerDao.getPlayerById(id)?.toDomain()
    }

    override suspend fun getAllPlayers(): List<Player> {
        return playerDao.getAllPlayersList().map { it.toDomain() }
    }

    override fun observeAllPlayers(): Flow<List<Player>> {
        return playerDao.getAllPlayers().map { list -> list.map { it.toDomain() } }
    }

    override fun observePlayersSortedByGames(mode: GameMode): Flow<List<Player>> {
        return playerDao.getPlayersSortedByGames(mode).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun saveGameRecord(
        playerId: Long,
        opponentId: Long?,
        gameMode: GameMode,
        difficulty: Difficulty?,
        result: GameResult,
        boardSnapshot: String,
        moves: Int
    ): Long {
        val record = GameRecordEntity(
            playerId = playerId,
            opponentId = opponentId,
            gameMode = gameMode,
            difficulty = difficulty,
            result = when (result) {
                GameResult.WIN -> io.github.ian_miller.wuziqi.data.local.entity.GameResult.WIN
                GameResult.LOSE -> io.github.ian_miller.wuziqi.data.local.entity.GameResult.LOSE
                GameResult.DRAW -> io.github.ian_miller.wuziqi.data.local.entity.GameResult.DRAW
            },
            boardSnapshot = boardSnapshot,
            startTime = System.currentTimeMillis() - 1000 * 60 * moves, // 估算开始时间
            endTime = System.currentTimeMillis(),
            moves = moves
        )
        return gameRecordDao.insertRecord(record)
    }

    override suspend fun getGameRecordsByPlayer(playerId: Long): List<GameRecord> {
        // 临时实现
        return emptyList()
    }

    override fun observeGameRecordsByPlayer(playerId: Long): Flow<List<GameRecord>> {
        return gameRecordDao.getRecordsByPlayer(playerId)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getWinRate(playerId: Long, gameMode: GameMode?, difficulty: Difficulty?): Double {
        val total = getTotalGames(playerId, gameMode, difficulty)
        if (total == 0) return 0.0
        val wins = getWins(playerId, gameMode, difficulty)
        return wins.toDouble() / total
    }

    override suspend fun getTotalGames(playerId: Long, gameMode: GameMode?, difficulty: Difficulty?): Int {
        return if (gameMode == null) {
            gameRecordDao.countByPlayer(playerId)
        } else if (difficulty == null) {
            gameRecordDao.countGamesByMode(playerId, gameMode)
        } else {
            gameRecordDao.countGamesByModeAndDifficulty(playerId, gameMode, difficulty)
        }
    }

    override suspend fun getWins(playerId: Long, gameMode: GameMode?, difficulty: Difficulty?): Int {
        val resultWin = io.github.ian_miller.wuziqi.data.local.entity.GameResult.WIN
        return if (gameMode == null) {
            gameRecordDao.countResults(playerId, resultWin)
        } else if (difficulty == null) {
            gameRecordDao.countResultsByMode(playerId, gameMode, resultWin)
        } else {
            gameRecordDao.countResultsByModeAndDifficulty(playerId, gameMode, difficulty, resultWin)
        }
    }

    override suspend fun getLosses(playerId: Long, gameMode: GameMode?, difficulty: Difficulty?): Int {
        val resultLose = io.github.ian_miller.wuziqi.data.local.entity.GameResult.LOSE
        return if (gameMode == null) {
            gameRecordDao.countResults(playerId, resultLose)
        } else if (difficulty == null) {
            gameRecordDao.countResultsByMode(playerId, gameMode, resultLose)
        } else {
            gameRecordDao.countResultsByModeAndDifficulty(playerId, gameMode, difficulty, resultLose)
        }
    }

    private fun PlayerEntity.toDomain(): Player = Player(
        id = id,
        name = name,
        avatarUri = avatarUri,
        createdAt = createdAt
    )

    private fun GameRecordEntity.toDomain(): GameRecord = GameRecord(
        id = id,
        playerId = playerId,
        opponentId = opponentId,
        gameMode = gameMode,
        difficulty = difficulty,
        result = when (result) {
            io.github.ian_miller.wuziqi.data.local.entity.GameResult.WIN -> GameResult.WIN
            io.github.ian_miller.wuziqi.data.local.entity.GameResult.LOSE -> GameResult.LOSE
            io.github.ian_miller.wuziqi.data.local.entity.GameResult.DRAW -> GameResult.DRAW
        },
        boardSnapshot = boardSnapshot,
        startTime = startTime,
        endTime = endTime,
        moves = moves
    )
}