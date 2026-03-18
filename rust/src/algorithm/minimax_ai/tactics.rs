use super::*;
use crate::algorithm::lifecycle::{SearchAbort, SearchLifecycle};
use crate::algorithm::shared_tactics::find_immediate_win;

impl MinimaxAi {
    fn is_compound_forcing_summary(&self, summary: ThreatSummary) -> bool {
        summary.win_moves > 0
            || summary.double_threats > 0
            || (summary.blocked_fours > 0 && summary.threes > 0)
            || summary.threes >= 2
    }

    fn compound_attack_candidates_for(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        attacker: Color,
    ) -> Vec<((usize, usize), ThreatSummary, i32)> {
        let defender = attacker.opponent();
        let mut ranked: Vec<((usize, usize), ThreatSummary, i32)> = moves
            .iter()
            .filter_map(|&(row, col)| {
                let mut next = board.clone();
                if !next.place(row, col, attacker) {
                    return None;
                }

                if next.check_win(row, col, attacker) {
                    let summary = ThreatSummary {
                        best_score: WIN_SCORE,
                        win_moves: 1,
                        ..ThreatSummary::default()
                    };
                    return Some(((row, col), summary, WIN_SCORE));
                }

                let next_moves = next.generate_moves();
                if find_immediate_win(&next, &next_moves, defender).is_some() {
                    return None;
                }

                let summary = self.threat_summary_for(&next, attacker, &next_moves);
                if !self.is_compound_forcing_summary(summary) {
                    return None;
                }

                let attack_score = self.move_tactical_score(board, row, col, attacker)
                    + self.threat_summary_score(summary);
                Some(((row, col), summary, attack_score))
            })
            .collect();

        ranked.sort_by(|a, b| {
            b.1.win_moves.cmp(&a.1.win_moves)
                .then(b.1.double_threats.cmp(&a.1.double_threats))
                .then(b.1.blocked_fours.cmp(&a.1.blocked_fours))
                .then(b.1.threes.cmp(&a.1.threes))
                .then(b.1.best_score.cmp(&a.1.best_score))
                .then(b.2.cmp(&a.2))
        });
        ranked.truncate(COMPOUND_ATTACK_CANDIDATES);
        ranked
    }

    fn compound_defense_candidates_for(
        &self,
        board: &Board,
        defender: Color,
    ) -> Vec<(usize, usize)> {
        let attacker = defender.opponent();
        let moves = board.generate_moves();
        let mut ranked: Vec<((usize, usize), i32, i32)> = moves
            .iter()
            .filter_map(|&(row, col)| {
                let mut next = board.clone();
                if !next.place(row, col, defender) {
                    return None;
                }

                let next_moves = next.generate_moves();
                let summary = self.threat_summary_for(&next, attacker, &next_moves);
                let defense_score = self.normalized_eval_score(board, row, col, attacker);
                let control = self.local_control_density(board, row, col);
                Some(((row, col), self.threat_summary_score(summary) - defense_score, control))
            })
            .collect();

        ranked.sort_by(|a, b| a.1.cmp(&b.1).then(b.2.cmp(&a.2)));
        ranked.truncate(COMPOUND_DEFENSE_CANDIDATES);
        ranked.into_iter().map(|(mv, _, _)| mv).collect()
    }

    fn compound_forcing_search_inner(
        &self,
        board: &mut Board,
        attacker: Color,
        depth: i32,
    ) -> Result<bool, SearchAbort> {
        if depth <= 0 {
            return Ok(false);
        }
        if let Some(reason) = self.abort_reason() {
            return Err(reason);
        }

        let moves = board.generate_moves();
        let attack_candidates = self.compound_attack_candidates_for(board, &moves, attacker);
        if attack_candidates.is_empty() {
            return Ok(false);
        }

        for ((attack_row, attack_col), summary, _) in attack_candidates {
            if !board.place(attack_row, attack_col, attacker) {
                continue;
            }

            if board.check_win(attack_row, attack_col, attacker) {
                board.unplace(attack_row, attack_col);
                return Ok(true);
            }

            let defender = attacker.opponent();
            let defense_moves = self.compound_defense_candidates_for(board, defender);
            let forced = if defense_moves.is_empty() {
                self.is_compound_forcing_summary(summary)
            } else {
                let mut all_hold = true;
                for (def_row, def_col) in defense_moves {
                    if let Some(reason) = self.abort_reason() {
                        board.unplace(attack_row, attack_col);
                        return Err(reason);
                    }
                    if !board.place(def_row, def_col, defender) {
                        continue;
                    }
                    let defender_wins_now = board.check_win(def_row, def_col, defender);
                    let attacker_still_forces = if defender_wins_now {
                        false
                    } else {
                        match self.compound_forcing_search_inner(board, attacker, depth - 2) {
                            Ok(v) => v,
                            Err(reason) => {
                                board.unplace(def_row, def_col);
                                board.unplace(attack_row, attack_col);
                                return Err(reason);
                            }
                        }
                    };
                    board.unplace(def_row, def_col);

                    if !attacker_still_forces {
                        all_hold = false;
                        break;
                    }
                }
                all_hold
            };

            board.unplace(attack_row, attack_col);
            if forced {
                return Ok(true);
            }
        }

        Ok(false)
    }

    pub(super) fn find_compound_forcing_move(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        depth: i32,
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        if depth <= 0 {
            return Ok(None);
        }

        for ((row, col), _, _) in self.compound_attack_candidates_for(board, moves, self.config.player) {
            let mut next = board.clone();
            if !next.place(row, col, self.config.player) {
                continue;
            }
            if next.check_win(row, col, self.config.player)
                || self.compound_forcing_search_inner(&mut next, self.config.player, depth - 1)?
            {
                return Ok(Some((row, col)));
            }
        }

        Ok(None)
    }

    pub(super) fn find_future_pressure_attack(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
    ) -> Option<(usize, usize)> {
        if self.config.max_depth < 20 || board.move_count < 45 {
            return None;
        }

        let opp = self.config.player.opponent();
        let mut ranked: Vec<((usize, usize), i32, ThreatSummary)> = moves
            .iter()
            .take(12)
            .filter_map(|&(row, col)| {
                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return None;
                }

                if self.opponent_authoritative_refutation_on_board(&next, opp).unwrap_or(true) {
                    return None;
                }

                let next_moves = next.generate_moves();
                let summary = self.threat_summary_for(&next, self.config.player, &next_moves);
                if summary.win_moves == 0 && summary.double_threats == 0 {
                    return None;
                }

                let score = self.heuristic_score(board, row, col)
                    + summary.win_moves * 24_000
                    + summary.double_threats * 8_000
                    + summary.blocked_fours * 2_000;
                Some(((row, col), score, summary))
            })
            .collect();

        ranked.sort_by(|a, b| b.1.cmp(&a.1));
        let best = ranked.first().copied()?;
        let runner_up = ranked.get(1).copied();
        let margin_ok = runner_up
            .map(|(_, score, _)| best.1 >= score + 4_000)
            .unwrap_or(true);

        if margin_ok {
            Some(best.0)
        } else {
            None
        }
    }

    pub(super) fn find_compound_defense(
        &self,
        board: &Board,
        depth: i32,
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        if depth <= 0 {
            return Ok(None);
        }

        let opp = self.config.player.opponent();
        let moves = board.generate_moves();
        if self.compound_attack_candidates_for(board, &moves, opp).is_empty() {
            return Ok(None);
        }

        let has_opp_force = self.compound_forcing_search_inner(&mut board.clone(), opp, depth)?;
        if !has_opp_force {
            return Ok(None);
        }

        Ok(self.compound_defense_candidates_for(board, self.config.player)
            .into_iter()
            .filter_map(|(row, col)| {
                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return None;
                }
                if self.opponent_authoritative_refutation_on_board(&next, opp).unwrap_or(true) {
                    return None;
                }
                let still_forced = match self.compound_forcing_search_inner(&mut next, opp, depth - 1) {
                    Ok(v) => v,
                    Err(_) => return None,
                };
                if still_forced {
                    return None;
                }

                let next_moves = next.generate_moves();
                let residual = self.threat_summary_for(&next, opp, &next_moves);
                let control = self.local_control_density(board, row, col);
                let shape = self.local_shape_bonus_after_placed(&next, row, col, self.config.player);
                Some(((row, col), residual, control, shape))
            })
            .min_by_key(|&(_, residual, control, shape)| {
                (
                    residual.win_moves,
                    residual.double_threats,
                    residual.blocked_fours,
                    residual.threes,
                    residual.best_score,
                    -control,
                    -shape,
                )
            })
            .map(|((row, col), _, _, _)| (row, col)))
    }

    pub(super) fn avoid_compound_trap_move(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        depth: i32,
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        if depth <= 0 || moves.is_empty() {
            return Ok(None);
        }

        let opp = self.config.player.opponent();
        if self.compound_attack_candidates_for(board, moves, opp).is_empty() {
            return Ok(None);
        }

        let top_move = moves[0];
        let top_is_trap = {
            let mut next = board.clone();
            next.place(top_move.0, top_move.1, self.config.player)
                && self.compound_forcing_search_inner(&mut next, opp, depth - 1)?
        };
        if !top_is_trap {
            return Ok(None);
        }

        Ok(moves
            .iter()
            .take(COMPOUND_DEFENSE_CANDIDATES)
            .copied()
            .find(|&(row, col)| {
                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return false;
                }
                if self.opponent_authoritative_refutation_on_board(&next, opp).unwrap_or(true) {
                    return false;
                }
                match self.compound_forcing_search_inner(&mut next, opp, depth - 1) {
                    Ok(v) => !v,
                    Err(_) => false,
                }
            }))
    }

    pub(super) fn avoid_counterplay_blunder(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        chosen: (usize, usize),
    ) -> Option<(usize, usize)> {
        if moves.is_empty() || board.move_count < 30 {
            return None;
        }

        let opp = self.config.player.opponent();
        let chosen_index = moves.iter().position(|&mv| mv == chosen)?;
        let chosen_allows_forced = self.opponent_authoritative_refutation_after(
            board,
            chosen.0,
            chosen.1,
            self.config.player,
            opp,
        );

        let mut chosen_board = board.clone();
        if !chosen_board.place(chosen.0, chosen.1, self.config.player) {
            return None;
        }

        let chosen_moves = chosen_board.generate_moves();
        if chosen_board.check_win(chosen.0, chosen.1, self.config.player) {
            return None;
        }

        let chosen_immediate = find_immediate_win(&chosen_board, &chosen_moves, opp);
        let chosen_summary = self.threat_summary_for(&chosen_board, opp, &chosen_moves);
        let chosen_risk = self.forced_counterplay_risk(&chosen_board, self.config.player);
        let chosen_summary_score = self.threat_summary_score(chosen_summary);
        let chosen_is_dangerous = chosen_allows_forced
            || chosen_immediate.is_some()
            || chosen_summary.double_threats > 0
            || chosen_summary.blocked_fours > 0
            || chosen_risk >= 45_000;
        if !chosen_is_dangerous {
            return None;
        }

        let preview_len = moves.len().min((chosen_index + 1).max(COMPOUND_DEFENSE_CANDIDATES * 2));
        let best_alternative = moves
            .iter()
            .take(preview_len)
            .enumerate()
            .filter_map(|(index, &(row, col))| {
                if (row, col) == chosen {
                    return None;
                }

                let mut next = board.clone();
                if !next.place(row, col, self.config.player) {
                    return None;
                }

                if self.opponent_authoritative_refutation_after(board, row, col, self.config.player, opp) {
                    return None;
                }

                let next_moves = next.generate_moves();
                if find_immediate_win(&next, &next_moves, opp).is_some() {
                    return None;
                }

                let summary = self.threat_summary_for(&next, opp, &next_moves);
                let risk = self.forced_counterplay_risk(&next, self.config.player);
                let summary_score = self.threat_summary_score(summary);
                Some(((row, col), index, risk, summary_score))
            })
            .min_by_key(|&(_, index, risk, summary_score)| (risk, summary_score, index));

        best_alternative
            .filter(|&(_, index, risk, summary_score)| {
                if chosen_allows_forced {
                    true
                } else {
                    index < chosen_index
                        && (risk + 6_000 < chosen_risk
                            || (risk < chosen_risk && summary_score + 4_000 < chosen_summary_score))
                }
            })
            .map(|(mv, _, _, _)| mv)
    }

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
        self.find_forced_win_for_player(board, moves, self.config.player)
    }

    fn find_forced_win_for_player(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        player: Color,
    ) -> Option<(usize, usize)> {
        let opp = player.opponent();
        let is_master = self.config.max_depth >= 20 && board.move_count >= 30;
        let prefer_pressure = self.config.max_depth >= 20 && board.move_count >= 42;
        if prefer_pressure {
            return moves
                .iter()
                .filter_map(|&(r, c)| {
                    let s = self.normalized_eval_score(board, r, c, player);
                    let is_forcing = is_real_four_threat(board, r, c, player) || s >= SCORE_FOUR;
                    if !is_forcing || (is_master && self.opponent_authoritative_refutation_after(board, r, c, player, opp)) {
                        return None;
                    }

                    Some(((r, c), self.heuristic_score(board, r, c), s))
                })
                .max_by_key(|&(_, heuristic, score)| (heuristic, score))
                .map(|((r, c), _, _)| (r, c));
        }

        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, player))
            .filter(|&&(r, c)| !is_master || !self.opponent_authoritative_refutation_after(board, r, c, player, opp))
            .max_by_key(|&&(r, c)| self.normalized_eval_score(board, r, c, player))
            .copied();
        if best_real.is_some() {
            return best_real;
        }

        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = self.normalized_eval_score(board, r, c, player);
                if s >= SCORE_FOUR && (!is_master || !self.opponent_authoritative_refutation_after(board, r, c, player, opp)) {
                    Some(((r, c), s))
                } else {
                    None
                }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    fn raw_forced_win_for_player(
        &self,
        board: &Board,
        moves: &[(usize, usize)],
        player: Color,
    ) -> Option<(usize, usize)> {
        moves.iter().any(|&(opp_row, opp_col)| {
            is_real_four_threat(board, opp_row, opp_col, player)
                || self.normalized_eval_score(board, opp_row, opp_col, player) >= SCORE_FOUR
        }).then(|| {
            moves
                .iter()
                .filter(|&&(r, c)| is_real_four_threat(board, r, c, player))
                .max_by_key(|&&(r, c)| self.normalized_eval_score(board, r, c, player))
                .copied()
                .or_else(|| {
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
                })
        }).flatten()
    }

    fn opponent_authoritative_refutation_on_board(
        &self,
        board: &Board,
        opp: Color,
    ) -> Result<bool, SearchAbort> {
        let next_moves = board.generate_moves();
        if find_immediate_win(board, &next_moves, opp).is_some() {
            return Ok(true);
        }
        if self.raw_forced_win_for_player(board, &next_moves, opp).is_some() {
            return Ok(true);
        }
        if self.compound_forcing_search_inner(&mut board.clone(), opp, COMPOUND_FORCE_DEPTH_MASTER)? {
            return Ok(true);
        }
        Ok(self.vcf_search_inner(&mut board.clone(), opp, VCF_DEPTH_MASTER)?.is_some())
    }

    pub(super) fn opponent_authoritative_refutation_after(
        &self,
        board: &Board,
        row: usize,
        col: usize,
        player: Color,
        opp: Color,
    ) -> bool {
        let mut next = board.clone();
        if !next.place(row, col, player) {
            return true;
        }

        self.opponent_authoritative_refutation_on_board(&next, opp)
            .unwrap_or(true)
    }

    pub(super) fn find_critical_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let candidates = self.critical_block_candidates_for(board, moves, self.config.player);
        if candidates.is_empty() {
            return None;
        }

        let top_tier = self.threat_tier(candidates[0].1);
        let is_master = self.config.max_depth >= 20 && board.move_count >= 30;
        let opp = self.config.player.opponent();
        let ranked: Vec<((usize, usize), i32, i32, i32)> = candidates
            .iter()
            .take_while(|(_, score)| self.threat_tier(*score) == top_tier)
            .filter_map(|&((row, col), _)| {
                if is_master && self.opponent_authoritative_refutation_after(board, row, col, self.config.player, opp) {
                    return None;
                }

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

        if is_master && ranked.len() == 1 {
            let (_, followup_risk, _, _) = ranked[0];
            if board.move_count >= 30 && followup_risk >= 45_000 {
                return None;
            }
        }

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
        let is_master = self.config.max_depth >= 20 && board.move_count >= 30;
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

        let ranked: Vec<((usize, usize), ThreatSummary, i32, i32, i32)> = moves
            .iter()
            .filter_map(|&(row, col)| {
                let defense = self.normalized_eval_score(board, row, col, opp);
                if defense < SCORE_THREE {
                    return None;
                }

                if is_master && self.opponent_authoritative_refutation_after(board, row, col, self.config.player, opp) {
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
            .collect();

        let best = ranked.iter().min_by_key(|&(_, after, followup_risk, control_density, local_shape)| {
            (
                after.win_moves,
                after.double_threats,
                after.blocked_fours,
                after.threes,
                after.best_score,
                *followup_risk,
                -*control_density,
                -*local_shape,
            )
        })?;

        if is_master && best.2 >= 45_000 {
            return None;
        }

        ranked
            .into_iter()
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
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        self.vcf_search_inner(board, player, depth)
    }

    pub(super) fn vcf_search_inner(
        &self,
        board: &mut Board,
        player: Color,
        depth: i32,
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        if depth <= 0 {
            return Ok(None);
        }
        if let Some(reason) = self.abort_reason() {
            return Err(reason);
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
                return Ok(Some((ar, ac)));
            }

            let threats = vcf_win_threats(board, player);
            if threats.is_empty() {
                board.unplace(ar, ac);
                continue;
            }
            if threats.len() >= 2 {
                board.unplace(ar, ac);
                return Ok(Some((ar, ac)));
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

            let success = self.vcf_search_inner(board, player, depth - 2)?.is_some();

            board.unplace(br, bc);
            board.unplace(ar, ac);

            if success {
                return Ok(Some((ar, ac)));
            }
        }

        Ok(None)
    }

    pub(super) fn vcf_defense(
        &self,
        board: &mut Board,
        player: Color,
        opp_vcf_depth: i32,
    ) -> Result<Option<(usize, usize)>, SearchAbort> {
        let opp = player.opponent();
        let opp_has_vcf = self.vcf_search_inner(board, opp, opp_vcf_depth)?.is_some();
        if !opp_has_vcf {
            return Ok(None);
        }

        for (mr, mc) in board.generate_moves() {
            if let Some(reason) = self.abort_reason() {
                return Err(reason);
            }
            if !board.place(mr, mc, player) {
                continue;
            }
            let still_vcf = self.vcf_search_inner(board, opp, opp_vcf_depth - 2)?.is_some();
            board.unplace(mr, mc);
            if !still_vcf {
                return Ok(Some((mr, mc)));
            }
        }

        Ok(None)
    }
}
