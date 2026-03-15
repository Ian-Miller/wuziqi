//! Guided MCTS (Monte Carlo Tree Search) AI 引擎
//!
//! ## 与传统 MCTS 的区别
//! - **走法剪枝**：每个节点只扩展 Top-K 高分候选走法（K=12），而非全部空格
//! - **评估替代 Rollout**：到达叶节点时用静态评估函数打分，不做随机模拟
//! - **即时胜负检测**：落子后立刻检查五连，命中直接返回最高分
//!
//! 适合 EASY 和 MEDIUM 难度：
//! - EASY：探索常数 C=2.0，时间短（500ms），AI 更随机、更容易犯错
//! - MEDIUM：探索常数 C=1.2，时间中等（1500ms），兼顾探索与利用

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::{evaluate_position, analyze_line, match_pattern_score, SCORE_FOUR, SCORE_THREE, SCORE_BLOCKED_FOUR};

// ============================================================================
// 常量
// ============================================================================

/// 胜利/失败的极值评估分（归一化到 [-1, 1] 区间的系数）
const WIN_VALUE: f64 = 1.0;
const LOSE_VALUE: f64 = -1.0;

/// 静态评估分数缩放系数（将 i32 评估值归一化到 (-1, 1)）
/// 实测：中局含威胁的局面总分约 50_000–200_000，
/// 用 250_000 让利用项在 [-0.8, 0.8] 范围内，UCB1 才能正常工作。
/// 原 2_000_000 导致 exploit ≈ 0，MCTS 退化为纯随机。
const EVAL_SCALE: f64 = 250_000.0;

/// 每个节点最多扩展的候选走法数（分支因子上限）
pub const MAX_CHILDREN_EASY: usize = 15;
pub const MAX_CHILDREN_MEDIUM: usize = 12;

/// 每隔多少次迭代检查一次时间（should_stop 每次都检查）
const TIME_CHECK_INTERVAL: u32 = 64;

// ============================================================================
// 威胁级别：用于 top_k_moves 的启发分加权
// ============================================================================

/// 威胁权重（加到启发分上，确保高威胁走法优先进入 Top-K）
/// 五连直接胜负，由 find_immediate_win/must_block 处理，不在此处理
const THREAT_WIN:         i32 = 8_000_000; // 活四（一步即胜）
const THREAT_BLOCK_WIN:   i32 = 7_000_000; // 挡对方活四（必须防守）
const THREAT_FOUR:        i32 = 600_000;   // 冲四（制造必须防守点）
const THREAT_DOUBLE_FOUR: i32 = 900_000;   // 双四（两个冲四，对方只能挡一个）
const THREAT_DOUBLE_THREE:i32 = 500_000;   // 双活三（成对活三，非常危险）
const THREAT_THREE_FOUR:  i32 = 700_000;   // 活三 + 冲四组合
const THREAT_THREE:       i32 = 50_000;    // 单活三（普通威胁）

// ============================================================================
// MCTS 配置
// ============================================================================

/// MCTS AI 配置
#[derive(Clone, Copy)]
pub struct MctsConfig {
    /// AI 执棋颜色
    pub player: Color,
    /// 时间限制（毫秒）
    pub time_limit_ms: u64,
    /// UCB1 探索常数。越大越随机（EASY 用大值，MEDIUM 用小值）
    pub exploration_c: f64,
    /// 每节点最大子节点数（分支因子）
    pub max_children: usize,
}

impl MctsConfig {
    pub fn easy(player: Color) -> Self {
        Self {
            player,
            time_limit_ms: 700,
            exploration_c: 1.4,
            max_children: MAX_CHILDREN_EASY,
        }
    }

    pub fn medium(player: Color) -> Self {
        Self {
            player,
            time_limit_ms: 1500,
            exploration_c: 1.2,
            max_children: MAX_CHILDREN_MEDIUM,
        }
    }
}

// ============================================================================
// MCTS 节点
// ============================================================================

struct MctsNode {
    /// 对应的走法（根节点为 None）
    mv: Option<(usize, usize)>,
    /// 落子颜色（执行 mv 时谁在落子；根节点为上一个落子方）
    color: Color,
    /// 访问次数
    visits: u32,
    /// 累计评估值（从 color 角度，区间 [-1, 1]）
    total_value: f64,
    /// 尚未扩展的候选走法（按启发分降序排列）
    untried_moves: Vec<(usize, usize)>,
    /// 已扩展的子节点
    children: Vec<MctsNode>,
}

impl MctsNode {
    /// 创建根节点
    fn root(board: &Board, mover: Color, max_children: usize) -> Self {
        let untried = top_k_moves(board, mover, max_children);
        Self {
            mv: None,
            color: mover.opponent(), // 根节点 color 是"上一步走的颜色"，即 mover 的对手
            visits: 0,
            total_value: 0.0,
            untried_moves: untried,
            children: Vec::new(),
        }
    }

    /// UCB1 得分（从父节点角度评价子节点价值）
    fn ucb1(&self, parent_visits: u32, c: f64) -> f64 {
        if self.visits == 0 {
            return f64::INFINITY;
        }
        // value 是从 self.color 角度的，但父节点需要对手角度
        // → 用 -avg_value 表示对父节点的"价值"
        let avg = self.total_value / self.visits as f64;
        let exploit = -avg; // 父节点视角
        let explore = c * ((parent_visits as f64).ln() / self.visits as f64).sqrt();
        exploit + explore
    }

    /// 是否已完全扩展（no untried moves left）
    fn is_fully_expanded(&self) -> bool {
        self.untried_moves.is_empty()
    }

    /// 是否为叶节点（无子节点）
    fn is_leaf(&self) -> bool {
        self.children.is_empty()
    }
}

// ============================================================================
// MCTS AI 引擎
// ============================================================================

pub struct MctsAi {
    config: MctsConfig,
    should_stop: Arc<AtomicBool>,
    start_time: Instant,
    /// 轻度失误概率（EASY > MEDIUM）
    mistake_prob: f64,
    /// 视野收窄概率（模拟新手“只看局部”）
    narrow_vision_prob: f64,
    /// 走法抽样温度（越大越随机）
    sample_temperature: f64,
    /// 从前 N 个候选中进行抽样
    sample_top_n: usize,
    /// 伪随机状态（xorshift64）
    rng_state: u64,
}

impl MctsAi {
    pub fn new(config: MctsConfig) -> Self {
        let is_easy = config.exploration_c >= 1.8 || config.max_children >= MAX_CHILDREN_EASY;
        let (mistake_prob, narrow_vision_prob, sample_temperature, sample_top_n) = if is_easy {
            // EASY：更像新手，允许更多“看走眼”和随机性
            (0.18, 0.22, 1.25, 4)
        } else {
            // MEDIUM：爱好者，偶发失误但整体稳健
            (0.06, 0.08, 0.70, 3)
        };

        let seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x9E37_79B9_7F4A_7C15);

        Self {
            config,
            should_stop: Arc::new(AtomicBool::new(false)),
            start_time: Instant::now(),
            mistake_prob,
            narrow_vision_prob,
            sample_temperature,
            sample_top_n,
            rng_state: seed ^ 0xA076_1D64_78BD_642F,
        }
    }

    // =========================================================================
    // 主入口
    // =========================================================================

    pub fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        // ⚠ 不在此处重置 should_stop：该标志由 Kotlin 端通过 validate()/invalidate() 独占控制。
        // 在 launchAiThinking 之前，Kotlin 端已调用 validate() 将其设为 false。
        // 若在此处重置，会导致与 Kotlin invalidate() 的竞态（信号被吃掉）。
        self.start_time = Instant::now();

        // 1. 开局库（前几手立即响应）
        if let Some(m) = opening_book(board) {
            return Some(m);
        }

        // 2. 生成候选（后续复用）
        let all_moves = board.generate_moves();
        if all_moves.is_empty() {
            return None;
        }
        let mut candidates = top_k_moves(board, self.config.player, self.config.max_children);
        if self.roll(self.narrow_vision_prob) && candidates.len() > 6 {
            // 视野收窄：仅保留局部前列候选（模拟新手只看“眼前几手”）
            candidates.truncate(6);
        }
        if candidates.is_empty() {
            return None;
        }

        // 3. 深度 1：己方五连 / 对方五连（必须立即响应）
        if let Some(m) = self.find_immediate_win(board, &candidates) {
            return Some(m);
        }
        // 对方五连：扫全部候选，避免五连点未进 Top-K
        if let Some(m) = self.find_must_block(board, &all_moves) {
            return Some(m);
        }

        // 4. 深度 2：活四级威胁（确定性处理，不交给 MCTS）
        // 己方可形成活四/双四/三四 → 几乎必胜，直接走
        if let Some(m) = self.find_forced_win(board, &all_moves) {
            return Some(m);
        }
        // 对方可形成活四/双四/三四 → 必须堵，否则对方下一步必胜
        if let Some(m) = self.find_critical_block(board, &all_moves) {
            return Some(m);
        }

        // 5. MCTS 主搜索（处理非强制性的复杂局面）
        self.mcts_search(board)
    }

    // =========================================================================
    // MCTS 主搜索
    // =========================================================================

    fn mcts_search(&mut self, board: &Board) -> Option<(usize, usize)> {
        let mover = self.config.player;
        let mut root = MctsNode::root(board, mover, self.config.max_children);
        let mut iter_count: u32 = 0;

        loop {
            // 时间 + 停止检查（每 TIME_CHECK_INTERVAL 次迭代检查一次）
            if iter_count % TIME_CHECK_INTERVAL == 0 {
                if self.should_stop.load(Ordering::Acquire) {
                    break;
                }
                if self.elapsed_ms() >= self.config.time_limit_ms {
                    break;
                }
            }
            iter_count += 1;

            // 每次迭代：Selection → Expansion → Evaluation → Backpropagation
            let mut board_clone = board.clone();
            self.run_one_iteration(&mut root, &mut board_clone, mover);
        }

        // 正常情况：访问次数最多
        let mut ranked: Vec<((usize, usize), u32)> = root
            .children
            .iter()
            .filter_map(|c| c.mv.map(|mv| (mv, c.visits)))
            .collect();
        ranked.sort_unstable_by(|a, b| b.1.cmp(&a.1));

        if ranked.is_empty() {
            return None;
        }

        // MEDIUM/EASY 人类化：在前 N 候选中按温度抽样（不是永远第一）
        let top_n = self.sample_top_n.min(ranked.len());
        let chosen = self.sample_softmax(&ranked[..top_n], self.sample_temperature)
            .unwrap_or(ranked[0].0);

        // 失误模式：偶发放弃最佳走法（但尽量不犯“立即送杀”）
        let maybe_mistake = if self.roll(self.mistake_prob) && ranked.len() >= 2 {
            let alt_pool = ranked.iter().skip(1).take(4).copied().collect::<Vec<_>>();
            if let Some(alt) = self.sample_softmax(&alt_pool, self.sample_temperature + 0.45) {
                alt
            } else {
                chosen
            }
        } else {
            chosen
        };

        // 战术底线：避免落子后被对手“一步五连”秒杀
        if self.allows_opponent_immediate_win(board, maybe_mistake, mover) {
            for (mv, _) in ranked {
                if !self.allows_opponent_immediate_win(board, mv, mover) {
                    return Some(mv);
                }
            }
            // 若所有候选都无法避免，回退当前选择
            return Some(maybe_mistake);
        }

        Some(maybe_mistake)
    }

    // =========================================================================
    // 单次 MCTS 迭代
    // =========================================================================

    fn run_one_iteration(&self, root: &mut MctsNode, board: &mut Board, root_mover: Color) {
        // ── Selection ────────────────────────────────────────────────────────
        // 沿 UCB1 最优路径下行，直到找到未完全扩展的节点或叶节点
        let node_ptr = root as *mut MctsNode;
        // SAFETY: 我们在单线程中使用原始指针遍历树，避免借用检查器的嵌套可变借用限制
        let (leaf, depth_color, path) = unsafe { self.select(node_ptr, board, root_mover) };

        let value;

        // ── Expansion + Evaluation ────────────────────────────────────────
        let leaf_ref = unsafe { &mut *leaf };
        if leaf_ref.visits > 0 && !leaf_ref.untried_moves.is_empty() {
            // 展开一个未尝试的走法
            let mv = leaf_ref.untried_moves.remove(0);
            let child_color = depth_color; // 即将落子的颜色
            let mut child_board = board.clone();
            child_board.place(mv.0, mv.1, child_color);

            // 立即胜利检测
            if child_board.check_win(mv.0, mv.1, child_color) {
                value = if child_color == self.config.player {
                    WIN_VALUE
                } else {
                    LOSE_VALUE
                };
            } else {
                // 静态评估（替代随机 Rollout）
                value = self.static_eval_normalized(&child_board);
            }

            let next_mover = child_color.opponent();
            let max_c = self.config.max_children;
            let child = MctsNode {
                mv: Some(mv),
                color: child_color,
                visits: 1,
                total_value: value,
                untried_moves: top_k_moves(&child_board, next_mover, max_c),
                children: Vec::new(),
            };
            leaf_ref.children.push(child);
        } else if leaf_ref.visits == 0 {
            // 首次访问叶节点：直接评估，不扩展
            value = self.static_eval_normalized(board);
        } else {
            // 已完全扩展但 visits > 0（终局节点）：重新评估
            value = self.static_eval_normalized(board);
        }

        // ── Backpropagation ───────────────────────────────────────────────
        // 使用 selection 路径回传（O(depth)），避免每次从根 DFS 查找叶子（旧实现 O(nodes)）
        self.backpropagate_path(root, &path, value);
    }

    // =========================================================================
    // Selection：UCB1 下行
    // =========================================================================

    /// 沿 UCB1 路径下行，返回目标节点指针和该节点"下一步应由谁落子"
    unsafe fn select(
        &self,
        mut node: *mut MctsNode,
        board: &mut Board,
        root_mover: Color,
    ) -> (*mut MctsNode, Color, Vec<usize>) {
        let mut current_mover = root_mover;
        let mut path: Vec<usize> = Vec::new();

        loop {
            let n = &mut *node;

            // 未完全扩展 → 在此处扩展
            if !n.is_fully_expanded() || n.is_leaf() {
                return (node, current_mover, path);
            }

            // 选择 UCB1 最大的子节点
            let parent_visits = n.visits;
            let c = self.config.exploration_c;
            let best_idx = n
                .children
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| {
                    a.ucb1(parent_visits, c)
                        .partial_cmp(&b.ucb1(parent_visits, c))
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                .map(|(i, _)| i)
                .unwrap_or(0);

            // 在 board 上落子，推进局面
            let child = &n.children[best_idx];
            if let Some((r, c_pos)) = child.mv {
                board.place(r, c_pos, current_mover);
            }
            current_mover = current_mover.opponent();
            path.push(best_idx);
            node = &mut n.children[best_idx] as *mut MctsNode;
        }
    }

    // =========================================================================
    // Backpropagation
    // =========================================================================

    /// 根据 selection 阶段记录的路径回传（root -> ... -> selected leaf）
    /// 复杂度 O(depth)，显著降低移动端每迭代开销。
    fn backpropagate_path(&self, root: &mut MctsNode, path: &[usize], value: f64) {
        root.visits += 1;
        root.total_value += value;

        let mut node = root;
        for &idx in path {
            if idx >= node.children.len() {
                break;
            }
            node = &mut node.children[idx];
            node.visits += 1;
            node.total_value += value;
        }
    }

    // =========================================================================
    // 静态评估（归一化到 [-1, 1]）
    // =========================================================================

    fn static_eval_normalized(&self, board: &Board) -> f64 {
        let mut score: i64 = 0;
        let player = self.config.player;
        let opp = player.opponent();

        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                if board.get(r, c) == Color::Empty {
                    score += evaluate_position(board, r, c, player) as i64;
                    score -= evaluate_position(board, r, c, opp) as i64 * 9 / 10;
                }
            }
        }

        // 钳制到 [-1, 1]
        (score as f64 / EVAL_SCALE).clamp(-1.0, 1.0)
    }

    // =========================================================================
    // 快速胜负检查
    // =========================================================================

    fn find_immediate_win(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
    ) -> Option<(usize, usize)> {
        for &(r, c) in moves {
            let mut b = board.clone();
            if b.place(r, c, self.config.player) && b.check_win(r, c, self.config.player) {
                return Some((r, c));
            }
        }
        None
    }

    fn find_must_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let opp = self.config.player.opponent();
        for &(r, c) in moves {
            let mut b = board.clone();
            if b.place(r, c, opp) && b.check_win(r, c, opp) {
                return Some((r, c));
            }
        }
        None
    }

    /// 检查己方是否可以在某处落子后形成活四/双四/三四组合（几乎必胜）。
    ///
    /// 优先用严格四连检测（不允许跳格），再回退到组合分评估。
    fn find_forced_win(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let player = self.config.player;
        // 第一步：找落子后形成真实四连（≥4 连续同色且一端开放）的走法，
        // 取其中 evaluate_position 最高的（例如活四 > 冲四）。
        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, player))
            .max_by_key(|&&(r, c)| evaluate_position(board, r, c, player))
            .copied();
        if best_real.is_some() {
            return best_real;
        }
        // 第二步：无真实四连时，检查高分组合威胁（双四 30_000、三四 20_000 等），
        // 这类组合的 evaluate_position 远超 SCORE_FOUR，也是必走局面。
        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = evaluate_position(board, r, c, player);
                if s >= SCORE_FOUR { Some(((r, c), s)) } else { None }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    /// 检查对方是否可以在某处落子后形成活四/双四/三四组合，若是则抢先堵住。
    ///
    /// 核心问题：`analyze_line` 允许窗口内存在跳格，导致「外侧一格」的位置
    /// 与真正堵点分数相同（如黑 7,6 7,7 7,8 时，(7,4) 和 (7,5) 都得 SCORE_FOUR）。
    /// 因此必须先用严格四连检测过滤，再从真实威胁中取评分最高的防守点。
    fn find_critical_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let opp = self.config.player.opponent();
        // 第一步：只考虑落子后对方能形成真实四连（连续 ≥4 且一端开放）的位置。
        // is_real_four_threat 不允许跳格，能正确排除「外侧一格」的假阳性。
        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, opp))
            .max_by_key(|&&(r, c)| evaluate_position(board, r, c, opp))
            .copied();
        if best_real.is_some() {
            return best_real;
        }
        // 第二步：无真实四连时，检查高分组合威胁（双四、三四等 evaluate >= SCORE_FOUR），
        // 这类组合 analyze_line 的假阳性概率低，仍用评估分筛选。
        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = evaluate_position(board, r, c, opp);
                if s >= SCORE_FOUR { Some(((r, c), s)) } else { None }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    // =========================================================================
    // 外部控制接口（JNI 调用）
    // =========================================================================

    pub fn invalidate(&self) {
        self.should_stop.store(true, Ordering::Release);
    }

    pub fn validate(&self) {
        self.should_stop.store(false, Ordering::Release);
    }

    pub fn clear(&mut self) {
        self.start_time = Instant::now();
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    fn elapsed_ms(&self) -> u64 {
        self.start_time.elapsed().as_millis() as u64
    }

    fn rand_u64(&mut self) -> u64 {
        // xorshift64*
        let mut x = self.rng_state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.rng_state = x;
        x
    }

    fn rand_f64(&mut self) -> f64 {
        let v = self.rand_u64() >> 11;
        (v as f64) / ((1u64 << 53) as f64)
    }

    fn roll(&mut self, p: f64) -> bool {
        if p <= 0.0 {
            return false;
        }
        if p >= 1.0 {
            return true;
        }
        self.rand_f64() < p
    }

    /// 对访问次数做 softmax 抽样（温度越高越随机）
    fn sample_softmax(&mut self, items: &[((usize, usize), u32)], temp: f64) -> Option<(usize, usize)> {
        if items.is_empty() {
            return None;
        }
        if items.len() == 1 {
            return Some(items[0].0);
        }

        let t = temp.max(0.05);
        let max_v = items.iter().map(|(_, v)| *v as f64).fold(f64::MIN, f64::max);
        let mut weights = Vec::with_capacity(items.len());
        let mut sum = 0.0;

        for (_, v) in items {
            let w = ((*v as f64 - max_v) / t).exp();
            weights.push(w);
            sum += w;
        }

        if sum <= f64::EPSILON {
            return Some(items[0].0);
        }

        let mut r = self.rand_f64() * sum;
        for (idx, w) in weights.iter().enumerate() {
            r -= *w;
            if r <= 0.0 {
                return Some(items[idx].0);
            }
        }
        Some(items[items.len() - 1].0)
    }

    /// 判断某步是否会让对手下一手立即五连
    fn allows_opponent_immediate_win(&self, board: &Board, mv: (usize, usize), mover: Color) -> bool {
        let mut b = board.clone();
        if !b.place(mv.0, mv.1, mover) {
            return true;
        }
        let opp = mover.opponent();
        let next_moves = b.generate_moves();
        next_moves.into_iter().any(|(r, c)| {
            let mut t = b.clone();
            t.place(r, c, opp) && t.check_win(r, c, opp)
        })
    }
}

// ============================================================================
// 模块级辅助函数
// ============================================================================

/// 严格四连威胁检测：在 (row, col) 落子（颜色 color）后，
/// 检查是否形成「真实四连」—— 从落子点出发，四个方向中任意一个方向
/// 有 ≥4 枚连续同色棋子（落子点本身计1），且至少一端开放。
///
/// ## 与 `analyze_line` 的区别
/// `analyze_line` 允许窗口内有空格（跳格计入 empty），会把
/// `_ _ X X X _`（外侧一格）和 `_ X X X X _`（真正堵点）评为同分，
/// 导致 AI 堵错位置。本函数只统计紧邻的连续棋子，不允许跳格。
fn is_real_four_threat(board: &Board, row: usize, col: usize, color: Color) -> bool {
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];
    for &(dr, dc) in &dirs {
        let mut count = 1i32; // 落子点本身
        // 正向：只计紧邻的连续同色棋子
        let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r += dr;
            c += dc;
        }
        let right_open = r >= 0 && r < BOARD_SIZE as i32
            && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == Color::Empty;
        // 反向
        let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r -= dr;
            c -= dc;
        }
        let left_open = r >= 0 && r < BOARD_SIZE as i32
            && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == Color::Empty;

        if count >= 4 && (left_open || right_open) {
            return true;
        }
    }
    false
}

/// 生成候选走法并取 Top-K（按综合启发分降序）
///
/// ## 启发分构成
/// 1. **基础分**：`evaluate_position(mover)` + `evaluate_position(opp) * 0.9`
/// 2. **威胁加权**：各类复合棋型的高权重奖励
/// 3. **近邻奖励**：紧邻（1格内）已有棋子的位置额外加分，引导 AI 在核心区域落子
fn top_k_moves(board: &Board, mover: Color, k: usize) -> Vec<(usize, usize)> {
    let raw = board.generate_moves();
    let opp = mover.opponent();
    let mut scored: Vec<((usize, usize), i32)> = raw
        .into_iter()
        .map(|(r, c)| {
            let base = evaluate_position(board, r, c, mover)
                + evaluate_position(board, r, c, opp) * 9 / 10;
            let threat = threat_score(board, r, c, mover, opp);
            // 近邻奖励：紧邻（切比雪夫距离 == 1）已有棋子的位置更优先
            let proximity = proximity_bonus(board, r, c);
            ((r, c), base + threat + proximity)
        })
        .collect();
    scored.sort_unstable_by(|a, b| b.1.cmp(&a.1));
    scored.into_iter().take(k).map(|(pos, _)| pos).collect()
}

/// 近邻奖励：若 (row, col) 的 8 个紧邻格中有己方或对方棋子，
/// 则额外加 300 分，引导 AI 聚焦于已有棋子周围而非远处空位。
fn proximity_bonus(board: &Board, row: usize, col: usize) -> i32 {
    let r0 = row as i32;
    let c0 = col as i32;
    for dr in -1i32..=1 {
        for dc in -1i32..=1 {
            if dr == 0 && dc == 0 { continue; }
            let nr = r0 + dr;
            let nc = c0 + dc;
            if nr >= 0 && nr < BOARD_SIZE as i32 && nc >= 0 && nc < BOARD_SIZE as i32 {
                if board.get(nr as usize, nc as usize) != Color::Empty {
                    return 300;
                }
            }
        }
    }
    0
}

/// 计算在 (row, col) 落子时产生的威胁加权分。
///
/// 扫描四个方向，统计己方和对方的棋型分布，
/// 识别双四、双三、活三+冲四等复合威胁。
fn threat_score(board: &Board, row: usize, col: usize, mover: Color, opp: Color) -> i32 {
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];

    // 统计落子后己方各棋型出现次数
    let mut my_fours = 0i32;   // 活四（score >= SCORE_FOUR = 10_000）
    let mut my_bfours = 0i32;  // 冲四（score >= SCORE_BLOCKED_FOUR = 1_000，但 < SCORE_FOUR）
    let mut my_threes = 0i32;  // 活三（score == SCORE_THREE = 1_000，与冲四分同，靠 empty/blocked 区分）

    // 统计对方棋型（用于判断是否需要防守）
    let mut opp_fours = 0i32;
    let mut opp_bfours = 0i32;
    let mut opp_threes = 0i32;

    for &(dr, dc) in &dirs {
        // 己方
        let my_pat = analyze_line(board, row, col, dr, dc, mover);
        let (cnt_m, emp_m, lb_m, rb_m) = my_pat;
        let ms = match_pattern_score(my_pat);
        if ms >= SCORE_FOUR {
            my_fours += 1;
        } else if ms >= SCORE_BLOCKED_FOUR {
            // emp>=5 在 count==2 时实际不可达（最大为4），用 emp>=4 检测活三
            let is_active_three = cnt_m == 2 && emp_m >= 4 && !lb_m && !rb_m;
            if is_active_three {
                my_threes += 1;
            } else {
                my_bfours += 1;
            }
        }

        // 对方
        let opp_pat = analyze_line(board, row, col, dr, dc, opp);
        let (cnt_o, emp_o, lb_o, rb_o) = opp_pat;
        let os = match_pattern_score(opp_pat);
        if os >= SCORE_FOUR {
            opp_fours += 1;
        } else if os >= SCORE_BLOCKED_FOUR {
            let is_active_three = cnt_o == 2 && emp_o >= 4 && !lb_o && !rb_o;
            if is_active_three {
                opp_threes += 1;
            } else {
                opp_bfours += 1;
            }
        }
    }

    let mut bonus = 0i32;

    // ── 进攻威胁 ────────────────────────────────────────────────────────
    if my_fours >= 1 {
        bonus += THREAT_WIN;
    } else if my_bfours >= 2 {
        bonus += THREAT_DOUBLE_FOUR;
    } else if my_bfours >= 1 && my_threes >= 1 {
        bonus += THREAT_THREE_FOUR;
    } else if my_threes >= 2 {
        bonus += THREAT_DOUBLE_THREE;
    } else if my_bfours >= 1 {
        bonus += THREAT_FOUR;
    } else if my_threes >= 1 {
        bonus += THREAT_THREE;
    }

    // ── 防御威胁（对方的进攻需要阻止）────────────────────────────────
    if opp_fours >= 1 {
        bonus += THREAT_BLOCK_WIN;
    } else if opp_bfours >= 2 {
        bonus += THREAT_DOUBLE_FOUR / 2;
    } else if opp_bfours >= 1 && opp_threes >= 1 {
        bonus += THREAT_THREE_FOUR / 2;
    } else if opp_threes >= 2 {
        bonus += THREAT_DOUBLE_THREE / 2;
    } else if opp_threes >= 1 {
        bonus += THREAT_THREE; // 对方单活三：与己方同等权重，必须优先堵
    }

    bonus
}

/// 开局库（与 GomokuAi 保持一致）
fn opening_book(board: &Board) -> Option<(usize, usize)> {
    const CENTER: (usize, usize) = (7, 7);
    match board.move_count {
        0 => Some(CENTER),
        1 => {
            if board.is_empty(CENTER.0, CENTER.1) {
                Some(CENTER)
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
