use std::sync::atomic::{AtomicBool, AtomicI32};
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use super::*;
use crate::algorithm::lifecycle::{HeartbeatConfig, ProgressState, SearchLifecycle};
use crate::algorithm::shared_tactics::{find_immediate_win, find_must_block};

impl SearchLifecycle for MctsAi {
    fn stop_flag(&self) -> &AtomicBool {
        &self.should_stop
    }

    fn elapsed_ms(&self) -> u64 {
        self.start_time.elapsed().as_millis() as u64
    }

    fn time_limit_ms(&self) -> u64 {
        self.config.time_limit_ms
    }

    fn heartbeat_config(&self) -> HeartbeatConfig {
        HeartbeatConfig {
            report_interval_ms: PROGRESS_REPORT_INTERVAL_MS,
            min_delta_percent: PROGRESS_MIN_DELTA_PERCENT,
            stop_check_interval: TIME_CHECK_INTERVAL,
            progress_check_interval: PROGRESS_HEARTBEAT_INTERVAL,
            progress_cap: 97,
        }
    }
}

impl MctsAi {
    pub fn take_turn_with_progress(
        &mut self,
        board: &Board,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
    ) -> Option<(usize, usize)> {
        self.start_time = Instant::now();

        let mut progress = ProgressState::new();
        self.maybe_report_progress(on_progress, 0, &mut progress, true);

        if let Some(m) = self.opening_book(board) {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return Some(m);
        }

        let all_moves = board.generate_moves();
        if all_moves.is_empty() {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return None;
        }

        let mut candidates = top_k_moves(board, self.config.player, self.config.max_children);
        let vision_miss = if self.easy_mode {
            self.narrow_vision_prob + self.attention_miss_prob(board) * 0.5
        } else {
            self.narrow_vision_prob
        };
        if self.roll(vision_miss) && candidates.len() > 5 {
            let keep = if self.easy_mode { 4 } else { 5 };
            candidates.truncate(keep);
        }
        if candidates.is_empty() {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return None;
        }

        if let Some(m) = find_immediate_win(board, &candidates, self.config.player) {
            let diag_miss = self.easy_mode
                && is_diagonal_threat(board, m.0, m.1, self.config.player)
                && self.roll(EASY_DIAGONAL_MISS_ATTACK);
            if !diag_miss {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
        }

        if let Some(m) = find_must_block(board, &all_moves, self.config.player) {
            let diag_miss = self.easy_mode
                && is_diagonal_threat(board, m.0, m.1, self.config.player.opponent())
                && self.roll(EASY_DIAGONAL_MISS_DEFENSE);
            if !diag_miss {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
        }

        let attention = self.attention_miss_prob(board);
        let skip_own_forced = self.easy_mode && self.roll(0.15 + attention * 0.30);
        let skip_opponent_block = self.easy_mode && self.roll(0.32 + attention * 0.50);

        if !skip_own_forced {
            if let Some(m) = self.find_forced_win(board, &all_moves) {
                let diag_miss = self.easy_mode
                    && is_diagonal_threat(board, m.0, m.1, self.config.player)
                    && self.roll(EASY_DIAGONAL_MISS_ATTACK + 0.10);
                if !diag_miss {
                    self.maybe_report_progress(on_progress, 100, &mut progress, true);
                    return Some(m);
                }
            }
        }

        if !skip_opponent_block {
            if let Some(m) = self.find_critical_block(board, &all_moves) {
                let diag_miss = self.easy_mode
                    && is_diagonal_threat(board, m.0, m.1, self.config.player.opponent())
                    && self.roll(EASY_DIAGONAL_MISS_DEFENSE + 0.10);
                if !diag_miss {
                    self.maybe_report_progress(on_progress, 100, &mut progress, true);
                    return Some(m);
                }
            }
        }

        let mv = self.mcts_search(board, on_progress, &mut progress);
        self.maybe_report_progress(on_progress, 100, &mut progress, true);
        mv
    }

    pub fn new(config: MctsConfig) -> Self {
        let is_easy = config.exploration_c >= 1.8 || config.max_children >= MAX_CHILDREN_EASY;
        let (mistake_prob, narrow_vision_prob, sample_temperature, sample_top_n) = if is_easy {
            (0.30, 0.38, 1.8, 5)
        } else {
            (0.04, 0.06, 0.55, 2)
        };

        let seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x9E37_79B9_7F4A_7C15);

        Self {
            config,
            should_stop: Arc::new(AtomicBool::new(false)),
            start_time: Instant::now(),
            easy_mode: is_easy,
            mistake_prob,
            narrow_vision_prob,
            sample_temperature,
            sample_top_n,
            rng_state: seed ^ 0xA076_1D64_78BD_642F,
            best_move_encoded: Arc::new(AtomicI32::new(-1)),
        }
    }

    pub fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        let mut none: Option<&mut dyn FnMut(i32)> = None;
        self.take_turn_with_progress(board, &mut none)
    }

    pub fn invalidate(&self) {
        self.invalidate_search();
    }

    pub fn validate(&self) {
        self.validate_search();
    }

    pub fn clear(&mut self) {
        self.start_time = Instant::now();
    }

    pub(super) fn attention_miss_prob(&self, board: &Board) -> f64 {
        let pieces = board.move_count;
        if pieces < 6 {
            0.0
        } else if pieces < 30 {
            0.10 + (pieces as f64 - 6.0) / 24.0 * 0.30
        } else {
            (0.40 + (pieces as f64 - 30.0) / 40.0 * 0.15).min(0.55)
        }
    }

    pub(super) fn rand_u64(&mut self) -> u64 {
        let mut x = self.rng_state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.rng_state = x;
        x
    }

    pub(super) fn rand_f64(&mut self) -> f64 {
        let v = self.rand_u64() >> 11;
        (v as f64) / ((1u64 << 53) as f64)
    }

    pub(super) fn roll(&mut self, p: f64) -> bool {
        if p <= 0.0 {
            return false;
        }
        if p >= 1.0 {
            return true;
        }
        self.rand_f64() < p
    }
}