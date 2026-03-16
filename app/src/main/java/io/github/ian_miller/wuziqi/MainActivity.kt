package io.github.ian_miller.wuziqi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.ian_miller.wuziqi.ui.theme.GomokuTheme
import io.github.ian_miller.wuziqi.ui.game.GameApp
import dagger.hilt.android.AndroidEntryPoint

import androidx.activity.enableEdgeToEdge

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GomokuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 使用导航组件
                    GameApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLifecycleState.isInForeground = true
    }

    override fun onStop() {
        super.onStop()
        AppLifecycleState.isInForeground = false
    }
}