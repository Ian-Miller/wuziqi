package io.github.ian_miller.wuziqi.ui.aitest

import java.util.concurrent.atomic.AtomicInteger

/**
 * 暂停协调器 - 合并多个暂停源的信号（线程安全）
 * 
 * 状态定义：
 * - 状态 0：所有暂停源都不为暂停
 * - 状态 1：至少一个暂停源为暂停
 * 
 * 只有状态变化时才发送 Pause/Resume 命令：
 * - 0 -> 1：发送 Pause
 * - 1 -> 0：发送 Resume
 */
class PauseCoordinator(
    private val onPause: () -> Unit,
    private val onResume: () -> Unit
) {
    /**
     * 暂停源类型
     */
    enum class Source {
        USER_CLICK,      // 用户手动点击
        BACKGROUND,      // App 退到后台
        SYSTEM_DIALOG    // 系统对话框（如来电）
    }

    // 使用 ConcurrentHashMap 的 KeySet 实现线程安全的集合
    private val activeSources = java.util.concurrent.ConcurrentHashMap.newKeySet<Source>()

    // 使用 AtomicInteger 保证状态读写的原子性
    private val currentState = AtomicInteger(0)

    /**
     * 设置某个暂停源的状态（线程安全）
     * @param source 暂停源
     * @param isPaused 该源是否要求暂停
     */
    fun setSource(source: Source, isPaused: Boolean) {
        val changed = if (isPaused) {
            activeSources.add(source)
        } else {
            activeSources.remove(source)
        }

        // 即使集合没有变化（如重复添加/删除相同元素），也检查状态
        // 计算新状态：集合非空则为暂停（状态1）
        val newState = if (activeSources.isEmpty()) 0 else 1
        val oldState = currentState.get()

        // 状态变化时才发送命令
        if (oldState != newState && currentState.compareAndSet(oldState, newState)) {
            if (newState == 1) {
                onPause()
            } else {
                onResume()
            }
        }
    }

    /**
     * 清理所有暂停源（用于 Stop，线程安全）
     */
    fun clear() {
        activeSources.clear()
        currentState.set(0)
    }

    /**
     * 获取当前活跃暂停源列表（线程安全，返回快照）
     */
    fun getActiveSources(): Set<Source> = activeSources.toSet()
}
