use std::cmp::Ordering;
use std::sync::atomic::Ordering as AtomicOrdering;

use super::*;
use crate::algorithm::lifecycle::{ProgressState, SearchLifecycle};

impl MctsAi {
    pub(super) fn mcts_search(
        &mut self,
        board: &Board,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress: &mut ProgressState,
    ) -> Option<(usize, usize)> {
        let mover = self.config.player;
        let mut root = MctsNode::root(board, mover, self.config.max_children);
        let mut iter_count: u64 = 0;

        loop {
            iter_count += 1;
            if self.heartbeat(iter_count, on_progress, progress).is_err() {
                break;
            }

            let mut board_clone = board.clone();
            self.run_one_iteration(&mut root, &mut board_clone, mover);

            if iter_count % PROGRESS_HEARTBEAT_INTERVAL == 0 {
                if let Some(best) = root.children.iter()
                    .filter_map(|c| c.mv.map(|mv| (mv, c.visits)))
                    .max_by_key(|&(_, v)| v)
                    .map(|(mv, _)| mv)
                {
                    self.best_move_encoded.store(
                        (best.0 * 15 + best.1) as i32,
                        AtomicOrdering::Relaxed,
                    );
                }
            }
        }

        let mut ranked: Vec<((usize, usize), u32)> = root
            .children
            .iter()
            .filter_map(|c| c.mv.map(|mv| (mv, c.visits)))
            .collect();
        ranked.sort_unstable_by(|a, b| b.1.cmp(&a.1));

        if ranked.is_empty() {
            return None;
        }

        let chosen = if self.easy_mode {
            let top_n = self.sample_top_n.min(ranked.len());
            self.sample_softmax(&ranked[..top_n], self.sample_temperature)
                .unwrap_or(ranked[0].0)
        } else {
            ranked[0].0
        };

        let maybe_mistake = if self.easy_mode && self.roll(self.mistake_prob) && ranked.len() >= 2 {
            let alt_pool = ranked.iter().skip(1).take(4).copied().collect::<Vec<_>>();
            if let Some(alt) = self.sample_softmax(&alt_pool, self.sample_temperature + 0.45) {
                alt
            } else {
                chosen
            }
        } else {
            chosen
        };

        let check_high_threat = !self.easy_mode;
        if self.allows_opponent_immediate_win(board, maybe_mistake, mover)
            || (check_high_threat && self.allows_opponent_high_threat(board, maybe_mistake, mover))
        {
            for (mv, _) in ranked {
                if !self.allows_opponent_immediate_win(board, mv, mover)
                    && (!check_high_threat || !self.allows_opponent_high_threat(board, mv, mover))
                {
                    return Some(mv);
                }
            }
            return Some(maybe_mistake);
        }

        Some(maybe_mistake)
    }

    fn run_one_iteration(&self, root: &mut MctsNode, board: &mut Board, root_mover: Color) {
        if self.should_stop.load(AtomicOrdering::Acquire) {
            return;
        }

        let node_ptr = root as *mut MctsNode;
        let (leaf, depth_color, path) = unsafe { self.select(node_ptr, board, root_mover) };

        let value;
        let leaf_ref = unsafe { &mut *leaf };
        if leaf_ref.visits > 0 && !leaf_ref.untried_moves.is_empty() {
            let mv = leaf_ref.untried_moves.remove(0);
            let child_color = depth_color;
            if !board.place(mv.0, mv.1, child_color) {
                return;
            }

            if board.check_win(mv.0, mv.1, child_color) {
                value = if child_color == self.config.player {
                    WIN_VALUE
                } else {
                    LOSE_VALUE
                };
            } else {
                value = self.static_eval_normalized(board);
            }

            let next_mover = child_color.opponent();
            let max_c = self.config.max_children;
            let child_untried = top_k_moves(board, next_mover, max_c);
            board.unplace(mv.0, mv.1);

            let child = MctsNode {
                mv: Some(mv),
                color: child_color,
                visits: 1,
                total_value: value,
                untried_moves: child_untried,
                children: Vec::new(),
            };
            leaf_ref.children.push(child);
        } else if leaf_ref.visits == 0 {
            value = self.static_eval_normalized(board);
        } else {
            value = self.static_eval_normalized(board);
        }

        self.backpropagate_path(root, &path, value);
    }

    unsafe fn select(
        &self,
        mut node: *mut MctsNode,
        board: &mut Board,
        root_mover: Color,
    ) -> (*mut MctsNode, Color, Vec<usize>) {
        let mut current_mover = root_mover;
        let mut path: Vec<usize> = Vec::new();

        loop {
            if self.should_stop.load(AtomicOrdering::Acquire) {
                return (node, current_mover, path);
            }

            let n = &mut *node;
            if !n.is_fully_expanded() || n.is_leaf() {
                return (node, current_mover, path);
            }

            let parent_visits = n.visits;
            let c = self.config.exploration_c;
            let sign = if current_mover == self.config.player { 1.0 } else { -1.0 };
            let best_idx = n
                .children
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| {
                    a.ucb1(parent_visits, c, sign)
                        .partial_cmp(&b.ucb1(parent_visits, c, sign))
                        .unwrap_or(Ordering::Equal)
                })
                .map(|(i, _)| i)
                .unwrap_or(0);

            let child = &n.children[best_idx];
            if let Some((r, c_pos)) = child.mv {
                if !board.place(r, c_pos, current_mover) {
                    return (node, current_mover, path);
                }
            }
            current_mover = current_mover.opponent();
            path.push(best_idx);
            node = &mut n.children[best_idx] as *mut MctsNode;
        }
    }

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

    fn static_eval_normalized(&self, board: &Board) -> f64 {
        let mut score: i64 = 0;
        let player = self.config.player;
        let opp = player.opponent();

        let candidates = board.generate_moves();
        if candidates.is_empty() {
            return 0.0;
        }
        for (r, c) in candidates {
            score += evaluate_position(board, r, c, player) as i64;
            score -= evaluate_position(board, r, c, opp) as i64 * 9 / 10;
        }

        (score as f64 / EVAL_SCALE).clamp(-1.0, 1.0)
    }
}