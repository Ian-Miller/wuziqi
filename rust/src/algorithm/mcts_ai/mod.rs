//! Guided MCTS (Monte Carlo Tree Search) AI 引擎
//!
//! 适合 EASY 和 MEDIUM 难度：
//! - EASY：探索常数 C=2.0，时间短，更随机，也更容易犯错
//! - MEDIUM：探索常数 C=1.2，时间中等，兼顾探索与利用

use crate::board::{Board, Color};
use crate::evaluator::{evaluate_position, SCORE_FOUR};

mod heuristics;
mod lifecycle;
mod node;
mod opening;
mod search;
mod tactics;
mod types;

pub use types::{MctsAi, MctsConfig};

use heuristics::{is_diagonal_threat, top_k_moves};
use node::MctsNode;

const WIN_VALUE: f64 = 1.0;
const LOSE_VALUE: f64 = -1.0;
const EVAL_SCALE: f64 = 100_000.0;

pub const MAX_CHILDREN_EASY: usize = 15;
pub const MAX_CHILDREN_MEDIUM: usize = 12;

const TIME_CHECK_INTERVAL: u64 = 64;
const PROGRESS_HEARTBEAT_INTERVAL: u64 = 32;

const EASY_DIAGONAL_MISS_ATTACK: f64 = 0.55;
const EASY_DIAGONAL_MISS_DEFENSE: f64 = 0.72;

const PROGRESS_REPORT_INTERVAL_MS: u64 = 50;
const PROGRESS_MIN_DELTA_PERCENT: i32 = 1;

const THREAT_WIN: i32 = 8_000_000;
const THREAT_BLOCK_WIN: i32 = 7_000_000;
const THREAT_FOUR: i32 = 600_000;
const THREAT_DOUBLE_FOUR: i32 = 900_000;
const THREAT_DOUBLE_THREE: i32 = 500_000;
const THREAT_THREE_FOUR: i32 = 700_000;
const THREAT_THREE: i32 = 50_000;
