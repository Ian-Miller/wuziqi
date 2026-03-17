use super::*;

impl MinimaxAi {
    pub(super) fn master_plan_bonus(&self, board: &Board, row: usize, col: usize) -> i32 {
        let mut b = board.clone();
        if !b.place(row, col, self.config.player) {
            return -500_000;
        }

        let opp = self.config.player.opponent();
        let next = b.generate_moves();
        let opp_can_win = next.iter().any(|&(r, c)| {
            let mut t = b.clone();
            t.place(r, c, opp) && t.check_win(r, c, opp)
        });
        if opp_can_win {
            return -220_000;
        }

        let opp_summary = self.threat_summary_for(&b, opp, &next);
        let mut high_threat_count = 0i32;
        let mut mid_threat_count = 0i32;
        let mut best_follow = 0i32;

        for (r, c) in next.iter().copied() {
            let s = self.normalized_eval_score(&b, r, c, self.config.player);
            if s >= SCORE_BLOCKED_FOUR {
                high_threat_count += 1;
            } else if s >= SCORE_THREE {
                mid_threat_count += 1;
            }
            if s > best_follow {
                best_follow = s;
            }
        }

        let (high_weight, mid_weight, follow_divisor) = if board.move_count <= 14 {
            (2_400, 1_100, 16)
        } else if board.move_count <= 40 {
            (3_200, 950, 12)
        } else {
            (4_000, 900, 10)
        };

        let shape_bonus = self.local_shape_bonus_after_placed(&b, row, col, self.config.player);
        let threat_penalty = when_threat_penalty(opp_summary.best_score, board.move_count);
        let counterplay_penalty = if self.config.max_depth >= 20 {
            self.forced_counterplay_risk(&b, self.config.player)
        } else {
            0
        };

        high_threat_count * high_weight
            + mid_threat_count * mid_weight
            + best_follow / follow_divisor
            + shape_bonus
            - threat_penalty
            - counterplay_penalty
    }

    pub(super) fn threat_profile(&self, board: &Board, player: Color) -> ThreatProfile {
        let candidates = board.generate_moves();
        let mut profile = ThreatProfile::default();

        for (row, col) in candidates {
            let score = self.normalized_eval_score(board, row, col, player);
            if score > profile.best_score {
                profile.best_score = score;
            }

            if is_real_four_threat(board, row, col, player) {
                profile.real_fours += 1;
            } else if score >= SCORE_BLOCKED_FOUR {
                profile.blocked_fours += 1;
            } else if score >= SCORE_THREE {
                profile.live_threes += 1;
            } else if score >= SCORE_TWO {
                profile.twos += 1;
            }
        }

        profile
    }

    pub(super) fn discrete_mainline_eval(&self, board: &Board, player: Color) -> i32 {
        let mine = self.threat_profile(board, player);
        let opp = self.threat_profile(board, player.opponent());

        let (best_w, real_four_w, blocked_four_w, three_w, two_w) = if board.move_count <= 12 {
            (16_000, 20_000, 6_500, 1_400, 180)
        } else if board.move_count <= 40 {
            (20_000, 26_000, 8_000, 1_900, 120)
        } else {
            (24_000, 30_000, 10_000, 2_300, 80)
        };

        let my_tier = self.threat_tier(mine.best_score);
        let opp_tier = self.threat_tier(opp.best_score);

        let my_score = my_tier * best_w
            + mine.real_fours * real_four_w
            + mine.blocked_fours * blocked_four_w
            + mine.live_threes * three_w
            + mine.twos * two_w;
        let opp_score = opp_tier * best_w
            + opp.real_fours * real_four_w
            + opp.blocked_fours * blocked_four_w
            + opp.live_threes * three_w
            + opp.twos * two_w;

        my_score - opp_score
    }

    pub(super) fn threat_tier(&self, score: i32) -> i32 {
        if score >= SCORE_FOUR {
            4
        } else if score >= DOUBLE_THREAT_THRESHOLD {
            3
        } else if score >= SCORE_BLOCKED_FOUR {
            2
        } else if score >= SCORE_THREE {
            1
        } else {
            0
        }
    }

    pub(super) fn forced_counterplay_risk(&self, board: &Board, player: Color) -> i32 {
        let opp = player.opponent();
        let moves = board.generate_moves();
        let mut replies: Vec<((usize, usize), i32)> = moves
            .iter()
            .filter_map(|&(r, c)| {
                let score = self.normalized_eval_score(board, r, c, opp);
                let mut next = board.clone();
                if !next.place(r, c, opp) {
                    return None;
                }
                let tactical_score = if next.check_win(r, c, opp) {
                    WIN_SCORE
                } else {
                    score
                };
                if tactical_score >= SCORE_THREE {
                    Some(((r, c), tactical_score))
                } else {
                    None
                }
            })
            .collect();

        replies.sort_by(|a, b| b.1.cmp(&a.1));

        let mut worst_risk = 0i32;
        for &((reply_row, reply_col), reply_score) in replies.iter().take(4) {
            let mut after_reply = board.clone();
            if !after_reply.place(reply_row, reply_col, opp) {
                continue;
            }
            if after_reply.check_win(reply_row, reply_col, opp) {
                return 260_000;
            }

            let reply_moves = after_reply.generate_moves();
            let defense_candidates = self.critical_block_candidates_for(&after_reply, &reply_moves, player);
            let residual_risk = if defense_candidates.is_empty() {
                self.threat_summary_score(self.threat_summary_for(&after_reply, opp, &reply_moves))
            } else {
                defense_candidates
                    .iter()
                    .take(4)
                    .filter_map(|&((def_row, def_col), _)| {
                        let mut after_defense = after_reply.clone();
                        if !after_defense.place(def_row, def_col, player) {
                            return None;
                        }
                        let next_moves = after_defense.generate_moves();
                        Some(self.threat_summary_score(self.threat_summary_for(&after_defense, opp, &next_moves)))
                    })
                    .min()
                    .unwrap_or(220_000)
            };

            let reply_shape_risk = self.local_shape_bonus_after_placed(&after_reply, reply_row, reply_col, opp).max(0);
            let combined_risk = reply_score / 2 + residual_risk + reply_shape_risk;
            if combined_risk > worst_risk {
                worst_risk = combined_risk;
            }
        }

        worst_risk
    }

    pub(super) fn move_tactical_score(&self, board: &Board, row: usize, col: usize, player: Color) -> i32 {
        let attack = self.normalized_eval_score(board, row, col, player);
        let defense = self.normalized_eval_score(board, row, col, player.opponent());
        let move_count = board.move_count;

        let attack_weighted = if move_count <= 14 && attack >= SCORE_BLOCKED_FOUR && attack < SCORE_FOUR {
            attack * 92 / 100
        } else {
            attack
        };

        let defense_weighted = if defense >= SCORE_FOUR {
            defense * 11 / 10
        } else if defense >= DOUBLE_THREAT_THRESHOLD {
            defense * 105 / 100
        } else if defense >= SCORE_BLOCKED_FOUR {
            if move_count <= 14 { defense * 95 / 100 } else { defense }
        } else if defense >= SCORE_THREE {
            if move_count <= 14 {
                defense * 78 / 100
            } else if move_count <= 40 {
                defense * 88 / 100
            } else {
                defense * 95 / 100
            }
        } else if move_count <= 14 {
            defense * 65 / 100
        } else {
            defense * 75 / 100
        };

        attack_weighted + defense_weighted
    }

    pub(super) fn local_shape_bonus_after_placed(&self, board: &Board, row: usize, col: usize, player: Color) -> i32 {
        let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];
        let mut bonus = 0i32;
        let mut open_two_dirs = 0i32;
        let mut open_three_dirs = 0i32;
        let mut isolated_forcing_dirs = 0i32;

        for &(dr, dc) in &dirs {
            let mut count = 1i32;

            let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
            while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
                && board.get(r as usize, c as usize) == player
            {
                count += 1;
                r += dr;
                c += dc;
            }
            let right_open = r >= 0 && r < BOARD_SIZE as i32
                && c >= 0 && c < BOARD_SIZE as i32
                && board.get(r as usize, c as usize) == Color::Empty;

            let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
            while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
                && board.get(r as usize, c as usize) == player
            {
                count += 1;
                r -= dr;
                c -= dc;
            }
            let left_open = r >= 0 && r < BOARD_SIZE as i32
                && c >= 0 && c < BOARD_SIZE as i32
                && board.get(r as usize, c as usize) == Color::Empty;

            let open_ends = (left_open as i32) + (right_open as i32);
            if count >= 2 {
                if open_ends == 2 {
                    bonus += count * count * 180;
                    if count >= 3 {
                        open_three_dirs += 1;
                    } else {
                        open_two_dirs += 1;
                    }
                } else if open_ends == 1 {
                    bonus += count * count * 60;
                    if count >= 4 {
                        isolated_forcing_dirs += 1;
                    }
                }
            }
        }

        bonus += open_two_dirs * 220;
        if open_three_dirs >= 2 {
            bonus += 5_000;
        } else if open_three_dirs == 1 && open_two_dirs >= 1 {
            bonus += 1_800;
        }

        if board.move_count <= 14 && isolated_forcing_dirs >= 1 && open_two_dirs == 0 && open_three_dirs == 0 {
            bonus -= 1_400 * isolated_forcing_dirs;
        }

        bonus
    }

    pub(super) fn local_control_density(&self, board: &Board, row: usize, col: usize) -> i32 {
        let mut density = 0i32;

        for dr in -2i32..=2 {
            for dc in -2i32..=2 {
                if dr == 0 && dc == 0 {
                    continue;
                }

                let nr = row as i32 + dr;
                let nc = col as i32 + dc;
                if nr < 0 || nr >= BOARD_SIZE as i32 || nc < 0 || nc >= BOARD_SIZE as i32 {
                    continue;
                }

                if board.get(nr as usize, nc as usize) != Color::Empty {
                    let distance = dr.abs().max(dc.abs());
                    density += 4 - distance;
                }
            }
        }

        density
    }

    fn static_eval(&self, board: &Board, player: Color) -> i32 {
        let mut score = 0;
        let candidates = board.generate_moves();
        if candidates.is_empty() {
            return 0;
        }
        for (r, c) in candidates {
            score += self.move_tactical_score(board, r, c, player);
            score -= self.move_tactical_score(board, r, c, player.opponent());
        }

        if self.config.max_depth >= 12 {
            let is_master = self.config.max_depth >= 20;
            let (center_w, link_w, mobility_w, line_w) = self.phase_weights(board.move_count, is_master);

            score += self.discrete_mainline_eval(board, player);
            score += self.strategic_eval(board, player, center_w, link_w, mobility_w);
            score += self.line_pressure_eval(board, player, line_w);
        }

        score
    }

    pub(super) fn static_eval_local(&self, board: &Board, player: Color, last_move: Option<(usize, usize)>) -> i32 {
        let Some((lr, lc)) = last_move else {
            return self.static_eval(board, player);
        };

        let mut marked = [[false; BOARD_SIZE]; BOARD_SIZE];
        let mut score = 0i32;
        let opp = player.opponent();

        for rr in (lr as i32 - LOCAL_EVAL_RADIUS)..=(lr as i32 + LOCAL_EVAL_RADIUS) {
            for cc in (lc as i32 - LOCAL_EVAL_RADIUS)..=(lc as i32 + LOCAL_EVAL_RADIUS) {
                if rr < 0 || rr >= BOARD_SIZE as i32 || cc < 0 || cc >= BOARD_SIZE as i32 {
                    continue;
                }
                let r = rr as usize;
                let c = cc as usize;
                if board.get(r, c) == Color::Empty && !marked[r][c] {
                    marked[r][c] = true;
                    score += self.move_tactical_score(board, r, c, player);
                    score -= self.move_tactical_score(board, r, c, opp);
                }
            }
        }

        if score == 0 {
            return self.static_eval(board, player);
        }

        if self.config.max_depth >= 12 {
            let is_master = self.config.max_depth >= 20;
            let (center_w, link_w, mobility_w, line_w) = self.phase_weights(board.move_count, is_master);
            score += self.discrete_mainline_eval(board, player);
            score += self.strategic_eval(board, player, center_w, link_w, mobility_w);
            score += self.line_pressure_eval(board, player, line_w);
        }

        score
    }

    fn phase_weights(&self, move_count: usize, is_master: bool) -> (i32, i32, i32, i32) {
        if is_master {
            if move_count <= 12 {
                (30, 18, 4, 16)
            } else if move_count <= 50 {
                (22, 14, 7, 20)
            } else {
                (14, 12, 10, 26)
            }
        } else if move_count <= 12 {
            (30, 18, 4, 16)
        } else if move_count <= 50 {
            (22, 14, 7, 20)
        } else {
            (14, 12, 10, 26)
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
                let dr = (r as i32 - 7).abs();
                let dc = (c as i32 - 7).abs();
                let dist = dr.max(dc);
                let center_bonus = 7 - dist;
                total += sign * center_bonus * center_w;

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

    pub(super) fn eval_cache_key(&self, hash: u64, player: Color, last_move: Option<(usize, usize)>) -> u64 {
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

    pub(super) fn history_bonus(&self, player: Color, row: usize, col: usize) -> i32 {
        let p = Self::player_idx(player);
        let idx = Self::move_index(row, col);
        self.history_scores[p][idx] / 64
    }

    pub(super) fn record_killer(&mut self, ply: usize, mv: (usize, usize)) {
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

    pub(super) fn bump_history(&mut self, player: Color, row: usize, col: usize, depth: i32) {
        let p = Self::player_idx(player);
        let idx = Self::move_index(row, col);
        let bonus = (depth.max(1) * depth.max(1) * 40).min(12_000);
        let v = &mut self.history_scores[p][idx];
        *v = (*v + bonus).min(HISTORY_MAX);
    }

    pub(super) fn decay_history(&mut self) {
        for p in 0..2 {
            for v in &mut self.history_scores[p] {
                *v = (*v * 15) / 16;
            }
        }
    }

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
}
