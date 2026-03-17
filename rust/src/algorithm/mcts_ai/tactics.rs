use super::*;
use crate::algorithm::shared_tactics::is_real_four_threat;

impl MctsAi {
    pub(super) fn find_forced_win(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let player = self.config.player;
        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, player))
            .max_by_key(|&&(r, c)| evaluate_position(board, r, c, player))
            .copied();
        if best_real.is_some() {
            return best_real;
        }

        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = evaluate_position(board, r, c, player);
                if s >= SCORE_FOUR { Some(((r, c), s)) } else { None }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    pub(super) fn find_critical_block(&self, board: &Board, moves: &[(usize, usize)]) -> Option<(usize, usize)> {
        let opp = self.config.player.opponent();
        let best_real = moves
            .iter()
            .filter(|&&(r, c)| is_real_four_threat(board, r, c, opp))
            .max_by_key(|&&(r, c)| evaluate_position(board, r, c, opp))
            .copied();
        if best_real.is_some() {
            return best_real;
        }

        moves
            .iter()
            .filter_map(|&(r, c)| {
                let s = evaluate_position(board, r, c, opp);
                if s >= SCORE_FOUR { Some(((r, c), s)) } else { None }
            })
            .max_by_key(|&(_, s)| s)
            .map(|((r, c), _)| (r, c))
    }

    pub(super) fn allows_opponent_immediate_win(&self, board: &Board, mv: (usize, usize), mover: Color) -> bool {
        let mut b = board.clone();
        if !b.place(mv.0, mv.1, mover) {
            return true;
        }
        let opp = mover.opponent();
        let next_moves = b.generate_moves();
        for (r, c) in next_moves {
            if b.place(r, c, opp) {
                let win = b.check_win(r, c, opp);
                b.unplace(r, c);
                if win {
                    return true;
                }
            }
        }
        false
    }

    pub(super) fn allows_opponent_high_threat(&self, board: &Board, mv: (usize, usize), mover: Color) -> bool {
        let mut b = board.clone();
        if !b.place(mv.0, mv.1, mover) {
            return true;
        }
        let opp = mover.opponent();
        let next_moves = b.generate_moves();
        for (r, c) in next_moves {
            let s = evaluate_position(&b, r, c, opp);
            if s >= SCORE_FOUR {
                return true;
            }
        }
        false
    }
}