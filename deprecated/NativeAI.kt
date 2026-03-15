package io.github.ian_miller.wuziqi.ai

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AI 类型枚举
 */
enum class AIType(val value: Int) {
    MASTER_AI(1),
    TEST_AI(0),
    BEGINNER_AI(2)
}

/**
 * AI 状态常量（与 C++ AI_STATE 枚举保持一致）
 */
object AI_STATE {
    const val VALID = 0
    const val INVALID = 1
}

/**
 * Native AI 封装 - 支持协作式取消
 *
 * @deprecated C++ JNI 桥接层，已由 RustAi.kt（Rust via JNI）取代。此文件通过 sourceSets 排除，不参与编译。
 */
@Deprecated(
    message = "C++ AI JNI 桥接层，已由 RustAi.kt 取代。此文件通过 sourceSets 排除，不参与编译。",
    level = DeprecationLevel.ERROR
)
class NativeAI private constructor(
    private val ptr: Long,
    val type: AIType
) {

    companion object {
        private const val TAG = "NativeAI"

        init {
            System.loadLibrary("gomoku-ai")
        }

        fun create(
            type: AIType = AIType.MASTER_AI,
            maxDepth: Int = 6,
            timeLimitMs: Int = 2000,
            ttSizeMB: Int = 64,
            useTT: Boolean = true
        ): NativeAI? {
            val ptr = nativeCreate(type.value, maxDepth, timeLimitMs, ttSizeMB, useTT)
            return if (ptr != 0L) {
                Log.d(TAG, "Created ${type.name} at $ptr")
                NativeAI(ptr, type)
            } else {
                Log.e(TAG, "Failed to create AI")
                null
            }
        }

        @JvmStatic private external fun nativeCreate(
            type: Int, maxDepth: Int, timeLimitMs: Int, ttSizeMB: Int, useTT: Boolean
        ): Long

        @JvmStatic private external fun nativeDestroy(ptr: Long)
        @JvmStatic private external fun nativeValidate(ptr: Long, state: Int)
        @JvmStatic private external fun nativeTakeTurn(ptr: Long, config: AIConfig): AIResult
    }

    private var isDestroyed = false

    /**
     * 轮到你行动 - 支持协作式取消的 suspend 函数
     */
    suspend fun takeTurn(config: AIConfig): AIResult {
        check(!isDestroyed) { "AI destroyed" }

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                Log.d(TAG, "takeTurn cancelled, invalidating AI")
                invalidate()
            }

            Thread {
                try {
                    val result = nativeTakeTurn(ptr, config)
                    if (continuation.isActive) {
                        if (result != null) {
                            continuation.resume(result)
                        } else {
                            continuation.resume(AIResult.invalid())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "takeTurn error: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(AIResult.invalid())
                    }
                }
            }.start()
        }
    }

    /**
     * 设置 AI 验证状态
     * @param isValid true=VALID, false=INVALID（默认为 true）
     */
    @JvmOverloads
    fun validate(isValid: Boolean = true) {
        if (!isDestroyed) {
            nativeValidate(ptr, if (isValid) AI_STATE.VALID else AI_STATE.INVALID)
        }
    }
    
    /**
     * 使 AI 无效（取消思考）
     */
    fun invalidate() = validate(false)

    fun destroy() {
        if (!isDestroyed) {
            isDestroyed = true
            nativeDestroy(ptr)
        }
    }
}

// 便捷创建函数
fun createMasterAI(
    maxDepth: Int = 6,
    timeLimitMs: Int = 2000
): NativeAI? = NativeAI.create(
    type = AIType.MASTER_AI,
    maxDepth = maxDepth,
    timeLimitMs = timeLimitMs
)

// 棋盘转位棋盘
fun Array<IntArray>.toBitBoard(): Pair<LongArray, LongArray> {
    val black = LongArray(4)
    val white = LongArray(4)
    for (r in 0..14) {
        for (c in 0..14) {
            val pos = r * 15 + c
            val idx = pos / 64
            val bit = pos % 64
            when (this[r][c]) {
                1 -> black[idx] = black[idx] or (1L shl bit)
                2 -> white[idx] = white[idx] or (1L shl bit)
            }
        }
    }
    return Pair(black, white)
}
