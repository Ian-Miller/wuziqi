package io.github.ian_miller.wuziqi.ai.future

/**
 * 威胁等级枚举
 * 
 * 用于表示各种威胁的优先级，从必胜到潜在威胁。
 */
enum class ThreatLevel {
    FIVE,           // 五连（必胜）
    OPEN_FOUR,      // 活四（必胜）
    CLOSED_FOUR,    // 冲四（一步杀）
    OPEN_THREE,     // 活三（两步杀）
    CLOSED_THREE,   // 眠三（潜在威胁）
    OPEN_TWO,       // 活二（发展潜力）
    POTENTIAL       // 潜在位置
}
