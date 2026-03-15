# ♟ 五子棋 (Gomoku)

一款功能完整的 Android 五子棋游戏，支持人机对战、本地双人对战和联机对战。

[隐私政策](https://Ian-Miller.github.io/wuziqi/privacy.html)

---

## 功能特性

- **人机对战** — 4 种 AI 难度（简单 / 中等 / 困难 / 大师）
- **双人对战** — 本地双人对弈，支持 AI 辅助提示
- **联机对战** — 基于 Nostr 协议的点对点联机，扫码或输入房间码加入
- **玩家档案** — 创建玩家、记录胜负统计
- **续局功能** — 意外退出后可恢复对局
- **撤悔** — 人机模式可撤回两步，双人模式撤回一步
- **音效 & 震动** — 落子音效与触感反馈

## AI 难度说明

| 难度 | 算法 | 思考时间 |
|------|------|---------|
| 简单 | Guided MCTS | 0.5 秒 |
| 中等 | Guided MCTS | 1.5 秒 |
| 困难 | Minimax + Alpha-Beta | 4 秒 |
| 大师 | Minimax + Alpha-Beta | 12 秒 |

## 技术栈

- **语言**: Kotlin 2.3
- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + StateFlow + Actor 状态机
- **AI 引擎**: Rust（通过 JNI 调用）
- **联机**: Nostr 协议 + WebSocket
- **依赖注入**: Hilt
- **数据库**: Room

## 构建

### 前置要求

- Android Studio（最新稳定版）
- JDK 17+
- Android SDK API 31–36
- Rust + cargo-ndk（用于编译 AI 引擎）

### 编译 AI 引擎

```bash
# 在 WSL 或 Linux 环境中
cd rust
./build_wsl.sh
```

### 编译 APK

```bash
./gradlew :app:assembleDebug
```

## 系统要求

- Android 12（API 31）及以上
- 仅支持 arm64-v8a 架构

## 许可证

MIT License
