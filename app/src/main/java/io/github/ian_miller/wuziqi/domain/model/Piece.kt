package io.github.ian_miller.wuziqi.domain.model

/**
 * 棋子颜色
 */
enum class PieceColor {
    BLACK,
    WHITE;

    fun opposite(): PieceColor = when (this) {
        BLACK -> WHITE
        WHITE -> BLACK
    }
}

/**
 * 棋盘上的一个棋子
 */
data class Piece(
    val row: Int,
    val col: Int,
    val color: PieceColor
)