use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64};
use std::sync::Arc;
use std::time::Instant;

use crate::board::{Color, BOARD_SIZE};

use super::eval_cache::EvalCache;
use super::transposition_table::TranspositionTable;
use super::zobrist::ZobristTable;

#[derive(Clone, Copy, Default)]
pub(super) struct ThreatSummary {
    pub(super) best_score: i32,
    pub(super) win_moves: i32,
    pub(super) double_threats: i32,
    pub(super) blocked_fours: i32,
    pub(super) threes: i32,
}

#[derive(Clone, Copy, Default)]
pub(super) struct ThreatProfile {
    pub(super) best_score: i32,
    pub(super) real_fours: i32,
    pub(super) blocked_fours: i32,
    pub(super) live_threes: i32,
    pub(super) twos: i32,
}

#[derive(Clone, Copy)]
pub struct MinimaxConfig {
    pub max_depth: i32,
    pub time_limit_ms: u64,
    pub player: Color,
}

impl Default for MinimaxConfig {
    fn default() -> Self {
        Self {
            max_depth: 4,
            time_limit_ms: 5000,
            player: Color::Black,
        }
    }
}

pub struct MinimaxAi {
    pub(super) config: MinimaxConfig,
    pub(super) should_stop: Arc<AtomicBool>,
    pub(super) node_count: AtomicU64,
    pub(super) start_time: Instant,
    pub(super) turn_time_limit_ms: u64,
    pub(super) last_completed_depth: i32,
    pub(super) last_depth_time_ms: u64,
    pub(super) last_root_score: i32,
    pub(super) recent_decisions: VecDeque<(u64, (usize, usize))>,
    pub(super) killer_moves: Vec<[Option<(usize, usize)>; 2]>,
    pub(super) history_scores: [[i32; BOARD_SIZE * BOARD_SIZE]; 2],
    pub(super) eval_cache: EvalCache,
    pub(super) tt: TranspositionTable,
    pub(super) zobrist: ZobristTable,
    pub best_move_encoded: Arc<AtomicI32>,
}