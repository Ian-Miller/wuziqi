package io.github.ian_miller.wuziqi.ai.future

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.collections.iterator
import kotlin.math.abs
import kotlin.math.min

/**
 * 拓扑威胁空间分析器：实现"n-监听"概念的数学工具。
 * 
 * 核心思想：
 * 1. 将棋盘视为拓扑空间，每个空位是一个"点"
 * 2. 能形成威胁的点是"开集"
 * 3. 多个威胁的"交集"是难以防守的关键点
 * 
 * 数学工具：
 * - 集合论：威胁点的并集、交集
 * - 图论：威胁点之间的连接关系
 * - 拓扑学：威胁空间的"覆盖"和"紧致性"
 */
class ThreatSpaceAnalyzer {
    
    // 四个方向
    private val directions = listOf(
        0 to 1,   // 水平
        1 to 0,   // 垂直
        1 to 1,   // 对角线
        1 to -1   // 反对角线
    )
    
    /**
     * 威胁空间分析结果
     */
    data class ThreatSpace(
        val myThreatPoints: Set<ThreatPoint>,           // 己方威胁点集合
        val opponentThreatPoints: Set<ThreatPoint>,     // 对方威胁点集合
        val intersectionPoints: Set<Pair<Int, Int>>,    // 威胁交集（关键点）
        val coverageScore: Double,                       // 威胁覆盖度 [0,1]
        val networkDensity: Double,                      // 威胁网络密度
        val criticalPoints: List<CriticalPoint>         // 关键防守/进攻点
    )
    
    /**
     * 威胁点：包含位置和威胁信息
     */
    data class ThreatPoint(
        val row: Int,
        val col: Int,
        val level: ThreatLevel,
        val affectedLines: List<LineThreat>,    // 影响的线路
        val potential: Double                    // 威胁潜力 [0,1]
    ) {
        fun toPair() = row to col
    }
    
    /**
     * 线路威胁
     */
    data class LineThreat(
        val direction: Int,      // 0-3 对应四个方向
        val consecutive: Int,    // 连续棋子数
        val openEnds: Int,       // 开放端点数 (0-2)
        val extensionPotential: Double  // 延伸潜力
    )
    
    /**
     * 关键点：具有战略价值的位置
     */
    data class CriticalPoint(
        val row: Int,
        val col: Int,
        val type: CriticalType,
        val importance: Double,   // 重要性评分
        val relatedThreats: Int   // 关联的威胁数
    )
    
    enum class CriticalType {
        DEFENSE_MUST,      // 必须防守
        ATTACK_KILL,       // 杀招点
        DOUBLE_THREAT,     // 双重威胁
        INTERSECTION,      // 威胁交集
        DEVELOPMENT       // 发展点
    }
    
    /**
     * 分析威胁空间（n-监听的核心实现）
     */
    fun analyzeThreatSpace(
        board: Board,
        player: PieceColor,
        opponent: PieceColor
    ): ThreatSpace {
        val myThreats = mutableSetOf<ThreatPoint>()
        val opponentThreats = mutableSetOf<ThreatPoint>()
        
        // 扫描已有棋子周围的空位
        val searchRange = 3
        val visited = HashSet<Pair<Int, Int>>()
        
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) != null) {
                    // 搜索周围空位
                    for (dr in -searchRange..searchRange) {
                        for (dc in -searchRange..searchRange) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until Board.SIZE && 
                                nc in 0 until Board.SIZE &&
                                board.getPiece(nr, nc) == null &&
                                visited.add(nr to nc)) {
                                
                                // 分析这个空位的威胁价值
                                analyzePosition(board, nr, nc, player)?.let {
                                    myThreats.add(it)
                                }
                                analyzePosition(board, nr, nc, opponent)?.let {
                                    opponentThreats.add(it)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 计算威胁交集（关键点）
        val intersection = findThreatIntersections(myThreats, opponentThreats)
        
        // 计算威胁覆盖度
        val coverage = calculateCoverage(myThreats, opponentThreats)
        
        // 计算威胁网络密度
        val density = calculateNetworkDensity(myThreats)
        
        // 识别关键点
        val criticalPoints = identifyCriticalPoints(
            board, myThreats, opponentThreats, intersection, player, opponent
        )
        
        return ThreatSpace(
            myThreatPoints = myThreats,
            opponentThreatPoints = opponentThreats,
            intersectionPoints = intersection,
            coverageScore = coverage,
            networkDensity = density,
            criticalPoints = criticalPoints
        )
    }
    
    /**
     * 分析单个位置的威胁价值
     */
    private fun analyzePosition(
        board: Board,
        row: Int,
        col: Int,
        player: PieceColor
    ): ThreatPoint? {
        val lineThreats = mutableListOf<LineThreat>()
        var maxLevel = ThreatLevel.POTENTIAL
        var totalPotential = 0.0
        
        for ((dirIdx, dir) in directions.withIndex()) {
            val (dr, dc) = dir
            
            // 分析这个方向的棋型
            val lineAnalysis = analyzeLine(board, row, col, dr, dc, player)
            
            if (lineAnalysis.consecutive > 0 || lineAnalysis.openEnds > 0) {
                lineThreats.add(
                    LineThreat(
                        direction = dirIdx,
                        consecutive = lineAnalysis.consecutive,
                        openEnds = lineAnalysis.openEnds,
                        extensionPotential = lineAnalysis.potential
                    )
                )
                
                // 更新威胁等级
                val level = threatLevelFromAnalysis(lineAnalysis)
                if (level.ordinal < maxLevel.ordinal) {
                    maxLevel = level
                }
                
                totalPotential += lineAnalysis.potential
            }
        }
        
        // 如果没有威胁价值，返回null
        if (lineThreats.isEmpty()) return null
        
        // 计算整体威胁潜力
        val potential = min(1.0, totalPotential / 4.0)  // 归一化
        
        return ThreatPoint(
            row = row,
            col = col,
            level = maxLevel,
            affectedLines = lineThreats,
            potential = potential
        )
    }
    
    /**
     * 分析一条线路的棋型
     */
    private fun analyzeLine(
        board: Board,
        row: Int,
        col: Int,
        dr: Int,
        dc: Int,
        player: PieceColor
    ): LineAnalysis {
        var consecutive = 1  // 假设在当前位置落子
        var openEnds = 0
        var blockedEnds = 0
        
        // 正向检查
        var distToEmptyPos = 0
        var nr = row + dr
        var nc = col + dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
            when (board.getPiece(nr, nc)) {
                player -> {
                    consecutive++
                    nr += dr
                    nc += dc
                }
                null -> {
                    distToEmptyPos++
                    break
                }
                else -> {
                    blockedEnds++
                    break
                }
            }
        }
        if (nr !in 0 until Board.SIZE || nc !in 0 until Board.SIZE) {
            blockedEnds++
        }
        
        // 反向检查
        var distToEmptyNeg = 0
        nr = row - dr
        nc = col - dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
            when (board.getPiece(nr, nc)) {
                player -> {
                    consecutive++
                    nr -= dr
                    nc -= dc
                }
                null -> {
                    distToEmptyNeg++
                    break
                }
                else -> {
                    blockedEnds++
                    break
                }
            }
        }
        if (nr !in 0 until Board.SIZE || nc !in 0 until Board.SIZE) {
            blockedEnds++
        }
        
        // 计算开放端点
        openEnds = 2 - blockedEnds
        
        // 计算延伸潜力
        val spaceAvailable = distToEmptyPos + distToEmptyNeg
        val potential = when {
            consecutive >= 5 -> 1.0
            consecutive == 4 && openEnds >= 1 -> 0.95
            consecutive == 4 && openEnds == 0 -> 0.7
            consecutive == 3 && openEnds == 2 -> 0.8
            consecutive == 3 && openEnds == 1 -> 0.5
            consecutive == 2 && openEnds == 2 -> 0.4
            consecutive == 2 && openEnds == 1 -> 0.2
            else -> 0.1
        } * (1 + spaceAvailable / 8.0) / 2
        
        return LineAnalysis(consecutive, openEnds, blockedEnds, potential)
    }
    
    /**
     * 根据线路分析确定威胁等级
     */
    private fun threatLevelFromAnalysis(analysis: LineAnalysis): ThreatLevel {
        return when {
            analysis.consecutive >= 5 -> ThreatLevel.FIVE
            analysis.consecutive == 4 && analysis.openEnds >= 1 -> ThreatLevel.OPEN_FOUR
            analysis.consecutive == 4 && analysis.openEnds == 0 -> ThreatLevel.CLOSED_FOUR
            analysis.consecutive == 3 && analysis.openEnds == 2 -> ThreatLevel.OPEN_THREE
            analysis.consecutive == 3 && analysis.openEnds == 1 -> ThreatLevel.CLOSED_THREE
            analysis.consecutive == 2 && analysis.openEnds == 2 -> ThreatLevel.OPEN_TWO
            else -> ThreatLevel.POTENTIAL
        }
    }
    
    /**
     * 寻找威胁交集（n-监听的关键）
     */
    private fun findThreatIntersections(
        myThreats: Set<ThreatPoint>,
        opponentThreats: Set<ThreatPoint>
    ): Set<Pair<Int, Int>> {
        val intersection = mutableSetOf<Pair<Int, Int>>()
        
        // 己方威胁之间的交集（双重威胁点）
        val myThreatMap = myThreats.groupBy { it.toPair() }
        for ((pos, threats) in myThreatMap) {
            if (threats.size >= 2 || 
                threats.any { it.level.ordinal <= ThreatLevel.OPEN_FOUR.ordinal }) {
                intersection.add(pos)
            }
        }
        
        // 敌我威胁的交集（攻防转换点）
        for (myThreat in myThreats) {
            for (opThreat in opponentThreats) {
                val dist = abs(myThreat.row - opThreat.row) + abs(myThreat.col - opThreat.col)
                if (dist <= 2) {
                    intersection.add(myThreat.toPair())
                    intersection.add(opThreat.toPair())
                }
            }
        }
        
        return intersection
    }
    
    /**
     * 计算威胁覆盖度
     */
    private fun calculateCoverage(
        myThreats: Set<ThreatPoint>,
        opponentThreats: Set<ThreatPoint>
    ): Double {
        val total = myThreats.size + opponentThreats.size
        return if (total > 0) myThreats.size.toDouble() / total else 0.5
    }
    
    /**
     * 计算威胁网络密度
     */
    private fun calculateNetworkDensity(threats: Set<ThreatPoint>): Double {
        if (threats.size < 2) return 0.0
        
        var edgeCount = 0
        val threatList = threats.toList()
        
        for (i in threatList.indices) {
            for (j in i + 1 until threatList.size) {
                val t1 = threatList[i]
                val t2 = threatList[j]
                val dist = abs(t1.row - t2.row) + abs(t1.col - t2.col)
                if (dist <= 3) {
                    edgeCount++
                }
            }
        }
        
        val maxEdges = threats.size * (threats.size - 1) / 2
        return if (maxEdges > 0) edgeCount.toDouble() / maxEdges else 0.0
    }
    
    /**
     * 识别关键点
     */
    private fun identifyCriticalPoints(
        board: Board,
        myThreats: Set<ThreatPoint>,
        opponentThreats: Set<ThreatPoint>,
        intersections: Set<Pair<Int, Int>>,
        player: PieceColor,
        opponent: PieceColor
    ): List<CriticalPoint> {
        val criticalPoints = mutableListOf<CriticalPoint>()
        
        // 1. 必须防守点（对方高级威胁）
        for (threat in opponentThreats) {
            if (threat.level.ordinal <= ThreatLevel.OPEN_FOUR.ordinal) {
                criticalPoints.add(
                    CriticalPoint(
                        row = threat.row,
                        col = threat.col,
                        type = CriticalType.DEFENSE_MUST,
                        importance = 1.0,
                        relatedThreats = threat.affectedLines.size
                    )
                )
            }
        }
        
        // 2. 杀招点（己方高级威胁）
        for (threat in myThreats) {
            if (threat.level.ordinal <= ThreatLevel.OPEN_FOUR.ordinal) {
                val existing = criticalPoints.find { it.row == threat.row && it.col == threat.col }
                if (existing == null) {
                    criticalPoints.add(
                        CriticalPoint(
                            row = threat.row,
                            col = threat.col,
                            type = CriticalType.ATTACK_KILL,
                            importance = 0.95,
                            relatedThreats = threat.affectedLines.size
                        )
                    )
                }
            }
        }
        
        // 3. 双重威胁点
        val myThreatPositions = myThreats.map { it.toPair() }
        for (i in myThreatPositions.indices) {
            for (j in i + 1 until myThreatPositions.size) {
                val p1 = myThreatPositions[i]
                val p2 = myThreatPositions[j]
                val dist = abs(p1.first - p2.first) + abs(p1.second - p2.second)
                if (dist <= 3) {
                    val midR = (p1.first + p2.first) / 2
                    val midC = (p1.second + p2.second) / 2
                    if (board.getPiece(midR, midC) == null) {
                        criticalPoints.add(
                            CriticalPoint(
                                row = midR,
                                col = midC,
                                type = CriticalType.DOUBLE_THREAT,
                                importance = 0.8,
                                relatedThreats = 2
                            )
                        )
                    }
                }
            }
        }
        
        // 4. 威胁交集点
        for (pos in intersections) {
            val existing = criticalPoints.find { it.row == pos.first && it.col == pos.second }
            if (existing == null) {
                criticalPoints.add(
                    CriticalPoint(
                        row = pos.first,
                        col = pos.second,
                        type = CriticalType.INTERSECTION,
                        importance = 0.7,
                        relatedThreats = 1
                    )
                )
            }
        }
        
        return criticalPoints.sortedByDescending { it.importance }
    }
    
    /**
     * 线路分析结果
     */
    private data class LineAnalysis(
        val consecutive: Int,
        val openEnds: Int,
        val blockedEnds: Int,
        val potential: Double
    )
}
