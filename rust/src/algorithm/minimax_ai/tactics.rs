use super::*;

impl MinimaxAi {
    pub(super) fn threat_summary_for(&self, board: &Board, player: Color, moves: &[(usize, usize)]) -> ThreatSummary {
        let mut summary = ThreatSummary::default();

        for &(row, col) in moves {
            let score = self.normalized_eval_score(board, row, col, player);
            if score > summary.best_score {
                summary.best_score = score;
            }

            let mut next = board.clone();
            if !next.place(row, col, player) {
                continue;
            }

            if next.check_win(row, col, player) {
                summary.win_moves += 1;
                summary.best_score = summary.best_score.max(WIN_SCORE);
                continue;
            }

            if score >= DOUBLE_THREAT_THRESHOLD {
                summary.double_threats += 1;
            } else if score >= SCORE_BLOCKED_FOUR {
                summary.blocked_fours += 1;
            } else if score >= SCORE_THREE {
                summary.threes += 1;
            }
        }

        summary
    }

    pub(super) fn threat_summary_score(&self, summary: ThreatSummary) -> i32 {
        summary.win_moves * 240_000
            + summary.double_threats * 32_000
            + summary.blocked_fours * 9_000
            + summary.threes * 1_500
            + summary.best_score / 3
    }

    pub(super) fn critical_block_candidates_for(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        defender: Color,
    ) -> Vec<((usize, usize), i32)> {
        let opp = defender.opponent();

        let mut critical: Vec<((usize, usize), i32)> = moves
            .iter()
            .filter_map(|&(r, c)| {
                let mut s = self.normalized_eval_score(board, r, c, opp);
                if is_real_four_threat(board, r, c, opp) {
                    s = s.max(SCORE_FOUR);
                }
                if s >= SCORE_BLOCKED_FOUR {
                    Some(((r, c), s))
                } else {
                    None
                }
            })
            .collect();
        critical.sort_by(|a, b| b.1.cmp(&a.1));
        critical
    }

    pub(super) fn find_forced_win(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let player = self.config.player;
        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, player))
            .max_by_key(|&&(r, c)| self.normalized_eval_score(board, r, c, player))
            .copied();
        if best_real.is_some() {
            return best_real;
        }

        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = self.normalized_eval_score(board, r, c, player);
                if s >= SCORE_FOUR {
                    Some(((r, c), s))
                } else {
                    None
                }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    pub(super) fn find_critical_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let candidates = self.critical_block_candidates_for(board, moves, self.config.player);
        if candidates.is_empty() {
            return None;
        }

        let top_tier = self.threat_tier(candidates[0].1);
        let ranked: Vec<((usize, usize), i32, i32, i32)> = candidates
            .iter()
            .take_while(|(_, score)| self.threat_tier(*score) == top_tier)
            .filter_map(|&((row, col), _)| {
                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return None;
                }

                let followup_risk = self.forced_counterplay_risk(&next, self.config.player);
                let local_shape = self.local_shape_bonus_after_placed(&next, row, col, self.config.player);
                let control_density = self.local_control_density(board, row, col);
                Some(((row, col), followup_risk, control_density, local_shape))
            })
            .collect();

        let best_followup = ranked.iter().map(|(_, followup_risk, _, _)| *followup_risk).min()?;
        ranked
            .into_iter()
            .filter(|(_, followup_risk, _, _)| *followup_risk <= best_followup + 96)
            .max_by_key(|&(_, _, control_density, local_shape)| (control_density, local_shape))
            .map(|((row, col), _, _, _)| (row, col))
    }

    pub(super) fn find_mainline_defense(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let opp = self.config.player.opponent();
        let current = self.threat_summary_for(board, opp, moves);
        let is_master = self.config.max_depth >= 20;
        let should_trigger = if is_master {
            current.best_score >= SCORE_THREE
                && (current.threes >= 3 || current.double_threats > 0 || current.blocked_fours > 0)
        } else {
            current.best_score >= SCORE_THREE
                && (current.best_score >= SCORE_BLOCKED_FOUR
                    || current.double_threats > 0
                    || current.threes >= 4)
        };
        if !should_trigger {
            return None;
        }

        moves
            .iter()
            .filter_map(|&(row, col)| {
                let defense = self.normalized_eval_score(board, row, col, opp);
                if defense < SCORE_THREE {
                    return None;
                }

                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return None;
                }

                let next_moves = next.generate_moves();
                let after = self.threat_summary_for(&next, opp, &next_moves);
                let followup_risk = self.forced_counterplay_risk(&next, self.config.player);
                let control_density = self.local_control_density(board, row, col);
                let local_shape = self.local_shape_bonus_after_placed(&next, row, col, self.config.player);

                Some(((row, col), after, followup_risk, control_density, local_shape))
            })
            .min_by_key(|&(_, after, followup_risk, control_density, local_shape)| {
                (
                    after.win_moves,
                    after.double_threats,
                    after.blocked_fours,
                    after.threes,
                    after.best_score,
                    followup_risk,
                    -control_density,
                    -local_shape,
                )
            })
            .map(|((row, col), _, _, _, _)| (row, col))
    }

    pub(super) fn vcf_search(
        &self,
        board: &mut Board,
        player: Color,
        depth: i32,
    ) -> Option<(usize, usize)> {
        self.vcf_search_inner(board, player, depth)
    }

    pub(super) fn vcf_search_inner(
        &self,
        board: &mut Board,
        player: Color,
        depth: i32,
    ) -> Option<(usize, usize)> {
        if depth <= 0 || !self.time_ok() {
            return None;
        }

        let opp = player.opponent();
        let candidates: Vec<(usize, usize)> = board
            .generate_moves()
            .into_iter()
            .filter(|&(r, c)| is_real_four_threat_if_placed(board, r, c, player))
            .collect();

        for (ar, ac) in candidates {
            if !board.place(ar, ac, player) {
                continue;
            }

            if board.check_win(ar, ac, player) {
                board.unplace(ar, ac);
                return Some((ar, ac));
            }

            let threats = vcf_win_threats(board, player);
            if threats.is_empty() {
                board.unplace(ar, ac);
                continue;
            }
            if threats.len() >= 2 {
                board.unplace(ar, ac);
                return Some((ar, ac));
            }

            let opp_wins_now = board.generate_moves().into_iter().any(|(r, c)| {
                if !board.place(r, c, opp) {
                    return false;
                }
                let win = board.check_win(r, c, opp);
                board.unplace(r, c);
                win
            });

            if opp_wins_now {
                board.unplace(ar, ac);
                continue;
            }

            let (br, bc) = threats[0];
            if !board.place(br, bc, opp) {
                board.unplace(ar, ac);
                continue;
            }

            let success = self.vcf_search_inner(board, player, depth - 2).is_some();

            board.unplace(br, bc);
            board.unplace(ar, ac);

            if success {
                return Some((ar, ac));
            }
        }

        None
    }

    pub(super) fn vcf_defense(
        &self,
        board: &mut Board,
        player: Color,
        opp_vcf_depth: i32,
    ) -> Option<(usize, usize)> {
        let opp = player.opponent();
        let opp_has_vcf = self.vcf_search_inner(board, opp, opp_vcf_depth).is_some();
        if !opp_has_vcf {
            return None;
        }

        for (mr, mc) in board.generate_moves() {
            if !self.time_ok() {
                break;
            }
            if !board.place(mr, mc, player) {
                continue;
            }
            let still_vcf = self.vcf_search_inner(board, opp, opp_vcf_depth - 2).is_some();
            board.unplace(mr, mc);
            if !still_vcf {
                return Some((mr, mc));
            }
        }

        None
    }
}
