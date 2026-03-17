use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crate::algorithm::shared_tactics::is_real_four_threat;
use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::{evaluate_position, SCORE_BLOCKED_FOUR, SCORE_FOUR, SCORE_THREE, SCORE_TWO};

mod eval_cache;
mod evaluation;
mod helpers;
mod lifecycle;
mod opening;
mod search;
mod tactics;
mod transposition_table;
mod types;
mod zobrist;

#[cfg(test)]
mod tests;

use eval_cache::EvalCache;
use helpers::{
    is_real_four_threat_if_placed,
    vcf_win_threats,
    when_threat_penalty,
};
use transposition_table::{TranspositionTable, TtFlag, TtStats};
pub use types::{MinimaxAi, MinimaxConfig};
use types::{ThreatProfile, ThreatSummary};
use zobrist::ZobristTable;

/// 胜利分数（五连或搜索路径内的强制赢棋）
const WIN_SCORE: i32 = 1_000_000;

/// 杀棋检测阈值：根节点得分超过此值视为找到了强制赢棋路线，无需继续加深
const KILL_THRESHOLD: i32 = WIN_SCORE / 2;

/// 时间检查频率（每 N 个节点检查一次时间；should_stop 每次都检查）
const TIME_CHECK_FREQ: u64 = 256;

/// 进度回调节流参数（避免 JNI 高频调用影响算力）
const PROGRESS_REPORT_INTERVAL_MS: u64 = 50;
const PROGRESS_MIN_DELTA_PERCENT: i32 = 1;
const PROGRESS_NODE_CHECK_FREQ: u64 = 512;

/// 智能加深：剩余时间 / 上一层耗时 >= 此值时，激进地跳 2 层
const FAST_JUMP_RATIO: u64 = 8;

/// 智能加深：预估下一层可接受的最低时间倍数
/// 剩余时间 >= 上一层耗时 * AFFORD_RATIO 才认为值得继续
const AFFORD_RATIO: u64 = 3;

/// VCF（连续冲四取胜）搜索最大层数
/// 每"轮"消耗 2 层（己方冲四 + 对方强制应手），所以：
///   HARD(10)  = 最多 5 次连续冲四序列
///   MASTER(20) = 最多 10 次连续冲四序列（可发现极长的强制路线）
const VCF_DEPTH_HARD: i32 = 10;
const VCF_DEPTH_MASTER: i32 = 20;

/// 根节点 Aspiration Window 参数
const ASPIRATION_MARGIN_BASE: i32 = 1_200;
const ASPIRATION_MARGIN_STEP: i32 = 2_000;
const ASPIRATION_RETRY_MAX: i32 = 2;

/// MASTER 根节点候选去冗余：前排候选若过于邻近，延后处理
const MASTER_REDUNDANT_RADIUS: i32 = 1;

/// 双威胁阈值： find_critical_block 在连四查找失败后的备用阈值。
/// 覆盖双活三（≈ 2×1000 + 5000 = 7000）、三四组合（≈24000）等
/// ——这些期望在 minimax 之前就强制封堵。
const DOUBLE_THREAT_THRESHOLD: i32 = 6_000;

/// 记录最近若干次决策（主要用于“撤销后反复同手”惩罚）
const DECISION_HISTORY_CAP: usize = 24;

/// Killer/History Heuristic 参数
const MAX_PLY: usize = 64;
const HISTORY_MAX: i32 = 2_000_000;
const LOCAL_EVAL_RADIUS: i32 = 3;

