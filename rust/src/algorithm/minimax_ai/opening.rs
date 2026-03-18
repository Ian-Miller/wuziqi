use super::*;

impl MinimaxAi {
    /// 根据局面阶段为当前回合分配时间预算。
    pub(super) fn choose_turn_time_budget(&self, board: &Board) -> u64 {
        let base = self.config.time_limit_ms;
        if self.config.max_depth >= 20 {
            match board.move_count {
                5..=6 => base.min(2_000),
                7..=14 => base.min(6_000),
                15..=24 => base.min(9_000),
                25..=34 => base.min(10_000),
                35..=44 => base.min(9_000),
                _ if board.move_count >= 45 => base.min(8_000),
                _ => base,
            }
        } else {
            base
        }
    }

    pub(super) fn opening_book(&self, board: &Board) -> Option<(usize, usize)> {
        const C: usize = 7;
        match board.move_count {
            0 => Some((C, C)),
            1 => {
                if board.is_empty(C, C) {
                    Some((C, C))
                } else {
                    [
                        (C, C - 1),
                        (C, C + 1),
                        (C - 1, C),
                        (C + 1, C),
                        (C - 1, C - 1),
                        (C + 1, C + 1),
                        (C - 1, C + 1),
                        (C + 1, C - 1),
                        (C, C - 2),
                        (C, C + 2),
                        (C - 2, C),
                        (C + 2, C),
                    ]
                    .iter()
                    .find(|&&p| board.is_empty(p.0, p.1))
                    .copied()
                }
            }
            2..=4 => self.opening_book_fast(board),
            _ => None,
        }
    }

    fn opening_book_fast(&self, board: &Board) -> Option<(usize, usize)> {
        const C: i32 = 7;
        const MAX_CENTER_DIST: i32 = 5;
        let player = self.config.player;
        let opponent = player.opponent();

        let candidates = board.generate_moves();
        for &(r, c) in &candidates {
            if self.normalized_eval_score(board, r, c, opponent) >= SCORE_BLOCKED_FOUR {
                return None;
            }
        }

        let mut my_pieces: Vec<(i32, i32)> = Vec::new();
        for r in 0..BOARD_SIZE {
            for c in 0..BOARD_SIZE {
                if board.get(r, c) == player {
                    my_pieces.push((r as i32, c as i32));
                }
            }
        }

        let mut best: Option<(usize, usize)> = None;
        let mut best_score: i64 = i64::MIN;

        for &(r, c) in &candidates {
            let ri = r as i32;
            let ci = c as i32;
            let center_d = (ri - C).abs().max((ci - C).abs());
            if center_d > MAX_CENTER_DIST {
                continue;
            }

            let my_d: i64 = if my_pieces.is_empty() {
                0
            } else {
                my_pieces
                    .iter()
                    .map(|&(pr, pc)| (ri - pr).abs().max((ci - pc).abs()))
                    .min()
                    .unwrap_or(10) as i64
            };

            let eval = self.move_tactical_score(board, r, c, player) as i64;
            let score = eval * 1_000 + (10 - my_d).max(0) * 80 + (8 - center_d as i64).max(0) * 30;

            if score > best_score {
                best_score = score;
                best = Some((r, c));
            }
        }

        best
    }

    pub(super) fn generate_ordered_moves(&self, board: &Board) -> Vec<(usize, usize)> {
        let raw = board.generate_moves();
        let mut scored: Vec<((usize, usize), i32)> = raw
            .into_iter()
            .map(|(r, c)| ((r, c), self.heuristic_score(board, r, c)))
            .collect();
        scored.sort_unstable_by(|a, b| b.1.cmp(&a.1));
        scored.into_iter().map(|(pos, _)| pos).collect()
    }

    pub(super) fn heuristic_score(&self, board: &Board, row: usize, col: usize) -> i32 {
        let mut score = self.move_tactical_score(board, row, col, self.config.player);
        if self.config.max_depth >= 12 {
            score += self.master_plan_bonus(board, row, col);
        }
        score
    }

    pub(super) fn normalized_eval_score(&self, board: &Board, row: usize, col: usize, player: Color) -> i32 {
        let score = evaluate_position(board, row, col, player);
        if score == SCORE_FOUR && !is_real_four_threat(board, row, col, player) {
            SCORE_THREE
        } else {
            score
        }
    }
}
