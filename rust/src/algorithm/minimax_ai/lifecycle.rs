use super::*;
use crate::algorithm::lifecycle::{HeartbeatConfig, ProgressState, SearchLifecycle};
use crate::algorithm::shared_tactics::{find_immediate_win, find_must_block};

impl SearchLifecycle for MinimaxAi {
    fn stop_flag(&self) -> &AtomicBool {
        &self.should_stop
    }

    fn elapsed_ms(&self) -> u64 {
        self.start_time.elapsed().as_millis() as u64
    }

    fn time_limit_ms(&self) -> u64 {
        self.turn_time_limit_ms
    }

    fn heartbeat_config(&self) -> HeartbeatConfig {
        HeartbeatConfig {
            report_interval_ms: PROGRESS_REPORT_INTERVAL_MS,
            min_delta_percent: PROGRESS_MIN_DELTA_PERCENT,
            stop_check_interval: TIME_CHECK_FREQ,
            progress_check_interval: PROGRESS_NODE_CHECK_FREQ,
            progress_cap: 97,
        }
    }
}

impl MinimaxAi {
    pub(super) fn tt_stats(&self) -> TtStats {
        self.tt.stats()
    }

    pub fn take_turn_with_progress(
        &mut self,
        board: &Board,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
    ) -> Option<(usize, usize)> {
        self.node_count.store(0, Ordering::Relaxed);
        self.start_time = Instant::now();
        self.turn_time_limit_ms = self.choose_turn_time_budget(board);

        let mut progress = ProgressState::new();
        self.maybe_report_progress(on_progress, 0, &mut progress, true);

        if let Some(m) = self.opening_book(board) {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return Some(m);
        }

        let moves = self.generate_ordered_moves(board);
        if moves.is_empty() {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return None;
        }

        if let Some(m) = find_immediate_win(board, &moves, self.config.player) {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return Some(m);
        }
        if let Some(m) = find_must_block(board, &moves, self.config.player) {
            self.maybe_report_progress(on_progress, 100, &mut progress, true);
            return Some(m);
        }

        if self.config.max_depth >= 12 {
            if let Some(m) = self.find_forced_win(board, &moves) {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
            if let Some(m) = self.find_critical_block(board, &moves) {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
            if let Some(m) = self.find_mainline_defense(board, &moves) {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
        }

        if self.config.max_depth >= 12 && self.time_ok() {
            let vcf_depth = if self.config.max_depth >= 20 {
                VCF_DEPTH_MASTER
            } else {
                VCF_DEPTH_HARD
            };
            if let Some(m) = self.vcf_search(&mut board.clone(), self.config.player, vcf_depth) {
                self.maybe_report_progress(on_progress, 100, &mut progress, true);
                return Some(m);
            }
            if self.config.max_depth >= 20 && self.time_ok() {
                if let Some(m) = self.vcf_defense(&mut board.clone(), self.config.player, vcf_depth) {
                    self.maybe_report_progress(on_progress, 100, &mut progress, true);
                    return Some(m);
                }
            }
        }

        let mv = self.iterative_deepening(
            board,
            &moves,
            on_progress,
            &mut progress,
        );
        self.maybe_report_progress(on_progress, 100, &mut progress, true);
        Some(mv)
    }

    pub fn new(config: MinimaxConfig) -> Self {
        Self {
            config,
            should_stop: Arc::new(AtomicBool::new(false)),
            node_count: AtomicU64::new(0),
            start_time: Instant::now(),
            turn_time_limit_ms: config.time_limit_ms,
            last_completed_depth: 0,
            last_depth_time_ms: 0,
            last_root_score: 0,
            recent_decisions: VecDeque::with_capacity(DECISION_HISTORY_CAP),
            killer_moves: vec![[None, None]; MAX_PLY],
            history_scores: [[0; BOARD_SIZE * BOARD_SIZE]; 2],
            eval_cache: EvalCache::new(),
            tt: TranspositionTable::new(),
            zobrist: ZobristTable::new(),
            best_move_encoded: Arc::new(AtomicI32::new(-1)),
        }
    }

    pub fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        let mut none: Option<&mut dyn FnMut(i32)> = None;
        self.take_turn_with_progress(board, &mut none)
    }

    /// 检查是否应继续搜索（should_stop Acquire 读 + 时间限制）
    fn should_continue(&self) -> bool {
        if self.should_stop.load(Ordering::Acquire) {
            return false;
        }
        let n = self.node_count.fetch_add(1, Ordering::Relaxed);
        if n % TIME_CHECK_FREQ == 0 {
            if self.elapsed_ms() > self.turn_time_limit_ms {
                return false;
            }
        }
        true
    }

    /// 外层快速时间 + 停止检查（用于每个候选走法前）
    pub(super) fn time_ok(&self) -> bool {
        if self.should_stop.load(Ordering::Acquire) {
            return false;
        }
        self.start_time.elapsed().as_millis() as u64 <= self.turn_time_limit_ms
    }

    pub fn invalidate(&self) {
        self.invalidate_search();
    }

    pub fn validate(&self) {
        self.validate_search();
    }

    pub fn clear(&mut self) {
        self.node_count.store(0, Ordering::Relaxed);
        self.start_time = Instant::now();
        self.turn_time_limit_ms = self.config.time_limit_ms;
        self.decay_history();
        self.eval_cache.clear();
        self.tt.clear();
    }

    pub fn co_validate(&self) -> bool {
        self.should_continue()
    }
}
