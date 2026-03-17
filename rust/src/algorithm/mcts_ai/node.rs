pub(super) struct MctsNode {
    /// 对应的走法（根节点为 None）
    pub(super) mv: Option<(usize, usize)>,
    /// 落子颜色（执行 mv 时谁在落子；根节点为上一个落子方）
    pub(super) color: crate::board::Color,
    /// 访问次数
    pub(super) visits: u32,
    /// 累计评估值（从 color 角度，区间 [-1, 1]）
    pub(super) total_value: f64,
    /// 尚未扩展的候选走法（按启发分降序排列）
    pub(super) untried_moves: Vec<(usize, usize)>,
    /// 已扩展的子节点
    pub(super) children: Vec<MctsNode>,
}

impl MctsNode {
    /// 创建根节点
    pub(super) fn root(board: &crate::board::Board, mover: crate::board::Color, max_children: usize) -> Self {
        let untried = super::heuristics::top_k_moves(board, mover, max_children);
        Self {
            mv: None,
            color: mover.opponent(),
            visits: 0,
            total_value: 0.0,
            untried_moves: untried,
            children: Vec::new(),
        }
    }

    /// UCB1 得分（带视角参数，sign=+1 AI层，-1 对手层）
    pub(super) fn ucb1(&self, parent_visits: u32, c: f64, sign: f64) -> f64 {
        if self.visits == 0 {
            return f64::INFINITY;
        }
        let avg = self.total_value / self.visits as f64;
        let exploit = sign * avg;
        let explore = c * ((parent_visits as f64).ln() / self.visits as f64).sqrt();
        exploit + explore
    }

    pub(super) fn is_fully_expanded(&self) -> bool {
        self.untried_moves.is_empty()
    }

    pub(super) fn is_leaf(&self) -> bool {
        self.children.is_empty()
    }
}
