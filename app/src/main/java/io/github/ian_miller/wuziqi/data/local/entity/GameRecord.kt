package io.github.ian_miller.wuziqi.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.PieceColor

@Entity(
    tableName = "game_records",
    foreignKeys = [
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["opponentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["playerId"]),
        androidx.room.Index(value = ["opponentId"])
    ]
)
data class GameRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playerId: Long,
    val opponentId: Long?,
    val gameMode: GameMode,
    val difficulty: Difficulty? = null, // Added difficulty
    val result: GameResult,
    val boardSnapshot: String, // 棋盘状态的序列化字符串（如 JSON）
    val startTime: Long,
    val endTime: Long,
    val moves: Int
)

enum class GameResult {
    WIN, LOSE, DRAW
}