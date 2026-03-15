package io.github.ian_miller.wuziqi.domain.model

/**
 * 游戏状态结果
 */
sealed class GameResult {
    object Ongoing : GameResult()
    data class Win(val winner: PieceColor) : GameResult()
    object Draw : GameResult()
}

/**
 * 游戏状态，包含棋盘、当前玩家、结果等信息
 */
data class GameState(
    val board: Board,
    val currentPlayer: PieceColor,
    val result: GameResult = GameResult.Ongoing,
    val lastMove: Piece? = null
) {
    companion object {
        fun initial(): GameState = GameState(
            board = Board.empty(),
            currentPlayer = PieceColor.BLACK
        )
    }

    /**
     * 执行落子，返回新的游戏状态
     */
    fun placePiece(row: Int, col: Int): GameState {
        require(result is GameResult.Ongoing) { "游戏已结束" }

        val newBoard = board.placePiece(row, col, currentPlayer)
        val newLastMove = Piece(row, col, currentPlayer)
        val newResult = evaluateResult(newBoard, newLastMove)
        val nextPlayer = if (newResult is GameResult.Ongoing) currentPlayer.opposite() else currentPlayer

        return copy(
            board = newBoard,
            currentPlayer = nextPlayer,
            result = newResult,
            lastMove = newLastMove
        )
    }

    /**
     * 撤销上一步落子
     */
    fun undoLastMove(): GameState? {
        val last = lastMove ?: return null
        val newBoard = board.removePiece(last.row, last.col)
        val previousPlayer = last.color.opposite()
        return copy(
            board = newBoard,
            currentPlayer = previousPlayer,
            result = GameResult.Ongoing,
            lastMove = null // 如果需要支持多次撤销，这里需要更复杂的处理
        )
    }

    /**
     * 检查游戏是否结束
     */
    fun isGameOver(): Boolean = result != GameResult.Ongoing

    /**
     * 获取获胜者颜色（如果游戏未结束则返回 null）
     */
    fun winner(): PieceColor? = when (result) {
        is GameResult.Win -> result.winner
        else -> null
    }

    /**
     * 评估棋盘是否有五子连珠
     */
    private fun evaluateResult(board: Board, lastMove: Piece): GameResult {
        // 检查横向
        var count = 1
        // 向左
        var c = lastMove.col - 1
        while (c >= 0 && board.getPiece(lastMove.row, c) == lastMove.color) {
            count++
            c--
        }
        // 向右
        c = lastMove.col + 1
        while (c < Board.SIZE && board.getPiece(lastMove.row, c) == lastMove.color) {
            count++
            c++
        }
        if (count >= 5) return GameResult.Win(lastMove.color)

        // 检查纵向
        count = 1
        var r = lastMove.row - 1
        while (r >= 0 && board.getPiece(r, lastMove.col) == lastMove.color) {
            count++
            r--
        }
        r = lastMove.row + 1
        while (r < Board.SIZE && board.getPiece(r, lastMove.col) == lastMove.color) {
            count++
            r++
        }
        if (count >= 5) return GameResult.Win(lastMove.color)

        // 检查左上-右下对角线
        count = 1
        r = lastMove.row - 1
        c = lastMove.col - 1
        while (r >= 0 && c >= 0 && board.getPiece(r, c) == lastMove.color) {
            count++
            r--
            c--
        }
        r = lastMove.row + 1
        c = lastMove.col + 1
        while (r < Board.SIZE && c < Board.SIZE && board.getPiece(r, c) == lastMove.color) {
            count++
            r++
            c++
        }
        if (count >= 5) return GameResult.Win(lastMove.color)

        // 检查右上-左下对角线
        count = 1
        r = lastMove.row - 1
        c = lastMove.col + 1
        while (r >= 0 && c < Board.SIZE && board.getPiece(r, c) == lastMove.color) {
            count++
            r--
            c++
        }
        r = lastMove.row + 1
        c = lastMove.col - 1
        while (r < Board.SIZE && c >= 0 && board.getPiece(r, c) == lastMove.color) {
            count++
            r++
            c--
        }
        if (count >= 5) return GameResult.Win(lastMove.color)

        // 检查平局
        if (board.isFull()) return GameResult.Draw

        return GameResult.Ongoing
    }
}