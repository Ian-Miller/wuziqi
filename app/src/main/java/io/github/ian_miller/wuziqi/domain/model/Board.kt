package io.github.ian_miller.wuziqi.domain.model

/**
 * 棋盘，15×15 网格
 */
class Board private constructor(
    private val grid: Array<Array<PieceColor?>>
) {
    companion object {
        const val SIZE = 15

        fun empty(): Board {
            val grid = Array(SIZE) { arrayOfNulls<PieceColor?>(SIZE) }
            return Board(grid)
        }
    }

    init {
        require(grid.size == SIZE && grid.all { it.size == SIZE }) {
            "棋盘必须是 $SIZE×$SIZE 大小"
        }
    }

    /**
     * 获取指定位置的棋子颜色，如果为空则返回 null
     */
    fun getPiece(row: Int, col: Int): PieceColor? {
        checkBounds(row, col)
        return grid[row][col]
    }

    /**
     * 放置一个棋子
     * @return 放置后的新棋盘（不可变）
     */
    fun placePiece(row: Int, col: Int, color: PieceColor): Board {
        checkBounds(row, col)
        require(grid[row][col] == null) { "该位置已有棋子" }

        val newGrid = grid.map { it.clone() }.toTypedArray()
        newGrid[row][col] = color
        return Board(newGrid)
    }

    /**
     * 移除一个棋子（用于撤销）
     */
    fun removePiece(row: Int, col: Int): Board {
        checkBounds(row, col)
        require(grid[row][col] != null) { "该位置没有棋子" }

        val newGrid = grid.map { it.clone() }.toTypedArray()
        newGrid[row][col] = null
        return Board(newGrid)
    }

    /**
     * 判断棋盘是否已满（没有空位）
     */
    fun isFull(): Boolean = grid.all { row -> row.all { it != null } }

    /**
     * 获取所有棋子位置
     */
    fun getAllPieces(): List<Piece> = buildList {
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val color = grid[row][col]
                if (color != null) {
                    add(Piece(row, col, color))
                }
            }
        }
    }

    /**
     * 复制棋盘
     */
    fun copy(): Board {
        val newGrid = grid.map { it.clone() }.toTypedArray()
        return Board(newGrid)
    }

    /**
     * 将棋盘序列化为 225 字节数组供 Rust AI 使用
     * 0 = 空, 1 = 黑棋, 2 = 白棋，行优先顺序
     */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(SIZE * SIZE)
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                bytes[row * SIZE + col] = when (grid[row][col]) {
                    PieceColor.BLACK -> 1
                    PieceColor.WHITE -> 2
                    null -> 0
                }
            }
        }
        return bytes
    }

    private fun checkBounds(row: Int, col: Int) {
        require(row in 0 until SIZE && col in 0 until SIZE) {
            "坐标 ($row, $col) 超出棋盘范围"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Board
        return grid.contentDeepEquals(other.grid)
    }

    override fun hashCode(): Int = grid.contentDeepHashCode()
}