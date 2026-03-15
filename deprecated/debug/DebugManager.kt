package io.github.ian_miller.wuziqi.ai.debug

import android.util.Log
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * AI调试管理器 - 统一的调试输出管理
 * 
 * 特性：
 * 1. 分级日志（VERBOSE/DEBUG/INFO/WARN/ERROR）
 * 2. 模块标签（便于筛选）
 * 3. 性能统计
 * 4. 决策路径追踪
 * 
 * 注意：所有方法使用inline + 编译期常量，确保release构建零开销
 * 
 * 编译期常量配置（原AiDebugConfig）：
 * - ENABLE_DEBUG_LOG: 主开关，控制是否输出任何日志
 * - ENABLE_BOARD_LOG: 棋盘打印开关
 * - ENABLE_PERFORMANCE_STATS: 性能统计开关
 */
object DebugManager {
    
    /**
     * 编译期常量配置
     * 
     * 重要：这些值必须是 const val 才能在release构建中被完全优化掉
     * 当 BuildConfig.DEBUG 为 false 时，所有日志代码将被R8移除，零运行时开销
     */
    object Config {
        const val ENABLE_DEBUG_LOG = true
        const val ENABLE_BOARD_LOG = true
        const val ENABLE_PERFORMANCE_STATS = false
        const val ENABLE_INDENT = true  // 启用层级缩进
    }
    
    enum class Level(val code: String) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E")
    }
    
    // 线程本地存储：当前缩进层级
    private val indentLevel = ThreadLocal.withInitial<Int> { 0 }
    
    /**
     * 增加缩进层级（进入更深一层）
     */
    fun pushIndent() {
        if (Config.ENABLE_INDENT) {
            indentLevel.set((indentLevel.get() ?: 0) + 1)
        }
    }
    
    /**
     * 减少缩进层级（返回上一层）
     */
    fun popIndent() {
        if (Config.ENABLE_INDENT) {
            indentLevel.set(((indentLevel.get() ?: 0) - 1).coerceAtLeast(0))
        }
    }
    
    /**
     * 重置缩进层级
     */
    fun resetIndent() {
        indentLevel.set(0)
    }
    
    /**
     * 获取当前缩进字符串
     */
    @PublishedApi
    internal fun getIndent(): String {
        if (!Config.ENABLE_INDENT) return ""
        return "  ".repeat(indentLevel.get() ?: 0)
    }
    
    /**
     * 在当前缩进层级执行代码块
     */
    inline fun <T> withIndent(block: () -> T): T {
        pushIndent()
        return try {
            block()
        } finally {
            popIndent()
        }
    }
    
    enum class Module(val tag: String) {
        EVALUATION("EVA"),       // 评估相关
        SEARCH("SEA"),           // 搜索相关
        MOVEGEN("MOV"),          // 走法生成
        THREAT("THR"),           // 威胁检测
        STRATEGY("STR"),         // 战略分析
        STRATEGIC("STE"),        // StrategicEvaluator
        SETUP("SET"),            // 布局分析
        COUNTER("CTR"),          // 反击分析
        INFLUENCE("INF"),        // 势力图
        PERFORMANCE("PRF"),      // 性能统计
        FRAMEWORK("FRM"),        // 框架核心
        THINK("THK")             // 思考控制（ThinkController, DepthFactor等）
    }
    
    // 各模块的日志级别控制
    @PublishedApi
    internal val moduleLevels = mutableMapOf<Module, Level>(
        Module.EVALUATION to Level.DEBUG,
        Module.SEARCH to Level.INFO,
        Module.MOVEGEN to Level.DEBUG,
        Module.THREAT to Level.DEBUG,
        Module.STRATEGY to Level.INFO,
        Module.SETUP to Level.INFO,
        Module.COUNTER to Level.INFO,
        Module.INFLUENCE to Level.DEBUG,
        Module.PERFORMANCE to Level.INFO,
        Module.FRAMEWORK to Level.DEBUG,
        Module.THINK to Level.DEBUG
    )
    
    /**
     * 主日志输出方法（带层级缩进）
     */
    inline fun log(
        module: Module,
        level: Level,
        message: String
    ) {
        if (!Config.ENABLE_DEBUG_LOG) return
        if (level.ordinal < (moduleLevels[module]?.ordinal ?: 0)) return
        
        val tag = "[WUZIQI]"
        val indent = getIndent()
        val fullMessage = "[${module.tag}][${level.code}] $indent$message"
        when (level) {
            Level.VERBOSE -> Log.v(tag, fullMessage)
            Level.DEBUG -> Log.d(tag, fullMessage)
            Level.INFO -> Log.i(tag, fullMessage)
            Level.WARN -> Log.w(tag, fullMessage)
            Level.ERROR -> Log.e(tag, fullMessage)
        }
    }
    
    /**
     * 便捷方法
     */
    inline fun v(module: Module, msg: String) = log(module, Level.VERBOSE, msg)
    inline fun d(module: Module, msg: String) = log(module, Level.DEBUG, msg)
    inline fun i(module: Module, msg: String) = log(module, Level.INFO, msg)
    inline fun w(module: Module, msg: String) = log(module, Level.WARN, msg)
    inline fun e(module: Module, msg: String) = log(module, Level.ERROR, msg)
    
    /**
     * 打印棋盘（带标记）
     */
    inline fun logBoard(
        board: Board,
        title: String = "棋盘状态",
        highlightPoints: List<Pair<Int, Int>> = emptyList(),
        highlightChar: String = "*"
    ) {
        if (!Config.ENABLE_BOARD_LOG) return
        
        val indent = getIndent()
        i(Module.FRAMEWORK, "${indent}==== $title ====")
        val highlightSet = highlightPoints.toSet()
        
        for (r in 0 until Board.SIZE) {
            val row = StringBuilder()
            for (c in 0 until Board.SIZE) {
                val piece = board.getPiece(r, c)
                val char = when {
                    (r to c) in highlightSet -> highlightChar
                    piece == PieceColor.BLACK -> "X"
                    piece == PieceColor.WHITE -> "O"
                    else -> "."
                }
                row.append(char).append(" ")
            }
            i(Module.FRAMEWORK, "${indent}  ${row.toString().trimEnd()}")
        }
        i(Module.FRAMEWORK, "${indent}==== 结束 ====")
    }
    
    /**
     * 性能计时器
     */
    inline fun <T> measureTime(
        module: Module,
        operation: String,
        block: () -> T
    ): T {
        if (!Config.ENABLE_PERFORMANCE_STATS) {
            return block()
        }
        
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0  // ms
        
        d(module, "[PERF] $operation took ${elapsed}ms")
        return result
    }
    
    /**
     * 决策路径追踪（用于理解AI的思考过程）
     */
    class DecisionTracer(private val module: Module) {
        private val path = mutableListOf<String>()
        private var indent = 0
        
        fun enter(decision: String) {
            i(module, "${"  ".repeat(indent)}→ $decision")
            path.add(decision)
            indent += 2
        }
        
        fun exit(result: String) {
            indent -= 2
            indent = indent.coerceAtLeast(0)
            i(module, "${"  ".repeat(indent)}← $result")
        }
        
        fun log(detail: String) {
            i(module, "${"  ".repeat(indent)}  $detail")
        }
        
        fun getPath(): String = path.joinToString(" → ")
    }
    
    /**
     * 创建决策追踪器
     */
    fun createTracer(module: Module): DecisionTracer = DecisionTracer(module)
    
    /**
     * 设置模块日志级别
     */
    fun setModuleLevel(module: Module, level: Level) {
        moduleLevels[module] = level
    }
    
    /**
     * 批量设置日志级别
     */
    fun setLevelsForPhase(phase: DebugPhase) {
        when (phase) {
            DebugPhase.EVALUATION_FOCUS -> {
                setModuleLevel(Module.EVALUATION, Level.DEBUG)
                setModuleLevel(Module.STRATEGY, Level.DEBUG)
                setModuleLevel(Module.SEARCH, Level.INFO)
            }
            DebugPhase.SEARCH_FOCUS -> {
                setModuleLevel(Module.SEARCH, Level.DEBUG)
                setModuleLevel(Module.MOVEGEN, Level.DEBUG)
                setModuleLevel(Module.EVALUATION, Level.INFO)
            }
            DebugPhase.PERFORMANCE_FOCUS -> {
                setModuleLevel(Module.PERFORMANCE, Level.DEBUG)
                moduleLevels.keys.forEach { setModuleLevel(it, Level.WARN) }
            }
            DebugPhase.NORMAL -> {
                moduleLevels.keys.forEach { setModuleLevel(it, Level.INFO) }
            }
        }
    }
    
    enum class DebugPhase {
        EVALUATION_FOCUS,   // 关注评估过程
        SEARCH_FOCUS,       // 关注搜索过程
        PERFORMANCE_FOCUS,  // 关注性能
        NORMAL              // 正常模式
    }
}

/**
 * 为框架模块提供的便捷扩展
 */
inline fun debugLog(module: DebugManager.Module, message: String) {
    DebugManager.d(module, message)
}

inline fun debugLogEvaluation(message: String) = DebugManager.d(DebugManager.Module.EVALUATION, message)
inline fun debugLogStrategy(message: String) = DebugManager.d(DebugManager.Module.STRATEGY, message)
inline fun debugLogSetup(message: String) = DebugManager.d(DebugManager.Module.SETUP, message)
inline fun debugLogCounter(message: String) = DebugManager.d(DebugManager.Module.COUNTER, message)
