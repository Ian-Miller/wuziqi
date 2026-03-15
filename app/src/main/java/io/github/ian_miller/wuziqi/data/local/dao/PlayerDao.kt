package io.github.ian_miller.wuziqi.data.local.dao

import androidx.room.*
import io.github.ian_miller.wuziqi.data.local.entity.Player
import io.github.ian_miller.wuziqi.domain.model.GameMode
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players ORDER BY name")
    fun getAllPlayers(): Flow<List<Player>>

    @Query("""
        SELECT p.*, COUNT(g.id) as gameCount 
        FROM players p 
        LEFT JOIN game_records g ON p.id = g.playerId AND g.gameMode = :mode 
        GROUP BY p.id 
        ORDER BY gameCount DESC, p.createdAt DESC
    """)
    fun getPlayersSortedByGames(mode: GameMode): Flow<List<Player>>

    @Query("SELECT * FROM players ORDER BY name")
    suspend fun getAllPlayersList(): List<Player>

    @Query("SELECT * FROM players WHERE id = :id")
    suspend fun getPlayerById(id: Long): Player?

    @Insert
    suspend fun insertPlayer(player: Player): Long

    @Update
    suspend fun updatePlayer(player: Player)

    @Delete
    suspend fun deletePlayer(player: Player)

    @Query("SELECT COUNT(*) FROM players")
    suspend fun countPlayers(): Int
}