use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;
use std::collections::VecDeque;

use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::{evaluate_position, SCORE_BLOCKED_FOUR, SCORE_THREE};

/// 胜利分数（五连或搜索路径内的强制赢棋）
const WIN_SCORE: i32 = 1_000_000;

/// 杀棋检测阈值：根节点得分超过此值视为找到了强制赢棋路线，无需继续加深
const KILL_THRESHOLD: i32 = WIN_SCORE / 2;

/// 时间检查频率（每 N 个节点检查一次时间；should_stop 每次都检查）
const TIME_CHECK_FREQ: u64 = 256;

/// 进度回调节流参数（避免 JNI 高频调用影响算力）
const PROGRESS_REPORT_INTERVAL_MS: u64 = 100;
const PROGRESS_MIN_DELTA_PERCENT: i32 = 1;

/// 开局中心点
const OPENING_CENTER: (usize, usize) = (7, 7);

/// 智能加深：剩余时间 / 上一层耗时 >= 此值时，激进地跳 2 层
const FAST_JUMP_RATIO: u64 = 8;

/// 智能加深：预估下一层可接受的最低时间倍数
/// 剩余时间 >= 上一层耗时 * AFFORD_RATIO 才认为值得继续
const AFFORD_RATIO: u64 = 3;

/// 根节点 Aspiration Window 参数
const ASPIRATION_MARGIN_BASE: i32 = 1_200;
const ASPIRATION_MARGIN_STEP: i32 = 2_000;
const ASPIRATION_RETRY_MAX: i32 = 2;

/// MASTER 根节点候选去冗余：前排候选若过于邻近，延后处理
const MASTER_REDUNDANT_RADIUS: i32 = 1;

/// 记录最近若干次决策（主要用于“撤销后反复同手”惩罚）
const DECISION_HISTORY_CAP: usize = 24;

/// Killer/History Heuristic 参数
const MAX_PLY: usize = 64;
const HISTORY_MAX: i32 = 2_000_000;
const LOCAL_EVAL_RADIUS: i32 = 3;

/// 叶子静态评估缓存（直接映射）
const EVAL_CACHE_SIZE: usize = 1 << 16;
const EVAL_CACHE_MASK: usize = EVAL_CACHE_SIZE - 1;

// ============================================================================
// 置换表（Transposition Table）
// ============================================================================

/// 置换表条目类型（节点类型）
#[derive(Clone, Copy, PartialEq)]
enum TtFlag {
    Exact,      // 精确值
    LowerBound, // Alpha 截断（实际值 >= 存储值）
    UpperBound, // Beta 截断（实际值 <= 存储值）
}

/// 置换表条目
#[derive(Clone, Copy)]
struct TtEntry {
    /// Zobrist 哈希键（用于验证是否哈希冲突）
    key: u64,
    /// 搜索深度
    depth: i32,
    /// 评估值
    score: i32,
    /// 最佳走法
    best_move: Option<(usize, usize)>,
    /// 节点类型
    flag: TtFlag,
}

/// 置换表大小（2^N 个条目，用位与取模）
/// 每个条目约 24 字节，2^18 = 262144 条目 ≈ 6MB
const TT_SIZE: usize = 1 << 18; // 262144
const TT_MASK: usize = TT_SIZE - 1;

struct TranspositionTable {
    entries: Vec<Option<TtEntry>>,
}

impl TranspositionTable {
    fn new() -> Self {
        Self {
            entries: vec![None; TT_SIZE],
        }
    }

    fn clear(&mut self) {
        for e in &mut self.entries {
            *e = None;
        }
    }

    fn get(&self, key: u64) -> Option<&TtEntry> {
        let idx = (key as usize) & TT_MASK;
        self.entries[idx]
            .as_ref()
            .filter(|e| e.key == key) // 哈希冲突检测
    }

    fn set(&mut self, key: u64, depth: i32, score: i32, best_move: Option<(usize, usize)>, flag: TtFlag) {
        let idx = (key as usize) & TT_MASK;
        // 替换策略：深度越深的条目越有价值，优先保留深层结果
        if let Some(existing) = &self.entries[idx] {
            if existing.key == key && existing.depth > depth {
                return; // 已有更深的结果，不覆盖
            }
        }
        self.entries[idx] = Some(TtEntry { key, depth, score, best_move, flag });
    }
}

// ============================================================================
// 叶子评估缓存（Leaf Eval Cache）
// ============================================================================

#[derive(Clone, Copy)]
struct EvalCacheEntry {
    key: u64,
    score: i32,
}

struct EvalCache {
    entries: Vec<Option<EvalCacheEntry>>,
}

impl EvalCache {
    fn new() -> Self {
        Self {
            entries: vec![None; EVAL_CACHE_SIZE],
        }
    }

    fn clear(&mut self) {
        for e in &mut self.entries {
            *e = None;
        }
    }

    fn get(&self, key: u64) -> Option<i32> {
        let idx = (key as usize) & EVAL_CACHE_MASK;
        self.entries[idx]
            .as_ref()
            .filter(|e| e.key == key)
            .map(|e| e.score)
    }

    fn set(&mut self, key: u64, score: i32) {
        let idx = (key as usize) & EVAL_CACHE_MASK;
        self.entries[idx] = Some(EvalCacheEntry { key, score });
    }
}

// ============================================================================
// Zobrist 哈希
// ============================================================================

/// Zobrist 哈希表（每格 × 颜色 的随机数）
struct ZobristTable {
    table: [[[u64; 2]; BOARD_SIZE]; BOARD_SIZE], // [row][col][color_idx]
}

impl ZobristTable {
    fn new() -> Self {
        // 使用固定种子的简单 LCG 伪随机数生成器（保证每次运行一致）
        let mut state: u64 = 0x_deadbeef_cafef00d;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };

        let mut table = [[[0u64; 2]; BOARD_SIZE]; BOARD_SIZE];
        for row in &mut table {
            for col in row {
                col[0] = next(); // Black
                col[1] = next(); // White
            }
        }
        Self { table }
    }

    fn hash_board(&self, board: &Board) -> u64 {
        let mut h: u64 = 0;
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                match board.get(r, c) {
                    Color::Black => h ^= self.table[r][c][0],
                    Color::White => h ^= self.table[r][c][1],
                    Color::Empty => {}
                }
            }
        }
        h
    }

    fn hash_move(&self, hash: u64, row: usize, col: usize, color: Color) -> u64 {
        let color_idx = if color == Color::Black { 0 } else { 1 };
        hash ^ self.table[row][col][color_idx]
    }
}

/// AI 配置
#[derive(Clone, Copy)]
pub struct AiConfig {
    pub max_depth: i32,
    pub time_limit_ms: u64,
    pub player: Color,
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            max_depth: 4,
            time_limit_ms: 5000,
            player: Color::Black,
        }
    }
}

/// Gomoku AI 引擎
pub struct GomokuAi {
    config: AiConfig,
    /// 停止标志
    /// 写入方（Kotlin/JNI 线程）使用 Release；读取方（搜索线程）使用 Acquire。
    /// 确保跨线程可见性。
    should_stop: Arc<AtomicBool>,
    /// 节点计数器（仅搜索线程访问，用于控制时间检查频率，Relaxed 即可）
    node_count: AtomicU64,
    /// 搜索开始时间（仅搜索线程访问）
    start_time: Instant,
    /// 本回合动态时间预算（毫秒）
    turn_time_limit_ms: u64,
    /// 跨回合历史：上一次 take_turn 完成的最大搜索深度（用于智能初始深度选择）
    last_completed_depth: i32,
    /// 跨回合历史：上一次最后一层搜索的耗时 ms（用于估算下一层时间）
    last_depth_time_ms: u64,
    /// 跨回合历史：上一次根节点评估分（用于 aspiration window）
    last_root_score: i32,
    /// 最近的根节点决策历史：[(root_hash, move)]
    recent_decisions: VecDeque<(u64, (usize, usize))>,
    /// Killer Moves（每层两个）
    killer_moves: Vec<[Option<(usize, usize)>; 2]>,
    /// History Heuristic 分数表 [color_idx][move_idx]
    history_scores: [[i32; BOARD_SIZE * BOARD_SIZE]; 2],
    /// 叶子评估缓存（仅用于 depth<=0 的 static eval）
    eval_cache: EvalCache,
    /// 置换表（跨回合复用，提升中后盘与重复局面命中率）
    tt: TranspositionTable,
    /// Zobrist 哈希表（固定不变，在构造时初始化）
    zobrist: ZobristTable,
}

impl GomokuAi {
    fn maybe_report_progress(
        &self,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress_percent: i32,
        last_report_ms: &mut u64,
        last_report_percent: &mut i32,
        force: bool,
    ) {
        let p = progress_percent.clamp(0, 100);
        let now = self.start_time.elapsed().as_millis() as u64;
        let should_emit = force
            || (*last_report_ms == 0)
            || (now.saturating_sub(*last_report_ms) >= PROGRESS_REPORT_INTERVAL_MS
                && (p - *last_report_percent).abs() >= PROGRESS_MIN_DELTA_PERCENT);
        if should_emit {
            if let Some(cb) = on_progress.as_deref_mut() {
                cb(p);
            }
            *last_report_ms = now;
            *last_report_percent = p;
        }
    }

    pub fn take_turn_with_progress(
        &mut self,
        board: &Board,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
    ) -> Option<(usize, usize)> {
        // ⚠ 不在此处重置 should_stop：该标志由 Kotlin 端通过 validate()/invalidate() 独占控制。
        // 在 launchAiThinking 之前，Kotlin 端已调用 validate() 将其设为 false。
        // 若在此处重置，会导致与 Kotlin invalidate() 的竞态（信号被吃掉）。
        self.node_count.store(0, Ordering::Relaxed);
        self.start_time = Instant::now();
        self.turn_time_limit_ms = self.choose_turn_time_budget(board);

        let mut last_report_ms: u64 = 0;
        let mut last_report_percent: i32 = -1;
        self.maybe_report_progress(on_progress, 0, &mut last_report_ms, &mut last_report_percent, true);

        // 1. 开局库（前几手立即响应，不耗时）
        if let Some(m) = self.opening_book(board) {
            self.maybe_report_progress(on_progress, 100, &mut last_report_ms, &mut last_report_percent, true);
            return Some(m);
        }

        // 2. 生成并排序候选走法
        let moves = self.generate_ordered_moves(board);
        if moves.is_empty() {
            self.maybe_report_progress(on_progress, 100, &mut last_report_ms, &mut last_report_percent, true);
            return None;
        }

        // 3. 立即胜利 / 必须防守（深度 1 快速检查）
        if let Some(m) = self.find_immediate_win(board, &moves) {
            self.maybe_report_progress(on_progress, 100, &mut last_report_ms, &mut last_report_percent, true);
            return Some(m);
        }
        if let Some(m) = self.find_must_block(board, &moves) {
            self.maybe_report_progress(on_progress, 100, &mut last_report_ms, &mut last_report_percent, true);
            return Some(m);
        }

        // 4. 迭代加深搜索（含智能深度选择 + 杀棋检测）
        let mv = self.iterative_deepening(
            board,
            &moves,
            on_progress,
            &mut last_report_ms,
            &mut last_report_percent,
        );
        self.maybe_report_progress(on_progress, 100, &mut last_report_ms, &mut last_report_percent, true);
        Some(mv)
    }

    pub fn new(config: AiConfig) -> Self {
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
        }
    }

    // =========================================================================
    // 主入口：takeTurn
    // =========================================================================

    /// 执行一次思考，返回最佳走法
    pub fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        let mut none: Option<&mut dyn FnMut(i32)> = None;
        self.take_turn_with_progress(board, &mut none)
    }

    /// 根据局面阶段为当前回合分配时间预算。
    ///
    /// 目标：MASTER 在开局第 2~3 手时不必用满 12s，
    /// 但仍保留中后盘满预算，避免棋力下滑。
    fn choose_turn_time_budget(&self, board: &Board) -> u64 {
        let base = self.config.time_limit_ms;

        // 仅对 MASTER 做开局预算下调（HARD 及以下保持原配置）
        if self.config.max_depth >= 20 {
            match board.move_count {
                0..=4 => base.min(4_000), // 你的场景：总第4手（AI第2手）
                5..=8 => base.min(7_000),
                _ => base,
            }
        } else {
            base
        }
    }

    // =========================================================================
    // 开局库
    // =========================================================================

    fn opening_book(&self, board: &Board) -> Option<(usize, usize)> {
        match board.move_count {
            0 => Some(OPENING_CENTER),
            1 => {
                if board.is_empty(OPENING_CENTER.0, OPENING_CENTER.1) {
                    Some(OPENING_CENTER)
                } else {
                    let candidates = [
                        (6, 7), (8, 7), (7, 6), (7, 8),
                        (6, 6), (8, 8), (6, 8), (8, 6),
                        (5, 7), (9, 7), (7, 5), (7, 9),
                    ];
                    candidates.iter().find(|&&p| board.is_empty(p.0, p.1)).copied()
                }
            }
            2 => {
                let near = [
                    (7, 7), (6, 7), (8, 7), (7, 6), (7, 8),
                    (6, 6), (8, 8), (6, 8), (8, 6),
                ];
                near.iter().find(|&&p| board.is_empty(p.0, p.1)).copied()
            }
            _ => None,
        }
    }

    // =========================================================================
    // 走法生成与排序
    // =========================================================================

    fn generate_ordered_moves(&self, board: &Board) -> Vec<(usize, usize)> {
        let raw = board.generate_moves();
        let mut scored: Vec<((usize, usize), i32)> = raw
            .into_iter()
            .map(|(r, c)| {
                let score = self.heuristic_score(board, r, c);
                ((r, c), score)
            })
            .collect();
        scored.sort_unstable_by(|a, b| b.1.cmp(&a.1));
        scored.into_iter().map(|(pos, _)| pos).collect()
    }

    fn heuristic_score(&self, board: &Board, row: usize, col: usize) -> i32 {
        // evaluate_position 现在内置了复合威胁（双三/双四/三四）奖励
        let my = evaluate_position(board, row, col, self.config.player);
        let opp = evaluate_position(board, row, col, self.config.player.opponent());
        // 进攻分全值 + 防御分 9/10（轻微偏向进攻）
        let mut score = my + opp * 9 / 10;

        // MASTER 专属：轻量两步计划加权（不做完整前瞻，仅用于走法排序）
        if self.config.max_depth >= 20 {
            score += self.master_plan_bonus(board, row, col);
        }

        score
    }

    /// MASTER 轻量计划分：
    /// - 落子后若让对手出现一步五连，强烈惩罚（避免“漂亮但送死”的布局）
    /// - 评估下一手可形成的高威胁密度（冲四/活三）
    /// - 用于根节点排序，不改变规则正确性
    fn master_plan_bonus(&self, board: &Board, row: usize, col: usize) -> i32 {
        let mut b = board.clone();
        if !b.place(row, col, self.config.player) {
            return -500_000;
        }

        let opp = self.config.player.opponent();
        let next = b.generate_moves();

        // 1) 防止“下完被秒杀”
        let opp_can_win = next.iter().any(|&(r, c)| {
            let mut t = b.clone();
            t.place(r, c, opp) && t.check_win(r, c, opp)
        });
        if opp_can_win {
            return -220_000;
        }

        // 2) 统计我方后续高威胁点密度（蛇线伏脉）
        let mut high_threat_count = 0i32;
        let mut mid_threat_count = 0i32;
        let mut best_follow = 0i32;

        for (idx, (r, c)) in next.iter().copied().enumerate() {
            // 控制计算量：只看前 24 个候选
            if idx >= 24 {
                break;
            }
            let s = evaluate_position(&b, r, c, self.config.player);
            if s >= SCORE_BLOCKED_FOUR {
                high_threat_count += 1;
            } else if s >= SCORE_THREE {
                mid_threat_count += 1;
            }
            if s > best_follow {
                best_follow = s;
            }
        }

        high_threat_count * 4_000 + mid_threat_count * 900 + best_follow / 10
    }

    // =========================================================================
    // 快速胜负检查
    // =========================================================================

    fn find_immediate_win(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
    ) -> Option<(usize, usize)> {
        for &(row, col) in moves {
            let mut b = board.clone();
            if b.place(row, col, self.config.player) && b.check_win(row, col, self.config.player) {
                return Some((row, col));
            }
        }
        None
    }

    fn find_must_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let opp = self.config.player.opponent();
        for &(row, col) in moves {
            let mut b = board.clone();
            if b.place(row, col, opp) && b.check_win(row, col, opp) {
                return Some((row, col));
            }
        }
        None
    }

    // =========================================================================
    // 迭代加深搜索（智能深度 + 杀棋检测）
    // =========================================================================

    fn iterative_deepening(
        &mut self,
        board: &Board,
        moves: &[(usize, usize)],
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        last_report_ms: &mut u64,
        last_report_percent: &mut i32,
    ) -> (usize, usize) {
        // 安全回退：走法已按启发分排序，第一个总是合理的
        let mut best_move = moves[0];

        // 智能初始深度：根据历史、时间限制和局面复杂度综合决定
        let start_depth = self.choose_start_depth(board, moves.len());

        // 跟踪上一层耗时（估算下一层用）
        let mut last_layer_ms: u64 = self.last_depth_time_ms.max(1);

        // 初始 Zobrist 哈希
        let root_hash = self.zobrist.hash_board(board);

        // ── 阶段 1：锚定搜索 ──────────────────────────────────────────────
        // 若起始深度 > 2，先在深度 2 做一次快速搜索作为安全回退。
        if start_depth > 2 && self.time_ok() {
            let t = self.start_time.elapsed().as_millis() as u64;
            let (d_best, done, win, root_score) =
                self.search_one_depth(board, moves, 2, best_move, root_hash, i32::MIN + 1, i32::MAX - 1);
            if done {
                best_move = d_best;
                self.last_root_score = root_score;
                last_layer_ms = (self.start_time.elapsed().as_millis() as u64 - t).max(1);
                if win {
                    return best_move;
                }
            }
        }

        // ── 阶段 2：主迭代加深 ───────────────────────────────────────────
        let mut current_depth = start_depth;

        loop {
            if current_depth > self.config.max_depth || !self.time_ok() {
                break;
            }

            if current_depth > start_depth && !self.can_afford_depth(last_layer_ms) {
                break;
            }

            let t = self.start_time.elapsed().as_millis() as u64;

            // Aspiration Window：围绕上一层分数做窄窗口搜索，失败再扩窗
            let mut attempt = 0;
            let mut margin = ASPIRATION_MARGIN_BASE;
            let mut alpha0 = i32::MIN + 1;
            let mut beta0 = i32::MAX - 1;
            if current_depth >= 4 {
                alpha0 = self.last_root_score - margin;
                beta0 = self.last_root_score + margin;
            }

            let (mut d_best, mut done, mut win, mut root_score) =
                self.search_one_depth(board, moves, current_depth, best_move, root_hash, alpha0, beta0);

            while done
                && current_depth >= 4
                && attempt < ASPIRATION_RETRY_MAX
                && (root_score <= alpha0 || root_score >= beta0)
                && self.time_ok()
            {
                attempt += 1;
                margin += ASPIRATION_MARGIN_STEP;
                alpha0 = self.last_root_score - margin;
                beta0 = self.last_root_score + margin;
                let (b2, d2, w2, s2) =
                    self.search_one_depth(board, moves, current_depth, d_best, root_hash, alpha0, beta0);
                d_best = b2;
                done = d2;
                win = w2;
                root_score = s2;
            }

            // 仍失败则退回全窗口，保证正确性
            if done
                && current_depth >= 4
                && (root_score <= alpha0 || root_score >= beta0)
                && self.time_ok()
            {
                let (b3, d3, w3, s3) =
                    self.search_one_depth(board, moves, current_depth, d_best, root_hash, i32::MIN + 1, i32::MAX - 1);
                d_best = b3;
                done = d3;
                win = w3;
                root_score = s3;
            }

            let elapsed_ms = (self.start_time.elapsed().as_millis() as u64 - t).max(1);

            if done {
                best_move = d_best;
                self.last_root_score = root_score;
                last_layer_ms = elapsed_ms;
                self.last_completed_depth = current_depth;
                self.last_depth_time_ms = elapsed_ms;

                let depth_ratio = if self.config.max_depth > 0 {
                    (current_depth as f64 / self.config.max_depth as f64).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let time_ratio = (self.start_time.elapsed().as_millis() as f64
                    / self.turn_time_limit_ms.max(1) as f64)
                    .clamp(0.0, 1.0);
                let blended = (depth_ratio.max(time_ratio) * 97.0).round() as i32;
                self.maybe_report_progress(
                    on_progress,
                    blended.clamp(0, 97),
                    last_report_ms,
                    last_report_percent,
                    false,
                );

                if win {
                    break;
                }

                let remaining = self
                    .turn_time_limit_ms
                    .saturating_sub(self.start_time.elapsed().as_millis() as u64);

                current_depth += if remaining > last_layer_ms * FAST_JUMP_RATIO {
                    2
                } else {
                    1
                };
            } else {
                if current_depth == start_depth && start_depth > 4 && self.time_ok() {
                    let fallback_depth = (start_depth / 2).max(3);
                    if fallback_depth < current_depth {
                        self.last_completed_depth =
                            (self.last_completed_depth - 2).max(0);
                        current_depth = fallback_depth;
                        continue;
                    }
                }
                break;
            }
        }

        // 记录本次根节点决策（用于重复局面惩罚）
        self.remember_root_decision(root_hash, best_move);

        best_move
    }

    // =========================================================================
    // 单层根节点搜索
    // =========================================================================

    fn search_one_depth(
        &mut self,
        board: &Board,
        moves: &[(usize, usize)],
        depth: i32,
        fallback: (usize, usize),
        root_hash: u64,
        mut alpha: i32,
        beta: i32,
    ) -> ((usize, usize), bool, bool, i32) {
        let mut best = fallback;
        let mut timed_out = false;
        let mut found_win = false;

        // 将置换表中的最佳走法移到列表首位（走法排序优化）
        let tt_best = self.tt.get(root_hash).and_then(|e| e.best_move);
        let ordered_base: Vec<(usize, usize)> = if let Some(tb) = tt_best {
            let mut v: Vec<(usize, usize)> = moves.iter().copied()
                .filter(|&m| m != tb)
                .collect();
            v.insert(0, tb);
            v
        } else {
            moves.to_vec()
        };

        // MASTER：抑制前排候选过于同质（同一小区域扎堆）
        let ordered: Vec<(usize, usize)> = if self.config.max_depth >= 20 {
            self.suppress_root_redundancy(&ordered_base)
        } else {
            ordered_base
        };

        for &(row, col) in &ordered {
            if !self.time_ok() {
                timed_out = true;
                break;
            }

            let mut new_board = board.clone();
            if !new_board.place(row, col, self.config.player) {
                continue;
            }

            if new_board.check_win(row, col, self.config.player) {
                return ((row, col), true, true, WIN_SCORE + depth);
            }

            let child_hash = self.zobrist.hash_move(root_hash, row, col, self.config.player);
            let score = -self.minimax(
                &mut new_board,
                self.config.player.opponent(),
                depth - 1,
                -beta,
                -alpha,
                child_hash,
                1,
                Some((row, col)),
            );

            // 根节点重复局面惩罚（主要改善撤销/重试时“机械复读同一步”）
            let adjusted_score = if self.config.max_depth >= 20 {
                let penalty = self.root_repetition_penalty(root_hash, (row, col));
                // 若是明确杀棋分，不做惩罚，避免错失战术手
                if score >= KILL_THRESHOLD { score } else { score - penalty }
            } else {
                score
            };

            if adjusted_score > alpha {
                alpha = adjusted_score;
                best = (row, col);
            }

            if alpha >= KILL_THRESHOLD {
                found_win = true;
                break;
            }
        }

        // 将本层结果存入置换表
        if !timed_out {
            self.tt.set(root_hash, depth, alpha, Some(best), TtFlag::Exact);
        }

        (best, !timed_out, found_win, alpha)
    }

    fn suppress_root_redundancy(&self, ordered: &[(usize, usize)]) -> Vec<(usize, usize)> {
        if ordered.len() <= 4 {
            return ordered.to_vec();
        }

        let mut selected: Vec<(usize, usize)> = Vec::with_capacity(ordered.len());
        let mut delayed: Vec<(usize, usize)> = Vec::new();

        for &m in ordered {
            let mut too_close = false;
            for &s in &selected {
                let dr = (m.0 as i32 - s.0 as i32).abs();
                let dc = (m.1 as i32 - s.1 as i32).abs();
                if dr.max(dc) <= MASTER_REDUNDANT_RADIUS {
                    too_close = true;
                    break;
                }
            }

            if too_close && selected.len() < 8 {
                delayed.push(m);
            } else {
                selected.push(m);
            }
        }

        selected.extend(delayed);
        selected
    }

    fn root_repetition_penalty(&self, root_hash: u64, mv: (usize, usize)) -> i32 {
        let mut repeats = 0i32;
        for &(h, m) in &self.recent_decisions {
            if h == root_hash && m == mv {
                repeats += 1;
            }
        }
        if repeats <= 0 { 0 } else { repeats * 8_000 }
    }

    fn remember_root_decision(&mut self, root_hash: u64, mv: (usize, usize)) {
        if self.recent_decisions.len() >= DECISION_HISTORY_CAP {
            self.recent_decisions.pop_front();
        }
        self.recent_decisions.push_back((root_hash, mv));
    }

    // =========================================================================
    // 智能初始深度选择
    // =========================================================================

    /// 根据历史、时间限制和当前局面综合决定初始搜索深度。
    ///
    /// 考虑因素：
    /// 1. 历史完成深度（上一回合）：避免从深度 1 重新扫描无意义的浅层
    /// 2. 时间限制：时间越长，起始深度可以越高
    /// 3. 候选走法数量：候选越少（局面简单），可以起步更深
    /// 4. 棋盘落子数量：棋局早期候选区域小，也可以起步更深
    fn choose_start_depth(&self, board: &Board, candidate_count: usize) -> i32 {
        let max = self.config.max_depth;
        let total_pieces = board.move_count;

        // 基于历史：从上一回合完成深度的一半开始（跳过已知的浅层）
        let hist_base = if self.last_completed_depth >= 4 {
            self.last_completed_depth / 2
        } else {
            0
        };

        // 基于时间限制
        let time_base = if self.turn_time_limit_ms >= 8000 {
            4 // 8 秒+：从深度 4 开始
        } else if self.turn_time_limit_ms >= 3000 {
            3 // 3–8 秒：从深度 3 开始
        } else if self.turn_time_limit_ms >= 1000 {
            2 // 1–3 秒：从深度 2 开始
        } else {
            1 // < 1 秒：从深度 1 开始
        };

        // 基于候选走法数量：候选少时可以起步更深
        let cand_bonus: i32 = if candidate_count <= 3 {
            2
        } else if candidate_count <= 8 {
            1
        } else {
            0
        };

        // 基于棋盘落子数：棋局早期棋盘稀疏，候选范围小，可以起步更深
        let piece_bonus: i32 = if total_pieces < 6 {
            1
        } else {
            0
        };

        // 综合：取历史和时间基准的较大值，加上局面加成
        // 不超过 max_depth 的 2/3（留出迭代空间），至少为 1
        let start = hist_base.max(time_base) + cand_bonus + piece_bonus;
        start.clamp(1, (max * 2 / 3).max(1))
    }

    // =========================================================================
    // 时间预估
    // =========================================================================

    /// 根据上一层耗时估算是否能负担下一层搜索。
    /// 下一层预计耗时 = last_layer_ms * AFFORD_RATIO（保守估计分支因子）。
    fn can_afford_depth(&self, last_layer_ms: u64) -> bool {
        let elapsed = self.start_time.elapsed().as_millis() as u64;
        let remaining = self.turn_time_limit_ms.saturating_sub(elapsed);
        remaining > last_layer_ms * AFFORD_RATIO
    }

    // =========================================================================
    // Negamax + Alpha-Beta
    // =========================================================================

    fn minimax(
        &mut self,
        board: &mut Board,
        player: Color,
        depth: i32,
        mut alpha: i32,
        beta: i32,
        hash: u64,
        ply: usize,
        last_move: Option<(usize, usize)>,
    ) -> i32 {
        // 每个节点都检查 should_stop（Acquire：确保看到其他线程的 Release 写入）
        if !self.should_continue() {
            return 0;
        }

        // ── 置换表查询 ──────────────────────────────────────────────────
        let orig_alpha = alpha;
        if let Some(entry) = self.tt.get(hash) {
            if entry.depth >= depth {
                match entry.flag {
                    TtFlag::Exact => return entry.score,
                    TtFlag::LowerBound => {
                        if entry.score > alpha {
                            alpha = entry.score;
                        }
                    }
                    TtFlag::UpperBound => {
                        // 上界：实际值 <= entry.score。若上界 <= alpha，可以剪枝
                        if entry.score <= alpha {
                            return entry.score;
                        }
                    }
                }
                if alpha >= beta {
                    return alpha;
                }
            }
        }

        if depth <= 0 {
            let eval_key = self.eval_cache_key(hash, player, last_move);
            let ev = if let Some(cached) = self.eval_cache.get(eval_key) {
                cached
            } else {
                let v = self.static_eval_local(board, player, last_move);
                self.eval_cache.set(eval_key, v);
                v
            };
            self.tt.set(hash, 0, ev, None, TtFlag::Exact);
            return ev;
        }

        // 生成并排序候选走法（走法排序对 alpha-beta 剪枝效率至关重要）
        let raw = board.generate_moves();
        if raw.is_empty() {
            return 0; // 棋盘满，平局
        }

        // 置换表最佳走法优先
        let tt_best_move = self.tt.get(hash).and_then(|e| e.best_move);
        let mut moves: Vec<((usize, usize), i32)> = raw
            .into_iter()
            .map(|(r, c)| {
                let mut score = evaluate_position(board, r, c, player)
                    + evaluate_position(board, r, c, player.opponent()) * 9 / 10;
                // 置换表最佳走法额外奖励，确保排在首位
                if Some((r, c)) == tt_best_move {
                    score += 1_000_000;
                }

                 // Killer move 优先（非根层）
                if ply < self.killer_moves.len() {
                    if self.killer_moves[ply][0] == Some((r, c)) {
                        score += 140_000;
                    } else if self.killer_moves[ply][1] == Some((r, c)) {
                        score += 95_000;
                    }
                }

                // History heuristic：累计在剪枝中成功的走法优先
                score += self.history_bonus(player, r, c);
                ((r, c), score)
            })
            .collect();
        moves.sort_unstable_by(|a, b| b.1.cmp(&a.1));

        let mut best_move = moves.first().map(|&((r, c), _)| (r, c));

        let mut first_move = true;
        for (idx, ((row, col), move_hint_score)) in moves.iter().enumerate() {
            let (row, col) = (*row, *col);
            if !self.should_continue() {
                return alpha;
            }

            if !board.place(row, col, player) {
                continue;
            }

            if board.check_win(row, col, player) {
                let win_score = WIN_SCORE + depth;
                board.unplace(row, col);
                self.tt.set(hash, depth, win_score, Some((row, col)), TtFlag::Exact);
                return win_score; // 越早胜利分越高
            }

            let child_hash = self.zobrist.hash_move(hash, row, col, player);
            // LMR（Late Move Reduction）：后序且“安静”的走法先浅搜
            // 以 move_hint_score 区分是否高威胁（>= 冲四级）
            let is_quiet = *move_hint_score < SCORE_BLOCKED_FOUR;
            let reduce = if depth >= 5 && idx >= 4 && is_quiet { 1 } else { 0 };
            let search_depth = (depth - 1 - reduce).max(0);

            // PVS（Principal Variation Search）
            // 首着全窗口，后续先零窗口探测，必要时再全窗口重搜
            let mut score = if first_move {
                -self.minimax(board, player.opponent(), search_depth, -beta, -alpha, child_hash, ply + 1, Some((row, col)))
            } else {
                let mut s = -self.minimax(board, player.opponent(), search_depth, -alpha - 1, -alpha, child_hash, ply + 1, Some((row, col)));
                if s > alpha && s < beta {
                    s = -self.minimax(board, player.opponent(), search_depth, -beta, -alpha, child_hash, ply + 1, Some((row, col)));
                }
                s
            };

            // 若 LMR 降深后出现潜在改进，再用完整深度复核一次
            if reduce > 0 && score > alpha {
                score = -self.minimax(board, player.opponent(), depth - 1, -beta, -alpha, child_hash, ply + 1, Some((row, col)));
            }

            // 回溯撤销
            board.unplace(row, col);

            first_move = false;

            if score > alpha {
                alpha = score;
                best_move = Some((row, col));
            }
            if alpha >= beta {
                self.record_killer(ply, (row, col));
                self.bump_history(player, row, col, depth);
                // Beta 剪枝 → 下界（LowerBound）
                self.tt.set(hash, depth, alpha, best_move, TtFlag::LowerBound);
                return alpha;
            }
        }

        // 存入置换表
        let flag = if alpha <= orig_alpha {
            TtFlag::UpperBound
        } else {
            TtFlag::Exact
        };
        self.tt.set(hash, depth, alpha, best_move, flag);

        alpha
    }

    /// 静态局面评估
    fn static_eval(&self, board: &Board, player: Color) -> i32 {
        let mut score = 0;

        // 基础评估只扫描候选区域（而非全盘 225 空位），显著降低叶子评估成本
        let candidates = board.generate_moves();
        if candidates.is_empty() {
            return 0;
        }
        for (r, c) in candidates {
            score += evaluate_position(board, r, c, player);
            score -= evaluate_position(board, r, c, player.opponent()) * 9 / 10;
        }

        // HARD / MASTER 增加全局战略评估（分阶段权重）：
        // opening: 中心/连通优先；middlegame: 平衡；endgame: 机动/线压优先
        if self.config.max_depth >= 12 {
            let is_master = self.config.max_depth >= 20;
            let (center_w, link_w, mobility_w, line_w) =
                self.phase_weights(board.move_count, is_master);

            score += self.strategic_eval(board, player, center_w, link_w, mobility_w);
            score += self.line_pressure_eval(board, player, line_w);
        }

        score
    }

    /// 叶子局面局部评估（增量近似）：
    /// 优先评估“最后落子点”附近窗口，降低 leaf 评估开销。
    /// 若 last_move 不可用则回退全局静态评估。
    fn static_eval_local(&self, board: &Board, player: Color, last_move: Option<(usize, usize)>) -> i32 {
        let Some((lr, lc)) = last_move else {
            return self.static_eval(board, player);
        };

        let mut marked = [[false; BOARD_SIZE]; BOARD_SIZE];
        let mut score = 0i32;
        let opp = player.opponent();

        // 在最后落子周围半径 R 的窗口内收集空位（四方向影响主要集中于此）
        for rr in (lr as i32 - LOCAL_EVAL_RADIUS)..=(lr as i32 + LOCAL_EVAL_RADIUS) {
            for cc in (lc as i32 - LOCAL_EVAL_RADIUS)..=(lc as i32 + LOCAL_EVAL_RADIUS) {
                if rr < 0 || rr >= BOARD_SIZE as i32 || cc < 0 || cc >= BOARD_SIZE as i32 {
                    continue;
                }
                let r = rr as usize;
                let c = cc as usize;
                if board.get(r, c) == Color::Empty && !marked[r][c] {
                    marked[r][c] = true;
                    score += evaluate_position(board, r, c, player);
                    score -= evaluate_position(board, r, c, opp) * 9 / 10;
                }
            }
        }

        // 窗口可能过稀（如开局边缘），给一个安全回退
        if score == 0 {
            return self.static_eval(board, player);
        }

        // 保留原有战略项，避免布局能力退化
        if self.config.max_depth >= 12 {
            let is_master = self.config.max_depth >= 20;
            let (center_w, link_w, mobility_w, line_w) =
                self.phase_weights(board.move_count, is_master);
            score += self.strategic_eval(board, player, center_w, link_w, mobility_w);
            score += self.line_pressure_eval(board, player, line_w);
        }

        score
    }

    fn phase_weights(&self, move_count: usize, is_master: bool) -> (i32, i32, i32, i32) {
        if is_master {
            if move_count <= 12 {
                // 开局：强调中心 + 结构搭建
                (30, 18, 4, 16)
            } else if move_count <= 50 {
                // 中盘：平衡攻防与布局
                (22, 14, 7, 20)
            } else {
                // 残局：强调线压与可拓展空间
                (14, 12, 10, 26)
            }
        } else {
            if move_count <= 12 {
                (14, 10, 3, 10)
            } else if move_count <= 50 {
                (11, 9, 5, 12)
            } else {
                (8, 8, 8, 16)
            }
        }
    }

    fn strategic_eval(
        &self,
        board: &Board,
        player: Color,
        center_w: i32,
        link_w: i32,
        mobility_w: i32,
    ) -> i32 {
        let mut total = 0i32;

        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                let color = board.get(r, c);
                if color == Color::Empty {
                    continue;
                }

                let sign = if color == player { 1 } else { -1 };

                // 1) 中心控制（切比雪夫距离）
                let dr = (r as i32 - 7).abs();
                let dc = (c as i32 - 7).abs();
                let dist = dr.max(dc);
                let center_bonus = 7 - dist;
                total += sign * center_bonus * center_w;

                // 2) 连通度 + 3) 机动性（8 邻域）
                let mut links = 0i32;
                let mut mobility = 0i32;
                for rr in (r as i32 - 1)..=(r as i32 + 1) {
                    for cc in (c as i32 - 1)..=(c as i32 + 1) {
                        if rr == r as i32 && cc == c as i32 {
                            continue;
                        }
                        if rr < 0 || rr >= BOARD_SIZE as i32 || cc < 0 || cc >= BOARD_SIZE as i32 {
                            continue;
                        }
                        match board.get(rr as usize, cc as usize) {
                            x if x == color => links += 1,
                            Color::Empty => mobility += 1,
                            _ => {}
                        }
                    }
                }

                total += sign * links * link_w;
                total += sign * mobility * mobility_w;
            }
        }

        total
    }

    fn eval_cache_key(&self, hash: u64, player: Color, last_move: Option<(usize, usize)>) -> u64 {
        let turn_bit = if player == Color::Black { 0u64 } else { 1u64 };
        let lm = if let Some((r, c)) = last_move {
            ((r as u64) << 4) ^ (c as u64)
        } else {
            0xFu64
        };
        hash ^ (turn_bit << 63) ^ (lm << 56)
    }

    fn player_idx(player: Color) -> usize {
        if player == Color::Black { 0 } else { 1 }
    }

    fn move_index(row: usize, col: usize) -> usize {
        row * BOARD_SIZE + col
    }

    fn history_bonus(&self, player: Color, row: usize, col: usize) -> i32 {
        let p = Self::player_idx(player);
        let idx = Self::move_index(row, col);
        // 缩放防止盖过战术分
        self.history_scores[p][idx] / 64
    }

    fn record_killer(&mut self, ply: usize, mv: (usize, usize)) {
        if ply >= self.killer_moves.len() {
            return;
        }
        let slot = &mut self.killer_moves[ply];
        if slot[0] == Some(mv) {
            return;
        }
        slot[1] = slot[0];
        slot[0] = Some(mv);
    }

    fn bump_history(&mut self, player: Color, row: usize, col: usize, depth: i32) {
        let p = Self::player_idx(player);
        let idx = Self::move_index(row, col);
        let bonus = (depth.max(1) * depth.max(1) * 40).min(12_000);
        let v = &mut self.history_scores[p][idx];
        *v = (*v + bonus).min(HISTORY_MAX);
    }

    fn decay_history(&mut self) {
        for p in 0..2 {
            for v in &mut self.history_scores[p] {
                *v = (*v * 15) / 16;
            }
        }
    }

    /// 线压评估：只从每条连续链的“起点”统计一次，
    /// 结合链长与两端开放性，强化“伏脉千里”的连线布局能力。
    fn line_pressure_eval(&self, board: &Board, player: Color, line_w: i32) -> i32 {
        let dirs = [(1i32, 0i32), (0, 1), (1, 1), (1, -1)];
        let mut total = 0i32;

        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                let color = board.get(r, c);
                if color == Color::Empty {
                    continue;
                }

                let sign = if color == player { 1 } else { -1 };

                for &(dr, dc) in &dirs {
                    // 仅链起点计分，避免重复计算
                    let pr = r as i32 - dr;
                    let pc = c as i32 - dc;
                    if pr >= 0
                        && pr < BOARD_SIZE as i32
                        && pc >= 0
                        && pc < BOARD_SIZE as i32
                        && board.get(pr as usize, pc as usize) == color
                    {
                        continue;
                    }

                    // 统计连续链长
                    let mut len = 0i32;
                    let mut rr = r as i32;
                    let mut cc = c as i32;
                    while rr >= 0
                        && rr < BOARD_SIZE as i32
                        && cc >= 0
                        && cc < BOARD_SIZE as i32
                        && board.get(rr as usize, cc as usize) == color
                    {
                        len += 1;
                        rr += dr;
                        cc += dc;
                    }

                    // 两端开放性
                    let left_open = pr >= 0
                        && pr < BOARD_SIZE as i32
                        && pc >= 0
                        && pc < BOARD_SIZE as i32
                        && board.get(pr as usize, pc as usize) == Color::Empty;
                    let right_open = rr >= 0
                        && rr < BOARD_SIZE as i32
                        && cc >= 0
                        && cc < BOARD_SIZE as i32
                        && board.get(rr as usize, cc as usize) == Color::Empty;
                    let open_ends = (left_open as i32) + (right_open as i32);

                    if len >= 2 {
                        let shape = len * len * (1 + open_ends);
                        total += sign * shape * line_w;
                    }
                }
            }
        }

        total
    }

    // =========================================================================
    // 取消控制
    // =========================================================================

    /// 检查是否应继续搜索（should_stop Acquire 读 + 时间限制）
    fn should_continue(&self) -> bool {
        // Acquire：确保能及时看到 Kotlin 线程 Release 写入的停止信号
        if self.should_stop.load(Ordering::Acquire) {
            return false;
        }
        // 每 TIME_CHECK_FREQ 个节点检查一次系统时间（避免频繁 syscall）
        let n = self.node_count.fetch_add(1, Ordering::Relaxed);
        if n % TIME_CHECK_FREQ == 0 {
            if self.start_time.elapsed().as_millis() as u64 > self.turn_time_limit_ms {
                return false;
            }
        }
        true
    }

    /// 外层快速时间 + 停止检查（用于每个候选走法前）
    fn time_ok(&self) -> bool {
        // Acquire：与 should_continue 保持一致的内存顺序语义
        if self.should_stop.load(Ordering::Acquire) {
            return false;
        }
        self.start_time.elapsed().as_millis() as u64 <= self.turn_time_limit_ms
    }

    // =========================================================================
    // 外部控制接口（JNI 调用）
    // =========================================================================

    /// 使当前计算失效（由 Kotlin 主线程调用）
    /// Release：确保搜索线程能及时读到停止信号
    pub fn invalidate(&self) {
        self.should_stop.store(true, Ordering::Release);
    }

    /// 恢复计算有效（在启动新的 take_turn 之前调用）
    /// Release：配合 take_turn 开头的 Acquire 读
    pub fn validate(&self) {
        self.should_stop.store(false, Ordering::Release);
    }

    /// 重置内部计时状态（新一局或撤销后调用）
    /// 保留 last_completed_depth / last_depth_time_ms 历史（跨回合复用）
    pub fn clear(&mut self) {
        self.node_count.store(0, Ordering::Relaxed);
        self.start_time = Instant::now();
        self.turn_time_limit_ms = self.config.time_limit_ms;
        self.decay_history();
        self.eval_cache.clear();
        self.tt.clear();
        // 注意：不重置 last_completed_depth / last_depth_time_ms
        // 这两个字段是跨回合历史，只有创建新 AI 对象时才清零
    }

    /// 向后兼容旧接口
    pub fn co_validate(&self) -> bool {
        self.should_continue()
    }
}
