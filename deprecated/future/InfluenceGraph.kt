package io.github.ian_miller.wuziqi.ai.future

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 势力范围图：使用图论和概率论分析棋盘控制。
 * 
 * 核心概念：
 * 1. 每个棋子产生"影响力场"，随距离衰减
 * 2. 影响力叠加形成"势力范围"
 * 3. 关键节点是影响力梯度变化大的位置
 * 
 * 数学工具：
 * - 高斯分布：影响力随距离衰减
 * - 图论：势力连通性分析
 * - 向量场：势力梯度分析
 */
class InfluenceGraph {
    
    // 影响力衰减参数（高斯分布标准差）
    private val influenceSigma = 2.5
    
    // 最大影响力范围
    private val maxInfluenceRange = 5
    
    /**
     * 势力分析结果
     */
    data class InfluenceAnalysis(
        val influenceMap: Array<DoubleArray>,           // 势力分布图
        val controlRegions: List<ControlRegion>,         // 控制区域
        val keyPoints: List<InfluenceKeyPoint>,          // 关键点
        val gradientVectors: Array<Array<Vector2D>>,     // 势力梯度场
        val totalInfluence: Double,                      // 总影响力
        val centerControl: Double                        // 中心控制度
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as InfluenceAnalysis
            return totalInfluence == other.totalInfluence &&
                   centerControl == other.centerControl
        }
        
        override fun hashCode(): Int {
            var result = totalInfluence.hashCode()
            result = 31 * result + centerControl.hashCode()
            return result
        }
    }
    
    /**
     * 二维向量
     */
    data class Vector2D(val x: Double, val y: Double) {
        fun magnitude() = sqrt(x * x + y * y)
        fun normalize(): Vector2D {
            val mag = magnitude()
            return if (mag > 0) Vector2D(x / mag, y / mag) else Vector2D(0.0, 0.0)
        }
        operator fun plus(other: Vector2D) = Vector2D(x + other.x, y + other.y)
        operator fun times(scalar: Double) = Vector2D(x * scalar, y * scalar)
    }
    
    /**
     * 控制区域
     */
    data class ControlRegion(
        val owner: PieceColor,
        val cells: Set<Pair<Int, Int>>,
        val strength: Double,           // 控制强度
        val stability: Double,          // 稳定性 [0,1]
        val expansionPotential: Double  // 扩张潜力
    )
    
    /**
     * 影响力关键点
     */
    data class InfluenceKeyPoint(
        val row: Int,
        val col: Int,
        val type: KeyPointType,
        val influenceDelta: Double,     // 影响力差值
        val strategicValue: Double      // 战略价值
    )
    
    enum class KeyPointType {
        BORDER,         // 势力边界
        WEAK_POINT,     // 薄弱点
        CHOKE_POINT,    // 咽喉点
        BRIDGE,         // 连接桥
        EXPANSION      // 扩张点
    }
    
    /**
     * 分析棋盘势力分布
     */
    fun analyzeInfluence(
        board: Board,
        player: PieceColor,
        opponent: PieceColor
    ): InfluenceAnalysis {
        val size = Board.SIZE
        
        // 计算影响力场
        val influenceMap = Array(size) { DoubleArray(size) }
        val playerInfluence = Array(size) { DoubleArray(size) }
        val opponentInfluence = Array(size) { DoubleArray(size) }
        
        // 计算每个棋子的影响力
        for (r in 0 until size) {
            for (c in 0 until size) {
                val piece = board.getPiece(r, c)
                if (piece != null) {
                    addInfluence(
                        if (piece == player) playerInfluence else opponentInfluence,
                        r, c, size
                    )
                }
            }
        }
        
        // 计算净影响力（己方 - 对方）
        var totalInfluence = 0.0
        val center = size / 2
        var centerControl = 0.0
        
        for (r in 0 until size) {
            for (c in 0 until size) {
                influenceMap[r][c] = playerInfluence[r][c] - opponentInfluence[r][c]
                totalInfluence += influenceMap[r][c]
                
                // 中心区域权重更高
                val distToCenter = abs(r - center) + abs(c - center)
                if (distToCenter <= 3) {
                    centerControl += influenceMap[r][c] * (4 - distToCenter)
                }
            }
        }
        
        // 计算势力梯度场
        val gradientVectors = calculateGradient(influenceMap)
        
        // 识别控制区域
        val controlRegions = identifyControlRegions(influenceMap, player, opponent)
        
        // 识别关键点
        val keyPoints = identifyKeyPoints(influenceMap, gradientVectors, board)
        
        return InfluenceAnalysis(
            influenceMap = influenceMap,
            controlRegions = controlRegions,
            keyPoints = keyPoints,
            gradientVectors = gradientVectors,
            totalInfluence = totalInfluence,
            centerControl = centerControl
        )
    }
    
    /**
     * 添加单个棋子的影响力（高斯分布）
     */
    private fun addInfluence(influenceArray: Array<DoubleArray>, row: Int, col: Int, size: Int) {
        for (dr in -maxInfluenceRange..maxInfluenceRange) {
            for (dc in -maxInfluenceRange..maxInfluenceRange) {
                val nr = row + dr
                val nc = col + dc
                if (nr in 0 until size && nc in 0 until size) {
                    val dist = sqrt((dr * dr + dc * dc).toDouble())
                    // 高斯衰减：influence = exp(-dist^2 / (2 * sigma^2))
                    val influence = exp(-dist * dist / (2 * influenceSigma * influenceSigma))
                    influenceArray[nr][nc] += influence
                }
            }
        }
    }
    
    /**
     * 计算势力梯度场
     */
    private fun calculateGradient(influenceMap: Array<DoubleArray>): Array<Array<Vector2D>> {
        val size = influenceMap.size
        val gradient = Array(size) { Array(size) { Vector2D(0.0, 0.0) } }
        
        for (r in 1 until size - 1) {
            for (c in 1 until size - 1) {
                // 使用中心差分计算梯度
                val dx = (influenceMap[r][c + 1] - influenceMap[r][c - 1]) / 2.0
                val dy = (influenceMap[r + 1][c] - influenceMap[r - 1][c]) / 2.0
                gradient[r][c] = Vector2D(dx, dy)
            }
        }
        
        return gradient
    }
    
    /**
     * 识别控制区域
     */
    private fun identifyControlRegions(
        influenceMap: Array<DoubleArray>,
        player: PieceColor,
        opponent: PieceColor
    ): List<ControlRegion> {
        val size = influenceMap.size
        val visited = Array(size) { BooleanArray(size) }
        val regions = mutableListOf<ControlRegion>()
        
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!visited[r][c] && influenceMap[r][c] != 0.0) {
                    val region = floodFillRegion(influenceMap, r, c, visited)
                    if (region.size >= 4) {  // 只考虑足够大的区域
                        val owner = if (influenceMap[r][c] > 0) player else opponent
                        val strength = region.sumOf { abs(influenceMap[it.first][it.second]) }
                        val stability = calculateStability(influenceMap, region)
                        val expansion = calculateExpansionPotential(influenceMap, region)
                        
                        regions.add(
                            ControlRegion(
                                owner = owner,
                                cells = region,
                                strength = strength,
                                stability = stability,
                                expansionPotential = expansion
                            )
                        )
                    }
                }
            }
        }
        
        return regions.sortedByDescending { it.strength }
    }
    
    /**
     * 洪水填充算法找出连通区域
     */
    private fun floodFillRegion(
        influenceMap: Array<DoubleArray>,
        startR: Int,
        startC: Int,
        visited: Array<BooleanArray>
    ): Set<Pair<Int, Int>> {
        val region = mutableSetOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        val sign = if (influenceMap[startR][startC] > 0) 1 else -1
        val size = influenceMap.size
        
        queue.add(startR to startC)
        visited[startR][startC] = true
        
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            region.add(r to c)
            
            // 检查四个方向
            val directions = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
            for ((dr, dc) in directions) {
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until size && nc in 0 until size &&
                    !visited[nr][nc] &&
                    influenceMap[nr][nc] * sign > 0) {
                    visited[nr][nc] = true
                    queue.add(nr to nc)
                }
            }
        }
        
        return region
    }
    
    /**
     * 计算区域稳定性
     */
    private fun calculateStability(
        influenceMap: Array<DoubleArray>,
        region: Set<Pair<Int, Int>>
    ): Double {
        if (region.isEmpty()) return 0.0
        
        var totalInfluence = 0.0
        var borderInfluence = 0.0
        val size = influenceMap.size
        
        for ((r, c) in region) {
            val inf = abs(influenceMap[r][c])
            totalInfluence += inf
            
            // 检查是否是边界点
            val isBorder = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).any { (dr, dc) ->
                val nr = r + dr
                val nc = c + dc
                nr !in 0 until size || nc !in 0 until size ||
                (nr to nc) !in region
            }
            
            if (isBorder) {
                borderInfluence += inf
            }
        }
        
        // 稳定性 = 1 - 边界影响比例
        return if (totalInfluence > 0) {
            1.0 - (borderInfluence / totalInfluence)
        } else 0.0
    }
    
    /**
     * 计算区域扩张潜力
     */
    private fun calculateExpansionPotential(
        influenceMap: Array<DoubleArray>,
        region: Set<Pair<Int, Int>>
    ): Double {
        val size = influenceMap.size
        var expansionPotential = 0.0
        
        for ((r, c) in region) {
            // 检查周围空位的扩张潜力
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in 0 until size && nc in 0 until size &&
                        (nr to nc) !in region) {
                        // 根据距离和当前影响力计算扩张潜力
                        val dist = sqrt((dr * dr + dc * dc).toDouble())
                        expansionPotential += abs(influenceMap[r][c]) / (1 + dist)
                    }
                }
            }
        }
        
        return expansionPotential
    }
    
    /**
     * 识别关键点
     */
    private fun identifyKeyPoints(
        influenceMap: Array<DoubleArray>,
        gradientVectors: Array<Array<Vector2D>>,
        board: Board
    ): List<InfluenceKeyPoint> {
        val size = influenceMap.size
        val keyPoints = mutableListOf<InfluenceKeyPoint>()
        
        for (r in 2 until size - 2) {
            for (c in 2 until size - 2) {
                if (board.getPiece(r, c) != null) continue
                
                val currentInf = influenceMap[r][c]
                val gradient = gradientVectors[r][c]
                
                // 1. 势力边界点（影响力接近0但梯度大）
                if (abs(currentInf) < 0.5 && gradient.magnitude() > 0.3) {
                    keyPoints.add(
                        InfluenceKeyPoint(
                            row = r,
                            col = c,
                            type = KeyPointType.BORDER,
                            influenceDelta = gradient.magnitude(),
                            strategicValue = gradient.magnitude()
                        )
                    )
                }
                
                // 2. 薄弱点（己方势力但数值小）
                else if (currentInf > 0 && currentInf < 0.3) {
                    keyPoints.add(
                        InfluenceKeyPoint(
                            row = r,
                            col = c,
                            type = KeyPointType.WEAK_POINT,
                            influenceDelta = currentInf,
                            strategicValue = 0.5
                        )
                    )
                }
                
                // 3. 咽喉点（梯度方向变化的点）
                else if (isChokePoint(r, c, gradientVectors)) {
                    keyPoints.add(
                        InfluenceKeyPoint(
                            row = r,
                            col = c,
                            type = KeyPointType.CHOKE_POINT,
                            influenceDelta = abs(currentInf),
                            strategicValue = 0.8
                        )
                    )
                }
            }
        }
        
        return keyPoints.sortedByDescending { it.strategicValue }.take(20)
    }
    
    /**
     * 判断是否是咽喉点（连接两个区域的狭窄通道）
     */
    private fun isChokePoint(r: Int, c: Int, gradients: Array<Array<Vector2D>>): Boolean {
        val current = gradients[r][c]
        val size = gradients.size
        
        // 检查周围梯度方向是否显著不同
        var directionChanges = 0
        val directions = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)
        
        for ((dr, dc) in directions) {
            val nr = r + dr
            val nc = c + dc
            if (nr in 0 until size && nc in 0 until size) {
                val neighbor = gradients[nr][nc]
                if (current.magnitude() > 0.1 && neighbor.magnitude() > 0.1) {
                    val dot = current.x * neighbor.x + current.y * neighbor.y
                    if (dot < 0) {  // 方向相反
                        directionChanges++
                    }
                }
            }
        }
        
        return directionChanges >= 2
    }
    
    /**
     * 获取位置的战略价值
     */
    fun getStrategicValue(
        analysis: InfluenceAnalysis,
        row: Int,
        col: Int
    ): Double {
        val size = analysis.influenceMap.size
        if (row !in 0 until size || col !in 0 until size) return 0.0
        
        var value = abs(analysis.influenceMap[row][col])
        
        // 梯度大小加成
        val gradient = analysis.gradientVectors[row][col].magnitude()
        value += gradient * 0.5
        
        // 关键点加成
        val isKeyPoint = analysis.keyPoints.any { it.row == row && it.col == col }
        if (isKeyPoint) value *= 1.5
        
        return value
    }
}
