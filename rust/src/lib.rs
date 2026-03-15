//! Gomoku Rust AI 引擎
//! 
//! 这个库提供五子棋 AI 功能，通过 JNI 与 Kotlin/Android 交互。
//! 
//! ## 架构
//! 
//! - **lib.rs** (本文件): JNI bridge，处理 Kotlin ↔ Rust 的 FFI 调用
//! - **ai_engine.rs**: Minimax + Alpha-Beta + 迭代加深 AI（HARD / MASTER 难度）
//! - **mcts_ai.rs**: Guided MCTS AI（EASY / MEDIUM 难度）
//! - **board.rs**: 棋盘表示和规则
//! - **evaluator.rs**: 棋局评估函数
//! - **ai.rs**: 搜索算法（minimax 等）
//! 
//! ## JNI 接口
//! 
//! Kotlin 通过 `RustAi` 类调用本库的 native 方法：
//! - `nativeCreate`: 创建 Minimax AI 实例（HARD/MASTER）
//! - `nativeCreateMcts`: 创建 MCTS AI 实例（EASY/MEDIUM）
//! - `nativeTakeTurn`: 执行思考
//! - `nativeInvalidate`: 请求停止
//! - `nativeClear`: 清理状态
//! - `nativeDestroy`: 销毁实例

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

// 导出内部模块
mod ai;
mod ai_engine;
mod mcts_ai;
mod board;
mod evaluator;

use ai_engine::{AiConfig, GomokuAi};
use mcts_ai::{MctsAi, MctsConfig};
use board::{Board, Color};

// ============================================================================
// AI 类型统一包装（通过原始指针传递给 Kotlin）
// ============================================================================

/// 统一 AI 枚举，允许同一套 JNI 接口同时支持两种 AI 实现。
/// Box 分配在堆上，通过 `Box::into_raw` 转为 `*mut AnyAi` 传给 Kotlin，
/// 再由 Kotlin 以 `jlong` 保存并在每次调用时传回。
enum AnyAi {
    Minimax(GomokuAi),
    Mcts(MctsAi),
}

impl AnyAi {
    fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        match self {
            AnyAi::Minimax(ai) => ai.take_turn(board),
            AnyAi::Mcts(ai) => ai.take_turn(board),
        }
    }

    fn invalidate(&self) {
        match self {
            AnyAi::Minimax(ai) => ai.invalidate(),
            AnyAi::Mcts(ai) => ai.invalidate(),
        }
    }

    fn validate(&self) {
        match self {
            AnyAi::Minimax(ai) => ai.validate(),
            AnyAi::Mcts(ai) => ai.validate(),
        }
    }

    fn clear(&mut self) {
        match self {
            AnyAi::Minimax(ai) => ai.clear(),
            AnyAi::Mcts(ai) => ai.clear(),
        }
    }
}

// ============================================================================
// JNI 工具
// ============================================================================

fn color_from_int(player: jint) -> Color {
    if player == 1 { Color::Black } else { Color::White }
}

unsafe fn ai_from_ptr<'a>(ptr: jlong) -> Option<&'a mut AnyAi> {
    if ptr == 0 { None } else { Some(&mut *(ptr as *mut AnyAi)) }
}

// ============================================================================
// JNI 接口：创建
// ============================================================================

/// 创建 Minimax AI（HARD / MASTER 难度）
/// Java: long nativeCreate(int maxDepth, int timeLimitMs, int player)
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    max_depth: jint,
    time_limit_ms: jint,
    player: jint,
) -> jlong {
    let config = AiConfig {
        max_depth,
        time_limit_ms: time_limit_ms as u64,
        player: color_from_int(player),
    };
    let ai = Box::new(AnyAi::Minimax(GomokuAi::new(config)));
    Box::into_raw(ai) as jlong
}

/// 创建 MCTS AI（EASY / MEDIUM 难度）
/// Java: long nativeCreateMcts(int timeLimitMs, int player, float explorationC)
/// explorationC: 探索常数（EASY=2.0，MEDIUM=1.2）
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeCreateMcts(
    _env: JNIEnv,
    _class: JClass,
    time_limit_ms: jint,
    player: jint,
    exploration_c_x100: jint, // 传入 C*100 的整数以避免浮点 JNI 问题
) -> jlong {
    let color = color_from_int(player);
    let exploration_c = exploration_c_x100 as f64 / 100.0;
    let max_children = if exploration_c >= 1.8 {
        mcts_ai::MAX_CHILDREN_EASY
    } else {
        mcts_ai::MAX_CHILDREN_MEDIUM
    };
    let config = MctsConfig {
        player: color,
        time_limit_ms: time_limit_ms as u64,
        exploration_c,
        max_children,
    };
    let ai = Box::new(AnyAi::Mcts(MctsAi::new(config)));
    Box::into_raw(ai) as jlong
}

// ============================================================================
// JNI 接口：生命周期
// ============================================================================

/// 销毁 AI 对象
/// Java: void nativeDestroy(long ptr)
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 { return; }
    unsafe { let _ = Box::from_raw(ptr as *mut AnyAi); }
}

/// 清理资源（重置内部状态）
/// Java: void nativeClear(long ptr)
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeClear(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if let Some(ai) = unsafe { ai_from_ptr(ptr) } {
        ai.clear();
    }
}

// ============================================================================
// JNI 接口：核心功能
// ============================================================================

/// 执行思考（takeTurn）
/// Java: int nativeTakeTurn(long ptr, byte[] boardData)
/// 返回: row * 15 + col，如果取消则返回 -1
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeTakeTurn(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    board_data: jni::objects::JByteArray,
) -> jint {
    if ptr == 0 { return -1; }

    let bytes = match env.convert_byte_array(&board_data) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    if bytes.len() < 225 { return -1; }

    let board = Board::from_bytes(&bytes);
    let ai = match unsafe { ai_from_ptr(ptr) } {
        Some(a) => a,
        None => return -1,
    };

    match ai.take_turn(&board) {
        Some((row, col)) => (row * 15 + col) as jint,
        None => -1,
    }
}

/// 使当前计算失效
/// Java: void nativeInvalidate(long ptr)
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeInvalidate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if let Some(ai) = unsafe { ai_from_ptr(ptr) } {
        ai.invalidate();
    }
}

/// 恢复计算有效
/// Java: void nativeValidate(long ptr)
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeValidate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if let Some(ai) = unsafe { ai_from_ptr(ptr) } {
        ai.validate();
    }
}

// ============================================================================
// JNI 接口：测试函数
// ============================================================================

/// 测试多实例创建
/// Java: boolean nativeTestMultiInstance()
#[no_mangle]
pub extern "C" fn Java_io_github_ian_1miller_wuziqi_ai_RustAi_nativeTestMultiInstance(
    _env: JNIEnv,
    _class: JClass,
) -> bool {
    let ai1 = Box::new(AnyAi::Minimax(GomokuAi::new(AiConfig::default())));
    let ai2 = Box::new(AnyAi::Mcts(MctsAi::new(MctsConfig::easy(Color::Black))));
    let ptr1 = Box::into_raw(ai1);
    let ptr2 = Box::into_raw(ai2);
    let different = ptr1 != ptr2;
    unsafe {
        let _ = Box::from_raw(ptr1);
        let _ = Box::from_raw(ptr2);
    }
    different
}
