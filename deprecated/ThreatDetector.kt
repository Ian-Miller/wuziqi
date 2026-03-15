package io.github.ian_miller.wuziqi.ai

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.math.abs

/**
 * 局势判断器：识别必胜/必输局面，提供紧急的落子建议。
 * 增强版：支持冲四检测、双三检测、更精准的威胁识别
 */
object ThreatDetector {

    /**
     * 寻找当前最紧急的落子位置。
     * 优先级：
     * 1. 自己连五（必胜）
     * 2. 对手连五（必防）
     * 3. 自己活四（必胜）
     * 4. 对手活四（必防）
     * 5. 自己冲四（下一步活四）
     * 6. 对手双活三（必防其一）
     * 7. 对手冲四（必防）
     * 8. 自己双活三（创造必杀局面）
     */
    fun findUrgentMove(board: Board, player: PieceColor): Pair<Int, Int>? {
        val opponent = player.opposite()

        // 1. 必胜：己方连五
        findAllFiveMoves(board, player).firstOrNull()?.let { return it }

        // 2. 必防：对手连五
        findAllFiveMoves(board, opponent).firstOrNull()?.let { return it }

        // 3. 必胜：己方活四
        findOpenFourMoves(board, player).firstOrNull()?.let { return it }

        // 4. 必防：对手活四
        val opOpenFourMoves = findOpenFourMoves(board, opponent)
        if (opOpenFourMoves.isNotEmpty()) {
            return selectBestMove(board, opOpenFourMoves, player)
        }
        
        // 5. 己方冲四（下一步变成活四）- 进攻性策略
        val myClosedFourMoves = findClosedFourMoves(board, player)
        if (myClosedFourMoves.isNotEmpty()) {
            // 优先选择能形成双冲四或活四的点
            return selectBestAttackMove(board, myClosedFourMoves, player)
        }
        
        // 6. 对手双活三检测 - 如果有两处或以上活三，必须防守
        val opOpenThreeMoves = findOpenThreeMoves(board, opponent)
        if (opOpenThreeMoves.size >= 2) {
            // 对手有双活三，这是一个严重威胁，尝试找到能同时防守两个点或最优的防守点
            return defendDoubleThreat(board, opOpenThreeMoves, opponent, player)
        }
        
        // 7. 必防：对手冲四（下一步活四）
        val opClosedFourMoves = findClosedFourMoves(board, opponent)
        if (opClosedFourMoves.isNotEmpty()) {
            return selectBestMove(board, opClosedFourMoves, player)
        }

        // 8. 己方双活三 - 创造必胜局面
        val myOpenThreeMoves = findOpenThreeMoves(board, player)
        if (myOpenThreeMoves.size >= 2) {
            return selectBestAttackMove(board, myOpenThreeMoves, player)
        }

        return null
    }

    /**
     * 寻找玩家立即获胜的位置（落子后形成五连）。
     */
    fun findImmediateWin(board: Board, player: PieceColor): Pair<Int, Int>? {
        return findAllFiveMoves(board, player).firstOrNull()
    }

    // ==================== 各类威胁检测 ====================

    // 查找所有能形成连五的点
    fun findAllFiveMoves(board: Board, player: PieceColor): List<Pair<Int, Int>> {
        return findMovesCheck(board, player) { b, r, c, p -> checkFive(b, r, c, p) }
    }

    // 查找所有能形成活四的点（两端开放的四连）- public供AI调用
    fun findOpenFourMoves(board: Board, player: PieceColor): List<Pair<Int, Int>> {
        return findMovesCheck(board, player) { b, r, c, p -> checkOpenFour(b, r, c, p) }
    }
    
    // 查找所有能形成冲四的点（一端开放的四连）- public供AI调用
    fun findClosedFourMoves(board: Board, player: PieceColor): List<Pair<Int, Int>> {
        return findMovesCheck(board, player) { b, r, c, p -> checkClosedFour(b, r, c, p) }
    }
    
    // 查找所有能形成活三的点（两端开放的三连）- public供AI调用
    fun findOpenThreeMoves(board: Board, player: PieceColor): List<Pair<Int, Int>> {
        return findMovesCheck(board, player) { b, r, c, p -> checkOpenThree(b, r, c, p) }
    }
    
    /**
     * 查找对手已经形成的活四（4个连续的子）的防守点
     * 这是专门用于防守的函数，不同于 findOpenFourMoves（检测落子后形成活四）
     */
    fun findExistingOpenFourDefense(board: Board, player: PieceColor): List<Pair<Int, Int>> {
        val defensePoints = mutableSetOf<Pair<Int, Int>>()
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val checked = mutableSetOf<String>()  // 避免重复检查同一组四连
        
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) == player) {
                    for ((dr, dc) in dirs) {
                        // 检查从这个棋子开始的四连
                        val lineKey = "$r,$c,$dr,$dc"
                        if (lineKey in checked) continue
                        
                        // 统计连续棋子
                        val pieces = mutableListOf<Pair<Int, Int>>()
                        pieces.add(r to c)
                        
                        // 正向
                        var nr = r + dr
                        var nc = c + dc
                        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
                               board.getPiece(nr, nc) == player) {
                            pieces.add(nr to nc)
                            nr += dr
                            nc += dc
                        }
                        val posEnd = nr to nc
                        
                        // 反向
                        nr = r - dr
                        nc = c - dc
                        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
                               board.getPiece(nr, nc) == player) {
                            pieces.add(nr to nc)
                            nr -= dr
                            nc -= dc
                        }
                        val negEnd = nr to nc
                        
                        // 标记已检查
                        for (p in pieces) {
                            checked.add("${p.first},${p.second},$dr,$dc")
                        }
                        
                        // 如果有4个连续的子，且两端都空，这是活四
                        if (pieces.size == 4 && 
                            isEmpty(board, posEnd.first, posEnd.second) &&
                            isEmpty(board, negEnd.first, negEnd.second)) {
                            defensePoints.add(posEnd)
                            defensePoints.add(negEnd)
                        }
                    }
                }
            }
        }
        
        return defensePoints.toList()
    }
    
    // 通用查找：尝试所有空位，如果 checkFunc 返回 true 则收集
    private fun findMovesCheck(
        board: Board, 
        player: PieceColor, 
        checkFunc: (Board, Int, Int, PieceColor) -> Boolean
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val visited = HashSet<Long>()
        
        // 优化：只在已有棋子周围搜索
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) != null) {
                    // 搜索该棋子周围3格范围内的空位
                    for (dr in -3..3) {
                        for (dc in -3..3) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
                                board.getPiece(nr, nc) == null) {
                                val pos = (nr.toLong() shl 16) or nc.toLong()
                                if (visited.add(pos)) {
                                    if (checkFunc(board, nr, nc, player)) {
                                        moves.add(nr to nc)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return moves
    }

    // ==================== 具体棋型检测 ====================

    private fun checkFive(board: Board, r: Int, c: Int, player: PieceColor): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            if (countTotal(board, r, c, dr, dc, player) >= 5) return true
        }
        return false
    }

    // 活四：连珠==4 且 两头空（严格检测）
    private fun checkOpenFour(board: Board, r: Int, c: Int, player: PieceColor): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val (count, negEnd, posEnd) = getLineInfo(board, r, c, dr, dc, player)
            // 必须是严格的4连，不能有跳
            if (count == 4) {
                // 检查是否真的是连续的4个（没有跳）
                val negEmpty = isEmpty(board, negEnd.first, negEnd.second)
                val posEmpty = isEmpty(board, posEnd.first, posEnd.second)
                // 两端都必须是空的
                if (negEmpty && posEmpty) {
                    // 额外检查：确保这是真正的活四，不是被堵的
                    // 检查实际连续的长度
                    val actualLength = countConsecutive(board, r, c, dr, dc, player)
                    if (actualLength == 4) {
                        return true
                    }
                }
            }
        }
        return false
    }
    
    // 计算严格连续的棋子数（不包含跳）
    private fun countConsecutive(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Int {
        var count = 1  // 当前位置
        
        // 正向
        var nr = r + dr
        var nc = c + dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && board.getPiece(nr, nc) == player) {
            count++
            nr += dr
            nc += dc
        }
        
        // 反向
        nr = r - dr
        nc = c - dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && board.getPiece(nr, nc) == player) {
            count++
            nr -= dr
            nc -= dc
        }
        
        return count
    }
    
    // 冲四：连珠==4 且 一头空（另一头被堵或边界）
    private fun checkClosedFour(board: Board, r: Int, c: Int, player: PieceColor): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val (count, negEnd, posEnd) = getLineInfo(board, r, c, dr, dc, player)
            if (count == 4) {
                val negEmpty = isEmpty(board, negEnd.first, negEnd.second)
                val posEmpty = isEmpty(board, posEnd.first, posEnd.second)
                // 恰好一端空（异或）
                if (negEmpty != posEmpty) {
                    // 确保是严格连续的4个
                    if (countConsecutive(board, r, c, dr, dc, player) == 4) {
                        return true
                    }
                }
            }
            // 还有另一种冲四：X_YYY_Y 形式（跳四）- 需要在isJumpFour中严格检查
            if (count == 3 && isJumpFour(board, r, c, dr, dc, player)) {
                return true
            }
        }
        return false
    }
    
    // 活三：连珠==3 且 两头空（中间无空隙）
    private fun checkOpenThree(board: Board, r: Int, c: Int, player: PieceColor): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val (count, negEnd, posEnd) = getLineInfo(board, r, c, dr, dc, player)
            if (count == 3) {
                // 检查是否严格连续
                val actualLength = countConsecutive(board, r, c, dr, dc, player)
                if (actualLength != 3) continue
                
                // 检查两端是否都为空
                val negEmpty = isEmpty(board, negEnd.first, negEnd.second)
                val posEmpty = isEmpty(board, posEnd.first, posEnd.second)
                
                // 两端都必须空，且距离合适（不能太远）
                if (negEmpty && posEmpty) {
                    // 检查两端距离（确保是紧邻的空位）
                    val negDist = kotlin.math.abs(negEnd.first - r) + kotlin.math.abs(negEnd.second - c)
                    val posDist = kotlin.math.abs(posEnd.first - r) + kotlin.math.abs(posEnd.second - c)
                    // 距离应该在1左右（紧邻）
                    if (negDist <= 4 && posDist <= 4) {
                        return true
                    }
                }
            }
            // 跳三检测：_YY_Y_ 形式
            if (checkJumpThree(board, r, c, dr, dc, player)) {
                return true
            }
        }
        return false
    }
    
    // 检测跳四：严格检测 X_YYY_Y 或 X_Y_YYY 形式
    private fun isJumpFour(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Boolean {
        // 在5格或6格窗口内检测特定跳四模式
        // 模式1: X_YYY_Y (己方-空-三子-空-己方，落子在第二个空)
        // 模式2: X_Y_YYY (己方-空-子-空-两子，落子在第二个空)
        
        for (offset in -3..0) {
            var pattern = ""
            for (i in 0..4) {
                val nr = r + dr * (offset + i)
                val nc = c + dc * (offset + i)
                pattern += when {
                    nr == r && nc == c -> "X"  // 落子位置
                    !isValid(board, nr, nc) -> "-"
                    board.getPiece(nr, nc) == player -> "O"
                    board.getPiece(nr, nc) == null -> "_"
                    else -> "#"  // 对手子
                }
            }
            // 严格跳四模式（一端被堵，中间有跳，另一端空）
            // X_OOO_ : 落子后在_位置形成冲四
            // X_OOOO : 不可能，这是5连
            // 实际上跳四是：一端有己方棋子，中间有空位，然后三子连，另一端空
            // 如：#_OOO_ (#是己方棋子，_是空位，落子形成冲四)
            if (pattern.matches(Regex("[#]_[OOO]{3}_"))) return true
            if (pattern.matches(Regex("[#]O_OO_"))) return true
            if (pattern.matches(Regex("[#]OO_O_"))) return true
        }
        
        // 检查6格窗口（如 _X_OOO_ 形式）
        for (offset in -3..0) {
            var pattern = ""
            for (i in 0..5) {
                val nr = r + dr * (offset + i)
                val nc = c + dc * (offset + i)
                pattern += when {
                    nr == r && nc == c -> "X"
                    !isValid(board, nr, nc) -> "-"
                    board.getPiece(nr, nc) == player -> "O"
                    board.getPiece(nr, nc) == null -> "_"
                    else -> "#"
                }
            }
            // _X_OOO_ : 落子形成双头冲四（特殊跳四）
            if (pattern == "_X_OOO_") return true
        }
        
        return false
    }
    
    // 检测跳三：如 _YY_Y_ 形式
    private fun checkJumpThree(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Boolean {
        // 在5格或6格窗口内检测跳三模式
        // 跳三：三个己方棋子，中间有一个空位，两端空位
        
        // 6格窗口检测（包含更多跳三变体）
        for (offset in -3..0) {
            val window = mutableListOf<Char>()
            for (i in 0..5) {
                val nr = r + dr * (offset + i)
                val nc = c + dc * (offset + i)
                window.add(when {
                    nr == r && nc == c -> 'X'  // 落子位置
                    !isValid(board, nr, nc) -> '-'
                    board.getPiece(nr, nc) == player -> 'O'
                    board.getPiece(nr, nc) == null -> '_'
                    else -> '#'  // 对手子
                })
            }
            
            // 统计X(落子点)和O(己方棋子)数量
            val myPieces = window.count { it == 'O' || it == 'X' }
            val emptyEnds = (window.first() == '_') && (window.last() == '_')
            val gaps = window.count { it == '_' || it == 'X' } - 1  // 减去落子点本身
            
            // 跳三条件：3个己方棋子(含落子点)，两端空，有间隔
            if (myPieces == 3 && emptyEnds && gaps >= 2) {
                // 确保三个子在6格范围内（不分散）
                val piecePositions = window.withIndex().filter { it.value == 'O' || it.value == 'X' }.map { it.index }
                if (piecePositions.maxOrNull()!! - piecePositions.minOrNull()!! <= 4) {
                    return true
                }
            }
        }
        
        // 原来的5格窗口检测（保留兼容性）
        for (offset in -2..0) {
            var pattern = ""
            for (i in 0..4) {
                val nr = r + dr * (offset + i)
                val nc = c + dc * (offset + i)
                pattern += when {
                    nr == r && nc == c -> "X"
                    !isValid(board, nr, nc) -> "-"
                    board.getPiece(nr, nc) == player -> "O"
                    board.getPiece(nr, nc) == null -> "_"
                    else -> "#"
                }
            }
            // 经典跳三模式
            if (pattern in setOf("_OO_O_", "_O_OO_", "_OO__O", "_O__OO")) return true
        }
        return false
    }
    
    // 检查连线中是否有间隔
    private fun hasGap(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Boolean {
        // 从负端点开始，检查是否连续3子都是player，然后空位
        val (count, negEnd, _) = getLineInfo(board, r, c, dr, dc, player)
        if (count < 3) return true
        
        // 检查从negEnd到posEnd之间是否正好是4个位置（3子+落子点）连续
        var nr = negEnd.first + dr
        var nc = negEnd.second + dc
        var consecutiveCount = 0
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
            if (nr == r && nc == c) {
                consecutiveCount++
            } else if (board.getPiece(nr, nc) == player) {
                consecutiveCount++
            } else {
                break
            }
            if (consecutiveCount >= 3) break
            nr += dr
            nc += dc
        }
        return consecutiveCount < 3
    }

    // ==================== 辅助方法 ====================

    private fun countTotal(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Int {
        val (count, _, _) = getLineInfo(board, r, c, dr, dc, player)
        return count
    }

    // 返回 (连续棋子数, 负方向端点, 正方向端点)
    private fun getLineInfo(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Triple<Int, Pair<Int,Int>, Pair<Int,Int>> {
        var count = 1
        
        var nr = r + dr
        var nc = c + dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && board.getPiece(nr, nc) == player) {
            count++
            nr += dr
            nc += dc
        }
        val posEnd = nr to nc
        
        nr = r - dr
        nc = c - dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && board.getPiece(nr, nc) == player) {
            count++
            nr -= dr
            nc -= dc
        }
        val negEnd = nr to nc
        
        return Triple(count, negEnd, posEnd)
    }

    private fun isEmpty(board: Board, r: Int, c: Int): Boolean {
        return r in 0 until Board.SIZE && c in 0 until Board.SIZE && board.getPiece(r, c) == null
    }
    
    private fun isValid(board: Board, r: Int, c: Int): Boolean {
        return r in 0 until Board.SIZE && c in 0 until Board.SIZE
    }

    // ==================== 选点策略 ====================

    private fun selectBestMove(board: Board, moves: List<Pair<Int, Int>>, player: PieceColor): Pair<Int, Int> {
        return moves.maxByOrNull { (r, c) -> 
            evaluatePosition(board, r, c, player)
        } ?: moves.first()
    }
    
    private fun selectBestAttackMove(board: Board, moves: List<Pair<Int, Int>>, player: PieceColor): Pair<Int, Int> {
        return moves.maxByOrNull { (r, c) -> 
            // 进攻位置评估：更看重连接性和进攻潜力
            var score = evaluatePosition(board, r, c, player)
            // 额外加分：如果这个位置还能形成其他威胁
            val tempBoard = board.placePiece(r, c, player)
            if (findOpenFourMoves(tempBoard, player).isNotEmpty()) score += 1000
            if (findClosedFourMoves(tempBoard, player).size >= 2) score += 500
            score
        } ?: moves.first()
    }
    
    // 防守双威胁：当对手有两个以上威胁点时，选择最优防守点
    private fun defendDoubleThreat(
        board: Board, 
        threatMoves: List<Pair<Int, Int>>, 
        opponent: PieceColor,
        player: PieceColor
    ): Pair<Int, Int> {
        // 优先找能同时防守多个威胁的点
        for (move in threatMoves) {
            val (r, c) = move
            var defenseCount = 0
            // 检查这个点是否是多个威胁的公共防守点
            for (otherMove in threatMoves) {
                if (move == otherMove) continue
                // 如果两点距离很近，可能一个点能同时影响
                if (abs(r - otherMove.first) <= 2 && abs(c - otherMove.second) <= 2) {
                    defenseCount++
                }
            }
            if (defenseCount > 0) {
                return move  // 能同时影响多个威胁的点
            }
        }
        // 否则选择最有战略价值的防守点
        return selectBestMove(board, threatMoves, player)
    }
    
    private fun evaluatePosition(board: Board, r: Int, c: Int, player: PieceColor): Int {
        var score = 0
        val opponent = player.opposite()
        
        // 靠近中心
        val center = Board.SIZE / 2
        score -= (abs(r - center) + abs(c - center)) * 2
        
        // 周围己方棋子数量（增加连接性）
        for (dr in -2..2) {
            for (dc in -2..2) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (isValid(board, nr, nc)) {
                    when (board.getPiece(nr, nc)) {
                        player -> score += 30
                        opponent -> score += 20  // 阻挡也有价值
                        else -> {} // 空位或其他情况不加不减
                    }
                }
            }
        }
        
        // 线形加分：如果在某条线上已经有己方棋子
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val count = countDirection(board, r, c, dr, dc, player) + 
                       countDirection(board, r, c, -dr, -dc, player)
            score += count * count * 10  // 平方加分，鼓励连线
        }
        
        return score
    }
    
    private fun countDirection(board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor): Int {
        var count = 0
        var nr = r + dr
        var nc = c + dc
        while (isValid(board, nr, nc) && board.getPiece(nr, nc) == player) {
            count++
            nr += dr
            nc += dc
        }
        return count
    }
}