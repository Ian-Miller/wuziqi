#!/bin/bash
# 在 WSL 中安装 Android NDK（Linux 版）

set -e

echo "📦 Setting up Android NDK in WSL..."

# 创建目录
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools

# 下载命令行工具（如果还没有）
if [ ! -d "latest" ]; then
    echo "⬇️  Downloading Android command line tools..."
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q commandlinetools-linux-11076708_latest.zip
    mv cmdline-tools latest
    rm commandlinetools-linux-11076708_latest.zip
fi

# 设置环境变量（如果还没有）
if ! grep -q "ANDROID_HOME" ~/.bashrc; then
    echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
    echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
    echo "✅ Added ANDROID_HOME to ~/.bashrc"
fi

# 加载环境变量
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 安装 NDK
echo "⬇️  Installing NDK..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install "ndk;27.2.12479018" "platform-tools"

echo "✅ NDK installed successfully!"
echo "📁 Location: $ANDROID_HOME/ndk/27.2.12479018"
echo ""
echo "📝 Please run: source ~/.bashrc"
echo "   Or restart your terminal"
