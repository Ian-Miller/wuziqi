# Rust 构建指南（Windows + WSL 方案）

## 前置要求

1. **WSL2** 已安装（Ubuntu 推荐）
2. **WSL 中已安装 Rust**

## 首次设置（只需一次）

### 1. WSL 中安装 Rust 工具链

```bash
# 进入 WSL
wsl

# 安装 Rust（如果还没装）
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# 添加 Android 目标
rustup target add aarch64-linux-android

# 安装 cargo-ndk
cargo install cargo-ndk

# 退出 WSL
exit
```

## 日常构建

### 方法 1：双击运行（最简单）

```
双击运行：rust/build.bat
```

等待显示 "Build complete!"，然后回到 Android Studio 点击 Run。

### 方法 2：命令行

```powershell
# PowerShell 或 CMD
cd rust
build.bat
```

或直接在 WSL 中：

```bash
cd /mnt/d/Documents/deepseek/wuziqi/rust
./build_wsl.sh
```

### 方法 3：Android Studio 内

```
Gradle 面板 → Tasks → build → buildRustWsl
```

## 文件说明

| 文件 | 用途 |
|------|------|
| `rust/build.bat` | Windows 双击脚本，调用 WSL 构建 |
| `rust/build_wsl.sh` | WSL 实际构建脚本 |
| `rust/src/lib.rs` | Rust 源代码 |
| `app/src/main/jniLibs/arm64-v8a/libgomoku_rust.so` | 编译输出 |

## 故障排除

### "wsl: command not found"

WSL 未安装，在 PowerShell（管理员）中运行：

```powershell
wsl --install
# 重启电脑后继续
```

### "cargo-ndk: command not found"

在 WSL 中安装：

```bash
cargo install cargo-ndk
```

### "linker 'cc' not found"

安装 C 编译器：

```bash
sudo apt update
sudo apt install build-essential
```

## 验证构建

构建成功后，检查文件：

```powershell
ls app\src\main\jniLibs\arm64-v8a\
# 应该看到: libgomoku_rust.so
```

然后运行 Android App，应该看到：
```
🦀 Rust says:
Hello from Rust! 🦀✨
安全、快速、零崩溃！
```
