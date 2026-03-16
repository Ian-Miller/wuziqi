package io.github.ian_miller.wuziqi

/**
 * 全局前台状态。
 * 由 MainActivity.onStart / onStop 维护，用于在 app 退到后台时抑制音效和震动。
 *
 * 使用 onStop（而非 onPause）：onPause 在弹窗、权限对话框等部分遮挡时也会触发，
 * 只有 onStop 才代表整个 Activity 真正不可见。
 */
object AppLifecycleState {
    @Volatile var isInForeground: Boolean = true
}
