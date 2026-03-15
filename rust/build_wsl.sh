#!/bin/bash
set -e

echo "🔨 Building Rust for Android (via WSL)..."

cd "$(dirname "$0")"

# 优先使用 WSL 中的 NDK（Linux 原生）
if [ -d "$HOME/android-sdk/ndk" ]; then
    NDK=$(ls -v "$HOME/android-sdk/ndk" 2>/dev/null | tail -1)
    if [ -n "$NDK" ]; then
        export ANDROID_NDK_HOME="$HOME/android-sdk/ndk/$NDK"
        echo "📁 Using WSL NDK: $ANDROID_NDK_HOME"
    fi
fi

# 如果 WSL 中没有，尝试检测 Windows NDK（可能不兼容，仅作后备）
if [ -z "$ANDROID_NDK_HOME" ]; then
    NDK_PATHS=(
        "/mnt/d/Android/Sdk/ndk"
        "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/ndk"
    )
    for base in "${NDK_PATHS[@]}"; do
        if [ -d "$base" ]; then
            NDK=$(ls -v "$base" 2>/dev/null | tail -1)
            if [ -n "$NDK" ]; then
                export ANDROID_NDK_HOME="$base/$NDK"
                echo "⚠️  Using Windows NDK (may not work in WSL): $ANDROID_NDK_HOME"
                echo "💡 Run ./setup_ndk_wsl.sh to install Linux NDK"
                break
            fi
        fi
    done
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ NDK not found!"
    echo ""
    echo "Please run: ./setup_ndk_wsl.sh"
    exit 1
fi

# 检查依赖
if ! command -v cargo-ndk &> /dev/null; then
    echo "📦 Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# 构建
echo "🎯 Building for arm64-v8a..."
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

echo "✅ Success!"
echo "📁 Output: ../app/src/main/jniLibs/arm64-v8a/libgomoku_rust.so"

# 显示文件大小
if [ -f "../app/src/main/jniLibs/arm64-v8a/libgomoku_rust.so" ]; then
    ls -lh ../app/src/main/jniLibs/arm64-v8a/libgomoku_rust.so
fi

echo ""
echo "👉 Now switch to Android Studio and click 'Run'"
