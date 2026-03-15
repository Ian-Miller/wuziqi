package io.github.ian_miller.wuziqi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GomokuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // AI配置已简化，无需额外初始化
    }
}