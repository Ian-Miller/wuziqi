$ErrorActionPreference = 'Stop'

Write-Host "🔨 Building Rust for Android (Windows PowerShell)..."

Set-Location -Path $PSScriptRoot

# 1) 检测 NDK（优先环境变量，其次默认 SDK 目录）
if (-not $env:ANDROID_NDK_HOME -or -not (Test-Path $env:ANDROID_NDK_HOME)) {
    $sdkCandidates = @(
        "$env:ANDROID_HOME\ndk",
        "$env:LOCALAPPDATA\Android\Sdk\ndk",
        "D:\Android\Sdk\ndk"
    )

    foreach ($base in $sdkCandidates) {
        if (Test-Path $base) {
            $latest = Get-ChildItem -Path $base -Directory | Sort-Object Name | Select-Object -Last 1
            if ($latest) {
                $env:ANDROID_NDK_HOME = $latest.FullName
                break
            }
        }
    }
}

if (-not $env:ANDROID_NDK_HOME -or -not (Test-Path $env:ANDROID_NDK_HOME)) {
    Write-Host "❌ NDK not found."
    Write-Host "Please install Android NDK from Android Studio SDK Manager"
    exit 1
}

Write-Host "📁 Using NDK: $env:ANDROID_NDK_HOME"

# 2) 检查 cargo-ndk
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Host "📦 Installing cargo-ndk..."
    cargo install cargo-ndk
}

# 3) 构建 arm64-v8a
Write-Host "🎯 Building for arm64-v8a..."
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

$soPath = "../app/src/main/jniLibs/arm64-v8a/libgomoku_rust.so"
if (Test-Path $soPath) {
    Write-Host "✅ Success!"
    Write-Host "📁 Output: $soPath"
    Get-Item $soPath | Format-List Name, Length, LastWriteTime
} else {
    Write-Host "❌ Build finished but output not found: $soPath"
    exit 1
}

Write-Host "👉 Now switch to Android Studio and click 'Run'"
