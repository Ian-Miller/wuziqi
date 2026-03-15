# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# BouncyCastle：保留所有类，防止 R8 裁剪加密算法实现
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# CameraX：保留核心类防止 R8 裁剪
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ML Kit Barcode：保留扫码相关类
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

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
-keep class io.github.ian_miller.wuziqi.** { *; }