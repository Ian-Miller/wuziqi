use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::evaluate_position;

/// 胜利分数（五连或搜索路径内的强制赢棋）
const WIN_SCORE: i32 = 1_000_000;

/// 杀棋检测阈值：根节点得分超过此值视为找到了强制赢棋路线，无需继续加深
const KILL_THRESHOLD: i32 = WIN_SCORE / 2;

/// 时间检查频率（每 N 个节点检查一次时间；should_stop 每次都检查）
const TIME_CHECK_FREQ: u64 = 256;

/// 开局中心点
const OPENING_CENTER: (usize, usize) = (7, 7);

/// 智能加深：剩余时间 / 上一层耗时 >= 此值时，激进地跳 2 层
const FAST_JUMP_RATIO: u64 = 8;

/// 智能加深：预估下一层可接受的最低时间倍数
/// 剩余时间 >= 上一层耗时 * AFFORD_RATIO 才认为值得继续
const AFFORD_RATIO: u64 = 3;

/// 战略评估（全局）在 HARD/MASTER 的权重参数
const HARD_CENTER_W: i32 = 10;
const HARD_LINK_W: i32 = 8;
const HARD_MOBILITY_W: i32 = 3;

const MASTER_CENTER_W: i32 = 22;
const MASTER_LINK_W: i32 = 14;
const MASTER_MOBILITY_W: i32 = 6;

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
    /// 跨回合历史：上一次 take_turn 完成的最大搜索深度（用于智能初始深度选择）
    last_completed_depth: i32,
    /// 跨回合历史：上一次最后一层搜索的耗时 ms（用于估算下一层时间）
    last_depth_time_ms: u64,
    /// Zobrist 哈希表（固定不变，在构造时初始化）
    zobrist: ZobristTable,
}

impl GomokuAi {
    pub fn new(config: AiConfig) -> Self {
        Self {
            config,
            should_stop: Arc::new(AtomicBool::new(false)),
            node_count: AtomicU64::new(0),
            start_time: Instant::now(),
            last_completed_depth: 0,
            last_depth_time_ms: 0,
            zobrist: ZobristTable::new(),
        }
    }

    // =========================================================================
    // 主入口：takeTurn
    // =========================================================================

    /// 执行一次思考，返回最佳走法
    pub fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        // ⚠ 不在此处重置 should_stop：该标志由 Kotlin 端通过 validate()/invalidate() 独占控制。
        // 在 launchAiThinking 之前，Kotlin 端已调用 validate() 将其设为 false。
        // 若在此处重置，会导致与 Kotlin invalidate() 的竞态（信号被吃掉）。
        self.node_count.store(0, Ordering::Relaxed);
        self.start_time = Instant::now();

        // 1. 开局库（前几手立即响应，不耗时）
        if let Some(m) = self.opening_book(board) {
            return Some(m);
        }

        // 2. 生成并排序候选走法
        let moves = self.generate_ordered_moves(board);
        if moves.is_empty() {
            return None;
        }

        // 3. 立即胜利 / 必须防守（深度 1 快速检查）
        if let Some(m) = self.find_immediate_win(board, &moves) {
            return Some(m);
        }
        if let Some(m) = self.find_must_block(board, &moves) {
            return Some(m);
        }

        // 4. 迭代加深搜索（含智能深度选择 + 杀棋检测）
        Some(self.iterative_deepening(board, &moves))
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
        my + opp * 9 / 10
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

    fn iterative_deepening(&mut self, board: &Board, moves: &[(usize, usize)]) -> (usize, usize) {
        // 安全回退：走法已按启发分排序，第一个总是合理的
        let mut best_move = moves[0];

        // 智能初始深度：根据历史、时间限制和局面复杂度综合决定
        let start_depth = self.choose_start_depth(board, moves.len());

        // 跟踪上一层耗时（估算下一层用）
        let mut last_layer_ms: u64 = self.last_depth_time_ms.max(1);

        // 每次 take_turn 共享一个置换表（跨迭代深度复用，深层结果对浅层有指导价值）
        let mut tt = TranspositionTable::new();

        // 初始 Zobrist 哈希
        let root_hash = self.zobrist.hash_board(board);

        // ── 阶段 1：锚定搜索 ──────────────────────────────────────────────
        // 若起始深度 > 2，先在深度 2 做一次快速搜索作为安全回退。
        if start_depth > 2 && self.time_ok() {
            let t = self.start_time.elapsed().as_millis() as u64;
            let (d_best, done, win) = self.search_one_depth(board, moves, 2, best_move, &mut tt, root_hash);
            if done {
                best_move = d_best;
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
            let (d_best, done, win) =
                self.search_one_depth(board, moves, current_depth, best_move, &mut tt, root_hash);
            let elapsed_ms = (self.start_time.elapsed().as_millis() as u64 - t).max(1);

            if done {
                best_move = d_best;
                last_layer_ms = elapsed_ms;
                self.last_completed_depth = current_depth;
                self.last_depth_time_ms = elapsed_ms;

                if win {
                    break;
                }

                let remaining = self
                    .config
                    .time_limit_ms
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

        best_move
    }

    // =========================================================================
    // 单层根节点搜索
    // =========================================================================

    fn search_one_depth(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        depth: i32,
        fallback: (usize, usize),
        tt: &mut TranspositionTable,
        root_hash: u64,
    ) -> ((usize, usize), bool, bool) {
        let mut best = fallback;
        let mut alpha = i32::MIN + 1;
        let beta = i32::MAX - 1;
        let mut timed_out = false;
        let mut found_win = false;

        // 将置换表中的最佳走法移到列表首位（走法排序优化）
        let tt_best = tt.get(root_hash).and_then(|e| e.best_move);
        let ordered: Vec<(usize, usize)> = if let Some(tb) = tt_best {
            let mut v: Vec<(usize, usize)> = moves.iter().copied()
                .filter(|&m| m != tb)
                .collect();
            v.insert(0, tb);
            v
        } else {
            moves.to_vec()
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
                return ((row, col), true, true);
            }

            let child_hash = self.zobrist.hash_move(root_hash, row, col, self.config.player);
            let score = -self.minimax(
                &new_board,
                self.config.player.opponent(),
                depth - 1,
                -beta,
                -alpha,
                tt,
                child_hash,
            );

            if score > alpha {
                alpha = score;
                best = (row, col);
            }

            if alpha >= KILL_THRESHOLD {
                found_win = true;
                break;
            }
        }

        // 将本层结果存入置换表
        if !timed_out {
            tt.set(root_hash, depth, alpha, Some(best), TtFlag::Exact);
        }

        (best, !timed_out, found_win)
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
        let time_base = if self.config.time_limit_ms >= 8000 {
            4 // 8 秒+：从深度 4 开始
        } else if self.config.time_limit_ms >= 3000 {
            3 // 3–8 秒：从深度 3 开始
        } else if self.config.time_limit_ms >= 1000 {
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
        let remaining = self.config.time_limit_ms.saturating_sub(elapsed);
        remaining > last_layer_ms * AFFORD_RATIO
    }

    // =========================================================================
    // Negamax + Alpha-Beta
    // =========================================================================

    fn minimax(
        &self,
        board: &Board,
        player: Color,
        depth: i32,
        mut alpha: i32,
        beta: i32,
        tt: &mut TranspositionTable,
        hash: u64,
    ) -> i32 {
        // 每个节点都检查 should_stop（Acquire：确保看到其他线程的 Release 写入）
        if !self.should_continue() {
            return 0;
        }

        // ── 置换表查询 ──────────────────────────────────────────────────
        let orig_alpha = alpha;
        if let Some(entry) = tt.get(hash) {
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
            let ev = self.static_eval(board, player);
            tt.set(hash, 0, ev, None, TtFlag::Exact);
            return ev;
        }

        // 生成并排序候选走法（走法排序对 alpha-beta 剪枝效率至关重要）
        let raw = board.generate_moves();
        if raw.is_empty() {
            return 0; // 棋盘满，平局
        }

        // 置换表最佳走法优先
        let tt_best_move = tt.get(hash).and_then(|e| e.best_move);
        let mut moves: Vec<((usize, usize), i32)> = raw
            .into_iter()
            .map(|(r, c)| {
                let mut score = evaluate_position(board, r, c, player)
                    + evaluate_position(board, r, c, player.opponent()) * 9 / 10;
                // 置换表最佳走法额外奖励，确保排在首位
                if Some((r, c)) == tt_best_move {
                    score += 1_000_000;
                }
                ((r, c), score)
            })
            .collect();
        moves.sort_unstable_by(|a, b| b.1.cmp(&a.1));

        let mut best_move = moves.first().map(|&((r, c), _)| (r, c));

        for ((row, col), _) in &moves {
            let (row, col) = (*row, *col);
            if !self.should_continue() {
                return alpha;
            }

            let mut new_board = board.clone();
            if !new_board.place(row, col, player) {
                continue;
            }

            if new_board.check_win(row, col, player) {
                let win_score = WIN_SCORE + depth;
                tt.set(hash, depth, win_score, Some((row, col)), TtFlag::Exact);
                return win_score; // 越早胜利分越高
            }

            let child_hash = self.zobrist.hash_move(hash, row, col, player);
            let score = -self.minimax(&new_board, player.opponent(), depth - 1, -beta, -alpha, tt, child_hash);

            if score > alpha {
                alpha = score;
                best_move = Some((row, col));
            }
            if alpha >= beta {
                // Beta 剪枝 → 下界（LowerBound）
                tt.set(hash, depth, alpha, best_move, TtFlag::LowerBound);
                return alpha;
            }
        }

        // 存入置换表
        let flag = if alpha <= orig_alpha {
            TtFlag::UpperBound
        } else {
            TtFlag::Exact
        };
        tt.set(hash, depth, alpha, best_move, flag);

        alpha
    }

    /// 静态局面评估
    fn static_eval(&self, board: &Board, player: Color) -> i32 {
        let mut score = 0;
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                if board.get(r, c) == Color::Empty {
                    score += evaluate_position(board, r, c, player);
                    score -= evaluate_position(board, r, c, player.opponent()) * 9 / 10;
                }
            }
        }

        // HARD / MASTER 增加全局战略评估：
        // - 中心控制（开中盘布局能力）
        // - 连通度（蛇形潜伏/连线组织能力）
        // - 机动性（周边可扩展空间）
        if self.config.max_depth >= 20 {
            score += self.strategic_eval(board, player, MASTER_CENTER_W, MASTER_LINK_W, MASTER_MOBILITY_W);
        } else if self.config.max_depth >= 12 {
            score += self.strategic_eval(board, player, HARD_CENTER_W, HARD_LINK_W, HARD_MOBILITY_W);
        }

        score
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
            if self.start_time.elapsed().as_millis() as u64 > self.config.time_limit_ms {
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
        self.start_time.elapsed().as_millis() as u64 <= self.config.time_limit_ms
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
        // 注意：不重置 last_completed_depth / last_depth_time_ms
        // 这两个字段是跨回合历史，只有创建新 AI 对象时才清零
    }

    /// 向后兼容旧接口
    pub fn co_validate(&self) -> bool {
        self.should_continue()
    }
}
