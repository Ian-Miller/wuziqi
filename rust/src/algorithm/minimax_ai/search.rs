use super::*;
use crate::algorithm::lifecycle::{ProgressState, SearchLifecycle};

impl MinimaxAi {
    pub(super) fn iterative_deepening(
        &mut self,
        board: &Board,
        moves: &[(usize, usize)],
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress: &mut ProgressState,
    ) -> (usize, usize) {
        let mut best_move = moves[0];
        let mut last_layer_ms: u64 = 1;
        let root_hash = self.zobrist.hash_board(board);
        let mut current_depth = 1;

        loop {
            if current_depth > self.config.max_depth || !self.time_ok() {
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

            let (mut d_best, mut done, mut win, mut root_score) = self.search_one_depth(
                board,
                moves,
                current_depth,
                best_move,
                root_hash,
                alpha0,
                beta0,
                on_progress,
                progress,
            );

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
                let (b2, d2, w2, s2) = self.search_one_depth(
                    board,
                    moves,
                    current_depth,
                    d_best,
                    root_hash,
                    alpha0,
                    beta0,
                    on_progress,
                    progress,
                );
                d_best = b2;
                done = d2;
                win = w2;
                root_score = s2;
            }

            if done
                && current_depth >= 4
                && (root_score <= alpha0 || root_score >= beta0)
                && self.time_ok()
            {
                let (b3, d3, w3, s3) = self.search_one_depth(
                    board,
                    moves,
                    current_depth,
                    d_best,
                    root_hash,
                    i32::MIN + 1,
                    i32::MAX - 1,
                    on_progress,
                    progress,
                );
                d_best = b3;
                done = d3;
                win = w3;
                root_score = s3;
            }

            if done {
                best_move = d_best;
                self.best_move_encoded
                    .store((best_move.0 * 15 + best_move.1) as i32, Ordering::Relaxed);
                self.last_root_score = root_score;
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

                if win {
                    break;
                }

                let remaining = self
                    .turn_time_limit_ms
                    .saturating_sub(self.start_time.elapsed().as_millis() as u64);

                current_depth += if remaining > last_layer_ms * FAST_JUMP_RATIO { 2 } else { 1 };
            } else {
                break;
            }
        }

        self.remember_root_decision(root_hash, best_move);
        best_move
    }

    fn search_one_depth(
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
    ) -> ((usize, usize), bool, bool, i32) {
        let mut best = fallback;
        let mut timed_out = false;
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
                on_progress,
                progress,
            );

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

            if adjusted_score > alpha {
                alpha = adjusted_score;
                best = (row, col);
            }

            if alpha >= KILL_THRESHOLD {
                found_win = true;
                break;
            }
        }

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
    ) -> i32 {
        let tick = self.node_count.fetch_add(1, Ordering::Relaxed) + 1;
        if !self.heartbeat(tick, on_progress, progress) {
            return 0;
        }

        let orig_alpha = alpha;
        if let Some((entry_depth, entry_score, _, entry_flag)) = self.tt.get(hash) {
            if entry_depth >= depth {
                match entry_flag {
                    TtFlag::Exact => return entry_score,
                    TtFlag::LowerBound => {
                        if entry_score > alpha {
                            alpha = entry_score;
                        }
                    }
                    TtFlag::UpperBound => {
                        if entry_score <= alpha {
                            return entry_score;
                        }
                        if entry_score < beta {
                            beta = entry_score;
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

        let raw = board.generate_moves();
        if raw.is_empty() {
            return 0;
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
            if !self.heartbeat(tick, on_progress, progress) {
                return alpha;
            }

            if !board.place(row, col, player) {
                continue;
            }

            if board.check_win(row, col, player) {
                let win_score = WIN_SCORE + depth;
                board.unplace(row, col);
                self.tt.set(hash, depth, win_score, Some((row, col)), TtFlag::Exact);
                return win_score;
            }

            let child_hash = self.zobrist.hash_move(hash, row, col, player);
            let is_quiet = *move_hint_score < SCORE_THREE * 2;
            let reduce = if depth >= 5 && idx >= 4 && is_quiet { 1 } else { 0 };
            let search_depth = (depth - 1 - reduce).max(0);

            let mut score = if first_move {
                -self.minimax(
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
                )
            } else {
                let mut s = -self.minimax(
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
                );
                if s > alpha && s < beta {
                    s = -self.minimax(
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
                    );
                }
                s
            };

            if reduce > 0 && score > alpha {
                score = -self.minimax(
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
                );
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
                return alpha;
            }
        }

        let flag = if alpha <= orig_alpha {
            TtFlag::UpperBound
        } else {
            TtFlag::Exact
        };
        self.tt.set(hash, depth, alpha, best_move, flag);
        alpha
    }
}
