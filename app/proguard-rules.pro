# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# BouncyCastle：保留所有类，防止 R8 裁剪加密算法实现
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* *;
}

# Hilt
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keepclassmembers @dagger.hilt.android.AndroidEntryPoint class * {
    @dagger.hilt.android.AndroidEntryPoint <init>();
}

# Compose
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.internal.ComposableLambda { *; }
-keep class androidx.compose.runtime.internal.ComposableLambda$* { *; }

# Navigation
-keep class androidx.navigation.NavController { *; }

# Coroutines
-keep class kotlinx.coroutines.* { *; }

# ViewModel
-keep class * extends androidx.lifecycle.ViewModel { *; }

# DataStore
-keep class androidx.datastore.preferences.Preferences { *; }

# Keep all classes that might be used via reflection
# 注意：不要保留ai.minimax包，让R8可以优化掉debug代码
-keep class com.example.gomoku.** { *; }
-keepclassmembers class com.example.gomoku.** { *; }

# 允许R8优化AI调试代码（在release中完全移除）
# 使用-assumenosideeffects告诉R8这些方法没有副作用，可以安全移除
-assumenosideeffects class com.example.gomoku.ai.minimax.MinimaxContext {
    public void debugLog(...);
    public void logBoard(...);
}
-assumenosideeffects class com.example.gomoku.ai.minimax.AiDebugConfig {
    <fields>;
}

# DebugManager优化 - 当ENABLE_DEBUG_LOG = false时完全移除日志代码
-assumenosideeffects class com.example.gomoku.ai.debug.DebugManager {
    public void log(...);
    public void logBoard(...);
    public void enter(...);
    public void exit(...);
    public void resetIndent();
}
-assumenosideeffects class com.example.gomoku.ai.debug.DebugManager$Config {
    <fields>;
}
-assumenosideeffects class com.example.gomoku.ai.debug.DebugManager$Module {
    <fields>;
}
-assumenosideeffects class com.example.gomoku.ai.debug.DebugManager$Level {
    <fields>;
}