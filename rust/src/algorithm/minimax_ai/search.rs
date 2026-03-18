use super::*;
use crate::algorithm::lifecycle::{ProgressState, SearchAbort, SearchLifecycle, TurnStatus};

pub(super) struct IterativeDeepeningResult {
    pub(super) best_move: (usize, usize),
    pub(super) status: TurnStatus,
}

pub(super) struct SearchDepthOutcome {
    best_move: (usize, usize),
    found_win: bool,
    root_score: i32,
}

impl MinimaxAi {
    pub(super) fn iterative_deepening(
        &mut self,
        board: &Board,
        moves: &[(usize, usize)],
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress: &mut ProgressState,
    ) -> IterativeDeepeningResult {
        let mut best_move = moves[0];
        let mut last_layer_ms: u64 = 1;
        let root_hash = self.zobrist.hash_board(board);
        let mut current_depth = 1;
        let mut status = TurnStatus::Completed;

        loop {
            if current_depth > self.config.max_depth {
                break;
            }
            if let Some(reason) = self.abort_reason() {
                status = reason.into();
                break;
            }
            if current_depth > 1 && !self.can_afford_depth(last_layer_ms) {
                break;
            }

            let t = self.start_time.elapsed().as_millis() as u64;
            let mut attempt = 0;
            let mut margin = ASPIRATION_MARGIN_BASE;
            let mut alpha0 = i32::MIN + 1;
            let mut beta0 = i32::MAX - 1;
            if current_depth >= 4 {
                alpha0 = self.last_root_score - margin;
                beta0 = self.last_root_score + margin;
            }

            let mut depth_outcome = match self.search_one_depth(
                board,
                moves,
                current_depth,
                best_move,
                root_hash,
                alpha0,
                beta0,
                on_progress,
                progress,
            ) {
                Ok(outcome) => outcome,
                Err(reason) => {
                    status = reason.into();
                    break;
                }
            };

            while current_depth >= 4
                && current_depth >= 4
                && attempt < ASPIRATION_RETRY_MAX
                && (depth_outcome.root_score <= alpha0 || depth_outcome.root_score >= beta0)
            {
                if let Some(reason) = self.abort_reason() {
                    status = reason.into();
                    break;
                }
                attempt += 1;
                margin += ASPIRATION_MARGIN_STEP;
                alpha0 = self.last_root_score - margin;
                beta0 = self.last_root_score + margin;
                depth_outcome = match self.search_one_depth(
                    board,
                    moves,
                    current_depth,
                    depth_outcome.best_move,
                    root_hash,
                    alpha0,
                    beta0,
                    on_progress,
                    progress,
                ) {
                    Ok(outcome) => outcome,
                    Err(reason) => {
                        status = reason.into();
                        break;
                    }
                };
            }

            if status != TurnStatus::Completed {
                break;
            }

            if current_depth >= 4
                && (depth_outcome.root_score <= alpha0 || depth_outcome.root_score >= beta0)
            {
                if let Some(reason) = self.abort_reason() {
                    status = reason.into();
                    break;
                }

                depth_outcome = match self.search_one_depth(
                    board,
                    moves,
                    current_depth,
                    depth_outcome.best_move,
                    root_hash,
                    i32::MIN + 1,
                    i32::MAX - 1,
                    on_progress,
                    progress,
                ) {
                    Ok(outcome) => outcome,
                    Err(reason) => {
                        status = reason.into();
                        break;
                    }
                };
            }

            if status != TurnStatus::Completed {
                break;
            }

            best_move = depth_outcome.best_move;
            self.best_move_encoded
                .store((best_move.0 * 15 + best_move.1) as i32, Ordering::Relaxed);
            self.last_root_score = depth_outcome.root_score;
            let elapsed_ms = (self.start_time.elapsed().as_millis() as u64 - t).max(1);
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
                progress,
                false,
            );

            if depth_outcome.found_win {
                break;
            }

            let remaining = self
                .turn_time_limit_ms
                .saturating_sub(self.start_time.elapsed().as_millis() as u64);

            current_depth += if remaining > last_layer_ms * FAST_JUMP_RATIO { 2 } else { 1 };
        }

        self.remember_root_decision(root_hash, best_move);
        IterativeDeepeningResult { best_move, status }
    }

    pub(super) fn search_one_depth(
        &mut self,
        board: &Board,
        moves: &[(usize, usize)],
        depth: i32,
        fallback: (usize, usize),
        root_hash: u64,
        mut alpha: i32,
        beta: i32,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress: &mut ProgressState,
    ) -> Result<SearchDepthOutcome, SearchAbort> {
        let mut best = fallback;
        let mut best_heuristic = self.heuristic_score(board, fallback.0, fallback.1);
        let mut found_win = false;

        let tt_best = self.tt.get(root_hash).and_then(|e| e.2);
        let ordered_base: Vec<(usize, usize)> = if let Some(tb) = tt_best {
            let mut v: Vec<(usize, usize)> = moves.iter().copied().filter(|&m| m != tb).collect();
            v.insert(0, tb);
            v
        } else {
            moves.to_vec()
        };

        let ordered: Vec<(usize, usize)> = if self.config.max_depth >= 12 {
            self.suppress_root_redundancy(&ordered_base)
        } else {
            ordered_base
        };

        for &(row, col) in &ordered {
            if let Some(reason) = self.abort_reason() {
                return Err(reason);
            }

            let mut new_board = board.clone();
            if !new_board.place(row, col, self.config.player) {
                continue;
            }

            if new_board.check_win(row, col, self.config.player) {
                return Ok(SearchDepthOutcome {
                    best_move: (row, col),
                    found_win: true,
                    root_score: WIN_SCORE + depth,
                });
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
                on_progress,
                progress,
            )?;

            let adjusted_score = if self.config.max_depth >= 12 {
                let penalty = self.root_repetition_penalty(root_hash, (row, col));
                if score >= KILL_THRESHOLD {
                    score
                } else {
                    score - penalty
                }
            } else {
                score
            };

            let candidate_heuristic = if self.config.max_depth >= 20 {
                self.heuristic_score(board, row, col)
            } else {
                0
            };

            if adjusted_score > alpha {
                alpha = adjusted_score;
                best = (row, col);
                best_heuristic = candidate_heuristic;
            } else if self.config.max_depth >= 20
                && adjusted_score == alpha
                && board.move_count >= 24
                && candidate_heuristic > best_heuristic
            {
                best = (row, col);
                best_heuristic = candidate_heuristic;
            }

            if alpha >= KILL_THRESHOLD {
                found_win = true;
                break;
            }
        }

        self.tt.set(root_hash, depth, alpha, Some(best), TtFlag::Exact);

        Ok(SearchDepthOutcome {
            best_move: best,
            found_win,
            root_score: alpha,
        })
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

    fn can_afford_depth(&self, last_layer_ms: u64) -> bool {
        let elapsed = self.start_time.elapsed().as_millis() as u64;
        let remaining = self.turn_time_limit_ms.saturating_sub(elapsed);
        remaining > last_layer_ms * AFFORD_RATIO
    }

    fn minimax(
        &mut self,
        board: &mut Board,
        player: Color,
        depth: i32,
        mut alpha: i32,
        mut beta: i32,
        hash: u64,
        ply: usize,
        last_move: Option<(usize, usize)>,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress: &mut ProgressState,
    ) -> Result<i32, SearchAbort> {
        let tick = self.node_count.fetch_add(1, Ordering::Relaxed) + 1;
        self.heartbeat(tick, on_progress, progress)?;

        let orig_alpha = alpha;
        if let Some((entry_depth, entry_score, _, entry_flag)) = self.tt.get(hash) {
            if entry_depth >= depth {
                match entry_flag {
                    TtFlag::Exact => return Ok(entry_score),
                    TtFlag::LowerBound => {
                        if entry_score > alpha {
                            alpha = entry_score;
                        }
                    }
                    TtFlag::UpperBound => {
                        if entry_score <= alpha {
                            return Ok(entry_score);
                        }
                        if entry_score < beta {
                            beta = entry_score;
                        }
                    }
                }
                if alpha >= beta {
                    return Ok(alpha);
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
            return Ok(ev);
        }

        let raw = board.generate_moves();
        if raw.is_empty() {
            return Ok(0);
        }

        let tt_best_move = self.tt.get(hash).and_then(|e| e.2);
        let mut moves: Vec<((usize, usize), i32)> = raw
            .into_iter()
            .map(|(r, c)| {
                let mut score = self.move_tactical_score(board, r, c, player);
                if Some((r, c)) == tt_best_move {
                    score += 1_000_000;
                }
                if ply < self.killer_moves.len() {
                    if self.killer_moves[ply][0] == Some((r, c)) {
                        score += 140_000;
                    } else if self.killer_moves[ply][1] == Some((r, c)) {
                        score += 95_000;
                    }
                }
                score += self.history_bonus(player, r, c);
                ((r, c), score)
            })
            .collect();
        moves.sort_unstable_by(|a, b| b.1.cmp(&a.1));

        let mut best_move = moves.first().map(|&((r, c), _)| (r, c));
        let mut first_move = true;
        for (idx, ((row, col), move_hint_score)) in moves.iter().enumerate() {
            let (row, col) = (*row, *col);
            let tick = self.node_count.fetch_add(1, Ordering::Relaxed) + 1;
            self.heartbeat(tick, on_progress, progress)?;

            if !board.place(row, col, player) {
                continue;
            }

            if board.check_win(row, col, player) {
                let win_score = WIN_SCORE + depth;
                board.unplace(row, col);
                self.tt.set(hash, depth, win_score, Some((row, col)), TtFlag::Exact);
                return Ok(win_score);
            }

            let child_hash = self.zobrist.hash_move(hash, row, col, player);
            let is_quiet = *move_hint_score < SCORE_THREE * 2;
            let reduce = if depth >= 5 && idx >= 4 && is_quiet { 1 } else { 0 };
            let search_depth = (depth - 1 - reduce).max(0);

            let mut score = if first_move {
                match self.minimax(
                    board,
                    player.opponent(),
                    search_depth,
                    -beta,
                    -alpha,
                    child_hash,
                    ply + 1,
                    Some((row, col)),
                    on_progress,
                    progress,
                ) {
                    Ok(v) => -v,
                    Err(reason) => {
                        board.unplace(row, col);
                        return Err(reason);
                    }
                }
            } else {
                let mut s = match self.minimax(
                    board,
                    player.opponent(),
                    search_depth,
                    -alpha - 1,
                    -alpha,
                    child_hash,
                    ply + 1,
                    Some((row, col)),
                    on_progress,
                    progress,
                ) {
                    Ok(v) => -v,
                    Err(reason) => {
                        board.unplace(row, col);
                        return Err(reason);
                    }
                };
                if s > alpha && s < beta {
                    s = match self.minimax(
                        board,
                        player.opponent(),
                        search_depth,
                        -beta,
                        -alpha,
                        child_hash,
                        ply + 1,
                        Some((row, col)),
                        on_progress,
                        progress,
                    ) {
                        Ok(v) => -v,
                        Err(reason) => {
                            board.unplace(row, col);
                            return Err(reason);
                        }
                    };
                }
                s
            };

            if reduce > 0 && score > alpha {
                score = match self.minimax(
                    board,
                    player.opponent(),
                    depth - 1,
                    -beta,
                    -alpha,
                    child_hash,
                    ply + 1,
                    Some((row, col)),
                    on_progress,
                    progress,
                ) {
                    Ok(v) => -v,
                    Err(reason) => {
                        board.unplace(row, col);
                        return Err(reason);
                    }
                };
            }

            board.unplace(row, col);
            first_move = false;

            if score > alpha {
                alpha = score;
                best_move = Some((row, col));
            }
            if alpha >= beta {
                self.record_killer(ply, (row, col));
                self.bump_history(player, row, col, depth);
                self.tt.set(hash, depth, alpha, best_move, TtFlag::LowerBound);
                return Ok(alpha);
            }
        }

        let flag = if alpha <= orig_alpha {
            TtFlag::UpperBound
        } else {
            TtFlag::Exact
        };
        self.tt.set(hash, depth, alpha, best_move, flag);
        Ok(alpha)
    }
}
